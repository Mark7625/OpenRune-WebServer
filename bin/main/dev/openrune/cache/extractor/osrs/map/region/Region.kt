package dev.openrune.cache.extractor.osrs.map.region

import dev.openrune.cache.extractor.osrs.map.MapDefinition
import dev.openrune.cache.extractor.osrs.map.loc.LocationsDefinition

class Region {

    companion object {
        const val X = 64
        const val Y = 64
        const val Z = 4
    }

    val regionID: Int
    val baseX: Int
    val baseY: Int

    private val tileHeights = Array(Z) { Array(X) { IntArray(Y) } }
    private val tileSettings = Array(Z) { Array(X) { ByteArray(Y) } }
    val overlayIds = Array(Z) { Array(X) { ShortArray(Y) } }
    private val overlayPaths = Array(Z) { Array(X) { ByteArray(Y) } }
    private val overlayRotations = Array(Z) { Array(X) { ByteArray(Y) } }
    val underlayIds = Array(Z) { Array(X) { ShortArray(Y) } }

    private val _locations = mutableListOf<Location>()

    constructor(id: Int) {
        regionID = id
        baseX = ((id shr 8) and 0xFF) shl 6
        baseY = (id and 0xFF) shl 6
    }

    constructor(x: Int, y: Int) {
        regionID = (x shl 8) or y
        baseX = x shl 6
        baseY = y shl 6
    }

    fun loadTerrain(map: MapDefinition) {
        val tiles = map.tiles
        for (z in 0 until Z) {
            for (x in 0 until X) {
                for (y in 0 until Y) {
                    val tile = tiles[z][x][y]

                    tileHeights[z][x][y] = when {
                        tile.height == null -> {
                            if (z == 0) {
                                -HeightCalc.calculate(baseX + x + 0xe3b7b, baseY + y + 0x87cce) * 8
                            } else {
                                tileHeights[z - 1][x][y] - 240
                            }
                        }
                        else -> {
                            var height = tile.height
                            if (height == 1) height = 0
                            if (z == 0)
                                -height!! * 8
                            else
                                tileHeights[z - 1][x][y] - height!! * 8
                        }
                    }

                    overlayIds[z][x][y] = tile.overlayId
                    overlayPaths[z][x][y] = tile.overlayPath
                    overlayRotations[z][x][y] = tile.overlayRotation
                    tileSettings[z][x][y] = tile.settings
                    underlayIds[z][x][y] = tile.underlayId
                }
            }
        }
    }

    fun loadLocations(locs: LocationsDefinition) {
        locs.locations.forEach { loc ->
            val newLoc = Location(
                id = loc.id,
                type = loc.type,
                orientation = loc.orientation,
                position = Position(
                    baseX + loc.position.x,
                    baseY + loc.position.y,
                    loc.position.z
                )
            )
            _locations.add(newLoc)
        }
    }

    fun getTileHeight(z: Int, x: Int, y: Int): Int = tileHeights[z][x][y]

    fun getTileSetting(z: Int, x: Int, y: Int): Byte = tileSettings[z][x][y]

    fun getOverlayId(z: Int, x: Int, y: Int): Int = overlayIds[z][x][y].toInt() and 0x7FFF

    fun getOverlayPath(z: Int, x: Int, y: Int): Byte = overlayPaths[z][x][y]

    fun getOverlayRotation(z: Int, x: Int, y: Int): Byte = overlayRotations[z][x][y]

    fun getUnderlayId(z: Int, x: Int, y: Int): Int = underlayIds[z][x][y].toInt() and 0x7FFF

    val locations: List<Location> get() = _locations.toList()

    val regionX: Int get() = baseX shr 6
    val regionY: Int get() = baseY shr 6
}