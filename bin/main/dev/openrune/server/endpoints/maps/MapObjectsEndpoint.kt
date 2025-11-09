package dev.openrune.server.endpoints.maps

import dev.openrune.ServerConfig
import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.util.XteaLoader
import dev.openrune.cache.extractor.osrs.map.loc.LocationsDefinition
import dev.openrune.cache.extractor.osrs.map.loc.LocationsLoader
import dev.openrune.cache.extractor.osrs.map.MapDefinition
import dev.openrune.cache.extractor.osrs.map.MapLoader
import dev.openrune.cache.extractor.osrs.map.region.Location
import dev.openrune.cache.extractor.osrs.map.region.Position
import dev.openrune.cache.extractor.osrs.map.region.Region
import dev.openrune.cache.MAPS
import dev.openrune.filesystem.Cache
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

class MapObjectsEndpoint(private val config: ServerConfig) {
    
    private val cache: Cache by lazy {
        val cacheDir = CachePathHelper.getCacheDirectory(
            config.gameType,
            config.environment,
            config.revision
        ).resolve("data").resolve("cache")
        Cache.load(cacheDir.toPath(), false)
    }
    
    init {
        // Load XTEA keys
        val xteaLoc = File(
            CachePathHelper.getCacheDirectory(
                config.gameType,
                config.environment,
                config.revision
            ), "xteas.json"
        )
        if (xteaLoc.exists()) {
            XteaLoader.load(xteaLoc)
        }
    }
    
    /**
     * Load a region by ID
     */
    private fun loadRegion(regionId: Int): Region? {
        return try {
            val x = regionId shr 8
            val y = regionId and 0xFF
            
            val data = cache.data(MAPS, "m${x}_$y", null) ?: return null
            val mapDef = MapLoader().load(x, y, data)
            
            val region = Region(regionId)
            region.loadTerrain(mapDef)
            
            val keys = XteaLoader.getKeys(regionId)
            keys?.let {
                cache.data(MAPS, "l${x}_$y", it)?.let { locData ->
                    val locDef = LocationsLoader().load(x, y, locData)
                    region.loadLocations(locDef)
                }
            }
            
            region
        } catch (e: Exception) {
            logger.debug("Failed to load region $regionId: ${e.message}")
            null
        }
    }
    
    /**
     * Find all locations where an object ID appears
     * If regionId is provided, searches only that region. Otherwise searches all regions.
     */
    suspend fun findObjectsById(call: ApplicationCall, objectId: String, regionId: String?) {
        try {
            val objectIdInt = objectId.toIntOrNull()
            if (objectIdInt == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid object ID. Must be a number")
                return
            }
            
            val locations = mutableListOf<Map<String, Any>>()
            
            if (regionId != null) {
                // Search in a specific region
                val regionIdInt = regionId.toIntOrNull()
                if (regionIdInt == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid region ID. Must be a number")
                    return
                }
                
                val region = loadRegion(regionIdInt)
                if (region == null) {
                    call.respond(HttpStatusCode.NotFound, "Region not found: $regionId")
                    return
                }
                
                region.locations.filter { it.id == objectIdInt }.forEach { location ->
                    locations.add(mapOf(
                        "id" to location.id,
                        "type" to location.type,
                        "orientation" to location.orientation,
                        "position" to mapOf(
                            "x" to location.position.x,
                            "y" to location.position.y,
                            "z" to location.position.z
                        ),
                        "regionId" to regionIdInt
                    ))
                }
            } else {
                // Search across all regions
                logger.info("Searching all regions for object ID: $objectIdInt")
                
                // Search through all possible region IDs (0-32767)
                // Region ID format: (regionX shl 8) | regionY, where regionX and regionY are 0-255
                var regionsChecked = 0
                var regionsFound = 0
                
                for (rx in 0..255) {
                    for (ry in 0..255) {
                        val rid = (rx shl 8) or ry
                        regionsChecked++
                        
                        try {
                            val region = loadRegion(rid) ?: continue
                            regionsFound++
                            
                            region.locations.filter { it.id == objectIdInt }.forEach { location ->
                                locations.add(mapOf(
                                    "id" to location.id,
                                    "type" to location.type,
                                    "orientation" to location.orientation,
                                    "position" to mapOf(
                                        "x" to location.position.x,
                                        "y" to location.position.y,
                                        "z" to location.position.z
                                    ),
                                    "regionId" to rid
                                ))
                            }
                        } catch (e: Exception) {
                            // Skip regions that fail to load
                            continue
                        }
                    }
                }
                
                logger.info("Searched $regionsChecked regions (found $regionsFound with data) for object ID: $objectIdInt, found ${locations.size} locations")
            }
            
            // If no locations found, return 404
            if (locations.isEmpty()) {
                val message = if (regionId != null) {
                    "Object ID $objectIdInt not found in region $regionId"
                } else {
                    "Object ID $objectIdInt not found in any region"
                }
                call.respond(HttpStatusCode.NotFound, mapOf(
                    "error" to "Object not found",
                    "message" to message,
                    "objectId" to objectIdInt
                ))
                return
            }
            
            val response = mutableMapOf<String, Any>(
                "objectId" to objectIdInt,
                "count" to locations.size,
                "locations" to locations
            )
            
            if (regionId != null) {
                response["regionId"] = regionId.toInt()
            }
            
            call.respond(response)
            
        } catch (e: Exception) {
            logger.error("Error finding objects: ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, "Internal server error: ${e.message}")
        }
    }
}

