
import cache.texture.TextureManager
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import dev.openrune.cache.CacheManager
import dev.openrune.cache.SPRITES
import dev.openrune.cache.filestore.definition.data.SpriteType
import dev.openrune.cache.filestore.definition.decoder.SpriteDecoder
import dev.openrune.cache.tools.tasks.impl.sprites.SpriteSet
import io.ktor.server.application.*
import io.ktor.server.response.*
import routes.open.cache.TextureHandler.json
import java.io.ByteArrayOutputStream

data class SpriteInfo(val id : Int, val subIndex : Int, val offsetX : Int, val offsetY : Int)

object SpriteHandler {

    private val allSprites : MutableList<SpriteInfo> = emptyList<SpriteInfo>().toMutableList()

    val sprites: MutableMap<Int, SpriteType> = mutableMapOf()

    private val responseCache: LoadingCache<String, BufferedImage> = Caffeine.newBuilder()
        .expireAfterWrite(12, TimeUnit.HOURS)
        .build { key ->
            runBlocking {
                // Fetch and resize image
                fetchImageWithKey(key)
            }
        }


    fun init() {

        sprites.putAll(SpriteDecoder().load(CacheManager.cache))

        sprites.forEach { sprite ->
            val size = sprites.size
            sprite.value.sprites!!.forEach { subSprite ->
                if (allSprites.count { it.id == sprite.key } == 0) {
                    allSprites.add(SpriteInfo(sprite.key,-1,subSprite.offsetX,subSprite.offsetY))
                }
            }
        }
    }

    suspend fun handleSpriteById(call: ApplicationCall) {
        val id = call.parameters["id"]?.toIntOrNull()
        val width = call.parameters["width"]?.toIntOrNull()
        val height = call.parameters["height"]?.toIntOrNull()
        val keepAspectRatio = call.parameters["keepAspectRatio"]?.toBoolean() ?: true

        if (id == null) {
            call.respondText(json.toJson(allSprites))
            return
        }

        val cacheKey = "$id:${width ?: "orig"}:${height ?: "orig"}:$keepAspectRatio"

        val texture = responseCache[cacheKey]

        call.response.header(HttpHeaders.ContentType, ContentType.Image.PNG.toString())
        call.respondBytes(ContentType.Image.PNG) {
            ByteArrayOutputStream().use { outputStream ->
                ImageIO.write(texture, "png", outputStream)
                outputStream.toByteArray()
            }
        }
    }

    // Method to parse key, decode the image, and resize if necessary
    private fun fetchImageWithKey(key: String): BufferedImage {
        // Parse the key to get id, width, height, and aspectRatio
        val (idStr, widthStr, heightStr, aspectRatioStr) = key.split(":")
        val id = idStr.toInt()
        val width = widthStr.toIntOrNull()
        val height = heightStr.toIntOrNull()
        val keepAspectRatio = aspectRatioStr.toBoolean()

        val originalImage = sprites[id]!!.toSprite()

        return if (width != null && height != null) {
            resizeImage(originalImage, width, height, keepAspectRatio)
        } else {
            originalImage
        }
    }

    // Function to resize an image while optionally keeping the aspect ratio
    private fun resizeImage(image: BufferedImage, targetWidth: Int, targetHeight: Int, keepAspectRatio: Boolean): BufferedImage {
        val width: Int
        val height: Int

        if (keepAspectRatio) {
            val aspectRatio = image.width.toDouble() / image.height.toDouble()
            if (targetWidth.toDouble() / targetHeight.toDouble() > aspectRatio) {
                width = (targetHeight * aspectRatio).toInt()
                height = targetHeight
            } else {
                width = targetWidth
                height = (targetWidth / aspectRatio).toInt()
            }
        } else {
            width = targetWidth
            height = targetHeight
        }

        val resized = BufferedImage(width, height, image.type)
        val g2d: Graphics2D = resized.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2d.drawImage(image.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, width, height, null)
        g2d.dispose()

        return resized
    }
}