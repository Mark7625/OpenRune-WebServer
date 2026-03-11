package dev.openrune.cache.map

import MapLoader
import dev.openrune.cache.map.loc.LocationsDefinition
import dev.openrune.cache.map.loc.LocationsLoader
import dev.openrune.cache.osrs.OsrsCacheIndex
import dev.openrune.cache.util.XteaLoader
import dev.openrune.filesystem.Cache
import java.io.IOException

class RegionLoader(
    private val cache: Cache,
    private val revision : Int
) {
    companion object {
        private const val MAX_REGION = 32768
    }


    private val _regions = mutableMapOf<Int, Region>()

    private var _lowestX: Region? = null
    private var _lowestY: Region? = null
    private var _highestX: Region? = null
    private var _highestY: Region? = null

    @Throws(IOException::class)
    fun loadRegions() {
        if (_regions.isNotEmpty()) return

        for (i in 0 until MAX_REGION) {
            try {
                loadRegionFromArchive(i)
            } catch (ex: IOException) {
                ex.printStackTrace()
                //log.debug("Can't decrypt region $i", ex)
            }
        }
    }

    @Throws(IOException::class)
    fun loadRegionFromArchive(id: Int): Region? {
        val x = id shr 8
        val y = id and 0xFF

        val groupId = (x shl 8) or (y and 0xFF)

        val data = if (revision >=237) {
            cache.data(OsrsCacheIndex.MAPS.id,groupId,0) ?: return null
        } else {
            cache.data(OsrsCacheIndex.MAPS.id, "m${x}_$y", null) ?: return null
        }

        if (data.isEmpty()) {
            return null
        }

        val mapDef = MapLoader().load(x, y, data,revision)

        val region = Region(id)
        region.loadTerrain(mapDef)


        if (revision >= 237) {
            cache.data(OsrsCacheIndex.MAPS.id, groupId,1)?.let { locData ->
                val locDef = LocationsLoader().load(x, y, locData,revision)
                region.loadLocations(locDef)
            }
        } else {
            val keys = XteaLoader.getKeys(id)
            keys?.let {
                cache.data(OsrsCacheIndex.MAPS.id, "l${x}_$y", it)?.let { locData ->
                    val locDef = LocationsLoader().load(x, y, locData,revision)
                    region.loadLocations(locDef)
                }
            }

        }

        _regions[id] = region
        return region
    }

    fun loadRegion(id: Int, map: MapDefinition, locs: LocationsDefinition?): Region {
        val region = Region(id)
        region.loadTerrain(map)
        locs?.let { region.loadLocations(it) }
        _regions[id] = region
        return region
    }

    fun calculateBounds() {
        for (region in _regions.values) {
            if (_lowestX == null || region.baseX < _lowestX!!.baseX) {
                _lowestX = region
            }
            if (_highestX == null || region.baseX > _highestX!!.baseX) {
                _highestX = region
            }
            if (_lowestY == null || region.baseY < _lowestY!!.baseY) {
                _lowestY = region
            }
            if (_highestY == null || region.baseY > _highestY!!.baseY) {
                _highestY = region
            }
        }
    }

    val regions: Collection<Region> get() = _regions.values

    fun findRegionForWorldCoordinates(x: Int, y: Int): Region? {
        val rx = x ushr 6
        val ry = y ushr 6
        return _regions[(rx shl 8) or ry]
    }

    fun findRegionForRegionCoordinates(x: Int, y: Int): Region? =
        _regions[(x shl 8) or y]

    val lowestX: Region? get() = _lowestX
    val lowestY: Region? get() = _lowestY
    val highestX: Region? get() = _highestX
    val highestY: Region? get() = _highestY
}