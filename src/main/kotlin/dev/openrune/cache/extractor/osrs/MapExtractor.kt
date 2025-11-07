package dev.openrune.cache.extractor.osrs

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.BaseExtractor
import dev.openrune.cache.extractor.osrs.map.MapImageDumper
import dev.openrune.cache.extractor.osrs.map.region.RegionLoader
import dev.openrune.filesystem.Cache
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll

private val logger = KotlinLogging.logger {}

data class MapRegionManifestEntry(
    val regionId: Int,
    val x: Int,
    val y: Int,
    val region: String
)

class MapExtractor(config: ServerConfig, cache: Cache) : BaseExtractor(config, cache) {

    private val regionLoader: RegionLoader = RegionLoader(cache)
    private val dumper: MapImageDumper

    init {
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
        val progress = AtomicInteger(0)
        val total = planes.count()

        initProgressBar("Extracting full map", total.toLong())

        runBlocking {
            val reporter = if (onProgress != null) {
                launch {
                    while (isActive) {
                        val pct = (progress.get() / total.toDouble()) * 100
                        onProgress(true, pct, "Extracting full map")
                        delay(100)
                    }
                }
            } else null

            try {
                planes.map { plane ->
                    async(Dispatchers.IO) {
                        val image = dumper.drawMap(plane)
                        savePng(image, "maps", "full", "current-map-image-$plane.png")
                        stepProgress()
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