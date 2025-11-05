package dev.openrune.cache.extractor.osrs

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.BaseExtractor
import dev.openrune.filesystem.Cache
import com.google.gson.reflect.TypeToken
import dev.openrune.OsrsCacheProvider
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.definition.codec.TextureCodec
import dev.openrune.definition.codec.SpriteCodec
import dev.openrune.cache.gameval.GameValHandler
import dev.openrune.cache.gameval.GameValHandler.lookupAs
import dev.openrune.cache.tools.tasks.impl.sprites.Sprite
import dev.openrune.definition.type.OverlayType
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Manifest entry for a texture
 */
data class TextureManifestEntry(
    val fileId: Int,
    val isTransparent: Boolean,
    val averageRgb: Int,
    val name: String?,
    val animationDirection: Int,
    val animationSpeed: Int,
    val attachments: TextureAttachmentsManifest
)

/**
 * Simplified attachments for manifest
 */
data class TextureAttachmentsManifest(
    val overlays: List<Int> = emptyList(),
    val items: List<String> = emptyList(),
    val objects: List<String> = emptyList(),
    val npcs: List<String> = emptyList(),
    val total: Int = 0
)

class TexturesExtractor(config: ServerConfig, cache: Cache) : BaseExtractor(config, cache) {

    private val textureCodec = TextureCodec(config.revision)

    private val gamevals = GameValHandler.readGameVal(GameValGroupTypes.SPRITETYPES, cache)

    private val modelManifest: Map<Int, ModelManifestEntry> by lazy {
        loadModelManifest()
    }

    private fun loadModelManifest(): Map<Int, ModelManifestEntry> {
        // Try data_web.json first, then fall back to legacy manifest.json
        val dataFile = getManifestFile("models", "data_web.json")
        val manifestFile = if (dataFile.exists()) {
            dataFile
        } else {
            getManifestFile("models", "manifest.json")
        }
        
        if (!manifestFile.exists()) {
            return emptyMap()
        }

        return try {
            val content = manifestFile.readText()
            val type = object : TypeToken<Map<String, ModelManifestEntry>>() {}.type
            val loaded: Map<String, ModelManifestEntry> = json.fromJson(content, type)

            loaded.mapNotNull { (key, entry) ->
                key.toIntOrNull()?.let { modelId -> modelId to entry }
            }.toMap()
        } catch (e: Exception) {
            logger.warn("Failed to load model manifest: ${e.message}")
            emptyMap()
        }
    }


    private var loadedOverlays: MutableMap<Int, OverlayType> = mutableMapOf()

    init {
        enableDualData("textures")
        OsrsCacheProvider.OverlayDecoder().load(cache, loadedOverlays)
    }

    override fun extract(index: Int, archive: Int, file: Int, data: ByteArray) {
        val texture = textureCodec.loadData(archive, data)

        val overlayIds = mutableListOf<Int>()
        val itemNames = mutableSetOf<String>()
        val objectNames = mutableSetOf<String>()
        val npcNames = mutableSetOf<String>()

        loadedOverlays.filter { overlay -> overlay.value.texture == file }.forEach { overlayType ->
            overlayIds.add(overlayType.key)
        }

        modelManifest
            .filter { model -> model.value.textures.contains(file) }
            .forEach { model ->
                itemNames.addAll(model.value.attachments.items)
                objectNames.addAll(model.value.attachments.objects)
                npcNames.addAll(model.value.attachments.npcs)
            }

        val webDataEntry = TextureManifestEntry(
            fileId = texture.fileId,
            name = gamevals.lookupAs<dev.openrune.cache.gameval.impl.Sprite>(texture.fileId)?.name,
            isTransparent = texture.isTransparent,
            averageRgb = texture.averageRgb,
            animationDirection = texture.animationDirection,
            animationSpeed = texture.animationSpeed,
            attachments = TextureAttachmentsManifest(
                overlays = overlayIds,
                items = itemNames.toList(),
                objects = objectNames.toList(),
                npcs = npcNames.toList(),
                total = overlayIds.size + itemNames.size + objectNames.size + npcNames.size
            )
        )

        getExtractionPath("sprites", "${texture.fileId}.png").copyTo(
            getExtractionPath(
                "textures",
                "${texture.fileId}.png"
            ), true
        )
        
        updateData(file, webDataEntry, texture)

        stepProgress()
    }
}
