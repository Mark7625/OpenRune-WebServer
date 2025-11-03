package routes.open.cache

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import dev.openrune.cache.filestore.definition.SpriteDecoder
import dev.openrune.definition.type.SpriteType
import gameCache
import routes.open.cache.TextureHandler.json
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.io.File

data class SpriteInfo(val id: Int, val subIndex: Int, val offsetX: Int, val offsetY: Int)

object SpriteHandler {

    private val allSprites = mutableListOf<SpriteInfo>()
    val sprites: MutableMap<Int, SpriteType> = mutableMapOf()

    private val responseCache: LoadingCache<String, BufferedImage> = Caffeine.newBuilder()
        .expireAfterWrite(12, TimeUnit.HOURS)
        .build { key -> runBlocking { fetchImageWithKey(key) } }

    fun init() {
        SpriteDecoder().load(gameCache, sprites)
        sprites.forEach { (id, spriteType) ->
            spriteType.sprites.forEachIndexed { index, subSprite ->
                val img = subSprite.toBufferedImage()
                if (allSprites.none { it.id == id }) {
                    allSprites.add(SpriteInfo(id, -1, subSprite.offsetX, subSprite.offsetY))
                }
            }
        }
    }

    suspend fun handleSpriteById(call: ApplicationCall) {
        val id = call.parameters["id"]?.toIntOrNull()
        val width = call.parameters["width"]?.toIntOrNull()
        val height = call.parameters["height"]?.toIntOrNull()
        val gameVal = call.parameters["gameVal"]?.toIntOrNull()
        val keepAspectRatio = call.parameters["keepAspectRatio"]?.toBoolean() ?: true

        if (id == null) {
            call.respondText(json.toJson(allSprites))
            return
        }

        val cacheKey = "${gameVal}:$id:${width ?: "orig"}:${height ?: "orig"}:$keepAspectRatio"
        val texture = responseCache[cacheKey]

        call.response.header(HttpHeaders.ContentType, ContentType.Image.PNG.toString())
        call.respondBytes(ContentType.Image.PNG) {
            ByteArrayOutputStream().use { outputStream ->
                ImageIO.write(texture, "png", outputStream)
                outputStream.toByteArray()
            }
        }
    }

    private fun fetchImageWithKey(key: String): BufferedImage {
        val (gameValStr, idStr, widthStr, heightStr, aspectRatioStr) = key.split(":")
        val id = idStr.toInt()
        val width = widthStr.toIntOrNull()
        val height = heightStr.toIntOrNull()
        val keepAspectRatio = aspectRatioStr.toBoolean()

        val originalImage = sprites[id]?.sprites?.first()?.toBufferedImage()
            ?: error("Sprite not found for id: $id")

        return if (width != null || height != null) {
            resizeImage(originalImage, width, height, keepAspectRatio)
        } else {
            originalImage
        }
    }

    private fun resizeImage(
        image: BufferedImage,
        targetWidth: Int?,
        targetHeight: Int?,
        keepAspectRatio: Boolean
    ): BufferedImage {
        val srcWidth = image.width
        val srcHeight = image.height

        val (finalWidth, finalHeight) = when {
            targetWidth != null && targetHeight != null && keepAspectRatio -> {
                val ratio = minOf(targetWidth.toDouble() / srcWidth, targetHeight.toDouble() / srcHeight)
                val w = maxOf(1, (srcWidth * ratio).toInt())
                val h = maxOf(1, (srcHeight * ratio).toInt())
                w to h
            }
            targetWidth != null && targetHeight != null -> {
                val w = maxOf(1, targetWidth)
                val h = maxOf(1, targetHeight)
                w to h
            }
            targetWidth != null -> {
                val newHeight = if (keepAspectRatio) maxOf(1, (srcHeight * (targetWidth.toDouble() / srcWidth)).toInt()) else srcHeight
                val w = maxOf(1, targetWidth)
                w to newHeight
            }
            targetHeight != null -> {
                val newWidth = if (keepAspectRatio) maxOf(1, (srcWidth * (targetHeight.toDouble() / srcHeight)).toInt()) else srcWidth
                val h = maxOf(1, targetHeight)
                newWidth to h
            }
            else -> srcWidth to srcHeight
        }

        val resizedImage = BufferedImage(finalWidth, finalHeight, BufferedImage.TYPE_INT_ARGB)
        val g2d: Graphics2D = resizedImage.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2d.drawImage(image, 0, 0, finalWidth, finalHeight, null)
        g2d.dispose()
        return resizedImage
    }
}