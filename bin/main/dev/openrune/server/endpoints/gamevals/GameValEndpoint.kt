package dev.openrune.server.endpoints.gamevals

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.GameValManifestEntry
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.server.BaseQueryParams
import dev.openrune.server.QueryHandlerJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class GameValEndpoint(private val config: ServerConfig) : QueryHandlerJson<GameValQueryParams>() {

    override val queryCache = dev.openrune.server.cache.QueryCache<Any>(defaultTtlMillis = 5 * 60 * 1000L, maxSize = 1000).also {
        dev.openrune.server.monitor.ActivityMonitor.registerCache("/gamevals", it)
    }

    private val allManifests: Map<GameValGroupTypes, Map<String, GameValManifestEntry>> by lazy {
        loadAllGameValManifests(config)
    }
    

    public override fun getQueryParams(call: ApplicationCall): GameValQueryParams {
        return GameValQueryParams(
            type = call.parameters["type"],
            id = call.parameters["id"]?.toIntOrNull(),
            gameVal = call.parameters["gameVal"],
            idRange = BaseQueryParams.parseIdRange(call.parameters["idRange"]),
            limit = call.parameters["limit"]?.toIntOrNull(),
            searchMode = null,
            q = null
        )
    }

    override suspend fun handle(call: ApplicationCall, params: GameValQueryParams) {
        try {
            // Validate type parameter if provided
            val gameValType = params.type?.let { typeName ->
                GameValGroupTypes.entries.find { 
                    it.name.equals(typeName, ignoreCase = true) || 
                    it.groupName.equals(typeName, ignoreCase = true)
                } ?: run {
                    respondError(call, "Invalid type: $typeName", 400)
                    return
                }
            }
            
            // If id or gameVal is provided, type is required
            if ((params.id != null || params.gameVal != null) && gameValType == null) {
                respondError(call, "Type parameter is required when querying by id or gameVal", 400)
                return
            }
            
            // If type is provided, get manifest for that type only
            val manifest = if (gameValType != null) {
                allManifests[gameValType] ?: emptyMap()
            } else {
                // If no type specified, return all manifests grouped by type
                val grouped = mutableMapOf<String, Any>()
                allManifests.forEach { (type, typeManifest) ->
                    val isSimple = typeManifest.values.all { it.data == null || it.data.isEmpty() }
                    if (isSimple) {
                        grouped[type.groupName] = typeManifest.mapValues { it.value.id }
                    } else {
                        grouped[type.groupName] = typeManifest
                    }
                }
                call.respond(grouped)
                return
            }
            
            if (manifest.isEmpty()) {
                respondError(call, "No gameval manifest found for type: ${params.type}", 404)
                return
            }
            
            // Check if this type is simple (all entries have no data)
            val isSimple = manifest.values.all { it.data == null || it.data.isEmpty() }
            
            // Try single entry lookup first
            val result = handleSingleEntryStringKey(
                call, params, manifest, { it.id },
                buildResponse = { key, entry ->
                    if (isSimple) {
                        mapOf(key to entry.id)
                    } else {
                        mapOf(
                            "name" to key,
                            "type" to gameValType.groupName,
                            "entry" to entry
                        )
                    }
                }
            )
            
            // If single entry was handled, return
            if (result != null) return
            
            // Otherwise, return full manifest with filtering
            handleFullManifestStringKey(
                call, params, manifest, { it.id },
                transformResponse = { filtered ->
                    if (isSimple) {
                        filtered.mapValues { it.value.id }
                    } else {
                        filtered
                    }
                },
                endpoint = "/gamevals"
            )
        } catch (e: IllegalArgumentException) {
            respondError(call, e.message ?: "Invalid request", 400)
        } catch (e: Exception) {
            logger.error("Error handling gameval request: ${e.message}", e)
            respondError(call, "Internal server error: ${e.message}", 500)
        }
    }
}

