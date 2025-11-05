package dev.openrune.server.endpoints.gamevals

import dev.openrune.ServerConfig
import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.extractor.osrs.GameValManifestEntry
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.util.json
import com.google.gson.reflect.TypeToken
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Shared function to load gameval manifest from disk for a specific type
 */
fun loadGameValManifest(config: ServerConfig, type: GameValGroupTypes): Map<String, GameValManifestEntry> {
    // Get the filename for this type (handles IFTYPES_V2 -> IFTYPES rename)
    val fileName = if (type.name == "IFTYPES_V2") {
        "${GameValGroupTypes.IFTYPES.groupName}.json"
    } else {
        "${type.groupName}.json"
    }
    
    val manifestFile = CachePathHelper.getCacheDirectory(
        config.gameType,
        config.environment,
        config.revision
    ).resolve("extracted").resolve("gamevals").resolve(fileName)

    if (!manifestFile.exists()) {
        logger.warn("GameVal manifest not found at ${manifestFile.path}")
        return emptyMap()
    }

    return try {
        val content = manifestFile.readText()
        
        // Try to load as simple Map<String, Number> first, then convert to Int
        val simpleTypeToken = object : TypeToken<Map<String, Number>>() {}.type
        try {
            val simpleLoaded: Map<String, Number> = json.fromJson(content, simpleTypeToken)
            // Convert to Int and then to full structure
            simpleLoaded.mapValues { (_, id) ->
                GameValManifestEntry(id = id.toInt(), data = null)
            }
        } catch (e: Exception) {
            // Not a simple map, try full structure
            val fullTypeToken = object : TypeToken<Map<String, GameValManifestEntry>>() {}.type
            json.fromJson(content, fullTypeToken)
        }
    } catch (e: Exception) {
        logger.error("Failed to load gameval manifest for ${type.name}: ${e.message}", e)
        emptyMap()
    }
}

/**
 * Load all gameval manifests
 */
fun loadAllGameValManifests(config: ServerConfig): Map<GameValGroupTypes, Map<String, GameValManifestEntry>> {
    val allManifests = mutableMapOf<GameValGroupTypes, Map<String, GameValManifestEntry>>()
    
    GameValGroupTypes.entries.forEach { type ->
        // Skip INVTYPES
        if (type.name == "INVTYPES") {
            return@forEach
        }
        
        val manifest = loadGameValManifest(config, type)
        if (manifest.isNotEmpty()) {
            allManifests[type] = manifest
        }
    }
    
    return allManifests
}

