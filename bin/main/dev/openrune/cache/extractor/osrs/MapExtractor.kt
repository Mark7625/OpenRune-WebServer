package dev.openrune.cache.extractor.osrs

import dev.openrune.ServerConfig
import dev.openrune.cache.CacheManager
import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.extractor.BaseExtractor
import dev.openrune.cache.extractor.osrs.map.MapImageDumper
import dev.openrune.cache.extractor.osrs.map.region.Location
import dev.openrune.cache.extractor.osrs.map.region.Position
import dev.openrune.cache.extractor.osrs.map.region.RegionLoader
import dev.openrune.cache.gameval.GameValHandler
import dev.openrune.cache.gameval.GameValHandler.lookup
import dev.openrune.cache.util.XteaLoader
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.filesystem.Cache
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import java.io.File

private val logger = KotlinLogging.logger {}

data class RegionData(
    val id: Int,
    var positions: List<Location> = emptyList(),
    var totalObjects : Int = 0,
    var overlayIds: List<List<List<Short>>> = emptyList(),
    var underlayIds: List<List<List<Short>>> = emptyList()
)

class MapExtractor(config: ServerConfig, cache: Cache) : BaseExtractor(config, cache) {

    private val regionLoader: RegionLoader = RegionLoader(cache)
    private val dumper: MapImageDumper

    private val gamevals = GameValHandler.readGameVal(GameValGroupTypes.LOCTYPES,cache)

    init {

        val xteaLoc = File(
            CachePathHelper.getCacheDirectory(
                config.gameType,
                config.environment,
                config.revision
            ), "xteas.json"
        )

        XteaLoader.load(xteaLoc)
        dumper = MapImageDumper(cache, regionLoader)
        regionLoader.loadRegions()
        dumper.load()
    }

    override fun extract(index: Int, archive: Int, file: Int, data: ByteArray) {
        extract(index, archive, file, data, null)
    }

    val mapRegionData = emptyMap<Int, RegionData>().toMutableMap()

    fun extract(
        index: Int,
        archive: Int,
        file: Int,
        data: ByteArray,
        onProgress: ((Boolean, Double?, String?) -> Unit)?
    ) {
        val planes = 0 until 4

        regionLoader.regions.forEach {
            val regionData = RegionData(it.regionID)

            regionData.positions = it.locations
            regionData.totalObjects = it.locations.size
            regionData.overlayIds = it.overlayIds.map { plane -> plane.map { it.toList() } }
            regionData.underlayIds = it.underlayIds.map { plane -> plane.map { it.toList() } }

            mapRegionData[it.regionID] = regionData
        }

        val objectPositions: Map<Int, List<Location>> = mapRegionData.values
            .flatMap { it.positions }
            .groupBy { it.id }

        val totalTasks = objectPositions.size + planes.count() + mapRegionData.size
        val progress = AtomicInteger(0)

        initProgressBar("Extracting full map", totalTasks.toLong())

        runBlocking {
            val reporter = if (onProgress != null) {
                launch {
                    while (isActive) {
                        val pct = (progress.get() / totalTasks.toDouble()) * 100
                        onProgress(true, pct, "Extracting full map")
                        delay(100)
                    }
                }
            } else null

            try {

                objectPositions.forEach {
                    saveJson(it.value, "maps", "objects", "${it.key}.json")
                    progress.incrementAndGet()
                }

                mapRegionData.forEach {
                    // Use compact JSON to reduce recursion depth during serialization
                    saveJsonCompact(it.value, "maps", "regions", "${it.key}.json")
                    progress.incrementAndGet()
                }


                // Draw map planes
                planes.map { plane ->
                    async(Dispatchers.IO) {
                        //val image = dumper.drawMap(plane)
                        //savePng(image, "maps", "full", "current-map-image-$plane.png")
                        progress.incrementAndGet()
                    }
                }.awaitAll()
            } finally {
                reporter?.cancelAndJoin()
                onProgress?.invoke(true, 100.0, "Extracting full map")
            }
        }
    }

    override fun end() {
        closeProgressBar()
    }
}