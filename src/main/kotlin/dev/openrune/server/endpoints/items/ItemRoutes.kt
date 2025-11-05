package dev.openrune.server.endpoints.items

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.configs.ItemManifestEntry
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

fun loadItemData(config: ServerConfig, type: DataType = DataType.WEB): Map<Int, Any> {
    return loadManifestData<ItemManifestEntry>(config, "item", type)
}

fun Route.registerItemEndpoints(config: ServerConfig) {
    val itemSearchConfig = object : SearchConfig<ItemManifestEntry> {
        override fun getNameField(entry: ItemManifestEntry): String? {
            val name = entry.name
            return if (name != null && name != "null" && name.isNotEmpty()) name else null
        }
        
        override fun getRegexField(entry: ItemManifestEntry): String? {
            return getNameField(entry)
        }
        
        override fun getGameValField(entry: ItemManifestEntry): String? = entry.gameVal
        
        override fun getValidSearchModes(): Set<SearchMode> = setOf(SearchMode.ID, SearchMode.GAMEVAL, SearchMode.REGEX, SearchMode.NAME)
    }

    val itemManifestConfig = ManifestEndpointConfig<ItemQueryParams, ItemManifestEntry>(
        name = "Item",
        endpoint = "/items",
        cacheKey = "item-data",
        loadWebData = { loadItemData(it, DataType.WEB) },
        loadCacheData = { loadItemData(it, DataType.CACHE) },
        searchConfig = itemSearchConfig,
        parseQueryParams = { call ->
            ItemQueryParams(
                id = call.parameters["id"]?.toIntOrNull(),
                idRange = BaseQueryParams.parseIdRange(call.parameters["idRange"]),
                limit = call.parameters["limit"]?.toIntOrNull(),
                searchMode = BaseQueryParams.parseSearchMode(call.parameters["searchMode"]),
                q = call.parameters["q"],
                notedOnly = call.parameters["notedOnly"]?.toBoolean(),
                filterNulls = call.parameters["filterNulls"]?.toBoolean()
            )
        },
        supportsDataType = true,
        keyType = ManifestKeyType.INT,
        errorMessageNotFound = "Item not found",
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

                if (params.notedOnly == true && !entry.noted) {
                    return@filter false
                }
                
                true
            }
        }
    )
    
    val itemManifestEndpoint = GlobalManifestEndpoint(config, itemManifestConfig)
    
    getDocumentedNoParams(
        path = "/items/info",
        category = "Items",
        description = "Get comprehensive statistics about all items including total entries, unique names, noted item counts, entries with game values, and null name distributions. Supports filtering via query parameters.",
        responseType = "application/json",
        examples = listOf("/items/info")
    ) { _ ->
        val handler = ItemInfoHandler(config)
        handler.handle(call)
    }

    getDocumented<ItemQueryParams>(
        path = "/items/data",
        category = "Items",
        description = "Retrieve item manifest data as JSON. Supports filtering by ID, ID range, gameVal, regex patterns, name search, and limits. Use dataType=web for optimized data or dataType=cache for complete cache entries. Supports ID, GAMEVAL, REGEX, and NAME search modes. Filter noted items with notedOnly=true. Exclude null names with filterNulls=true.",
        responseType = "application/json",
        examples = listOf(
            "/items/data",
            "/items/data?dataType=web",
            "/items/data?dataType=cache",
            "/items/data?id=12",
            "/items/data?idRange=45..49",
            "/items/data?limit=20",
            "/items/data?searchMode=id&q=45+90",
            "/items/data?searchMode=name&q=sword",
            "/items/data?notedOnly=true",
            "/items/data?notedOnly=true&limit=50",
            "/items/data?filterNulls=true",
            "/items/data?filterNulls=true&notedOnly=true"
        )
    ) { _ ->
        val params = itemManifestEndpoint.getQueryParams(call)
        itemManifestEndpoint.handle(call, params)
    }
}

