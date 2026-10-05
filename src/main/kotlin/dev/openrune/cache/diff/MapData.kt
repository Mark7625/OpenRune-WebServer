package dev.openrune.cache.diff

/** One object placement in a region: packed position plus the orientation and type it was placed with. */
data class LocationCustom(
    val id: Int,
    val type: Int,
    val orientation: Int,
    val position: Int,
    val isDynamic: Boolean = false,
)

/** A map square's decoded contents: object placements and the tiles each overlay / underlay covers. */
data class RegionData(
    val id: Int,
    val positions: List<LocationCustom> = emptyList(),
    val totalObjects: Int = 0,
    val overlayIds: Map<Int, List<Int>> = emptyMap(),
    val underlayIds: Map<Int, List<Int>> = emptyMap(),
)
