
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.openrune.cache.CacheManager
import dev.openrune.cache.MODELS
import dev.openrune.cache.filestore.definition.ModelDecoder
import dev.openrune.cache.gameval.GameValElement
import dev.openrune.cache.gameval.GameValHandler.lookup
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

    fun init(
        objectGameVals: List<GameValElement>,
        npcGameVals: List<GameValElement>,
        itemGameVals: List<GameValElement>
    ) {
        modelDecoder = ModelDecoder(gameCache)
        File("./data/models/").mkdirs()
        if (!manifest.exists()) {
            loadModelData(objectGameVals,npcGameVals,itemGameVals)
        } else {
            val typeToken = object : TypeToken<MutableMap<Int, ModelData>>() {}.type
            models = Gson().fromJson<MutableMap<Int, ModelData>>(manifest.readText(), typeToken) ?: ConcurrentHashMap()
        }
    }

    private fun loadModelData(
        objectGameVals: List<GameValElement>,
        npcGameVals: List<GameValElement>,
        itemGameVals: List<GameValElement>
    ) {
        val npcCache = CacheManager.getNpcs()
        val objectCache = CacheManager.getObjects()
        val itemCache = CacheManager.getItems()

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
                            items = mutableSetOf(),
                            objects = mutableSetOf(),
                            npcs = mutableSetOf()
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

                        processAttachments(
                            archive,
                            attachments,
                            npcCache,
                            objectCache,
                            itemCache,
                            objectGameVals,
                            npcGameVals,
                            itemGameVals
                        )
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
        itemCache: Map<Int, ItemType>,
        objectGameVals: List<GameValElement>,
        npcGameVals: List<GameValElement>,
        itemGameVals: List<GameValElement>
    ) {

        npcCache.forEach { npcEntry ->
            val npc = npcEntry.value
            npc.models?.let { models ->
                if (models.contains(archive)) {
                    attachments.addNpc(npcGameVals.lookup(npcEntry.key)!!)
                }
            }
        }

        objectCache.forEach { objEntry ->
            val obj = objEntry.value
            obj.objectModels?.let { models ->
                if (models.contains(archive)) {
                    attachments.addObject(objectGameVals.lookup(objEntry.key)!!)
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
                    attachments.addItem(itemGameVals.lookup(itemEntry.key)!!)
                }
            }
        }
    }
}
