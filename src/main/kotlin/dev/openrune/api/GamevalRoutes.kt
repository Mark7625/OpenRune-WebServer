package dev.openrune.api

import com.google.gson.stream.JsonWriter
import dev.openrune.definition.GameValGroupTypes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.StringWriter

/** `/gameval/{type}`, `/gamevals` and the gameval availability manifest. */
fun Route.gamevalRoutes(ctx: ApiContext) {
    EndpointRegistry.registerEndpoint("GET", "/gamevals", "Gameval map for type and rev. Query: type, rev, includeExtras.", "Gamevals", null, "application/json", listOf("/gamevals?type=items&rev=236"))
    EndpointRegistry.registerEndpoint("GET", "/gameval/{type}", "Gameval map for a type path segment. Query: rev, includeExtras.", "Gamevals", null, "application/json", listOf("/gameval/items?rev=236"))
    EndpointRegistry.registerEndpoint("GET", "/gamevals/{type}", "Gameval map for a type path segment; same as /gameval/{type}. Query: rev, includeExtras.", "Gamevals", null, "application/json", listOf("/gamevals/items?rev=236"))
    EndpointRegistry.registerEndpoint("GET", "/gamevals/manifest", "Gameval group availability by revision. Query: rev.", "Gamevals", null, "application/json", listOf("/gamevals/manifest?rev=236"))
    EndpointRegistry.registerEndpoint("GET", "/diff/gamevals/manifest", "Gameval group availability by revision; same as /gamevals/manifest. Query: rev.", "Gamevals", null, "application/json", listOf("/diff/gamevals/manifest?rev=236"))
    EndpointRegistry.registerEndpoint("GET", "/cache/gameval", "Raw gameval payload for one group at a revision.", "Cache", null, "application/json", listOf("/cache/gameval?type=items&rev=237"))

    suspend fun ApplicationCall.respondGroup(typeParam: String) {
        noStore()
        val group = gamevalGroupFromParam(typeParam) ?: badRequest("Invalid type")
        val rev = ctx.catalog.resolve(request.queryParameters["rev"]) ?: badRequest("Invalid rev")
        val minRev = gamevalMinRevision(group)
        if (rev < minRev) {
            respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Gameval type '$group' is not available for rev $rev", "type" to group, "rev" to rev, "availableFromRev" to minRev),
            )
            return
        }
        if (!ctx.catalog.isPublished(rev)) missingRevision(rev)
        val includeExtras = boolParam("includeExtras") ?: true
        respondConditional(etagFor(ctx, "gameval", group, rev, includeExtras)) {
            val json = ctx.cached("gameval|$group|$rev|$includeExtras") { gamevalJson(ctx, group, rev, includeExtras) }
            respondJson(json)
        }
    }

    get("/gamevals") { call.respondGroup(call.request.queryParameters["type"] ?: badRequest("Missing type")) }
    get("/gamevals/{type}") { call.respondGroup(call.parameters["type"] ?: badRequest("Missing type")) }
    get("/gameval/{type}") { call.respondGroup(call.parameters["type"] ?: badRequest("Missing type")) }

    get("/gamevals/manifest") { call.noStore(); call.respond(gamevalSupport(call.intParam("rev") ?: ctx.catalog.latest() ?: 1)) }
    get("/diff/gamevals/manifest") { call.noStore(); call.respond(gamevalSupport(call.intParam("rev") ?: ctx.catalog.latest() ?: 1)) }

    get("/cache/gameval") {
        val group = call.request.queryParameters["type"]?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: badRequest("Missing type")
        val rev = call.publishedRev(ctx)
        val type = ctx.gamevalType(group) ?: badRequest("Invalid type")
        call.respondConditional(etagFor(ctx, "cache-gameval", group, rev)) {
            val writer = StringWriter()
            JsonWriter(writer).use { w ->
                w.beginObject().name("rev").value(rev).name("type").value(group).name("entries").beginObject()
                ctx.entities.forEachPayload(type, rev) { id, name, body ->
                    w.name(id.toString()).beginObject()
                    w.name("searchable").value(name ?: "")
                    w.name("text").value(body.asJsonObject["text"]?.asString ?: name ?: "")
                    w.name("sub").beginObject()
                    body.asJsonObject["sub"]?.asJsonObject?.entrySet()?.forEach { (k, v) -> w.name(k).value(v.asString) }
                    w.endObject().endObject()
                }
                w.endObject().endObject()
            }
            call.respondJson(writer.toString())
        }
    }
}

internal fun gamevalJson(ctx: ApiContext, group: String, rev: Int, includeExtras: Boolean): String {
    val type = ctx.gamevalType(group)
    val writer = StringWriter(1 shl 16)
    JsonWriter(writer).use { w ->
        if (!includeExtras) {
            w.beginObject()
            if (type != null) ctx.entities.names(type, rev).forEach { (id, name) -> w.name(name).value(id) }
            w.endObject()
            return@use
        }
        w.beginObject().name("type").value(group).name("rev").value(rev)
        w.name("values").beginObject()
        if (type != null) ctx.entities.names(type, rev).forEach { (id, name) -> w.name(name).value(id) }
        w.endObject()
        w.name("gameval").beginObject()
        if (type != null) {
            ctx.entities.forEachPayload(type, rev) { id, name, body ->
                w.name(id.toString()).beginObject()
                w.name("searchable").value(name ?: "")
                w.name("text").value(body.asJsonObject["text"]?.asString ?: name ?: "")
                w.name("sub").beginObject()
                body.asJsonObject["sub"]?.asJsonObject?.entrySet()?.forEach { (k, v) -> w.name(k).value(v.asString) }
                w.endObject().endObject()
            }
        }
        w.endObject().endObject()
    }
    return writer.toString()
}

private fun gamevalSupport(rev: Int): Map<String, Any> {
    val minRevByGroup = GameValGroupTypes.entries.groupBy { it.groupName }.mapValues { (group, _) -> gamevalMinRevision(group) }
    val supported = minRevByGroup.mapValues { (_, min) -> rev >= min }
    return mapOf(
        "rev" to rev,
        "supported" to supported,
        "minRev" to minRevByGroup,
        "available" to supported.filterValues { it }.keys.sorted(),
        "unsupported" to supported.filterValues { !it }.keys.sorted(),
    )
}
