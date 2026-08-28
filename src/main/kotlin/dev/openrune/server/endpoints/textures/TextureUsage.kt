package dev.openrune.server.endpoints.textures

import dev.openrune.ServerConfig
import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.cache.diff.DefinitionSnapshot
import dev.openrune.cache.diff.DiffBinaryCache

/**
 * Which definitions use a texture at a given revision.
 *
 * Nothing extra is stored for this — it is the inverse of data already in the revision binary.
 * A definition counts as using the texture when any of these hold:
 *  - one of its models has the texture on a face (the usual case)
 *  - it retextures *from* the texture (`originalTextureColours`)
 *  - it retextures *to* the texture (`modifiedTextureColours`), so the texture is what renders
 *
 * Those three sources are merged into one list per definition kind. Overlays are separate because
 * they name a texture directly through `OverlayType.texture` rather than through a model.
 */
data class TextureUsage(
    val models: List<Int> = emptyList(),
    val overlays: List<Int> = emptyList(),
    val items: List<Int> = emptyList(),
    val npcs: List<Int> = emptyList(),
    val objects: List<Int> = emptyList(),
) {
    val total: Int
        get() = models.size + overlays.size + items.size + npcs.size + objects.size
}

object TextureUsageIndex {

    private data class Key(val game: String, val environment: String, val rev: Int)

    private const val MAX_CACHED = 4
    private val lock = Any()
    private val cache = object : LinkedHashMap<Key, Map<Int, TextureUsage>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Map<Int, TextureUsage>>): Boolean =
            size > MAX_CACHED
    }

    /** Texture id -> everything that uses it at [rev]. */
    fun forRevision(config: ServerConfig, rev: Int): Map<Int, TextureUsage> {
        val key = Key(config.gameType.name, config.environment.name, rev)
        synchronized(lock) { cache[key]?.let { return it } }
        val built = build(config, rev)
        synchronized(lock) { cache[key] = built }
        return built
    }

    private fun build(config: ServerConfig, rev: Int): Map<Int, TextureUsage> {
        val models = HashMap<Int, MutableSet<Int>>()
        val items = HashMap<Int, MutableSet<Int>>()
        val npcs = HashMap<Int, MutableSet<Int>>()
        val objects = HashMap<Int, MutableSet<Int>>()

        DiffBinaryCache.getCombinedModels(config, rev).forEach { (modelId, meta) ->
            meta.textures.forEach { textureId ->
                models.getOrPut(textureId) { mutableSetOf() }.add(modelId)
                if (meta.itemIds.isNotEmpty()) items.getOrPut(textureId) { mutableSetOf() }.addAll(meta.itemIds)
                if (meta.npcIds.isNotEmpty()) npcs.getOrPut(textureId) { mutableSetOf() }.addAll(meta.npcIds)
                if (meta.objectIds.isNotEmpty()) objects.getOrPut(textureId) { mutableSetOf() }.addAll(meta.objectIds)
            }
        }

        // Retexture pairs fold into the same buckets: both the texture being replaced and the
        // replacement count as "this definition involves that texture".
        addRetextureRefs(config, rev, ConfigDiffType.ITEMS.fileName, items)
        addRetextureRefs(config, rev, ConfigDiffType.NPCS.fileName, npcs)
        addRetextureRefs(config, rev, ConfigDiffType.OBJECTS.fileName, objects)

        val overlays = HashMap<Int, MutableSet<Int>>()
        DiffBinaryCache.getTypedCombinedConfig(config, ConfigDiffType.OVERLAY.fileName, rev)
            .forEach { (overlayId, snapshot) ->
                val textureId = snapshot.intValue("texture") ?: return@forEach
                if (textureId >= 0) overlays.getOrPut(textureId) { mutableSetOf() }.add(overlayId)
            }

        val textureIds = HashSet<Int>()
        textureIds.addAll(models.keys)
        textureIds.addAll(overlays.keys)
        textureIds.addAll(items.keys)
        textureIds.addAll(npcs.keys)
        textureIds.addAll(objects.keys)

        return textureIds.sorted().associateWith { textureId ->
            TextureUsage(
                models = models[textureId]?.sorted().orEmpty(),
                overlays = overlays[textureId]?.sorted().orEmpty(),
                items = items[textureId]?.sorted().orEmpty(),
                npcs = npcs[textureId]?.sorted().orEmpty(),
                objects = objects[textureId]?.sorted().orEmpty(),
            )
        }
    }

    /** Fold `originalTextureColours` (replaced) and `modifiedTextureColours` (replacement) into [target]. */
    private fun addRetextureRefs(
        config: ServerConfig,
        rev: Int,
        type: String,
        target: HashMap<Int, MutableSet<Int>>,
    ) {
        DiffBinaryCache.getTypedCombinedConfig(config, type, rev).forEach { (defId, snapshot) ->
            val textureIds = snapshot.intListValue("originalTextureColours") +
                snapshot.intListValue("modifiedTextureColours")
            textureIds.forEach { textureId ->
                if (textureId >= 0) target.getOrPut(textureId) { mutableSetOf() }.add(defId)
            }
        }
    }

    private fun DefinitionSnapshot.intValue(field: String): Int? = this[field]?.value as? Int

    private fun DefinitionSnapshot.intListValue(field: String): List<Int> {
        val value = this[field]?.value ?: return emptyList()
        return (value as? List<*>)?.filterIsInstance<Int>() ?: emptyList()
    }
}
