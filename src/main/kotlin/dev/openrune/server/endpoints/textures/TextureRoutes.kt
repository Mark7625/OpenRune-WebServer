package dev.openrune.server.endpoints.textures

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.TextureManifestEntry
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

fun loadTextureData(config: ServerConfig, type: DataType = DataType.WEB): Map<Int, Any> {
    return loadManifestData<TextureManifestEntry>(config, "textures", type)
}


fun Route.registerTextureEndpoints(config: ServerConfig) {
    val textureSearchConfig = object : SearchConfig<TextureManifestEntry> {
        override fun getNameField(entry: TextureManifestEntry): String? = entry.name
        override fun getRegexField(entry: TextureManifestEntry): String? = entry.name
        override fun getGameValField(entry: TextureManifestEntry): String? = entry.name
        override fun getValidSearchModes(): Set<SearchMode> = setOf(SearchMode.ID, SearchMode.GAMEVAL, SearchMode.REGEX)
    }

    val textureManifestConfig = ManifestEndpointConfig(
        name = "Texture",
        endpoint = "/textures",
        cacheKey = "texture-data",
        loadWebData = { loadTextureData(it, DataType.WEB) },
        loadCacheData = { loadTextureData(it, DataType.CACHE) },
        searchConfig = textureSearchConfig,
        parseQueryParams = { call ->
            TextureQueryParams(
                id = call.parameters["id"]?.toIntOrNull(),
                idRange = dev.openrune.server.BaseQueryParams.parseIdRange(call.parameters["idRange"]),
                limit = call.parameters["limit"]?.toIntOrNull(),
                searchMode = dev.openrune.server.BaseQueryParams.parseSearchMode(call.parameters["searchMode"]),
                q = call.parameters["q"]
            )
        },
        supportsDataType = true,
        keyType = ManifestKeyType.INT,
        customHandler = customHandler@{ call, params, typedData, searchConfig ->
            // Custom handler for texture: if ID is provided, return entry directly (not wrapped)
            if (params.id != null) {
                @Suppress("UNCHECKED_CAST")
                val data = typedData as Map<Int, TextureManifestEntry>
                val entry = data[params.id]
                if (entry == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Texture archive not found"))
                    return@customHandler true
                }
                call.respond(entry)
                return@customHandler true
            }
            false
        },
        errorMessageNotFound = "Texture archive not found"
    )
    
    val textureManifestEndpoint = GlobalManifestEndpoint(config, textureManifestConfig)
    
    // Texture image endpoint (PNG) - loads from sprite cache using fileId
    getDocumented<TextureQueryParams>(
        path = "/textures",
        category = "Textures",
        description = "Retrieve a texture image as PNG. Texture images are rendered from sprite cache using fileId. Supports optional resizing with width, height, and aspect ratio controls.",
        responseType = "image/png",
        examples = listOf(
            "/textures?id=12",
            "/textures?id=12&width=64",
            "/textures?id=12&height=64",
            "/textures?id=12&width=128&height=128&keepAspectRatio=false"
        )
    ) { _ ->
        val handler = TextureImageEndpoint(config)
        val params = handler.getQueryParams(call)
        handler.handle(call, params)
    }

    // Texture info endpoint
    getDocumentedNoParams(
        path = "/textures/info",
        category = "Textures",
        description = "Get comprehensive statistics about all textures including total entries, attachment counts, and distribution metrics.",
        responseType = "application/json",
        examples = listOf("/textures/info")
    ) { _ ->
        val handler = TextureInfoHandler(config)
        handler.handle(call)
    }

    getDocumented<TextureQueryParams>(
        path = "/textures/data",
        category = "Textures",
        description = "Retrieve texture manifest data as JSON. Supports filtering by ID, ID range, and limits. Use dataType=web for optimized data or dataType=cache for complete cache entries. Supports ID, GAMEVAL, and REGEX search modes.",
        responseType = "application/json",
        examples = listOf(
            "/textures/data",
            "/textures/data?dataType=web",
            "/textures/data?dataType=cache",
            "/textures/data?id=12",
            "/textures/data?idRange=45..49",
            "/textures/data?idRange=100-200",
            "/textures/data?limit=20",
            "/textures/data?idRange=45..49&limit=10"
        )
    ) { _ ->
        val params = textureManifestEndpoint.getQueryParams(call)
        textureManifestEndpoint.handle(call, params)
    }
}

