package cache.map

import java.nio.ByteBuffer

val ByteBuffer.uShort: Int get() = this.short.toInt() and 0xffff
val ByteBuffer.uByte: Int get() = this.get().toInt() and 0xff
val ByteBuffer.byte: Byte get() = this.get()
val ByteBuffer.rsString: String get() = getString(this)
val ByteBuffer.medium: Int get() = this.get().toInt() and 0xff shl 16 or (this.get().toInt() and 0xff shl 8) or (this.get().toInt() and 0xff)

val ByteBuffer.shortSmart : Int get() {
    val peek = uByte
    return if (peek < 128) peek - 64 else (peek shl 8 or uByte) - 49152
}

fun ByteBuffer.readByteArray(length: Int): ByteArray {
    val array = ByteArray(length)
    get(array)
    return array
}

fun ByteBuffer.readParams(): MutableMap<Int, String> {
    val params : MutableMap<Int,String> = mutableMapOf()
    (0 until uByte).forEach { _ ->
        val string: Boolean = (uByte) == 1
        val key: Int = medium
        val value: Any = if (string) { rsString } else { int }
        params[key] = value.toString()
    }
    return params
}

fun getString(buffer: ByteBuffer): String {
    val builder = StringBuilder()
    var b: Int
    while ((buffer.uByte).also { b = it } != 0) {
        builder.append(b.toChar())
    }
    return builder.toString()
}

class MapLoader {

    private val readOverlayAsShort = true

    fun load(regionX: Int, regionY: Int, b: ByteArray): MapDefinition {
        val map = MapDefinition(regionX,regionY)
        loadTerrain(map, b)
        return map
    }

    private fun loadTerrain(map: MapDefinition, data: ByteArray) {
        val tiles = map.tiles
        val buffer = ByteBuffer.wrap(data)
        for (z in 0 until regionSizeZ) {
            for (x in 0 until regionSizeX) {
                for (y in 0 until regionSizeY) {
                    tiles[z][x][y] = Tile()
                    val tile = tiles[z][x][y]
                    while (true) {
                        val attribute: Int = if (readOverlayAsShort) buffer.uShort else buffer.uByte
                        if (attribute == 0) {
                            break
                        } else if (attribute == 1) {
                            val height: Int = buffer.uByte
                            tile.height = height
                            break
                        } else if (attribute <= 49) {
                            tile.attrOpcode = attribute
                            tile.overlayId = if (readOverlayAsShort) buffer.uShort.toShort() else buffer.uByte.toShort()
                            tile.overlayPath = ((attribute - 2) / 4).toByte()
                            tile.overlayRotation = (attribute - 2 and 3).toByte()
                        } else if (attribute <= 81) {
                            tile.settings = (attribute - 49).toByte()
                        } else {
                            tile.underlayId = (attribute - 81).toByte().toShort()
                        }
                    }
                }
            }
        }
    }

}