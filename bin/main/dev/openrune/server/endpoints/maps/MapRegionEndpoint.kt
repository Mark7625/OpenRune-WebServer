package dev.openrune.server.endpoints.maps

import dev.openrune.ServerConfig
import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.extractor.osrs.RegionData
import dev.openrune.util.json
import com.google.gson.reflect.TypeToken
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

class MapRegionEndpoint(private val config: ServerConfig) {
    
    private val regionsDir: File by lazy {
        CachePathHelper.getCacheDirectory(
            config.gameType,
            config.environment,
            config.revision
        ).resolve("extracted").resolve("maps").resolve("regions")
    }
    
    /**
     * Get region data by region ID
     * Loads the extracted JSON file for the region
     */
    suspend fun getRegionData(call: ApplicationCall, regionId: String) {
        try {
            val regionIdInt = regionId.toIntOrNull()
            if (regionIdInt == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid region ID. Must be a number")
                return
            }
            
            val regionFile = regionsDir.resolve("${regionIdInt}.json")
            
            if (!regionFile.exists()) {
                call.respond(HttpStatusCode.NotFound, mapOf(
                    "error" to "Region not found",
                    "message" to "Region $regionIdInt not found. The region may not have been extracted yet.",
                    "regionId" to regionIdInt
                ))
                return
            }
            
            try {
                val content = regionFile.readText()
                val type = object : TypeToken<RegionData>() {}.type
                val regionData: RegionData = json.fromJson(content, type)
                
                call.respond(regionData)
            } catch (e: Exception) {
                logger.error("Error parsing region data for region $regionIdInt: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf(
                    "error" to "Failed to parse region data",
                    "message" to "Could not parse region data: ${e.message}",
                    "regionId" to regionIdInt
                ))
            }
            
        } catch (e: Exception) {
            logger.error("Error retrieving region data: ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, "Internal server error: ${e.message}")
        }
    }
}

