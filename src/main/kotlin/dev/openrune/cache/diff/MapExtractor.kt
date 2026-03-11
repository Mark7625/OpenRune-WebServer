package dev.openrune.cache.diff

import dev.openrune.ServerConfig
import dev.openrune.cache.CacheManager
import dev.openrune.cache.map.RegionLoader
import dev.openrune.cache.util.XteaLoader
import dev.openrune.filesystem.Cache


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

data class ExtractedMapData(
    val objectPositions: Map<Int, List<LocationCustom>>,
    val regions: Map<Int, RegionData>
)

class MapExtractor(val config: ServerConfig, val cache: Cache) {

    private val regionLoader = RegionLoader(cache,config.revision)

    private val mapRegionData = mutableMapOf<Int, RegionData>()

    fun extract(onProgress: ((Boolean, Double?, String?) -> Unit)?): ExtractedMapData {
        if (config.revision < 237) {
            val loadedFromBin = XteaLoader.loadFromDiffBinary(config, config.revision)
            if (!loadedFromBin && !XteaLoader.hasKeys()) {
                throw IllegalStateException(
                    "Missing legacy XTEA keys in diff binary for rev ${config.revision}. " +
                            "Regenerate the .bin so map extraction can decrypt locations."
                )
            }
        }
        regionLoader.loadRegions()

        onProgress?.invoke(true, 0.0, "Extracting full map")

        val totalRegions = regionLoader.regions.size.coerceAtLeast(1)
        var processedRegions = 0
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
            regionData.overlayIds = region.overlayIdPositions.mapValues { (_, positions) -> positions.toList().sorted() }
            regionData.underlayIds = region.underlayIdPositions.mapValues { (_, positions) -> positions.toList().sorted() }

            mapRegionData[region.regionID] = regionData
            processedRegions++
            if (processedRegions % 50 == 0 || processedRegions == totalRegions) {
                val pct = (processedRegions.toDouble() / totalRegions.toDouble()) * 85.0
                onProgress?.invoke(true, pct, "Extracting full map")
            }
        }

        val objectPositions: Map<Int, List<LocationCustom>> = mapRegionData.values.flatMap { it.positions }
            .groupBy { it.id }
            .mapValues { (_, positions) -> positions.sortedBy { it.position } }

        val sortedRegions = mapRegionData.toSortedMap().mapValues { (_, data) -> data.copy(
            positions = data.positions.sortedBy { it.position },
            overlayIds = data.overlayIds.toSortedMap(),
            underlayIds = data.underlayIds.toSortedMap()
        ) }

        onProgress?.invoke(true, 100.0, "Extracting full map")
        return ExtractedMapData(objectPositions = objectPositions.toSortedMap(), regions = sortedRegions)
    }

}