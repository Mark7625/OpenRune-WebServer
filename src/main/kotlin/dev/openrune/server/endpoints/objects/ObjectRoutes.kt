package dev.openrune.server.endpoints.objects

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.configs.ObjectsManifestEntry
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

fun loadObjectData(config: ServerConfig, type: DataType = DataType.WEB): Map<Int, Any> {
    return loadManifestData<ObjectsManifestEntry>(config, "object", type)
}

fun Route.registerObjectEndpoints(config: ServerConfig) {
    val objectSearchConfig = object : SearchConfig<ObjectsManifestEntry> {
        override fun getNameField(entry: ObjectsManifestEntry): String? {
            val name = entry.name
            return if (name != null && name != "null" && name.isNotEmpty()) name else null
        }
        
        override fun getRegexField(entry: ObjectsManifestEntry): String? {
            return getNameField(entry)
        }
        
        override fun getGameValField(entry: ObjectsManifestEntry): String? = entry.gameVal
        
        override fun getValidSearchModes(): Set<SearchMode> = setOf(SearchMode.ID, SearchMode.GAMEVAL, SearchMode.REGEX, SearchMode.NAME)
    }

    val objectManifestConfig = ManifestEndpointConfig<ObjectQueryParams, ObjectsManifestEntry>(
        name = "Object",
        endpoint = "/objects",
        cacheKey = "object-data",
        loadWebData = { loadObjectData(it, DataType.WEB) },
        loadCacheData = { loadObjectData(it, DataType.CACHE) },
        searchConfig = objectSearchConfig,
        parseQueryParams = { call ->
            ObjectQueryParams(
                id = call.parameters["id"]?.toIntOrNull(),
                idRange = BaseQueryParams.parseIdRange(call.parameters["idRange"]),
                limit = call.parameters["limit"]?.toIntOrNull(),
                searchMode = BaseQueryParams.parseSearchMode(call.parameters["searchMode"]),
                q = call.parameters["q"],
                interactiveOnly = call.parameters["interactiveOnly"]?.toBoolean(),
                filterNulls = call.parameters["filterNulls"]?.toBoolean()
            )
        },
        supportsDataType = true,
        keyType = ManifestKeyType.INT,
        errorMessageNotFound = "Object not found",
        customFilter = { filtered, params ->
            @Suppress("UNCHECKED_CAST")
            val entries = filtered

            entries.filter { (_, entry) ->
                if (params.filterNulls == true) {
                    val name = entry.name
                    if (name == "null" || name.isEmpty()) {
                        return@filter false
                    }
                }

                if (params.interactiveOnly == true && !entry.interactive) {
                    return@filter false
                }
                
                true
            }
        }
    )
    
    val objectManifestEndpoint = GlobalManifestEndpoint(config, objectManifestConfig)
    
    getDocumentedNoParams(
        path = "/objects/info",
        category = "Objects",
        description = "Get comprehensive statistics about all objects including total entries, unique names, interactive object counts, entries with game values, and null name distributions. Supports filtering via query parameters.",
        responseType = "application/json",
        examples = listOf("/objects/info")
    ) { _ ->
        val handler = ObjectInfoHandler(config)
        handler.handle(call)
    }

    getDocumented<ObjectQueryParams>(
        path = "/objects/data",
        category = "Objects",
        description = "Retrieve object manifest data as JSON. Supports filtering by ID, ID range, gameVal, regex patterns, name search, and limits. Use dataType=web for optimized data or dataType=cache for complete cache entries. Supports ID, GAMEVAL, REGEX, and NAME search modes. Filter interactive objects with interactiveOnly=true. Exclude null names with filterNulls=true.",
        responseType = "application/json",
        examples = listOf(
            "/objects/data",
            "/objects/data?dataType=web",
            "/objects/data?dataType=cache",
            "/objects/data?id=12",
            "/objects/data?idRange=45..49",
            "/objects/data?limit=20",
            "/objects/data?searchMode=id&q=45+90",
            "/objects/data?searchMode=name&q=door",
            "/objects/data?interactiveOnly=true",
            "/objects/data?interactiveOnly=true&limit=50",
            "/objects/data?filterNulls=true",
            "/objects/data?filterNulls=true&interactiveOnly=true"
        )
    ) { _ ->
        val params = objectManifestEndpoint.getQueryParams(call)
        objectManifestEndpoint.handle(call, params)
    }
}
