package dev.openrune.server.endpoints.textures

import dev.openrune.ServerConfig
import dev.openrune.server.endpoints.ImageResizeHelper
import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.extractor.osrs.ModelManifestEntry
import dev.openrune.cache.extractor.osrs.SpriteManifestEntry
import dev.openrune.cache.extractor.osrs.TextureManifestEntry
import dev.openrune.server.QueryHandlerRaw
import dev.openrune.server.cache.ImageCache
import dev.openrune.server.endpoints.loadManifestData
import dev.openrune.server.endpoints.sprites.DataType
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import mu.KotlinLogging
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

private val logger = KotlinLogging.logger {}

class TextureImageEndpoint(private val config: ServerConfig) : QueryHandlerRaw<TextureQueryParams>() {
    
    private val manifest: Map<Int, TextureManifestEntry> by lazy {
        @Suppress("UNCHECKED_CAST")
        loadManifestData<ModelManifestEntry>(config, "textures", DataType.WEB) as Map<Int, TextureManifestEntry>
    }

    private val imageCache = ImageCache.default

    public override fun getQueryParams(call: ApplicationCall): TextureQueryParams {
        return TextureQueryParams(
            id = call.parameters["id"]?.toIntOrNull(),
            idRange = dev.openrune.server.BaseQueryParams.parseIdRange(call.parameters["idRange"]),
            limit = call.parameters["limit"]?.toIntOrNull(),
            width = call.parameters["width"]?.toIntOrNull(),
            height = call.parameters["height"]?.toIntOrNull(),
            keepAspectRatio = call.parameters["keepAspectRatio"]?.toBoolean() ?: true
        )
    }

    override suspend fun handle(call: ApplicationCall, params: TextureQueryParams) {
        try {
            val textureId = params.id ?: run {
                respondError(call, "Texture ID is required", 400)
                return
            }

            val manifestEntry = manifest[textureId] ?: run {
                respondError(call, "Texture not found", 404)
                return
            }

            val spriteFileId = manifestEntry.fileId

            val needsResize = params.width != null || params.height != null
            val cachedResized = if (needsResize) {
                imageCache.getResized(
                    textureId,
                    null,
                    params.width,
                    params.height,
                    params.keepAspectRatio
                )
            } else {
                null
            }

            if (cachedResized != null) {
                call.response.header(HttpHeaders.ContentType, ContentType.Image.PNG.toString())
                call.respondBytes(bytes = imageToBytes(cachedResized), contentType = ContentType.Image.PNG)
                return
            }

            var image = imageCache.getOriginal(textureId, null)

            if (image == null) {
                image = imageCache.getOriginal(spriteFileId, null) ?: run {
                    val spriteFile = getSpriteFile(spriteFileId)
                    if (!spriteFile.exists()) {
                        respondError(call, "Sprite file not found for texture fileId=$spriteFileId", 404)
                        return
                    }

                    ImageIO.read(spriteFile) ?: run {
                        respondError(call, "Failed to read sprite image for texture fileId=$spriteFileId", 500)
                        return
                    }
                }

                imageCache.putOriginal(textureId, null, image)
            }

            val finalImage = if (needsResize) {
                val resized = resizeImage(image, params.width, params.height, params.keepAspectRatio)
                imageCache.putResized(
                    textureId,
                    null,
                    params.width,
                    params.height,
                    params.keepAspectRatio,
                    resized
                )
                resized
            } else {
                image
            }

            call.response.header(HttpHeaders.ContentType, ContentType.Image.PNG.toString())
            call.respondBytes(bytes = imageToBytes(finalImage), contentType = ContentType.Image.PNG)
        } catch (e: IllegalArgumentException) {
            respondError(call, e.message ?: "Invalid request", 400)
        } catch (e: Exception) {
            logger.error("Error handling texture request: ${e.message}", e)
            respondError(call, "Internal server error: ${e.message}", 500)
        }
    }

    private fun getSpriteFile(spriteFileId: Int): File {
        val cacheDir = CachePathHelper.getCacheDirectory(
            config.gameType,
            config.environment,
            config.revision
        ).resolve("extracted").resolve("sprites")

        return cacheDir.resolve("$spriteFileId.png")
    }

    private fun resizeImage(
        image: BufferedImage,
        targetWidth: Int?,
        targetHeight: Int?,
        keepAspectRatio: Boolean
    ): BufferedImage {
        if (targetWidth == null && targetHeight == null) {
            return image
        }

        val (finalWidth, finalHeight) = ImageResizeHelper.calculateDimensions(
            originalWidth = image.width,
            originalHeight = image.height,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            keepAspectRatio = keepAspectRatio
        )
        
        if (finalWidth == image.width && finalHeight == image.height) {
            return image
        }

        val resized = BufferedImage(finalWidth, finalHeight, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = resized.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(image, 0, 0, finalWidth, finalHeight, null)
        g.dispose()

        return resized
    }



    private fun imageToBytes(image: BufferedImage): ByteArray {
        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, "png", outputStream)
        return outputStream.toByteArray()
    }
}

