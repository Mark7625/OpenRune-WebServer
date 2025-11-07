package dev.openrune.server.endpoints.sprites

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.SpriteManifestEntry
import dev.openrune.server.SearchMode
import dev.openrune.server.SearchConfig
import dev.openrune.server.endpoints.GlobalManifestEndpoint
import dev.openrune.server.endpoints.ManifestEndpointConfig
import dev.openrune.server.endpoints.ManifestKeyType
import dev.openrune.server.endpoints.loadManifestData
import dev.openrune.server.getDocumented
import dev.openrune.server.getDocumentedNoParams
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun loadSpriteData(config: ServerConfig, type: DataType = DataType.WEB): Map<Int, Any> {
    return loadManifestData<SpriteManifestEntry>(config, "sprites", type)
}


fun Route.registerSpriteEndpoints(config: ServerConfig) {
    val spriteSearchConfig = object : SearchConfig<SpriteManifestEntry> {
        override fun getNameField(entry: SpriteManifestEntry): String? = entry.name
        override fun getRegexField(entry: SpriteManifestEntry): String? = entry.name
        override fun getGameValField(entry: SpriteManifestEntry): String? = entry.name
        override fun getValidSearchModes(): Set<SearchMode> = setOf(SearchMode.ID, SearchMode.GAMEVAL, SearchMode.REGEX)
    }

    val spriteManifestConfig = ManifestEndpointConfig<SpriteQueryParams, SpriteManifestEntry>(
        name = "Sprite",
        endpoint = "/sprites",
        cacheKey = "sprite-data",
        loadWebData = { loadSpriteData(it, DataType.WEB) },
        loadCacheData = { loadSpriteData(it, DataType.CACHE) },
        searchConfig = spriteSearchConfig,
        parseQueryParams = { call ->
            SpriteQueryParams(
                id = call.parameters["id"]?.toIntOrNull(),
                gameVal = call.parameters["gameVal"],
                idRange = dev.openrune.server.BaseQueryParams.parseIdRange(call.parameters["idRange"]),
                limit = call.parameters["limit"]?.toIntOrNull(),
                searchMode = dev.openrune.server.BaseQueryParams.parseSearchMode(call.parameters["searchMode"]),
                q = call.parameters["q"],
                width = call.parameters["width"]?.toIntOrNull(),
                height = call.parameters["height"]?.toIntOrNull(),
                keepAspectRatio = call.parameters["keepAspectRatio"]?.toBoolean() ?: true
            )
        },
        supportsDataType = true,
        keyType = ManifestKeyType.INT,
        errorMessageNotFound = "Sprite archive not found"
    )
    
    val spriteManifestEndpoint = GlobalManifestEndpoint(config, spriteManifestConfig)
    val spriteImageEndpoint = SpriteImageEndpoint(config)
    getDocumented<SpriteQueryParams>(
        path = "/sprites",
        category = "Sprites",
        description = "Retrieve a sprite image as PNG. Supports optional resizing with width, height, and aspect ratio controls. Can query by ID or gameVal. Supports indexed frame selection for multi-frame sprites.",
        responseType = "image/png",
        examples = listOf(
            "/sprites?id=1234",
            "/sprites?id=317&indexed=34",
            "/sprites?gameVal=sprite_name",
            "/sprites?id=1234&width=64",
            "/sprites?id=1234&height=64",
            "/sprites?gameVal=sprite_name&width=64&height=64",
            "/sprites?id=1234&width=128&height=128&keepAspectRatio=false",
            "/sprites?id=1234&width=128&keepAspectRatio=true"
        )
    ) { _ ->
        val params = spriteImageEndpoint.getQueryParams(call)
        spriteImageEndpoint.handle(call, params)
    }

    getDocumentedNoParams(
        path = "/sprites/info",
        category = "Sprites",
        description = "Get comprehensive statistics about all sprites including total entries, named sprites, and distribution metrics.",
        responseType = "application/json",
        examples = listOf("/sprites/info")
    ) { _ ->
        val handler = SpriteInfoHandler(config)
        handler.handle(call)
    }

    getDocumented<SpriteQueryParams>(
        path = "/sprites/data",
        category = "Sprites",
        description = "Retrieve sprite manifest data as JSON. Supports filtering by ID, ID range, gameVal, regex patterns, and limits. Use dataType=web for optimized data or dataType=cache for complete cache entries. Supports ID, GAMEVAL, and REGEX search modes.",
        responseType = "application/json",
        examples = listOf(
            "/sprites/data",
            "/sprites/data?dataType=web",
            "/sprites/data?dataType=cache",
            "/sprites/data?id=1234",
            "/sprites/data?gameVal=sprite_name",
            "/sprites/data?idRange=45..49",
            "/sprites/data?idRange=100-200",
            "/sprites/data?idRange=50,100",
            "/sprites/data?limit=20",
            "/sprites/data?idRange=45..49&limit=10"
        )
    ) { _ ->
        val params = spriteManifestEndpoint.getQueryParams(call)
        spriteManifestEndpoint.handle(call, params)
    }
}
