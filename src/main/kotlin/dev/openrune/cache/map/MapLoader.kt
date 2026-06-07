import dev.openrune.cache.map.MapDefinition
import dev.openrune.cache.map.net.InputStream

class MapLoader {

    fun load(regionX: Int, regionY: Int, data: ByteArray, rev: Int): MapDefinition {
        val map = MapDefinition().apply {
            this.regionX = regionX
            this.regionY = regionY
        }

        try {
            if (rev >= 209) {
                loadTerrainNew(map, data)
            } else {
                loadTerrainOld(map, data)
            }
        } catch (e: Exception) {
            //e.printStackTrace()
        }

        return map
    }

    private fun loadTerrainOld(map: MapDefinition, bytes: ByteArray) {
        val tiles = map.tiles
        val input = InputStream(bytes)

        for (z in 0 until MapDefinition.Z) {
            for (x in 0 until MapDefinition.X) {
                for (y in 0 until MapDefinition.Y) {
                    val tile = MapDefinition.Tile()
                    tiles[z][x][y] = tile

                    while (true) {
                        val attribute = input.readUnsignedByte()

                        when {
                            attribute == 0 -> break

                            attribute == 1 -> {
                                tile.height = input.readUnsignedByte()
                                break
                            }

                            attribute <= 49 -> {
                                tile.attrOpcode = attribute
                                tile.overlayId = input.readByte().toShort()
                                tile.overlayPath = ((attribute - 2) / 4).toByte()
                                tile.overlayRotation = ((attribute - 2) and 3).toByte()
                            }

                            attribute <= 81 -> {
                                tile.settings = (attribute - 49).toByte()
                            }

                            else -> {
                                tile.underlayId = (attribute - 81).toShort()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadTerrainNew(map: MapDefinition, bytes: ByteArray) {
        val tiles = map.tiles
        val input = InputStream(bytes)

        for (z in 0 until MapDefinition.Z) {
            for (x in 0 until MapDefinition.X) {
                for (y in 0 until MapDefinition.Y) {
                    val tile = MapDefinition.Tile()
                    tiles[z][x][y] = tile

                    while (true) {
                        val attribute = input.readUnsignedShort()

                        when {
                            attribute == 0 -> break

                            attribute == 1 -> {
                                tile.height = input.readUnsignedByte()
                                break
                            }

                            attribute <= 49 -> {
                                tile.attrOpcode = attribute
                                tile.overlayId = input.readShort()
                                tile.overlayPath = ((attribute - 2) / 4).toByte()
                                tile.overlayRotation = ((attribute - 2) and 3).toByte()
                            }

                            attribute <= 81 -> {
                                tile.settings = (attribute - 49).toByte()
                            }

                            else -> {
                                tile.underlayId = (attribute - 81).toShort()
                            }
                        }
                    }
                }
            }
        }
    }
}