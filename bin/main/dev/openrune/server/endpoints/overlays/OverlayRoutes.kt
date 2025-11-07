package dev.openrune.server.endpoints.overlays

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.configs.OverlaysManifestEntry
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

fun loadOverlayData(config: ServerConfig, type: DataType = DataType.WEB): Map<Int, Any> {
    return loadManifestData<OverlaysManifestEntry>(config, "overlay", type)
}

fun Route.registerOverlayEndpoints(config: ServerConfig) {
    val overlaySearchConfig = object : SearchConfig<OverlaysManifestEntry> {
        override fun getNameField(entry: OverlaysManifestEntry): String? = null
        override fun getRegexField(entry: OverlaysManifestEntry): String? = null
        override fun getGameValField(entry: OverlaysManifestEntry): String? = null
        override fun getValidSearchModes(): Set<SearchMode> = setOf(SearchMode.ID)
    }

    val overlayManifestConfig = ManifestEndpointConfig<OverlayQueryParams, OverlaysManifestEntry>(
        name = "Overlay",
        endpoint = "/overlays",
        cacheKey = "overlay-data",
        loadWebData = { loadOverlayData(it, DataType.WEB) },
        loadCacheData = { loadOverlayData(it, DataType.CACHE) },
        searchConfig = overlaySearchConfig,
        parseQueryParams = { call ->
            OverlayQueryParams(
                id = call.parameters["id"]?.toIntOrNull(),
                idRange = BaseQueryParams.parseIdRange(call.parameters["idRange"]),
                limit = call.parameters["limit"]?.toIntOrNull(),
                searchMode = BaseQueryParams.parseSearchMode(call.parameters["searchMode"]),
                q = call.parameters["q"]
            )
        },
        supportsDataType = true,
        keyType = ManifestKeyType.INT,
        errorMessageNotFound = "Overlay not found"
    )
    
    val overlayManifestEndpoint = GlobalManifestEndpoint(config, overlayManifestConfig)
    
    getDocumentedNoParams(
        path = "/overlays/info",
        category = "Overlays",
        description = "Get comprehensive statistics about all overlays including total entries, texture counts, water overlays, and distribution metrics.",
        responseType = "application/json",
        examples = listOf("/overlays/info")
    ) { _ ->
        val handler = OverlayInfoHandler(config)
        handler.handle(call)
    }

    getDocumented<OverlayQueryParams>(
        path = "/overlays/data",
        category = "Overlays",
        description = "Retrieve overlay manifest data as JSON. Supports filtering by ID, ID range, and limits. Use dataType=web for optimized data or dataType=cache for complete cache entries. Supports ID search mode only.",
        responseType = "application/json",
        examples = listOf(
            "/overlays/data",
            "/overlays/data?dataType=web",
            "/overlays/data?dataType=cache",
            "/overlays/data?id=12",
            "/overlays/data?idRange=45..49",
            "/overlays/data?limit=20",
            "/overlays/data?searchMode=id&q=45+90"
        )
    ) { _ ->
        val params = overlayManifestEndpoint.getQueryParams(call)
        overlayManifestEndpoint.handle(call, params)
    }
}

