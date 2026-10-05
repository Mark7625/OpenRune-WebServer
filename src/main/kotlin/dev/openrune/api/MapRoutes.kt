package dev.openrune.api

import com.google.gson.stream.JsonWriter
import dev.openrune.cache.diff.LocationCustom
import dev.openrune.ingest.Payloads
import dev.openrune.model.OsrsEntityTypes
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Growable primitive int list. A world-wide merge holds one entry per tile per overlay; as boxed
 * `Integer`s in an `ArrayList` that is ~20 bytes an entry instead of 4.
 */
private class IntBag {
    private var data = IntArray(16)
    private var size = 0

    fun addAll(values: List<Int>) {
        if (size + values.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + values.size))
        values.forEach { data[size++] = it }
    }

    /** Sorted with duplicates dropped, matching what the buffered version returned. */
    fun sortedDistinct(): IntArray {
        val sorted = data.copyOf(size).also { it.sort() }
        var out = 0
        for (i in sorted.indices) if (i == 0 || sorted[i] != sorted[i - 1]) sorted[out++] = sorted[i]
        return sorted.copyOf(out)
    }
}

private fun JsonWriter.writePosition(p: LocationCustom) {
    beginObject()
    name("id").value(p.id)
    name("type").value(p.type)
    name("orientation").value(p.orientation)
    name("position").value(p.position)
    name("isDynamic").value(p.isDynamic)
    endObject()
}

private fun JsonWriter.writeTiles(field: String, tiles: Map<Int, IntBag>) {
    name(field).beginObject()
    tiles.keys.sorted().forEach { key ->
        name(key.toString()).beginArray()
        tiles.getValue(key).sortedDistinct().forEach { value(it) }
        endArray()
    }
    endObject()
}

/** Map regions and object placements, read from `map.regions` payloads at one revision. */
fun Route.mapRoutes(ctx: ApiContext) {
    listOf(
        Triple("/map/regions", "One region (?id=) or all regions merged (?all=true). Query: rev.", listOf("/map/regions?id=12850&rev=236")),
        Triple("/map/regions/all", "All regions merged. Query: rev.", listOf("/map/regions/all?rev=236")),
        Triple("/map/regions/{regionId}", "Single region. Query: rev.", listOf("/map/regions/12850?rev=236")),
        Triple("/map/objects/{objectId}", "Placements of an object. Query: rev, regionId.", listOf("/map/objects/1276?rev=236")),
    ).forEach { (path, desc, examples) -> EndpointRegistry.registerEndpoint("GET", path, desc, "Maps", null, "application/json", examples) }

    val regions = ctx.type(OsrsEntityTypes.MAP_REGIONS)

    suspend fun ApplicationCall.respondRegion(regionId: Int) {
        noStore()
        val rev = publishedRev(ctx)
        val row = ctx.entities.get(regions, rev, regionId) ?: notFound("Region $regionId not found", mapOf("requestedRev" to rev, "rev" to rev))
        respond(mapOf("id" to regionId, "requestedRev" to rev, "rev" to rev, "region" to Payloads.regionFromPayload(regionId, row.payload!!.asJsonObject)))
    }

    /**
     * Every region merged. This is the whole world's object placements — millions of them — so the
     * response is streamed as it is read and the tile lists are accumulated as primitive ints.
     * Collecting them into `List<LocationCustom>` / `List<Int>` first exhausted the heap and the
     * endpoint answered `500 Java heap space`.
     */
    suspend fun ApplicationCall.respondAll() {
        noStore()
        val rev = publishedRev(ctx)
        val count = ctx.entities.countAt(regions, rev)
        if (count == 0) notFound("No regions in map data", mapOf("requestedRev" to rev, "rev" to rev))

        val overlays = HashMap<Int, IntBag>()
        val underlays = HashMap<Int, IntBag>()

        respondOutputStream(ContentType.Application.Json) {
            JsonWriter(bufferedWriter()).use { w ->
                w.beginObject()
                w.name("all").value(true)
                w.name("requestedRev").value(rev)
                w.name("rev").value(rev)
                w.name("regionCount").value(count)

                w.name("positions").beginArray()
                ctx.entities.forEachPayload(regions, rev) { id, _, body ->
                    val region = Payloads.regionFromPayload(id, body.asJsonObject)
                    region.positions.forEach { p -> w.writePosition(p) }
                    region.overlayIds.forEach { (tile, list) -> overlays.getOrPut(tile) { IntBag() }.addAll(list) }
                    region.underlayIds.forEach { (tile, list) -> underlays.getOrPut(tile) { IntBag() }.addAll(list) }
                }
                w.endArray()

                w.writeTiles("overlayIds", overlays)
                w.writeTiles("underlayIds", underlays)
                w.endObject()
            }
        }
    }

    get("/map/regions") {
        if (call.boolParam("all") == true) call.respondAll()
        else call.respondRegion(call.intParam("id") ?: badRequest("Missing id query parameter (use ?id=, ?all=true, or GET /map/regions/all)"))
    }
    get("/map/regions/all") { call.respondAll() }
    get("/map/regions/{regionId}") {
        val raw = call.parameters["regionId"] ?: badRequest("Region ID parameter is required")
        if (raw.equals("all", ignoreCase = true)) call.respondAll()
        else call.respondRegion(raw.toIntOrNull() ?: badRequest("Invalid region ID. Must be a number"))
    }

    get("/map/objects/{objectId}") {
        val objectId = call.parameters["objectId"]?.toIntOrNull() ?: badRequest("Invalid object ID. Must be a number")
        call.noStore()
        val rev = call.publishedRev(ctx)
        val regionFilter = call.intParam("regionId")
        val values: List<LocationCustom> = if (regionFilter != null) {
            val row = ctx.entities.get(regions, rev, regionFilter) ?: notFound("Region $regionFilter not found", mapOf("requestedRev" to rev, "rev" to rev, "regionId" to regionFilter))
            Payloads.regionFromPayload(regionFilter, row.payload!!.asJsonObject).positions.filter { it.id == objectId }
        } else {
            ctx.entities.referencing(regions, rev, objectId).flatMap { row ->
                Payloads.regionFromPayload(row.id, row.payload!!.asJsonObject).positions.filter { it.id == objectId }
            }.sortedBy { it.position }
        }
        call.respond(
            mapOf(
                "id" to objectId, "objectId" to objectId, "requestedRev" to rev, "rev" to rev,
                "count" to values.size, "objects" to values, "locations" to values,
            ),
        )
    }
}
