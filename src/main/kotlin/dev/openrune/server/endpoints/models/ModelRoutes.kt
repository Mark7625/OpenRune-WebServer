package dev.openrune.server.endpoints.models

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.ModelManifestEntry
import dev.openrune.server.BaseQueryParams
import dev.openrune.server.SearchMode
import dev.openrune.server.SearchConfig
import dev.openrune.server.endpoints.GlobalManifestEndpoint
import dev.openrune.server.endpoints.ManifestEndpointConfig
import dev.openrune.server.endpoints.ManifestKeyType
import dev.openrune.server.endpoints.loadManifestData
import dev.openrune.server.endpoints.sprites.DataType
import dev.openrune.server.getDocumented
import dev.openrune.server.getDocumentedNoParams
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun loadModelData(config: ServerConfig, type: DataType = DataType.WEB): Map<Int, Any> {
    return loadManifestData<ModelManifestEntry>(config, "models", type)
}

fun Route.registerModelEndpoints(config: ServerConfig) {
    val modelSearchConfig = object : SearchConfig<ModelManifestEntry> {
        override fun getNameField(entry: ModelManifestEntry): String? {
            val allNames = entry.attachments.items + entry.attachments.objects + entry.attachments.npcs
            return allNames.joinToString(",").takeIf { it.isNotEmpty() }
        }
        
        override fun getRegexField(entry: ModelManifestEntry): String? {
            return getNameField(entry)
        }
        
        override fun getGameValField(entry: ModelManifestEntry): String? {
            return getNameField(entry)
        }
    }

    val modelManifestConfig = ManifestEndpointConfig<ModelQueryParams, ModelManifestEntry>(
        name = "Model",
        endpoint = "/models",
        cacheKey = "model-data",
        loadWebData = { loadModelData(it, DataType.WEB) },
        loadCacheData = { loadModelData(it, DataType.CACHE) },
        searchConfig = modelSearchConfig,
        parseQueryParams = { call ->
            val id = call.parameters["id"]?.toIntOrNull()
            val idRange = BaseQueryParams.parseIdRange(call.parameters["idRange"])
            val limit = call.parameters["limit"]?.toIntOrNull()
            val searchMode = BaseQueryParams.parseSearchMode(call.parameters["searchMode"])
            val q = call.parameters["q"]
            ModelQueryParams(id = id, idRange = idRange, limit = limit, searchMode = searchMode, q = q)
        },
        supportsDataType = true,
        keyType = ManifestKeyType.INT,
        requireParams = true,
        customHandler = customHandler@{ call, params, typedData, searchConfig ->
            // Custom handler for models: require params and wrap ID response
            if (params.id != null) {
                @Suppress("UNCHECKED_CAST")
                val data = typedData as Map<Int, ModelManifestEntry>
                val entry = data[params.id]
                if (entry == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Model not found"))
                    return@customHandler true
                }
                call.respond(mapOf("id" to params.id, "entry" to entry))
                return@customHandler true
            }

            // If no params provided, return error
            if (params.idRange == null && params.limit == null && params.searchMode == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Either 'id', 'idRange', 'limit', or 'searchMode' parameter is required. Returning all models is not allowed due to size constraints.")
                )
                return@customHandler true
            }
            false
        },
        errorMessageNotFound = "Model not found"
    )
    
    val modelInfoEndpoint = GlobalManifestEndpoint(config, modelManifestConfig)
    
    getDocumentedNoParams(
        path = "/models",
        category = "Models",
        description = "Get comprehensive statistics about all models including total entries, face counts, vertex counts, and geometric metrics. Returns summary statistics only, not full manifest data.",
        responseType = "application/json",
        examples = listOf("/models")
    ) { _ ->
        val handler = ModelInfoHandler(config)
        handler.handle(call)
    }

    getDocumented<ModelQueryParams>(
        path = "/models/{param}",
        category = "Models",
        description = "Retrieve raw model data. For a single model ID, returns the raw .dat file bytes. For ID ranges (e.g., 300..900), returns filtered model manifest entries as JSON. Supports dot notation (300..900) or dash notation (300-900) for ranges.",
        responseType = "application/octet-stream or application/json",
        examples = listOf(
            "/models/1234",
            "/models/5000",
            "/models/300..900",
            "/models/100-200"
        )
    ) { _ ->
        val param = call.parameters["param"]
        if (param == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid parameter"))
            return@getDocumented
        }
        
        val idRange = BaseQueryParams.parseIdRange(param)
        if (idRange != null) {
            val params = ModelQueryParams(idRange = idRange)
            modelInfoEndpoint.handle(call, params)
        } else {
            val id = param.toIntOrNull()
            if (id != null) {
                val handler = ModelRawEndpoint(config)
                val params = ModelQueryParams(id = id)
                handler.handle(call, params)
            } else {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid ID or range format"))
            }
        }
    }

    getDocumentedNoParams(
        path = "/models/info",
        category = "Models",
        description = "Get comprehensive statistics about all models including total entries, face counts, vertex counts, and geometric metrics.",
        responseType = "application/json",
        examples = listOf("/models/info")
    ) { _ ->
        val handler = ModelInfoHandler(config)
        handler.handle(call)
    }

    // Data endpoint (alternative to info endpoint)
    getDocumented<ModelQueryParams>(
        path = "/models/data",
        category = "Models",
        description = "Retrieve model manifest data as JSON. Supports filtering by ID, ID range, and limits. Returns filtered model entries with metadata. Use dataType=web for optimized data or dataType=cache for complete cache entries. Note: All models query is not allowed due to size constraints - use ID, ID range, or limit parameters.",
        responseType = "application/json",
        examples = listOf(
            "/models/data?id=1234",
            "/models/data?idRange=300..900",
            "/models/data?idRange=100-200",
            "/models/data?idRange=50,100",
            "/models/data?limit=50",
            "/models/data?idRange=300..900&limit=100"
        )
    ) { _ ->
        val params = modelInfoEndpoint.getQueryParams(call)
        modelInfoEndpoint.handle(call, params)
    }
}


