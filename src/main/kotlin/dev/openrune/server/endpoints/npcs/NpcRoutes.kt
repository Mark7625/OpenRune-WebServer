package dev.openrune.server.endpoints.npcs

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.configs.NpcManifestEntry
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

fun loadNpcData(config: ServerConfig, type: DataType = DataType.WEB): Map<Int, Any> {
    return loadManifestData<NpcManifestEntry>(config, "npc", type)
}

fun Route.registerNpcEndpoints(config: ServerConfig) {
    val npcSearchConfig = object : SearchConfig<NpcManifestEntry> {
        override fun getNameField(entry: NpcManifestEntry): String? = null
        override fun getRegexField(entry: NpcManifestEntry): String? = null
        override fun getGameValField(entry: NpcManifestEntry): String? = null
        override fun getValidSearchModes(): Set<SearchMode> = setOf(SearchMode.ID, SearchMode.GAMEVAL, SearchMode.REGEX, SearchMode.NAME)
    }

    val npcManifestConfig = ManifestEndpointConfig<NpcQueryParams, NpcManifestEntry>(
        name = "NPC",
        endpoint = "/npcs",
        cacheKey = "npc-data",
        loadWebData = { loadNpcData(it, DataType.WEB) },
        loadCacheData = { loadNpcData(it, DataType.CACHE) },
        searchConfig = npcSearchConfig,
        parseQueryParams = { call ->
            NpcQueryParams(
                id = call.parameters["id"]?.toIntOrNull(),
                idRange = BaseQueryParams.parseIdRange(call.parameters["idRange"]),
                limit = call.parameters["limit"]?.toIntOrNull(),
                searchMode = BaseQueryParams.parseSearchMode(call.parameters["searchMode"]),
                q = call.parameters["q"]
            )
        },
        supportsDataType = true,
        keyType = ManifestKeyType.INT,
        errorMessageNotFound = "NPC not found"
    )
    
    val npcManifestEndpoint = GlobalManifestEndpoint(config, npcManifestConfig)
    
    getDocumentedNoParams(
        path = "/npcs/info",
        category = "NPCs",
        description = "Get comprehensive statistics about all NPCs including total entries, unique names, and distribution metrics.",
        responseType = "application/json",
        examples = listOf("/npcs/info")
    ) { _ ->
        val handler = NpcInfoHandler(config)
        handler.handle(call)
    }

    getDocumented<NpcQueryParams>(
        path = "/npcs/data",
        category = "NPCs",
        description = "Retrieve NPC manifest data as JSON. Supports filtering by ID, ID range, and limits. Use dataType=web for optimized data or dataType=cache for complete cache entries. Supports ID search mode only.",
        responseType = "application/json",
        examples = listOf(
            "/npcs/data",
            "/npcs/data?dataType=web",
            "/npcs/data?dataType=cache",
            "/npcs/data?id=12",
            "/npcs/data?idRange=45..49",
            "/npcs/data?limit=20",
            "/npcs/data?searchMode=id&q=45+90"
        )
    ) { _ ->
        val params = npcManifestEndpoint.getQueryParams(call)
        npcManifestEndpoint.handle(call, params)
    }
}
