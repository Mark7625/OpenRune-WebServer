package dev.openrune.cache.map.loc

import dev.openrune.cache.map.region.Location

data class LocationsDefinition(
    var regionX: Int = 0,
    var regionY: Int = 0,
    val locations: MutableList<Location> = mutableListOf()
)