package dev.openrune.server.endpoints.npcs

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.configs.NpcManifestEntry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class NpcInfoHandler(private val config: ServerConfig) {
    
    private val manifest: Map<Int, NpcManifestEntry> by lazy {
        @Suppress("UNCHECKED_CAST")
        loadNpcData(config, dev.openrune.server.endpoints.sprites.DataType.WEB) as Map<Int, NpcManifestEntry>
    }
    
    suspend fun handle(call: ApplicationCall) {
        try {
            val totalEntries = manifest.size
            val uniqueNames = manifest.values.map { it.name }.toSet().size
            val uniqueGameVals = manifest.values.map { it.gameVal }.filterNotNull().toSet().size
            val entriesWithGameVal = manifest.values.count { !it.gameVal.isNullOrEmpty() }
            
            val response = mapOf(
                "totalEntries" to totalEntries,
                "uniqueNames" to uniqueNames,
                "entriesWithGameVal" to entriesWithGameVal,
                "uniqueGameVals" to uniqueGameVals
            )
            
            call.respond(response)
        } catch (e: Exception) {
            logger.error("Error handling NPC info request: ${e.message}", e)
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
