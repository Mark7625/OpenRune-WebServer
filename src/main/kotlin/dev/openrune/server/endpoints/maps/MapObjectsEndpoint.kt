package dev.openrune.server.endpoints.maps

import dev.openrune.ServerConfig
import dev.openrune.cache.diff.LocationCustom
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

class MapObjectsEndpoint(private val config: ServerConfig) {

    suspend fun findObjectsById(call: ApplicationCall, objectId: String) {
        call.appendMapJsonNoStore()
        val objectIdInt = objectId.toIntOrNull()
        if (objectIdInt == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid object ID. Must be a number"))
            return
        }

        val requestedRev = mapRevisionFromQuery(call, config) ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid rev"))
            return
        }

        val binRevs = diffBinaryRevisionsSet(config)
        if (requestedRev !in binRevs) {
            call.respondMissingDiffBinary(requestedRev)
            return
        }
        if (!call.ensureDecodedRevisionReadyForMaps(config, requestedRev)) return

        val regionIdParam = call.request.queryParameters["regionId"]?.toIntOrNull()
        val resolved = decodedWithMapPayload(config, requestedRev) ?: run {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf(
                    "error" to "No map object data available",
                    "requestedRev" to requestedRev,
                    "objectId" to objectIdInt
                )
            )
            return
        }
        val (resolvedRev, decoded) = resolved

        val values: List<LocationCustom> = if (regionIdParam != null) {
            val region = decoded.mapRegions[regionIdParam]
            if (region == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf(
                        "error" to "Region $regionIdParam not found",
                        "requestedRev" to requestedRev,
                        "rev" to resolvedRev,
                        "regionId" to regionIdParam
                    )
                )
                return
            }
            region.positions.filter { it.id == objectIdInt }
        } else {
            decoded.mapObjects[objectIdInt].orEmpty()
        }

        call.respond(
            mapOf(
                "id" to objectIdInt,
                "objectId" to objectIdInt,
                "requestedRev" to requestedRev,
                "rev" to resolvedRev,
                "count" to values.size,
                "objects" to values,
                "locations" to values
            )
        )
    }
}

internal fun ApplicationCall.appendMapJsonNoStore() {
    response.headers.append(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate")
    response.headers.append(HttpHeaders.Pragma, "no-cache")
    response.headers.append(HttpHeaders.Expires, "0")
}
