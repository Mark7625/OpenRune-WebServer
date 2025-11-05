package dev.openrune.server.endpoints.underlays

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.configs.UnderlayManifestEntry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class UnderlayInfoHandler(private val config: ServerConfig) {
    
    private val manifest: Map<Int, UnderlayManifestEntry> by lazy {
        @Suppress("UNCHECKED_CAST")
        loadUnderlayData(config, dev.openrune.server.endpoints.sprites.DataType.WEB) as Map<Int, UnderlayManifestEntry>
    }
    
    suspend fun handle(call: ApplicationCall) {
        try {
            val totalEntries = manifest.size
            val uniqueRgbValues = manifest.values.map { it.rgb }.toSet().size
            val minRgb = manifest.values.minOfOrNull { it.rgb }
            val maxRgb = manifest.values.maxOfOrNull { it.rgb }
            
            val response = mapOf(
                "totalEntries" to totalEntries,
                "uniqueRgbValues" to uniqueRgbValues,
                "rgbRange" to mapOf(
                    "min" to (minRgb ?: 0),
                    "max" to (maxRgb ?: 0)
                )
            )
            
            call.respond(response)
        } catch (e: Exception) {
            logger.error("Error handling underlay info request: ${e.message}", e)
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

