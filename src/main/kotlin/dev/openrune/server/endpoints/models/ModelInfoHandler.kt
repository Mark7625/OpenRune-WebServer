package dev.openrune.server.endpoints.models

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.ModelManifestEntry
import dev.openrune.server.endpoints.loadManifestData
import dev.openrune.server.endpoints.sprites.DataType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class ModelInfoHandler(private val config: ServerConfig) {
    
    private val manifest: Map<Int, ModelManifestEntry> by lazy {
        @Suppress("UNCHECKED_CAST")
        loadManifestData<ModelManifestEntry>(config, "models", DataType.WEB) as Map<Int, ModelManifestEntry>
    }
    
    suspend fun handle(call: ApplicationCall) {
        try {
            val totalEntries = manifest.size
            var totalFaces = 0
            var totalVerts = 0
            val textures = mutableSetOf<Int>()
            val colors = mutableSetOf<Int>()
            var totalItems = 0
            var totalObjects = 0
            var totalNpcs = 0
            
            manifest.values.forEach { entry ->
                totalFaces += entry.totalFaces
                totalVerts += entry.totalVerts
                textures.addAll(entry.textures)
                colors.addAll(entry.colors)
                totalItems += entry.attachments.items.size
                totalObjects += entry.attachments.objects.size
                totalNpcs += entry.attachments.npcs.size
            }
            
            val response = mapOf(
                "totalEntries" to totalEntries,
                "totalFaces" to totalFaces,
                "totalVerts" to totalVerts,
                "uniqueTextures" to textures.size,
                "uniqueColors" to colors.size,
                "attachments" to mapOf(
                    "totalItems" to totalItems,
                    "totalObjects" to totalObjects,
                    "totalNpcs" to totalNpcs
                )
            )
            
            call.respond(response)
        } catch (e: Exception) {
            logger.error("Error handling model info request: ${e.message}", e)
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






