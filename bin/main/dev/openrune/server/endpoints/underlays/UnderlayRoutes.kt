package dev.openrune.server.endpoints.underlays

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.configs.UnderlayManifestEntry
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

fun loadUnderlayData(config: ServerConfig, type: DataType = DataType.WEB): Map<Int, Any> {
    return loadManifestData<UnderlayManifestEntry>(config, "underlay", type)
}

fun Route.registerUnderlayEndpoints(config: ServerConfig) {
    val underlaySearchConfig = object : SearchConfig<UnderlayManifestEntry> {
        override fun getNameField(entry: UnderlayManifestEntry): String? = null
        override fun getRegexField(entry: UnderlayManifestEntry): String? = null
        override fun getGameValField(entry: UnderlayManifestEntry): String? = null
        override fun getValidSearchModes(): Set<SearchMode> = setOf(SearchMode.ID)
    }

    val underlayManifestConfig = ManifestEndpointConfig<UnderlayQueryParams, UnderlayManifestEntry>(
        name = "Underlay",
        endpoint = "/underlays",
        cacheKey = "underlay-data",
        loadWebData = { loadUnderlayData(it, DataType.WEB) },
        loadCacheData = { loadUnderlayData(it, DataType.CACHE) },
        searchConfig = underlaySearchConfig,
        parseQueryParams = { call ->
            UnderlayQueryParams(
                id = call.parameters["id"]?.toIntOrNull(),
                idRange = BaseQueryParams.parseIdRange(call.parameters["idRange"]),
                limit = call.parameters["limit"]?.toIntOrNull(),
                searchMode = BaseQueryParams.parseSearchMode(call.parameters["searchMode"]),
                q = call.parameters["q"]
            )
        },
        supportsDataType = true,
        keyType = ManifestKeyType.INT,
        errorMessageNotFound = "Underlay not found"
    )
    
    val underlayManifestEndpoint = GlobalManifestEndpoint(config, underlayManifestConfig)
    
    getDocumentedNoParams(
        path = "/underlays/info",
        category = "Underlays",
        description = "Get comprehensive statistics about all underlays including total entries, unique RGB color values, and distribution metrics.",
        responseType = "application/json",
        examples = listOf("/underlays/info")
    ) { _ ->
        val handler = UnderlayInfoHandler(config)
        handler.handle(call)
    }

    getDocumented<UnderlayQueryParams>(
        path = "/underlays/data",
        category = "Underlays",
        description = "Retrieve underlay manifest data as JSON. Supports filtering by ID, ID range, and limits. Use dataType=web for optimized data or dataType=cache for complete cache entries. Supports ID search mode only.",
        responseType = "application/json",
        examples = listOf(
            "/underlays/data",
            "/underlays/data?dataType=web",
            "/underlays/data?dataType=cache",
            "/underlays/data?id=12",
            "/underlays/data?idRange=45..49",
            "/underlays/data?limit=20",
            "/underlays/data?searchMode=id&q=45+90"
        )
    ) { _ ->
        val params = underlayManifestEndpoint.getQueryParams(call)
        underlayManifestEndpoint.handle(call, params)
    }
}

