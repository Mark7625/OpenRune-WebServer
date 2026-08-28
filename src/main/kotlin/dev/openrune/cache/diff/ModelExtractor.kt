package dev.openrune.cache.diff

import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.MODELS
import dev.openrune.cache.filestore.definition.ModelDecoder
import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import dev.openrune.definition.type.ItemType
import dev.openrune.definition.type.NpcType
import dev.openrune.definition.type.ObjectType
import dev.openrune.filesystem.Cache
import me.tongfei.progressbar.ProgressBarBuilder
import me.tongfei.progressbar.ProgressBarStyle
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Decodes the model index into [ModelMeta] — the per-model summary stored in the revision `.bin`.
 *
 * Meshes are decoded one at a time and thrown away; only the summary is retained, so a full
 * ~60k model index costs little memory. Raw `.dat` bytes go to the CDN separately ([ModelCdn]).
 */
object ModelExtractor {

    /** Per-revision metadata sidecar, written next to that revision's downloaded cache. */
    fun jsonFile(gameType: GameType, environment: CacheEnvironment, rev: Int): File =
        File(CachePathHelper.getCacheDirectory(gameType, environment, rev), "models.json")

    /**
     * Metadata for [rev], decoding meshes only when there is no usable sidecar.
     *
     * A revision's cache never changes once downloaded, so `models.json` is written on the first
     * pass and reused by every later dump / migrate run instead of decoding ~60k meshes again.
     * Pass [force] to ignore an existing sidecar and rebuild it.
     */
    fun loadOrExtract(
        cache: Cache,
        gameType: GameType,
        environment: CacheEnvironment,
        rev: Int,
        attachments: () -> Attachments,
        ids: List<Int> = modelIds(cache),
        force: Boolean = false,
        showProgressBar: Boolean = true,
        onProgress: (String) -> Unit = {},
    ): Map<Int, ModelMeta> {
        val sidecar = jsonFile(gameType, environment, rev)
        if (!force) {
            ModelJson.readFile(sidecar)?.let { cached ->
                onProgress("Models reused from ${sidecar.name} (${cached.size}, no mesh decode)")
                return cached
            }
        }
        val models = extract(cache, attachments(), ids, showProgressBar, onProgress)
        if (models.isNotEmpty()) {
            ModelJson.writeFile(sidecar, models)
            onProgress("Models written to ${sidecar.name} (${models.size})")
        }
        return models
    }

    fun modelIds(cache: Cache): List<Int> =
        runCatching { cache.archives(MODELS).toList().sorted() }
            .getOrElse { e ->
                logger.warn(e) { "Failed listing model archives" }
                emptyList()
            }

    /** Reverse attachment maps: model id -> ids of the definitions that reference it. */
    data class Attachments(
        val items: Map<Int, List<Int>> = emptyMap(),
        val npcs: Map<Int, List<Int>> = emptyMap(),
        val objects: Map<Int, List<Int>> = emptyMap(),
    )

    fun attachmentsFrom(
        itemTypes: Map<Int, ItemType>,
        npcTypes: Map<Int, NpcType>,
        objectTypes: Map<Int, ObjectType>,
    ): Attachments = Attachments(
        items = reverseItems(itemTypes),
        npcs = reverseNpcs(npcTypes),
        objects = reverseObjects(objectTypes),
    )

    /**
     * Reverse attachments derived from already-serialized config snapshots, so callers holding
     * merged `.bin` content do not have to re-decode the config archives.
     */
    fun attachmentsFromSnapshots(configs: Map<String, Map<Int, DefinitionSnapshot>>): Attachments {
        val items = HashMap<Int, MutableList<Int>>()
        configs[ConfigDiffType.ITEMS.fileName]?.forEach { (itemId, snapshot) ->
            ITEM_MODEL_FIELDS.forEach { field ->
                snapshot.intValue(field)?.let { modelId ->
                    if (modelId > 0) items.getOrPut(modelId) { mutableListOf() }.add(itemId)
                }
            }
        }
        val npcs = HashMap<Int, MutableList<Int>>()
        configs[ConfigDiffType.NPCS.fileName]?.forEach { (npcId, snapshot) ->
            (snapshot.intListValue("models") + snapshot.intListValue("chatheadModels")).forEach { modelId ->
                if (modelId > 0) npcs.getOrPut(modelId) { mutableListOf() }.add(npcId)
            }
        }
        val objects = HashMap<Int, MutableList<Int>>()
        configs[ConfigDiffType.OBJECTS.fileName]?.forEach { (objectId, snapshot) ->
            snapshot.intListValue("objectModels").forEach { modelId ->
                if (modelId > 0) objects.getOrPut(modelId) { mutableListOf() }.add(objectId)
            }
        }
        return Attachments(items.sortDistinct(), npcs.sortDistinct(), objects.sortDistinct())
    }

    /**
     * Build metadata for every model in the cache, including which items / npcs / objects use it.
     */
    fun extract(
        cache: Cache,
        itemTypes: Map<Int, ItemType>,
        npcTypes: Map<Int, NpcType>,
        objectTypes: Map<Int, ObjectType>,
        ids: List<Int> = modelIds(cache),
        showProgressBar: Boolean = true,
        onProgress: (String) -> Unit = {},
    ): Map<Int, ModelMeta> = extract(
        cache = cache,
        attachments = attachmentsFrom(itemTypes, npcTypes, objectTypes),
        ids = ids,
        showProgressBar = showProgressBar,
        onProgress = onProgress,
    )

    fun extract(
        cache: Cache,
        attachments: Attachments,
        ids: List<Int> = modelIds(cache),
        showProgressBar: Boolean = true,
        onProgress: (String) -> Unit = {},
    ): Map<Int, ModelMeta> {
        if (ids.isEmpty()) return emptyMap()

        val itemsByModel = attachments.items
        val npcsByModel = attachments.npcs
        val objectsByModel = attachments.objects

        val decoder = ModelDecoder(cache)
        val out = LinkedHashMap<Int, ModelMeta>(ids.size)
        var failed = 0

        val bar = if (showProgressBar) {
            ProgressBarBuilder()
                .setTaskName("models")
                .setInitialMax(ids.size.toLong())
                .setStyle(ProgressBarStyle.UNICODE_BLOCK)
                .setUpdateIntervalMillis(200)
                .build()
        } else {
            null
        }
        try {
            for (id in ids) {
                val model = runCatching { decoder.getModel(id) }.getOrElse { e ->
                    logger.debug(e) { "Failed decoding model $id" }
                    null
                }
                if (model == null) {
                    failed++
                } else {
                    out[id] = ModelMeta(
                        vertexCount = model.vertexCount,
                        faceCount = model.triangleCount,
                        texturedFaceCount = model.textureTriangleCount,
                        transparentFaceCount = model.triangleAlphas?.count { it != 0 } ?: 0,
                        version = model.version,
                        renderPriority = model.renderPriority,
                        textures = model.triangleTextures
                            ?.filter { it != -1 }
                            ?.map { it and 0xFFFF }
                            ?.distinct()
                            ?.sorted()
                            .orEmpty(),
                        colors = model.triangleColors
                            ?.map { it.toInt() and 0xFFFF }
                            ?.distinct()
                            ?.sorted()
                            .orEmpty(),
                        itemIds = itemsByModel[id].orEmpty(),
                        npcIds = npcsByModel[id].orEmpty(),
                        objectIds = objectsByModel[id].orEmpty(),
                    )
                }
                bar?.step()
            }
        } finally {
            bar?.close()
        }

        onProgress("Models decoded (${out.size}${if (failed > 0) ", $failed unreadable" else ""})")
        return out
    }

    private val ITEM_MODEL_FIELDS = listOf(
        "inventoryModel",
        "maleModel0", "maleModel1", "maleModel2",
        "maleHeadModel0", "maleHeadModel1",
        "femaleModel0", "femaleModel1", "femaleModel2",
        "femaleHeadModel0", "femaleHeadModel1",
    )

    /**
     * Model ids a single definition references, read straight from its stored snapshot.
     * [type] is a [ConfigDiffType] file name; anything without model fields yields an empty list.
     */
    fun modelIdsForDefinition(type: String, snapshot: DefinitionSnapshot): List<Int> {
        val ids = when (type) {
            ConfigDiffType.ITEMS.fileName -> ITEM_MODEL_FIELDS.mapNotNull { snapshot.intValue(it) }
            ConfigDiffType.NPCS.fileName ->
                snapshot.intListValue("models") + snapshot.intListValue("chatheadModels")
            ConfigDiffType.OBJECTS.fileName -> snapshot.intListValue("objectModels")
            else -> emptyList()
        }
        return ids.filter { it > 0 }.distinct().sorted()
    }

    private fun DefinitionSnapshot.intValue(field: String): Int? = this[field]?.value as? Int

    @Suppress("UNCHECKED_CAST")
    private fun DefinitionSnapshot.intListValue(field: String): List<Int> {
        val value = this[field]?.value ?: return emptyList()
        return (value as? List<*>)?.filterIsInstance<Int>() ?: emptyList()
    }

    private fun Map<Int, MutableList<Int>>.sortDistinct(): Map<Int, List<Int>> =
        mapValues { (_, ids) -> ids.distinct().sorted() }

    private fun reverseItems(itemTypes: Map<Int, ItemType>): Map<Int, List<Int>> {
        val map = HashMap<Int, MutableList<Int>>()
        itemTypes.forEach { (itemId, item) ->
            val modelIds = listOf(
                item.inventoryModel,
                item.maleModel0, item.maleModel1, item.maleModel2,
                item.maleHeadModel0, item.maleHeadModel1,
                item.femaleModel0, item.femaleModel1, item.femaleModel2,
                item.femaleHeadModel0, item.femaleHeadModel1,
            )
            modelIds.forEach { modelId ->
                if (modelId > 0) map.getOrPut(modelId) { mutableListOf() }.add(itemId)
            }
        }
        return map.mapValues { (_, ids) -> ids.distinct().sorted() }
    }

    private fun reverseNpcs(npcTypes: Map<Int, NpcType>): Map<Int, List<Int>> {
        val map = HashMap<Int, MutableList<Int>>()
        npcTypes.forEach { (npcId, npc) ->
            (npc.models.orEmpty() + npc.chatheadModels.orEmpty()).forEach { modelId ->
                if (modelId > 0) map.getOrPut(modelId) { mutableListOf() }.add(npcId)
            }
        }
        return map.mapValues { (_, ids) -> ids.distinct().sorted() }
    }

    private fun reverseObjects(objectTypes: Map<Int, ObjectType>): Map<Int, List<Int>> {
        val map = HashMap<Int, MutableList<Int>>()
        objectTypes.forEach { (objectId, obj) ->
            obj.objectModels.orEmpty().forEach { modelId ->
                if (modelId > 0) map.getOrPut(modelId) { mutableListOf() }.add(objectId)
            }
        }
        return map.mapValues { (_, ids) -> ids.distinct().sorted() }
    }

    /** Compare a revision's models against the base snapshot, mirroring the config diff shape. */
    fun diff(base: Map<Int, ModelMeta>, current: Map<Int, ModelMeta>): ConfigDiffSummary {
        val baseIds = base.keys
        val currentIds = current.keys
        return ConfigDiffSummary(
            added = (currentIds - baseIds).sorted(),
            removed = (baseIds - currentIds).sorted(),
            changed = (baseIds intersect currentIds).filter { current[it] != base[it] }.sorted(),
        )
    }
}
