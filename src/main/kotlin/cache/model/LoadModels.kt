
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.openrune.cache.CacheManager
import dev.openrune.cache.MODELS
import dev.openrune.cache.filestore.definition.ModelDecoder
import dev.openrune.definition.type.ItemType
import dev.openrune.definition.type.NpcType
import dev.openrune.definition.type.ObjectType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

data class ModelData(
    val id : Int,
    val totalFaces: Int,
    val totalVerts: Int,
    val textures: IntArray,
    val colors: IntArray,
    val attachments: ModelAttachments
)

data class ModelAttachments(
    val items: MutableList<String>,
    val objects: MutableList<String>,
    val npcs: MutableList<String>,
    var total: Int = 0 // Total count of all attachments, initialized at 0
) {
    fun addItem(item: String) {
        items.add(item)
        total++ // Increment total in real-time
    }

    fun addObject(obj: String) {
        objects.add(obj)
        total++ // Increment total in real-time
    }

    fun addNpc(npc: String) {
        npcs.add(npc)
        total++ // Increment total in real-time
    }
}

object LoadModels {
    lateinit var modelDecoder: ModelDecoder

    var models: MutableMap<Int, ModelData> = ConcurrentHashMap()

    val manifest = File("./data/models/manifest.json")

    suspend fun showProgressBar(progress: Int, total: Int) {
        val percent = (progress.toDouble() / total * 100).roundToInt()
        val barLength = 50
        val filledLength = (barLength * progress / total).coerceAtMost(barLength)
        val bar = "#".repeat(filledLength) + "-".repeat(barLength - filledLength)
        print("\rProcessing models: |$bar| $percent% ($progress/$total)")
    }

    fun init() {
        modelDecoder = ModelDecoder(gameCache)
        if (!manifest.exists()) {
            loadModelData()
        } else {
            val typeToken = object : TypeToken<MutableMap<Int, ModelData>>() {}.type
            models = Gson().fromJson<MutableMap<Int, ModelData>>(manifest.readText(), typeToken) ?: ConcurrentHashMap()
        }
    }

    private fun loadModelData() {
        val npcCache = CacheManager.getNpcs()
        val objectCache = CacheManager.getObjects()
        val itemCache = CacheManager.getItems()

        // Launching coroutines to process archives concurrently
        runBlocking {
            val archives = gameCache.archives(MODELS)
            val totalArchives = archives.size
            var processedCount = 0

            val lock = Any()

            archives.map { archive ->
                launch(Dispatchers.IO) {
                    try {
                        val model = modelDecoder.getModel(archive)
                        val attachments = ModelAttachments(
                            items = mutableListOf(),
                            objects = mutableListOf(),
                            npcs = mutableListOf()
                        )

                        // Populate model data
                        models[archive] = ModelData(
                            id = archive,
                            totalFaces = model!!.triangleCount,
                            totalVerts = model.vertexCount,
                            textures = model.triangleTextures?.map { it and 0xFFFF }?.toSet()?.toIntArray() ?: intArrayOf(),
                            colors = model.triangleColors?.map { it.toInt() and 0xFFFF }?.toSet()?.toIntArray() ?: intArrayOf(),
                            attachments = attachments
                        )

                        processAttachments(archive, attachments, npcCache, objectCache, itemCache)
                    } catch (e: NullPointerException) {
                        println("Error processing model archive: $archive")
                        println("Bytes for archive are null or model loading failed for archive $archive")
                        e.printStackTrace()
                    } catch (e: Exception) {
                        println("An unexpected error occurred while processing model archive: $archive")
                        e.printStackTrace()
                    } finally {
                        // Safely increment the counter in a critical section
                        val currentProgress: Int
                        synchronized(lock) {
                            processedCount++
                            currentProgress = processedCount
                        }
                        // Update the progress bar outside of the critical section
                        showProgressBar(currentProgress, totalArchives)
                    }
                }
            }.joinAll()

            println("\nProcessing complete!")
        }
        manifest.writeText(GsonBuilder().setPrettyPrinting().create().toJson(models))
    }

    private fun processAttachments(
        archive: Int,
        attachments: ModelAttachments,
        npcCache: Map<Int, NpcType>,
        objectCache: Map<Int, ObjectType>,
        itemCache: Map<Int, ItemType>
    ) {
        npcCache.forEach { npcEntry ->
            val npc = npcEntry.value
            npc.models?.let { models ->
                if (models.contains(archive)) {
                    attachments.addNpc(npc.name)
                }
            }
            npc.chatheadModels?.let { chatheadModels ->
                if (chatheadModels.contains(archive)) {
                    attachments.addNpc("${npc.name} Chat Heads")
                }
            }
        }

        objectCache.forEach { objEntry ->
            val obj = objEntry.value
            obj.objectModels?.let { models ->
                if (models.contains(archive)) {
                    attachments.addObject(obj.name?: "null")
                }
            }
        }


        itemCache.forEach { itemEntry ->
            val item = itemEntry.value
            // Caching models for the item before looping
            val models = listOfNotNull(
                item.maleModel0, item.maleModel1, item.maleModel2,
                item.maleHeadModel0, item.maleHeadModel1,
                item.femaleModel0, item.femaleModel1, item.femaleModel2,
                item.femaleHeadModel0, item.femaleHeadModel1
            )

            models.forEach { model ->
                if (model == archive) {
                    attachments.addItem(item.name)
                }
            }
        }
    }
}
