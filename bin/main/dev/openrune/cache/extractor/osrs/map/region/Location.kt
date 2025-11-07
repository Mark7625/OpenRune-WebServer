package dev.openrune.cache.extractor.osrs.map.region

data class Location(
    val id: Int,
    val type: Int,
    val orientation: Int,
    val position: Position
)