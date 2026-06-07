package dev.openrune.server.endpoints.maps

import dev.openrune.ServerConfig
import dev.openrune.server.EndpointRegistry
import dev.openrune.server.getDocumentedNoParams
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.registerMapEndpoints(config: ServerConfig) {
    val mapObjectsEndpoint = MapObjectsEndpoint(config)
    val mapRegionEndpoint = MapRegionEndpoint(config)

    EndpointRegistry.registerEndpoint(
        method = "GET",
        path = "/map/regions",
        description = "Map region JSON from one binary file (query rev, default server revision). Uses only {rev}.bin — never multiple revision files. ?id= one region; ?all=true or /map/regions/all merges all regions inside that same binary.",
        category = "Maps",
        queryParamsClass = null,
        responseType = "application/json",
        examples = listOf(
            "/map/regions?id=12850&rev=236",
            "/map/regions?all=true&rev=236",
            "/map/regions/all?rev=236"
        )
    )
    get("/map/regions") {
        mapRegionEndpoint.getRegionsQuery(call)
    }

    EndpointRegistry.registerEndpoint(
        method = "GET",
        path = "/map/regions/all",
        description = "Merged map for all regions inside a single binary (query rev). Same JSON as GET /map/regions?all=true. One {rev}.bin only.",
        category = "Maps",
        queryParamsClass = null,
        responseType = "application/json",
        examples = listOf("/map/regions/all", "/map/regions/all?rev=236")
    )
    get("/map/regions/all") {
        mapRegionEndpoint.getAllRegionsMerged(call)
    }

    getDocumentedNoParams(
        path = "/map/objects/{objectId}",
        category = "Maps",
        description = "Object placements from a single binary (query rev, default server revision). One {rev}.bin only; optional regionId filters within that file.",
        responseType = "application/json",
        examples = listOf(
            "/map/objects/1276",
            "/map/objects/1276?rev=236",
            "/map/objects/1276?regionId=12850&rev=236"
        )
    ) { _ ->
        val call = context
        val objectId = call.parameters["objectId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Object ID parameter is required"))
            return@getDocumentedNoParams
        }
        mapObjectsEndpoint.findObjectsById(call, objectId)
    }

    getDocumentedNoParams(
        path = "/map/regions/{regionId}",
        category = "Maps",
        description = "Single region from one binary (query rev, default server revision). One {rev}.bin only.",
        responseType = "application/json",
        examples = listOf(
            "/map/regions/12850",
            "/map/regions/12850?rev=236"
        )
    ) { _ ->
        val call = context
        val regionId = call.parameters["regionId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Region ID parameter is required"))
            return@getDocumentedNoParams
        }
        mapRegionEndpoint.getRegionById(call, regionId)
    }
}
