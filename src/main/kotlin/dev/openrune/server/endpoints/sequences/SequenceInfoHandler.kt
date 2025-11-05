package dev.openrune.server.endpoints.sequences

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.configs.SequenceManifestEntry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class SequenceInfoHandler(private val config: ServerConfig) {
    
    private val manifest: Map<Int, SequenceManifestEntry> by lazy {
        @Suppress("UNCHECKED_CAST")
        loadSequenceData(config, dev.openrune.server.endpoints.sprites.DataType.WEB) as Map<Int, SequenceManifestEntry>
    }
    
    suspend fun handle(call: ApplicationCall) {
        try {
            val totalEntries = manifest.size
            val uniqueGameVals = manifest.values.map { it.gameVal }.filterNotNull().toSet().size
            val entriesWithGameVal = manifest.values.count { !it.gameVal.isNullOrEmpty() }
            val entriesWithDuration = manifest.values.count { it.tickDuration != -1 }
            val avgTickDuration = if (entriesWithDuration > 0) {
                manifest.values.filter { it.tickDuration != -1 }.map { it.tickDuration }.average().toInt()
            } else {
                0
            }
            
            val response = mapOf(
                "totalEntries" to totalEntries,
                "entriesWithGameVal" to entriesWithGameVal,
                "uniqueGameVals" to uniqueGameVals,
                "entriesWithDuration" to entriesWithDuration,
                "averageTickDuration" to avgTickDuration
            )
            
            call.respond(response)
        } catch (e: Exception) {
            logger.error("Error handling Sequence info request: ${e.message}", e)
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
