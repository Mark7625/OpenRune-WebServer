package dev.openrune.server.endpoints

import dev.openrune.ServerConfig
import dev.openrune.cache.CachePathHelper
import dev.openrune.server.endpoints.sprites.DataType
import dev.openrune.util.json
import com.google.gson.reflect.TypeToken
import dev.openrune.server.WebServer
import mu.KotlinLogging
import java.io.File

/**
 * Generic function to load manifest data from disk
 * @param config Server configuration
 * @param category Category name (e.g., "sprites", "textures", "models")
 * @param type DataType (WEB or CACHE)
 * @return Map of ID to data entry
 */
inline fun <reified E : Any> loadManifestData(
    config: ServerConfig,
    category: String,
    type: DataType = DataType.WEB
): Map<Int, Any> {
    val filename = when (type) {
        DataType.WEB -> "data_web.json"
        DataType.CACHE -> "data_cache.json"
    }
    
    val dataFile = CachePathHelper.getCacheDirectory(
        config.gameType,
        config.environment,
        config.revision
    ).resolve("extracted").resolve(category).resolve(filename)

    return try {
        val content = dataFile.readText()
        if (type == DataType.WEB) {
            val typeToken = object : TypeToken<Map<String, E>>() {}.type
            val loaded: Map<String, E> = json.fromJson(content, typeToken)
            loaded.mapNotNull { (key, entry) ->
                key.toIntOrNull()?.let { archiveId -> archiveId to entry }
            }.toMap()
        } else {
            val typeToken = object : TypeToken<Map<String, Any>>() {}.type
            val loaded: Map<String, Any> = json.fromJson(content, typeToken)
            loaded.mapNotNull { (key, entry) ->
                key.toIntOrNull()?.let { archiveId -> archiveId to entry }
            }.toMap()
        }
    } catch (e: Exception) {
        WebServer.logger.error("Failed to load $category data: ${e.message}", e)
        emptyMap()
    }
}