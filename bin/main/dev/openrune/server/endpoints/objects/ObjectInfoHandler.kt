package dev.openrune.server.endpoints.objects

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.configs.ObjectsManifestEntry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class ObjectInfoHandler(private val config: ServerConfig) {
    
    private val manifest: Map<Int, ObjectsManifestEntry> by lazy {
        @Suppress("UNCHECKED_CAST")
        loadObjectData(config, dev.openrune.server.endpoints.sprites.DataType.WEB) as Map<Int, ObjectsManifestEntry>
    }
    
    suspend fun handle(call: ApplicationCall) {
        try {
            val totalEntries = manifest.size
            val uniqueNames = manifest.values
                .map { it.name }
                .filter { it != "null" && it.isNotEmpty() }
                .toSet()
                .size
            val uniqueGameVals = manifest.values.mapNotNull { it.gameVal }.toSet().size
            val entriesWithGameVal = manifest.values.count { !it.gameVal.isNullOrEmpty() }
            val interactiveEntries = manifest.values.count { it.interactive }
            val entriesWithNullNames = manifest.values.count {
                val name = it.name
                name == "null" && name.isNotEmpty()
            }

            val entriesWithNoneNullNames = manifest.values.count {
                val name = it.name
                name != "null" && name.isNotEmpty()
            }


            val response = mapOf(
                "totalEntries" to totalEntries,
                "uniqueNames" to uniqueNames,
                "entriesWithGameVal" to entriesWithGameVal,
                "uniqueGameVals" to uniqueGameVals,
                "interactiveEntries" to interactiveEntries,
                "entriesWithNullNames" to entriesWithNullNames,
                "entriesWithNoneNullNames" to entriesWithNoneNullNames
            )
            
            call.respond(response)
        } catch (e: Exception) {
            logger.error("Error handling Object info request: ${e.message}", e)
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
