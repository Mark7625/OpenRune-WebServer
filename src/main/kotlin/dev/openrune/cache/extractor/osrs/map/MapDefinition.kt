package dev.openrune.cache.extractor.osrs.map

data class MapDefinition(
    var regionX: Int = 0,
    var regionY: Int = 0,
    val tiles: Array<Array<Array<Tile>>> = Array(Z) { Array(X) { Array(Y) { Tile() } } }
) {
    companion object {
        const val X = 64
        const val Y = 64
        const val Z = 4
    }

    data class Tile(
        var height: Int? = null,
        var attrOpcode: Int = 0,
        var settings: Byte = 0,
        var overlayId: Short = 0,
        var overlayPath: Byte = 0,
        var overlayRotation: Byte = 0,
        var underlayId: Short = 0
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MapDefinition

        if (regionX != other.regionX) return false
        if (regionY != other.regionY) return false
        if (!tiles.contentDeepEquals(other.tiles)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = regionX
        result = 31 * result + regionY
        result = 31 * result + tiles.contentDeepHashCode()
        return result
    }
}