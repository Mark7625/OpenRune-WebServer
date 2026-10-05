package dev.openrune.cache.map

import dev.openrune.cache.map.loc.LocationsDefinition
import dev.openrune.cache.map.region.Location
import dev.openrune.cache.map.region.Position

/**
 * One decoded map square: the object placements it contains and, per overlay / underlay id, the
 * packed tile positions it covers. Tile heights are not kept — nothing downstream reads them.
 */
class Region(val regionID: Int) {

    companion object {
        const val X = 64
        const val Y = 64
        const val Z = 4
    }

    val baseX: Int = ((regionID shr 8) and 0xFF) shl 6
    val baseY: Int = (regionID and 0xFF) shl 6

    val locations = ArrayList<Location>()
    val overlayIdPositions = HashMap<Int, MutableList<Int>>()
    val underlayIdPositions = HashMap<Int, MutableList<Int>>()

    fun loadTerrain(map: MapDefinition) {
        val tiles = map.tiles
        for (z in 0 until Z) {
            for (x in 0 until X) {
                for (y in 0 until Y) {
                    val tile = tiles[z][x][y]
                    val packed = Position(baseX + x, baseY + y, z).pack()
                    val overlay = tile.overlayId.toInt() and 0x7FFF
                    if (overlay != 0) overlayIdPositions.getOrPut(overlay) { ArrayList() }.add(packed)
                    val underlay = tile.underlayId.toInt() and 0x7FFF
                    if (underlay != 0) underlayIdPositions.getOrPut(underlay) { ArrayList() }.add(packed)
                }
            }
        }
    }

    fun loadLocations(locs: LocationsDefinition) {
        locs.locations.forEach { loc ->
            locations.add(
                Location(
                    id = loc.id,
                    type = loc.type,
                    orientation = loc.orientation,
                    position = Position(baseX + loc.position.x, baseY + loc.position.y, loc.position.z),
                ),
            )
        }
    }
}
