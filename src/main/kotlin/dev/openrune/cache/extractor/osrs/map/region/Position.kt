package dev.openrune.cache.extractor.osrs.map.region

data class Position(
    val x: Int,
    val y: Int,
    val z: Int
) {
    fun pack(): Int {
        return (z shl 28) or (x shl 14) or y
    }

    companion object {
        fun fromPacked(packedPosition: Int): Position {
            return if (packedPosition == -1) {
                Position(-1, -1, -1)
            } else {
                val z = (packedPosition shr 28) and 3
                val x = (packedPosition shr 14) and 16383
                val y = packedPosition and 16383
                Position(x, y, z)
            }
        }
    }
}