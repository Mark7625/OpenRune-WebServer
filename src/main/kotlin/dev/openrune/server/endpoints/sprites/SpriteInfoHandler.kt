package dev.openrune.server.endpoints.sprites

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.SpriteManifestEntry
import dev.openrune.server.endpoints.loadManifestData
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class SpriteInfoHandler(private val config: ServerConfig) {
    
    private val manifest: Map<Int, SpriteManifestEntry> by lazy {
        @Suppress("UNCHECKED_CAST")
        loadManifestData<SpriteManifestEntry>(config, "sprites", DataType.WEB) as Map<Int, SpriteManifestEntry>
    }
    
    suspend fun handle(call: ApplicationCall) {
        try {
            val totalEntries = manifest.size
            val entriesWithNames = manifest.values.count { it.name != null }
            val totalSprites = manifest.values.sumOf { it.count }
            val entriesWithMultipleSprites = manifest.values.count { it.count > 1 }
            
            val response = mapOf(
                "totalEntries" to totalEntries,
                "entriesWithNames" to entriesWithNames,
                "totalSprites" to totalSprites,
                "entriesWithMultipleSprites" to entriesWithMultipleSprites,
                "averageSpritesPerEntry" to if (totalEntries > 0) totalSprites / totalEntries else 0
            )
            
            call.respond(response)
        } catch (e: Exception) {
            logger.error("Error handling info request: ${e.message}", e)
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

