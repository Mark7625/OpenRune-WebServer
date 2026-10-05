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

    /** Build metadata for every model in the cache, including which definitions reference it. */
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
}
