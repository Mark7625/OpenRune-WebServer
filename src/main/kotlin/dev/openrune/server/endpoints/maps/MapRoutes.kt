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
    val mapObjectsEndpoint = MapObjectsEndpoint(config)
    val mapRegionEndpoint = MapRegionEndpoint(config)
    
    // Route to get map objects by ID - MUST come before /map/{zoom}/{filename} to avoid route conflicts
    getDocumentedNoParams(
        path = "/map/objects/{objectId}",
        category = "Maps",
        description = "Retrieve all locations where a specific object ID appears. Optionally filter by regionId query parameter. If regionId is not provided, searches all regions. Returns object locations with position (x, y, z), type, orientation, and regionId.",
        responseType = "application/json",
        examples = listOf(
            "/map/objects/1",
            "/map/objects/123?regionId=12850",
            "/map/objects/456?regionId=4140"
        )
    ) { _ ->
        val objectId = call.parameters["objectId"] ?: run {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, "Object ID parameter is required")
            return@getDocumentedNoParams
        }

        mapObjectsEndpoint.findObjectsById(call, objectId)
    }
    
    // Route to get region data by ID - MUST come before /map/region/{regionId} to avoid route conflicts
    getDocumentedNoParams(
        path = "/map/regions/{regionId}",
        category = "Maps",
        description = "Retrieve region data by region ID. Returns region information including positions, total objects, overlay IDs, and underlay IDs. RegionId is calculated as (regionX shl 8) or regionY.",
        responseType = "application/json",
        examples = listOf(
            "/map/regions/12850",
            "/map/regions/4140",
            "/map/regions/12342"
        )
    ) { _ ->
        val regionId = call.parameters["regionId"] ?: run {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, "Region ID parameter is required")
            return@getDocumentedNoParams
        }
        
        mapRegionEndpoint.getRegionData(call, regionId)
    }
    
    // Shortcut route: /map/{regionId} - must come before /map/{zoom}/{filename}
    getDocumentedNoParams(
        path = "/map/{regionId}",
        category = "Maps",
        description = "Retrieve a map region image as PNG by regionId (shortcut). Defaults to zoom=2 and plane=0. RegionId is calculated as (regionX shl 8) or regionY. Plane and zoom can be specified via query parameters.",
        responseType = "image/png",
        examples = listOf(
            "/map/12342",
            "/map/12850?plane=1",
            "/map/12850?plane=0&zoom=3",
            "/map/4140?plane=2&zoom=1"
        )
    ) { _ ->
        val regionId = call.parameters["regionId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "RegionId parameter is required")
            return@getDocumentedNoParams
        }
        
        // Check if regionId is numeric (to avoid matching /map/objects/123)
        if (regionId.toIntOrNull() == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid regionId. Must be a number")
            return@getDocumentedNoParams
        }

        val plane = call.request.queryParameters["plane"] ?: "0"
        val zoom = call.request.queryParameters["zoom"] ?: "2"

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
    
    // Most general route - must come last to avoid matching more specific routes
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
}

