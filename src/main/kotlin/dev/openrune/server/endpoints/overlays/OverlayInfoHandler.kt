package dev.openrune.server.endpoints.overlays

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.configs.OverlaysManifestEntry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class OverlayInfoHandler(private val config: ServerConfig) {
    
    private val manifest: Map<Int, OverlaysManifestEntry> by lazy {
        @Suppress("UNCHECKED_CAST")
        loadOverlayData(config, dev.openrune.server.endpoints.sprites.DataType.WEB) as Map<Int, OverlaysManifestEntry>
    }
    
    suspend fun handle(call: ApplicationCall) {
        try {
            val totalEntries = manifest.size
            val entriesWithTexture = manifest.values.count { it.texture != -1 }
            val entriesWithWater = manifest.values.count { it.water != -1 }
            val entriesHidingUnderlay = manifest.values.count { it.hideUnderlay }
            val uniqueTextures = manifest.values.map { it.texture }.filter { it != -1 }.toSet().size
            
            val response = mapOf(
                "totalEntries" to totalEntries,
                "entriesWithTexture" to entriesWithTexture,
                "entriesWithWater" to entriesWithWater,
                "entriesHidingUnderlay" to entriesHidingUnderlay,
                "uniqueTextures" to uniqueTextures
            )
            
            call.respond(response)
        } catch (e: Exception) {
            logger.error("Error handling overlay info request: ${e.message}", e)
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

