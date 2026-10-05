package dev.openrune.cache.map

import MapLoader
import dev.openrune.cache.MAPS
import dev.openrune.cache.map.loc.LocationsLoader
import dev.openrune.cache.util.XteaLoader
import dev.openrune.filesystem.Cache

const val MAX_REGION = 32768

/**
 * Decodes one map square at a time. Regions are not retained, so a full-map pass costs one
 * region's worth of memory rather than the whole world.
 *
 * Revisions before 237 name map groups `m{x}_{y}` / `l{x}_{y}` and encrypt locations with an
 * XTEA key per region; later revisions use numeric group ids and no encryption.
 */
class RegionLoader(
    private val cache: Cache,
    private val revision: Int,
    private val xteas: Map<Int, IntArray> = emptyMap(),
) {
    fun load(id: Int): Region? {
        val x = id shr 8
        val y = id and 0xFF
        val legacy = revision < 237

        val terrain = if (legacy) cache.data(MAPS, "m${x}_$y", null) else cache.data(MAPS, id, 0)
        if (terrain == null || terrain.isEmpty()) return null

        val region = Region(id)
        region.loadTerrain(MapLoader().load(x, y, terrain, revision))

        val locations = if (legacy) {
            xteas[id]?.let { key -> cache.data(MAPS, "l${x}_$y", XteaLoader.normalize(key)) }
        } else {
            cache.data(MAPS, id, 1)
        }
        locations?.let { region.loadLocations(LocationsLoader().load(x, y, it, revision)) }
        return region
    }
}
