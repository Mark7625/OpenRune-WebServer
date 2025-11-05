package dev.openrune.server.endpoints.textures

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.TextureManifestEntry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class TextureInfoHandler(private val config: ServerConfig) {
    
    private val manifest: Map<Int, TextureManifestEntry> by lazy {
        @Suppress("UNCHECKED_CAST")
        loadTextureData(config, dev.openrune.server.endpoints.sprites.DataType.WEB) as Map<Int, TextureManifestEntry>
    }
    
    suspend fun handle(call: ApplicationCall) {
        try {
            val totalArchives = manifest.size
            var totalOverlays = 0
            var totalItems = 0
            var totalObjects = 0
            var totalNpcs = 0
            val uniqueFileIds = mutableSetOf<Int>()
            
            manifest.values.forEach { entries ->
                uniqueFileIds.add(entries.fileId)
                totalOverlays += entries.attachments.overlays.size
                totalItems += entries.attachments.items.size
                totalObjects += entries.attachments.objects.size
                totalNpcs += entries.attachments.npcs.size
            }
            
            val response = mapOf(
                "totalArchives" to totalArchives,
                "uniqueSpriteFileIds" to uniqueFileIds.size,
                "attachments" to mapOf(
                    "totalOverlays" to totalOverlays,
                    "totalItems" to totalItems,
                    "totalObjects" to totalObjects,
                    "totalNpcs" to totalNpcs
                )
            )
            
            call.respond(response)
        } catch (e: Exception) {
            logger.error("Error handling texture info request: ${e.message}", e)
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
