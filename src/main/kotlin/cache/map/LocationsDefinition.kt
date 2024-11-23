package cache.map

data class LocationsDefinition(
    var regionX : Int,
    var regionY : Int,
    val locations: MutableList<Location>
)