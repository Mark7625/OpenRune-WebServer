package dev.openrune.cache.extractor.osrs

import dev.openrune.ServerConfig
import dev.openrune.cache.CacheManager
import dev.openrune.cache.extractor.BaseExtractor
import dev.openrune.filesystem.Cache
import dev.openrune.cache.filestore.definition.ModelDecoder
import dev.openrune.cache.gameval.GameValElement
import dev.openrune.cache.gameval.GameValHandler
import dev.openrune.cache.gameval.GameValHandler.lookup
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.definition.type.ItemType
import dev.openrune.definition.type.NpcType
import dev.openrune.definition.type.ObjectType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.tongfei.progressbar.ProgressBarBuilder
import me.tongfei.progressbar.ProgressBarStyle
import mu.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue

private val logger = KotlinLogging.logger {}

private data class ModelFileWrite(
    val data: ByteArray,
    val archive: Int
)

data class ModelData(
    val totalFaces: Int,
    val totalVerts: Int,
    val textures: IntArray,
    val colors: IntArray,
    val attachments: ModelAttachments
)

data class ModelAttachments(
    val items: MutableSet<GameValElement> = mutableSetOf(),
    val objects: MutableSet<GameValElement> = mutableSetOf(),
    val npcs: MutableSet<GameValElement> = mutableSetOf(),
    var total: Int = 0
) {
    fun addItem(item: GameValElement) {
        items.add(item)
        total++
    }

    fun addObject(obj: GameValElement) {
        objects.add(obj)
        total++
    }

    fun addNpc(npc: GameValElement) {
        npcs.add(npc)
        total++
    }
}

/**
 * Manifest entry for a model archive
 */
data class ModelManifestEntry(
    val totalFaces: Int,
    val totalVerts: Int,
    val textures: List<Int>,
    val colors: List<Int>,
    val attachments: ModelAttachmentsManifest
)

/**
 * Simplified attachments for manifest (using names instead of full GameValElement)
 */
data class ModelAttachmentsManifest(
    val items: List<String> = emptyList(),
    val objects: List<String> = emptyList(),
    val npcs: List<String> = emptyList(),
    val total: Int = 0
)

class ModelsExtractor(config: ServerConfig, cache: Cache) : BaseExtractor(config, cache) {

    private val npcCache = CacheManager.getNpcs()
    private val objectCache = CacheManager.getObjects()
    private val itemCache = CacheManager.getItems()
    private val objectGameVals: List<GameValElement> = GameValHandler.readGameVal(GameValGroupTypes.LOCTYPES, cache)
    private val npcGameVals: List<GameValElement> = GameValHandler.readGameVal(GameValGroupTypes.NPCTYPES, cache)
    private val itemGameVals: List<GameValElement> = GameValHandler.readGameVal(GameValGroupTypes.OBJTYPES, cache)

    private val modelDecoder: ModelDecoder = ModelDecoder(cache)
    
    // Queue for model file writes to be processed in parallel
    private val fileWriteQueue = ConcurrentLinkedQueue<ModelFileWrite>()
    private val batchSize = 1000 // Process file writes in batches of 1000 (for ~59k files)
    
    // Reverse lookup maps for efficient attachment processing (modelId -> list of entityIds)
    private val modelToNpcs: Map<Int, List<Pair<Int, GameValElement>>>
    private val modelToObjects: Map<Int, List<Pair<Int, GameValElement>>>
    private val modelToItems: Map<Int, List<Pair<Int, GameValElement>>>

    init {
        enableDualData("models")
        
        // Build reverse lookup maps for O(1) attachment lookup instead of O(n) iteration
        modelToNpcs = buildModelToNpcsMap()
        modelToObjects = buildModelToObjectsMap()
        modelToItems = buildModelToItemsMap()
    }

    override fun extract(index: Int, archive: Int, file: Int, data: ByteArray) {
        val model = modelDecoder.getModel(archive) ?: return

        // Queue file write instead of writing synchronously
        fileWriteQueue.offer(ModelFileWrite(data, archive))

        val attachments = ModelAttachments(
            items = mutableSetOf(),
            objects = mutableSetOf(),
            npcs = mutableSetOf()
        )

        // Use optimized reverse lookup maps for O(1) attachment processing
        processAttachmentsOptimized(archive, attachments)

        // Calculate unique textures and colors more efficiently
        val textureSet = model.triangleTextures?.let { textures ->
            if (textures.isEmpty()) intArrayOf()
            else textures.map { it and 0xFFFF }.distinct().toIntArray()
        } ?: intArrayOf()
        
        val colorSet = model.triangleColors?.let { colors ->
            if (colors.isEmpty()) intArrayOf()
            else colors.map { (it.toInt() and 0xFFFF) }.distinct().toIntArray()
        } ?: intArrayOf()

        val modelData = ModelData(
            totalFaces = model.triangleCount,
            totalVerts = model.vertexCount,
            textures = textureSet,
            colors = colorSet,
            attachments = attachments
        )

        val webDataEntry = ModelManifestEntry(
            totalFaces = modelData.totalFaces,
            totalVerts = modelData.totalVerts,
            textures = textureSet.toList(), // Already computed, just convert
            colors = colorSet.toList(), // Already computed, just convert
            attachments = ModelAttachmentsManifest(
                items = attachments.items.map { it.name },
                objects = attachments.objects.map { it.name },
                npcs = attachments.npcs.map { it.name },
                total = attachments.total
            )
        )
        
        updateData(archive, webDataEntry, modelData)
        stepProgress()
    }

    /**
     * Build reverse lookup map: modelId -> list of (npcId, gameVal)
     */
    private fun buildModelToNpcsMap(): Map<Int, List<Pair<Int, GameValElement>>> {
        val map = mutableMapOf<Int, MutableList<Pair<Int, GameValElement>>>()
        npcCache.forEach { (npcId, npc) ->
            npc.models?.forEach { modelId ->
                npcGameVals.lookup(npcId)?.let { gameVal ->
                    map.getOrPut(modelId) { mutableListOf() }.add(npcId to gameVal)
                }
            }
        }
        return map
    }
    
    /**
     * Build reverse lookup map: modelId -> list of (objectId, gameVal)
     */
    private fun buildModelToObjectsMap(): Map<Int, List<Pair<Int, GameValElement>>> {
        val map = mutableMapOf<Int, MutableList<Pair<Int, GameValElement>>>()
        objectCache.forEach { (objId, obj) ->
            obj.objectModels?.forEach { modelId ->
                objectGameVals.lookup(objId)?.let { gameVal ->
                    map.getOrPut(modelId) { mutableListOf() }.add(objId to gameVal)
                }
            }
        }
        return map
    }
    
    /**
     * Build reverse lookup map: modelId -> list of (itemId, gameVal)
     */
    private fun buildModelToItemsMap(): Map<Int, List<Pair<Int, GameValElement>>> {
        val map = mutableMapOf<Int, MutableList<Pair<Int, GameValElement>>>()
        itemCache.forEach { (itemId, item) ->
            // Collect all model IDs for this item
            val modelIds = listOfNotNull(
                item.maleModel0, item.maleModel1, item.maleModel2,
                item.maleHeadModel0, item.maleHeadModel1,
                item.femaleModel0, item.femaleModel1, item.femaleModel2,
                item.femaleHeadModel0, item.femaleHeadModel1
            )
            modelIds.forEach { modelId ->
                itemGameVals.lookup(itemId)?.let { gameVal ->
                    map.getOrPut(modelId) { mutableListOf() }.add(itemId to gameVal)
                }
            }
        }
        return map
    }
    
    /**
     * Optimized attachment processing using reverse lookup maps (O(1) instead of O(n))
     */
    private fun processAttachmentsOptimized(archive: Int, attachments: ModelAttachments) {
        modelToNpcs[archive]?.forEach { (_, gameVal) ->
            attachments.addNpc(gameVal)
        }
        
        modelToObjects[archive]?.forEach { (_, gameVal) ->
            attachments.addObject(gameVal)
        }
        
        modelToItems[archive]?.forEach { (_, gameVal) ->
            attachments.addItem(gameVal)
        }
    }
    
    /**
     * Process queued file writes in parallel batches
     */
    private suspend fun processFileWrites() = withContext(Dispatchers.IO) {
        if (fileWriteQueue.isEmpty()) {
            return@withContext
        }
        
        // Drain queue more efficiently using ArrayList with initial capacity estimate
        val queueSize = fileWriteQueue.size
        val writes = ArrayList<ModelFileWrite>(queueSize)
        var polled = fileWriteQueue.poll()
        while (polled != null) {
            writes.add(polled)
            polled = fileWriteQueue.poll()
        }
        
        if (writes.isEmpty()) {
            return@withContext
        }

        // Initialize progress bar for model file writing
        val writeProgressBar = ProgressBarBuilder()
            .setTaskName("Writing model files")
            .setInitialMax(writes.size.toLong())
            .setStyle(ProgressBarStyle.UNICODE_BLOCK)
            .setUpdateIntervalMillis(100)
            .showSpeed()
            .build()
        
        var errorCount = 0
        try {
            coroutineScope {
                // Process writes in batches
                writes.chunked(batchSize).forEach { batch ->
                    val batchJobs = batch.map { write ->
                        async {
                            try {
                                saveBytes(write.data, "models", "${write.archive}.dat")
                                writeProgressBar.step()
                            } catch (e: Exception) {
                                errorCount++
                                writeProgressBar.step()
                                logger.error("Error writing model file ${write.archive}.dat: ${e.message}", e)
                            }
                        }
                    }
                    // Wait for all writes in this batch to complete before starting next batch
                    batchJobs.awaitAll()
                }
            }

        } finally {
            writeProgressBar.close()
        }
    }
    
    override fun end() {
        // Process all queued file writes before ending
        runBlocking {
            processFileWrites()
        }
        
        super.end()
    }
    
    override fun clearExtractionData() {
        // Clear the file write queue
        fileWriteQueue.clear()
        
        // Note: We don't clear npcCache, objectCache, itemCache, or GameVals
        // as they may be needed by other extractors or the server
        
        // Clear parent data
        super.clearExtractionData()
    }

}

