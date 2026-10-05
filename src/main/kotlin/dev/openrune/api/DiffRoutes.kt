package dev.openrune.api

import com.google.gson.Gson
import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.ingest.ArtifactKind
import dev.openrune.model.ChangeKind
import dev.openrune.model.OsrsEntityTypes
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.util.Base64

private val gson = Gson()

/** Revision-level diff summaries, support manifest, interface manifest and client scripts. */
fun Route.diffRoutes(ctx: ApiContext) {
    EndpointRegistry.registerEndpoint("GET", "/diff/delta/summary", "Config delta counts by type between base and rev.", "Diff", null, "application/json", listOf("/diff/delta/summary?base=240&rev=241"))
    EndpointRegistry.registerEndpoint("GET", "/diff/support/manifest", "Archive/config support and counts by revision. Query: rev.", "Diff", null, "application/json", listOf("/diff/support/manifest?rev=236"))
    EndpointRegistry.registerEndpoint("GET", "/diff/manifest/{rev}", "Added/removed/changed ids versus the previous published revision.", "Diff", null, "application/json", listOf("/diff/manifest/241"))
    EndpointRegistry.registerEndpoint("GET", "/diff/interface/manifest", "Interface manifest (interfaceId, gameval, iflegacy). Query: rev.", "Diff", null, "application/json", listOf("/diff/interface/manifest?rev=236"))
    EndpointRegistry.registerEndpoint("GET", "/diff/clientscripts/manifest", "Client script ids and byte lengths. Query: rev.", "Diff", null, "application/json", listOf("/diff/clientscripts/manifest?rev=236"))
    EndpointRegistry.registerEndpoint("GET", "/diff/clientscript/{id}", "Client script raw bytes (base64). Query: rev.", "Diff", null, "application/json", listOf("/diff/clientscript/1234?rev=236"))

    get("/diff/delta/summary") {
        val (base, rev) = call.revisionPair(ctx)
        call.respondConditional(etagFor(ctx, "delta-summary", base, rev)) {
            call.respond(mapOf("base" to base, "rev" to rev, "configs" to Views.deltaSummary(ctx, base, rev)))
        }
    }

    get("/diff/support/manifest") {
        val rev = call.publishedRev(ctx)
        call.noStore()
        call.respondConditional(etagFor(ctx, "support", rev)) {
            call.respond(Views.supportManifest(ctx, rev))
        }
    }

    get("/diff/manifest/{rev}") {
        val rev = call.parameters["rev"]?.toIntOrNull() ?: badRequest("Invalid rev")
        if (!ctx.catalog.isPublished(rev)) notFound("No diff for rev $rev")
        val previous = ctx.catalog.published().filter { it < rev }.lastOrNull() ?: rev
        call.respondConditional(etagFor(ctx, "manifest", rev)) {
            val payload = ctx.cached("manifest|$rev") {
                fun summary(key: String): Map<String, List<Int>> {
                    val entries = if (previous == rev) emptyList() else ctx.diffs.ids(ctx.type(key), previous, rev)
                    return mapOf(
                        "added" to entries.filter { it.kind == ChangeKind.ADDED }.map { it.id },
                        "removed" to entries.filter { it.kind == ChangeKind.REMOVED }.map { it.id },
                        "changed" to entries.filter { it.kind == ChangeKind.CHANGED }.map { it.id },
                    )
                }
                mapOf(
                    "revision" to rev,
                    "previousRevision" to previous,
                    "sprites" to summary(OsrsEntityTypes.SPRITES),
                    "configs" to ConfigDiffType.diffTypeNames.associateWith { summary(it) },
                    "gamevals" to emptyMap<String, Any>(),
                )
            }
            call.respond(payload)
        }
    }

    get("/diff/interface/manifest") {
        val rev = call.publishedRev(ctx)
        call.noStore()
        val rows = ctx.revisions.artifact(ctx.game.game.id, rev, ArtifactKind.INTERFACE_MANIFEST)
            ?.let { gson.fromJson(it, List::class.java) }
            ?: emptyList<Any>()
        call.respond(mapOf("rev" to rev, "rows" to rows))
    }

    get("/diff/clientscripts/manifest") {
        val rev = call.publishedRev(ctx)
        call.noStore()
        val type = ctx.type(OsrsEntityTypes.CLIENTSCRIPTS)
        val rows = ArrayList<Map<String, Any>>()
        ctx.entities.forEachPayload(type, rev) { id, _, body -> rows.add(mapOf("id" to id, "length" to (body.asJsonObject["length"]?.asInt ?: 0))) }
        call.respond(mapOf("rev" to rev, "count" to rows.size, "rows" to rows))
    }

    get("/diff/clientscript/{id}") {
        val id = call.parameters["id"]?.toIntOrNull() ?: badRequest("Invalid clientscript id")
        val rev = call.publishedRev(ctx)
        call.noStore()
        val bytes = ctx.entities.blobForEntity(ctx.type(OsrsEntityTypes.CLIENTSCRIPTS), rev, id)
            ?: notFound("Clientscript $id not found for rev $rev")
        call.respond(mapOf("id" to id, "rev" to rev, "length" to bytes.size, "rawBytesBase64" to Base64.getEncoder().encodeToString(bytes)))
    }
}
