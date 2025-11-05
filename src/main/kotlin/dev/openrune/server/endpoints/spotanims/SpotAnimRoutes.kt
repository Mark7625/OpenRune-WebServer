package dev.openrune.server.endpoints.spotanims

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.configs.SpotAnimManifestEntry
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
import io.ktor.server.application.call
import io.ktor.server.routing.Route

fun loadSpotAnimData(config: ServerConfig, type: DataType = DataType.WEB): Map<Int, Any> {
    return loadManifestData<SpotAnimManifestEntry>(config, "spotanim", type)
}

fun Route.registerSpotAnimEndpoints(config: ServerConfig) {
    val spotAnimSearchConfig = object : SearchConfig<SpotAnimManifestEntry> {
        override fun getNameField(entry: SpotAnimManifestEntry): String? = null
        override fun getRegexField(entry: SpotAnimManifestEntry): String? = null
        override fun getGameValField(entry: SpotAnimManifestEntry): String? = null
        override fun getValidSearchModes(): Set<SearchMode> = setOf(SearchMode.ID)
    }

    val spotAnimManifestConfig = ManifestEndpointConfig<SpotAnimQueryParams, SpotAnimManifestEntry>(
        name = "SpotAnim",
        endpoint = "/spotanims",
        cacheKey = "spotanim-data",
        loadWebData = { loadSpotAnimData(it, DataType.WEB) },
        loadCacheData = { loadSpotAnimData(it, DataType.CACHE) },
        searchConfig = spotAnimSearchConfig,
        parseQueryParams = { call ->
            SpotAnimQueryParams(
                id = call.parameters["id"]?.toIntOrNull(),
                idRange = BaseQueryParams.parseIdRange(call.parameters["idRange"]),
                limit = call.parameters["limit"]?.toIntOrNull(),
                searchMode = BaseQueryParams.parseSearchMode(call.parameters["searchMode"]),
                q = call.parameters["q"]
            )
        },
        supportsDataType = true,
        keyType = ManifestKeyType.INT,
        errorMessageNotFound = "SpotAnim not found"
    )
    
    val spotAnimManifestEndpoint = GlobalManifestEndpoint(config, spotAnimManifestConfig)
    
    getDocumentedNoParams(
        path = "/spotanims/info",
        category = "SpotAnims",
        description = "Get comprehensive statistics about all spot animations including total entries, gameVal distributions, tick durations, and animation metrics.",
        responseType = "application/json",
        examples = listOf("/spotanims/info")
    ) { _ ->
        val handler = SpotAnimInfoHandler(config)
        handler.handle(call)
    }

    getDocumented<SpotAnimQueryParams>(
        path = "/spotanims/data",
        category = "SpotAnims",
        description = "Retrieve spot animation manifest data as JSON. Supports filtering by ID, ID range, and limits. Use dataType=web for optimized data or dataType=cache for complete cache entries. Supports ID search mode only.",
        responseType = "application/json",
        examples = listOf(
            "/spotanims/data",
            "/spotanims/data?dataType=web",
            "/spotanims/data?dataType=cache",
            "/spotanims/data?id=12",
            "/spotanims/data?idRange=45..49",
            "/spotanims/data?limit=20",
            "/spotanims/data?searchMode=id&q=45+90"
        )
    ) { _ ->
        val params = spotAnimManifestEndpoint.getQueryParams(call)
        spotAnimManifestEndpoint.handle(call, params)
    }
}
