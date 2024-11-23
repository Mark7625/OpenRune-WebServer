package routes.open.cache

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import dev.openrune.cache.CacheManager
import dev.openrune.cache.filestore.definition.data.ItemType
import dev.openrune.game.item.ItemSpriteBuilder
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.coroutines.runBlocking
import routes.open.cache.TextureHandler.json
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

object ItemCache {
    private var cachedItems: List<ItemType>? = null
    private var cachedFilteredItems: List<ItemType>? = null

    fun invalidateCache() {
        cachedItems = null
        cachedFilteredItems = null
    }

    fun getItems(showNulls: Boolean): List<ItemType> {
        return if (showNulls) {
            cachedItems ?: CacheManager.getItems().values.sortedBy { it.id }.also { cachedItems = it }
        } else {
            cachedFilteredItems ?: CacheManager.getItems().values
                .filter { it.name != "null" }
                .sortedBy { it.id }
                .also { cachedFilteredItems = it }
        }
    }
}

object ItemHandler {

    private val responseCache: LoadingCache<String, BufferedImage> = Caffeine.newBuilder()
        .expireAfterWrite(12, TimeUnit.HOURS)
        .build { key ->
            runBlocking { fetchImageWithKey(key) }
        }

    suspend fun handleItemRequest(call: ApplicationCall) {
        val id = call.parameters["id"]?.toIntOrNull()
        val isIconRequest = call.request.path().endsWith("/icon")
        val showNulls = call.parameters["nulls"]?.toBooleanStrictOrNull() ?: true

        if (id == null) {
            call.respondText(json.toJson(ItemCache.getItems(showNulls)))
            return
        }

        val itemExists = CacheManager.getItems().contains(id)
        if (!itemExists) {
            call.respond(HttpStatusCode.NotFound, "Item Not Found")
            return
        }

        if (isIconRequest) {
            handleIconRequest(call, id)
        } else {
            // Respond with item details
            call.respondText(json.toJson(CacheManager.getItem(id)))
        }
    }

    private suspend fun handleIconRequest(call: ApplicationCall, id: Int) {
        val params = parseParameters(call, id)
        val cacheKey = generateCacheKey(params)

        val icon = responseCache[cacheKey]

        icon?.let {
            call.response.header(HttpHeaders.ContentType, ContentType.Image.PNG.toString())
            call.respondBytes(ContentType.Image.PNG) {
                ByteArrayOutputStream().use { outputStream ->
                    ImageIO.write(it, "png", outputStream)
                    outputStream.toByteArray()
                }
            }
        } ?: call.respond(HttpStatusCode.NotFound, "Icon Not Found")
    }

    private fun fetchImageWithKey(key: String): BufferedImage {
        val params = parseCacheKey(key)
        val itemSpriteBuilder = ItemSpriteBuilder(params.id)
            .quantity(params.quantity)
            .width(params.width)
            .height(params.height)
            .border(params.border)
            .shadowColor(params.shadowColor)
            .noted(params.isNoted)
            .xan2d(params.xan2d)
            .yan2d(params.yan2d)
            .zan2d(params.zan2d)
            .zoom2d(params.zoom2d)
            .xOffset2d(params.xOffset2d)
            .yOffset2d(params.yOffset2d)

        return itemSpriteBuilder.build()
    }

    private fun parseParameters(call: ApplicationCall, id: Int): IconParams {
        return IconParams(
            id = id,
            quantity = call.parameters["quantity"]?.toIntOrNull() ?: 1,
            border = call.parameters["border"]?.toIntOrNull() ?: 1,
            shadowColor = call.parameters["shadowColor"]?.toIntOrNull() ?: 3153952,
            isNoted = call.parameters["isNoted"]?.toBooleanStrictOrNull() ?: false,
            xan2d = call.parameters["xan2d"]?.toIntOrNull() ?: -1,
            yan2d = call.parameters["yan2d"]?.toIntOrNull() ?: -1,
            zan2d = call.parameters["zan2d"]?.toIntOrNull() ?: -1,
            xOffset2d = call.parameters["xOffset2d"]?.toIntOrNull() ?: -1,
            yOffset2d = call.parameters["yOffset2d"]?.toIntOrNull() ?: -1,
            width = call.parameters["width"]?.toIntOrNull() ?: 36,
            height = call.parameters["height"]?.toIntOrNull() ?: 36,
            zoom2d = call.parameters["zoom2d"]?.toIntOrNull() ?: -1,
        )
    }

    private fun generateCacheKey(params: IconParams): String {
        return "${params.id}:${params.quantity}:${params.border}:${params.shadowColor}:${params.isNoted}:${params.xan2d}:${params.yan2d}:${params.zan2d}:${params.xOffset2d}:${params.yOffset2d}:${params.width}:${params.height}:${params.zoom2d}"
    }

    private fun parseCacheKey(key: String): IconParams {
        val parts = key.split(":")
        return IconParams(
            id = parts[0].toInt(),
            quantity = parts[1].toInt(),
            border = parts[2].toInt(),
            shadowColor = parts[3].toInt(),
            isNoted = parts[4].toBoolean(),
            xan2d = parts[5].toInt(),
            yan2d = parts[6].toInt(),
            zan2d = parts[7].toInt(),
            xOffset2d = parts[8].toInt(),
            yOffset2d = parts[9].toInt(),
            width = parts[10].toInt(),
            height = parts[11].toInt(),
            zoom2d = parts[12].toInt(),
        )
    }

    private data class IconParams(
        val id: Int,
        val quantity: Int,
        val border: Int,
        val shadowColor: Int,
        val isNoted: Boolean,
        val xan2d: Int,
        val yan2d: Int,
        val zan2d: Int,
        val xOffset2d: Int,
        val yOffset2d: Int,
        val width: Int,
        val height: Int,
        val zoom2d: Int
    )
}
