package dev.openrune.server.endpoints.maps

import dev.openrune.ServerConfig
import dev.openrune.server.getDocumented
import dev.openrune.server.getDocumentedNoParams
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.registerMapEndpoints(config: ServerConfig) {
    val mapImageEndpoint = MapImageEndpoint(config)
    
    getDocumentedNoParams(
        path = "/map/{zoom}/{filename}",
        category = "Maps",
        description = "Retrieve a map region image as PNG by filename. Zoom levels: 0-11. Default zoom is 2. Filename format: {plane}_{regionX}_{regionY}.png.",
        responseType = "image/png",
        examples = listOf(
            "/map/0/0_50_50.png",
            "/map/2/1_100_100.png",
            "/map/4/3_200_200.png"
        )
    ) { _ ->
        val zoom = call.parameters["zoom"] ?: "2"
        
        val filename = call.parameters["filename"] ?: run {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, "Filename parameter is required")
            return@getDocumentedNoParams
        }
        
        mapImageEndpoint.handle(call, zoom, filename)
    }
    
    // Route with zoom in path
    getDocumentedNoParams(
        path = "/map/{zoom}/region/{regionId}",
        category = "Maps",
        description = "Retrieve a map region image as PNG by regionId and plane. Zoom levels: 0-11. Default zoom is 2. RegionId is calculated as (regionX shl 8) or regionY. Plane must be 0-3 (query parameter, defaults to 0).",
        responseType = "image/png",
        examples = listOf(
            "/map/0/region/12850?plane=0",
            "/map/2/region/4140?plane=1",
            "/map/4/region/12850?plane=3"
        )
    ) { _ ->
        val zoom = call.parameters["zoom"] ?: "2"
        
        val regionId = call.parameters["regionId"] ?: run {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, "RegionId parameter is required")
            return@getDocumentedNoParams
        }
        
        val plane = call.request.queryParameters["plane"] ?: "0"
        
        mapImageEndpoint.handleByRegionId(call, zoom, regionId, plane)
    }
    
    // Route without zoom (defaults to 2)
    getDocumentedNoParams(
        path = "/map/region/{regionId}",
        category = "Maps",
        description = "Retrieve a map region image as PNG by regionId, plane, and zoom. RegionId is calculated as (regionX shl 8) or regionY. Plane must be 0–3 (query parameter, defaults to 0). Zoom controls the scale factor (default 2 = 4x).",
        responseType = "image/png",
        examples = listOf(
            "/map/region/12850",
            "/map/region/12850?plane=1",
            "/map/region/12850?plane=0&zoom=3",
            "/map/region/4140?plane=2&zoom=1"
        )
    ) { _ ->
        val regionId = call.parameters["regionId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "RegionId parameter is required")
            return@getDocumentedNoParams
        }

        val plane = call.request.queryParameters["plane"] ?: "0"
        val zoom = call.request.queryParameters["zoom"] ?: "2"

        mapImageEndpoint.handleByRegionId(call, zoom, regionId, plane)
    }
}

