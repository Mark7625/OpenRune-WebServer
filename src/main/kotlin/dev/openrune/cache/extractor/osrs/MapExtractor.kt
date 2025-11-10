package dev.openrune.cache.extractor.osrs

import dev.openrune.ServerConfig
import dev.openrune.cache.CacheManager
import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.extractor.BaseExtractor
import dev.openrune.cache.extractor.osrs.map.MapImageDumper
import dev.openrune.cache.extractor.osrs.map.region.RegionLoader
import dev.openrune.cache.gameval.GameValHandler
import dev.openrune.cache.util.XteaLoader
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.filesystem.Cache
import kotlinx.coroutines.*
import mu.KotlinLogging
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

data class LocationCustom(
    val id: Int,
    val type: Int,
    val orientation: Int,
    val position: Int,
    val isDynamic: Boolean = false
)

data class RegionData(
    val id: Int,
    var positions: List<LocationCustom> = emptyList(),
    var totalObjects: Int = 0,
    var overlayIds: Map<Int, List<Int>> = emptyMap(),
    var underlayIds: Map<Int, List<Int>> = emptyMap()
)

class MapExtractor(config: ServerConfig, cache: Cache) : BaseExtractor(config, cache) {

    private val regionLoader = RegionLoader(cache)
    private val dumper: MapImageDumper

    private val mapRegionData = mutableMapOf<Int, RegionData>()

    init {
        val xteaLoc = File(
            CachePathHelper.getCacheDirectory(
                config.gameType,
                config.environment,
                config.revision
            ),
            "xteas.json"
        )

        XteaLoader.load(xteaLoc)
        dumper = MapImageDumper(cache, regionLoader)
        regionLoader.loadRegions()
        dumper.load()
    }

    override fun extract(index: Int, archive: Int, file: Int, data: ByteArray) {
        extract(index, archive, file, data, null)
    }

    fun extract(
        index: Int,
        archive: Int,
        file: Int,
        data: ByteArray,
        onProgress: ((Boolean, Double?, String?) -> Unit)?
    ) {
        val planes = 0 until 4

        regionLoader.regions.forEach { region ->
            val regionData = RegionData(region.regionID)

            regionData.positions = region.locations.map {
                LocationCustom(
                    id = it.id,
                    type = it.type,
                    orientation = it.orientation,
                    position = it.position.pack(),
                    isDynamic = CacheManager.getObject(it.id)?.animationId != -1
                )
            }

            regionData.totalObjects = region.locations.size
            regionData.overlayIds = region.overlayIdPositions
            regionData.underlayIds = region.underlayIdPositions

            mapRegionData[region.regionID] = regionData
        }

        val objectPositions: Map<Int, List<LocationCustom>> = mapRegionData.values
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
                    saveJsonCompact(it.value, "maps", "regions", "${it.key}.json")
                    progress.incrementAndGet()
                }

                planes.map { plane ->
                    async(Dispatchers.IO) {
                        // Uncomment if you want to render the map planes
                        // val image = dumper.drawMap(plane)
                        // savePng(image, "maps", "full", "current-map-image-$plane.png")
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