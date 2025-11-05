package dev.openrune.server.endpoints.sequences

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.configs.SequenceManifestEntry
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

fun loadSequenceData(config: ServerConfig, type: DataType = DataType.WEB): Map<Int, Any> {
    return loadManifestData<SequenceManifestEntry>(config, "sequence", type)
}

fun Route.registerSequenceEndpoints(config: ServerConfig) {
    val sequenceSearchConfig = object : SearchConfig<SequenceManifestEntry> {
        override fun getNameField(entry: SequenceManifestEntry): String? = null
        override fun getRegexField(entry: SequenceManifestEntry): String? = null
        override fun getGameValField(entry: SequenceManifestEntry): String? = null
        override fun getValidSearchModes(): Set<SearchMode> = setOf(SearchMode.ID)
    }

    val sequenceManifestConfig = ManifestEndpointConfig<SequenceQueryParams, SequenceManifestEntry>(
        name = "Sequence",
        endpoint = "/sequences",
        cacheKey = "sequence-data",
        loadWebData = { loadSequenceData(it, DataType.WEB) },
        loadCacheData = { loadSequenceData(it, DataType.CACHE) },
        searchConfig = sequenceSearchConfig,
        parseQueryParams = { call ->
            SequenceQueryParams(
                id = call.parameters["id"]?.toIntOrNull(),
                idRange = BaseQueryParams.parseIdRange(call.parameters["idRange"]),
                limit = call.parameters["limit"]?.toIntOrNull(),
                searchMode = BaseQueryParams.parseSearchMode(call.parameters["searchMode"]),
                q = call.parameters["q"]
            )
        },
        supportsDataType = true,
        keyType = ManifestKeyType.INT,
        errorMessageNotFound = "Sequence not found"
    )
    
    val sequenceManifestEndpoint = GlobalManifestEndpoint(config, sequenceManifestConfig)
    
    getDocumentedNoParams(
        path = "/sequences/info",
        category = "Sequences",
        description = "Get comprehensive statistics about all animation sequences including total entries, gameVal distributions, tick durations, and animation metrics.",
        responseType = "application/json",
        examples = listOf("/sequences/info")
    ) { _ ->
        val handler = SequenceInfoHandler(config)
        handler.handle(call)
    }

    getDocumented<SequenceQueryParams>(
        path = "/sequences/data",
        category = "Sequences",
        description = "Retrieve animation sequence manifest data as JSON. Supports filtering by ID, ID range, and limits. Use dataType=web for optimized data or dataType=cache for complete cache entries. Supports ID search mode only.",
        responseType = "application/json",
        examples = listOf(
            "/sequences/data",
            "/sequences/data?dataType=web",
            "/sequences/data?dataType=cache",
            "/sequences/data?id=12",
            "/sequences/data?idRange=45..49",
            "/sequences/data?limit=20",
            "/sequences/data?searchMode=id&q=45+90"
        )
    ) { _ ->
        val params = sequenceManifestEndpoint.getQueryParams(call)
        sequenceManifestEndpoint.handle(call, params)
    }
}
