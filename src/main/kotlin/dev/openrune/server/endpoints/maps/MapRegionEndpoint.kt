package dev.openrune.server.endpoints.maps

import dev.openrune.ServerConfig
import dev.openrune.cache.diff.LocationCustom
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

class MapRegionEndpoint(private val config: ServerConfig) {

    /**
     * Merges every region **inside the same single [rev].bin** (same payload as GET /map/regions?all=true).
     * Does not read or combine other revision binaries.
     */
    suspend fun getAllRegionsMerged(call: ApplicationCall) {
        call.appendMapJsonNoStore()
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

        val resolved = decodedWithMapPayload(config, requestedRev) ?: run {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "No region map data available", "requestedRev" to requestedRev)
            )
            return
        }
        val (resolvedRev, decoded) = resolved
        val map = decoded.mapRegions
        if (map.isEmpty()) {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf(
                    "error" to "No regions in map data",
                    "requestedRev" to requestedRev,
                    "rev" to resolvedRev
                )
            )
            return
        }
        val mergedPositions = ArrayList<LocationCustom>()
        val mergedOverlay = linkedMapOf<Int, MutableList<Int>>()
        val mergedUnderlay = linkedMapOf<Int, MutableList<Int>>()
        fun mergeTileMap(dest: MutableMap<Int, MutableList<Int>>, src: Map<Int, List<Int>>) {
            src.forEach { (tileId, packedList) ->
                dest.getOrPut(tileId) { mutableListOf() }.addAll(packedList)
            }
        }
        for ((_, reg) in map) {
            mergedPositions.addAll(reg.positions)
            mergeTileMap(mergedOverlay, reg.overlayIds)
            mergeTileMap(mergedUnderlay, reg.underlayIds)
        }
        val overlayOut = mergedOverlay.mapValues { (_, list) -> list.distinct().sorted() }
        val underlayOut = mergedUnderlay.mapValues { (_, list) -> list.distinct().sorted() }
        call.respond(
            mapOf(
                "all" to true,
                "requestedRev" to requestedRev,
                "rev" to resolvedRev,
                "regionCount" to map.size,
                "positions" to mergedPositions,
                "overlayIds" to overlayOut,
                "underlayIds" to underlayOut
            )
        )
    }

    suspend fun getRegionById(call: ApplicationCall, regionId: String) {
        call.appendMapJsonNoStore()
        if (regionId.equals("all", ignoreCase = true)) {
            getAllRegionsMerged(call)
            return
        }
        val regionIdInt = regionId.toIntOrNull()
        if (regionIdInt == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid region ID. Must be a number"))
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

        val resolved = decodedWithMapPayload(config, requestedRev) ?: run {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "No region map data available", "requestedRev" to requestedRev)
            )
            return
        }
        val (resolvedRev, decoded) = resolved
        val region = decoded.mapRegions[regionIdInt] ?: run {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf(
                    "error" to "Region $regionIdInt not found",
                    "requestedRev" to requestedRev,
                    "rev" to resolvedRev
                )
            )
            return
        }

        call.respond(
            mapOf(
                "id" to regionIdInt,
                "requestedRev" to requestedRev,
                "rev" to resolvedRev,
                "region" to region
            )
        )
    }

    suspend fun getRegionsQuery(call: ApplicationCall) {
        if (call.request.queryParameters["all"]?.equals("true", ignoreCase = true) == true) {
            getAllRegionsMerged(call)
            return
        }

        call.appendMapJsonNoStore()
        val idParam = call.request.queryParameters["id"]?.toIntOrNull()

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

        val resolved = decodedWithMapPayload(config, requestedRev) ?: run {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "No region map data available", "requestedRev" to requestedRev)
            )
            return
        }
        val (resolvedRev, decoded) = resolved

        if (idParam == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to "Missing id query parameter (use ?id=, ?all=true, or GET /map/regions/all)",
                    "requestedRev" to requestedRev
                )
            )
            return
        }

        val region = decoded.mapRegions[idParam] ?: run {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf(
                    "error" to "Region $idParam not found",
                    "requestedRev" to requestedRev,
                    "rev" to resolvedRev
                )
            )
            return
        }

        call.respond(
            mapOf(
                "id" to idParam,
                "requestedRev" to requestedRev,
                "rev" to resolvedRev,
                "region" to region
            )
        )
    }
}
