package dev.openrune.server.endpoints.items

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.configs.ItemManifestEntry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class ItemInfoHandler(private val config: ServerConfig) {
    
    private val manifest: Map<Int, ItemManifestEntry> by lazy {
        @Suppress("UNCHECKED_CAST")
        loadItemData(config, dev.openrune.server.endpoints.sprites.DataType.WEB) as Map<Int, ItemManifestEntry>
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
            val notedEntries = manifest.values.count { it.noted }
            val entriesWithNullNames = manifest.values.count {
                val name = it.name
                name == "null" || name.isEmpty()
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
                "notedEntries" to notedEntries,
                "entriesWithNullNames" to entriesWithNullNames,
                "entriesWithNoneNullNames" to entriesWithNoneNullNames
            )
            
            call.respond(response)
        } catch (e: Exception) {
            logger.error("Error handling Item info request: ${e.message}", e)
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

