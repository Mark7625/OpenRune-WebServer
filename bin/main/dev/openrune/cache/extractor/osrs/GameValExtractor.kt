package dev.openrune.cache.extractor.osrs

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.BaseExtractor
import dev.openrune.cache.gameval.GameValHandler
import dev.openrune.cache.gameval.impl.Sprite
import dev.openrune.cache.gameval.impl.Table
import dev.openrune.cache.gameval.impl.Interface
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.filesystem.Cache
import com.google.gson.reflect.TypeToken
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Manifest entry for a single gameval file
 */
data class GameValManifestEntry(
    val id: Int,
    val data: Map<String, Any>? = null
)

/**
 * Simple data structure for manifest storage (id, name, and type-specific fields as maps)
 */
data class GameValSimpleData(
    val id: Int,
    val name: String,
    val data: Map<String, Any> = emptyMap()
)

class GameValExtractor(config: ServerConfig, cache: Cache) : BaseExtractor(config, cache) {

    private val manifests = mutableMapOf<GameValGroupTypes, MutableMap<String, GameValManifestEntry>>()

    init {
        loadExistingManifests()
    }

    private fun loadExistingManifests() {
        GameValGroupTypes.entries.forEach { type ->
            if (type.name == "INVTYPES") {
                return@forEach
            }
            
            loadManifestForType(type)
        }
    }

    private fun loadManifestForType(type: GameValGroupTypes) {
        val fileName = if (type.name == "IFTYPES_V2") "${GameValGroupTypes.IFTYPES.groupName}.json" else "${type.groupName}.json"
        val manifestFile = getManifestFile("gamevals", fileName)
        if (!manifestFile.exists()) {
            return
        }

        try {
            val content = manifestFile.readText()
            
            val simpleTypeToken = object : TypeToken<Map<String, Number>>() {}.type
            try {
                val simpleLoaded: Map<String, Number> = json.fromJson(content, simpleTypeToken)
                val typeManifest = simpleLoaded.mapValues { (_, id) ->
                    GameValManifestEntry(id = id.toInt(), data = null)
                }.toMutableMap()
                manifests[type] = typeManifest
                logger.debug("Loaded ${typeManifest.size} entries from simple gameval manifest: ${type.name}")
                return
            } catch (e: Exception) {
            }
            
            val fullTypeToken = object : TypeToken<Map<String, GameValManifestEntry>>() {}.type
            val loaded: Map<String, GameValManifestEntry> = json.fromJson(content, fullTypeToken)
            val typeManifest = loaded.toMutableMap()
            
            manifests[type] = typeManifest
            logger.debug("Loaded ${typeManifest.size} entries from gameval manifest: ${type.name}")
        } catch (e: Exception) {
            logger.warn("Failed to load gameval manifest for ${type.name}: ${e.message}")
        }
    }

    override fun extract(index: Int, archive: Int, file: Int,data: ByteArray) {
        try {
            val type = GameValGroupTypes.fromId(archive)
            
            if (type.name == "INVTYPES") {
                stepProgress()
                return
            }
            
            val gamevals = GameValHandler.unpackGameVal(type, file, data)

            val gamevalManifestData = gamevals.map { gameval ->
                when (gameval) {
                    is Sprite -> GameValSimpleData(
                        id = gameval.id,
                        name = gameval.name,
                    )
                    is Table -> GameValSimpleData(
                        id = gameval.id,
                        name = gameval.name,
                        data = mapOf(
                            "columns" to gameval.columns.map { column ->
                                mapOf(
                                    "id" to column.id,
                                    "name" to column.name
                                )
                            }
                        )
                    )
                    is Interface -> GameValSimpleData(
                        id = gameval.id,
                        name = gameval.name,
                        data = mapOf(
                            "components" to gameval.components.map { component ->
                                mapOf(
                                    "componentId" to component.id,
                                    "name" to component.name,
                                    "packed" to component.packed
                                )
                            }
                        )
                    )
                    else -> GameValSimpleData(
                        id = gameval.id,
                        name = gameval.name
                    )
                }
            }

            val typeManifest = manifests.getOrPut(type) { mutableMapOf() }

            gamevalManifestData.forEach { gamevalData ->
                typeManifest[gamevalData.name] = GameValManifestEntry(
                    id = file,
                    data = gamevalData.data
                )
            }

            stepProgress()
        } catch (e: Exception) {
            logger.warn("Failed to extract gameval: archive=$archive, file=$file, error=${e.message}")
        }
    }

    override fun end() {
        manifests.forEach { (type, typeManifest) ->
            if (typeManifest.isEmpty()) {
                return@forEach
            }

            try {
                val fileName = if (type.name == "IFTYPES_V2") "${GameValGroupTypes.IFTYPES.groupName}.json" else "${type.groupName}.json"

                val allSimple = typeManifest.values.all { it.data == null || it.data.isEmpty() }
                
                if (allSimple) {
                    val simpleMap = typeManifest.mapValues { it.value.id }
                    saveManifest(simpleMap, "gamevals", fileName)
                } else {
                    saveManifest(typeManifest, "gamevals", fileName)
                }
            } catch (e: Exception) {
                logger.error("Failed to save gameval manifest for ${type.name}: ${e.message}", e)
            }
        }
    }
}

