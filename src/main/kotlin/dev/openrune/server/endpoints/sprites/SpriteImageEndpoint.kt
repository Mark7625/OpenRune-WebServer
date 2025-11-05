package dev.openrune.server.endpoints.sprites

import dev.openrune.ServerConfig
import dev.openrune.server.endpoints.ImageResizeHelper
import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.extractor.osrs.ModelManifestEntry
import dev.openrune.cache.extractor.osrs.SpriteManifestEntry
import dev.openrune.definition.type.SpriteType
import dev.openrune.server.QueryHandlerRaw
import dev.openrune.server.cache.ImageCache
import dev.openrune.server.endpoints.loadManifestData
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


class SpriteImageEndpoint(private val config: ServerConfig) : QueryHandlerRaw<SpriteQueryParams>() {
    
    private val manifest: Map<Int, SpriteManifestEntry> by lazy {
        @Suppress("UNCHECKED_CAST")
        loadManifestData<SpriteManifestEntry>(config, "models", DataType.WEB) as Map<Int, SpriteManifestEntry>
    }
    
    private val imageCache = ImageCache.default
    private val gameValLookupField: (SpriteManifestEntry) -> String? = { it.name }

    public override fun getQueryParams(call: ApplicationCall): SpriteQueryParams {
        return SpriteQueryParams(
            id = call.parameters["id"]?.toIntOrNull(),
            gameVal = call.parameters["gameVal"],
            idRange = dev.openrune.server.BaseQueryParams.parseIdRange(call.parameters["idRange"]),
            limit = call.parameters["limit"]?.toIntOrNull(),
            width = call.parameters["width"]?.toIntOrNull(),
            height = call.parameters["height"]?.toIntOrNull(),
            keepAspectRatio = call.parameters["keepAspectRatio"]?.toBoolean() ?: true,
            indexed = call.parameters["indexed"]?.toIntOrNull()
        )
    }

    override suspend fun handle(call: ApplicationCall, params: SpriteQueryParams) {
        try {
            val archiveId = resolveArchiveId(params, manifest, gameValLookupField)
            val manifestEntry = manifest[archiveId] ?: run {
                respondError(call, "Sprite not found", 404)
                return
            }
            
            params.indexed?.let { frameIndex ->
                if (frameIndex < 0 || frameIndex >= manifestEntry.count) {
                    respondError(call, "Invalid indexed frame. Valid range: 0-${manifestEntry.count - 1}", 400)
                    return
                }
            }
            
            val needsResize = params.width != null || params.height != null
            val cachedResized = if (needsResize) {
                imageCache.getResized(
                    archiveId,
                    params.indexed,
                    params.width,
                    params.height,
                    params.keepAspectRatio
                )
            } else {
                null
            }
            
            if (cachedResized != null) {
                call.response.header(HttpHeaders.ContentType, ContentType.Image.PNG.toString())
                call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 86400 * 7)) // 7 days
                call.respondBytes(bytes = imageToBytes(cachedResized), contentType = ContentType.Image.PNG)
                return
            }
            
            var image = imageCache.getOriginal(archiveId, params.indexed)
            
            if (image == null) {
                val spriteFile = getSpriteFile(archiveId, manifestEntry, params.indexed) ?: run {
                    respondError(call, "Sprite file not found", 404)
                    return
                }

                if (!spriteFile.exists()) {
                    respondError(call, "Sprite file does not exist on disk", 404)
                    return
                }

                image = ImageIO.read(spriteFile)
                    ?: run {
                        respondError(call, "Failed to read sprite image", 500)
                        return
                    }
                
                imageCache.putOriginal(archiveId, params.indexed, image)
            }
            
            val finalImage = if (needsResize) {
                val resized = resizeImage(image, params.width, params.height, params.keepAspectRatio)
                imageCache.putResized(
                    archiveId,
                    params.indexed,
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
            call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 86400 * 7)) // 7 days
            call.respondBytes(bytes = imageToBytes(finalImage), contentType = ContentType.Image.PNG)
        } catch (e: IllegalArgumentException) {
            respondError(call, e.message ?: "Invalid request", 400)
        } catch (e: Exception) {
            logger.error("Error handling sprite request: ${e.message}", e)
            respondError(call, "Internal server error: ${e.message}", 500)
        }
    }

    /**
     * Get the sprite file path for a given archive ID and optional indexed frame
     */
    private fun getSpriteFile(archiveId: Int, manifestEntry: SpriteManifestEntry, frameIndex: Int?): File? {
        val cacheDir = CachePathHelper.getCacheDirectory(
            config.gameType,
            config.environment,
            config.revision
        ).resolve("extracted").resolve("sprites")
        
        // If indexed frame is provided, look for indexed sprite file
        frameIndex?.let { index ->
            val spriteName = manifestEntry.name ?: archiveId.toString()
            return cacheDir.resolve(spriteName).resolve("$index.png")
        }
        
        // Otherwise return the main sprite file
        return cacheDir.resolve("$archiveId.png")
    }

    /**
     * Resize image with optional aspect ratio preservation
     */
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

    /**
     * Convert BufferedImage to PNG bytes
     */
    private fun imageToBytes(image: BufferedImage): ByteArray {
        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, "png", outputStream)
        return outputStream.toByteArray()
    }
}

