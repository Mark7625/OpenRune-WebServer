package dev.openrune.cache.extractor.osrs.map.loc

import dev.openrune.cache.extractor.osrs.map.region.Location

data class LocationsDefinition(
    var regionX: Int = 0,
    var regionY: Int = 0,
    val locations: MutableList<Location> = mutableListOf()
)