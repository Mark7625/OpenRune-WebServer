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
            spriteType.sprites.forEach { subSprite ->
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
        val (gameValStr,idStr, widthStr, heightStr, aspectRatioStr) = key.split(":")
        val gameVal = gameValStr
        val id = idStr.toInt()
        val width = widthStr.toIntOrNull()
        val height = heightStr.toIntOrNull()
        val keepAspectRatio = aspectRatioStr.toBoolean()

        val originalImage = sprites[id]?.getSprite(true) ?: error("Sprite not found for id: $id")

        return if (width != null && height != null) {
            try {
                resizeImage(originalImage, width, height, keepAspectRatio)
            } catch (e: Exception) {
                originalImage
            }
        } else {
            originalImage
        }
    }

    private fun resizeImage(image: BufferedImage, targetWidth: Int, targetHeight: Int, keepAspectRatio: Boolean): BufferedImage {
        val (width, height) = if (keepAspectRatio) {
            val aspectRatio = image.width.toDouble() / image.height.toDouble()
            if (targetWidth.toDouble() / targetHeight > aspectRatio) {
                ((targetHeight * aspectRatio).toInt()) to targetHeight
            } else {
                targetWidth to (targetWidth / aspectRatio).toInt()
            }
        } else {
            targetWidth to targetHeight
        }

        val resized = BufferedImage(width, height, image.type)
        return resized
    }
}
