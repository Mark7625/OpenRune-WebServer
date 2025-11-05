package dev.openrune.server.endpoints.gamevals

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.GameValManifestEntry
import dev.openrune.definition.GameValGroupTypes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class GameValInfoHandler(private val config: ServerConfig) {
    
    private val allManifests: Map<GameValGroupTypes, Map<String, GameValManifestEntry>> by lazy {
        loadAllGameValManifests(config)
    }
    
    suspend fun handle(call: ApplicationCall) {
        try {
            val typeStats = mutableMapOf<String, Map<String, Any>>()
            var totalEntries = 0
            
            allManifests.forEach { (type, manifest) ->
                val count = manifest.size
                totalEntries += count
                
                val isSimple = manifest.values.all { entry ->
                    entry.data == null || entry.data.isEmpty()
                }
                
                typeStats[type.groupName] = mapOf(
                    "type" to type.groupName,
                    "count" to count,
                    "isSimple" to isSimple
                )
            }
            
            val response = mapOf(
                "totalTypes" to typeStats.size,
                "totalEntries" to totalEntries,
                "types" to typeStats
            )
            
            call.respond(response)
        } catch (e: Exception) {
            logger.error("Error handling gameval info request: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "error" to "Internal server error",
                    "message" to (e.message ?: "Unknown error")
                )
            )
        }
    }
}

