package dev.openrune.server.endpoints.gamevals

import dev.openrune.ServerConfig
import dev.openrune.server.getDocumented
import dev.openrune.server.getDocumentedNoParams
import io.ktor.server.application.*
import io.ktor.server.routing.*

/**
 * Register all gameval-related endpoints with auto-documentation
 */
fun Route.registerGameValEndpoints(config: ServerConfig) {
    // Create singleton instances to share cache across requests
    val gameValEndpoint = GameValEndpoint(config)
    
    // GameVal info endpoint
    getDocumentedNoParams(
        path = "/gamevals/info",
        category = "Game Vals",
        description = "Get comprehensive statistics about all game values including counts per type (SPRITETYPES, TABLETYPES, IFTYPES, ITEMS, etc.) and distribution metrics.",
        responseType = "application/json",
        examples = listOf("/gamevals/info")
    ) { _ ->
        val handler = GameValInfoHandler(config)
        handler.handle(call)
    }

    // GameVal endpoint - simple /gamevals with optional type and id
    getDocumented<GameValQueryParams>(
        path = "/gamevals",
        category = "Game Vals",
        description = "Retrieve game value data as JSON. Returns all game values grouped by type when no filters are applied. Supports filtering by type (e.g., IFTYPES, ITEMS, TABLETYPES), ID, ID range, gameVal, and limits. Use gameVal parameter to search by name or use as archive ID.",
        responseType = "application/json",
        examples = listOf(
            "/gamevals",
            "/gamevals?type=IFTYPES",
            "/gamevals?type=ITEMS",
            "/gamevals?type=TABLETYPES",
            "/gamevals?type=IFTYPES&id=1",
            "/gamevals?type=ITEMS&gameVal=mcannonrailing1_obj",
            "/gamevals?type=ITEMS&id=995",
            "/gamevals?type=ITEMS&idRange=0..40",
            "/gamevals?type=ITEMS&idRange=0..40&limit=20"
        )
    ) { _ ->
        val params = gameValEndpoint.getQueryParams(call)
        gameValEndpoint.handle(call, params)
    }
}

