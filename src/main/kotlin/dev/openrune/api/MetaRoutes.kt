package dev.openrune.api

import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.definition.GameValGroupTypes
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Revision lists, type lists and navigation metadata. All derived from code or the catalog. */
fun Route.metaRoutes(ctx: ApiContext) {
    EndpointRegistry.registerEndpoint("GET", "/revisions", "Published revisions (revisions + serverRevision).", "Meta", null, "application/json", listOf("/revisions"))
    EndpointRegistry.registerEndpoint("GET", "/diff/revisions", "Published revisions with diff data.", "Diff", null, "application/json", listOf("/diff/revisions"))
    EndpointRegistry.registerEndpoint("GET", "/config-types", "Config types (types for the diff viewer, pathSegments for config API URLs).", "Meta", null, "application/json", listOf("/config-types"))
    EndpointRegistry.registerEndpoint("GET", "/cache/revisions", "Published revisions.", "Cache", null, "application/json", listOf("/cache/revisions"))
    EndpointRegistry.registerEndpoint("GET", "/cache/types", "Supported typed config cache types.", "Cache", null, "application/json", listOf("/cache/types"))
    EndpointRegistry.registerEndpoint("GET", "/cache/nav", "Navigation sections (archives and config types) for the diff sidebar.", "Cache", null, "application/json", listOf("/cache/nav"))
    EndpointRegistry.registerEndpoint("GET", "/cache/gameval/groups", "All gameval groups with display names and minimum supported revision.", "Cache", null, "application/json", listOf("/cache/gameval/groups"))
    EndpointRegistry.registerEndpoint("GET", "/diff/decode/status", "Decode status for revision(s); always ready for published revisions. Query: rev or revs.", "Diff", null, "application/json", listOf("/diff/decode/status?revs=1,236"))
    EndpointRegistry.registerEndpoint("GET", "/diff/config-types", "List available config diff types.", "Diff", null, "application/json", listOf("/diff/config-types"))

    fun revisionsPayload(): Map<String, Any> {
        val revs = ctx.catalog.published()
        return mapOf(
            "revisions" to revs,
            "serverRevision" to (ctx.catalog.latest() ?: -1),
            "revisionOptions" to listOf("latest") + revs.map { it.toString() },
        )
    }

    get("/revisions") { call.respond(revisionsPayload()) }
    get("/diff/revisions") { call.respond(revisionsPayload()) }
    get("/cache/revisions") {
        call.respond(mapOf("revisions" to ctx.catalog.published(), "serverRevision" to (ctx.catalog.latest() ?: -1)))
    }
    get("/config-types") {
        call.respond(mapOf("types" to ConfigDiffType.diffTypeNames, "pathSegments" to ConfigDiffType.httpExposed.map { it.pathSegment }))
    }
    get("/diff/config-types") { call.respond(mapOf("types" to ConfigDiffType.diffTypeNames)) }
    get("/cache/types") { call.respond(mapOf("types" to ConfigDiffType.diffTypeNames)) }

    get("/diff/decode/status") {
        val revs = (call.request.queryParameters["revs"]?.split(',') ?: listOfNotNull(call.request.queryParameters["rev"]))
            .mapNotNull { it.trim().toIntOrNull() }.distinct()
        if (revs.isEmpty()) badRequest("Missing rev or revs query")
        val statuses = revs.map { rev ->
            val published = ctx.catalog.isPublished(rev)
            mapOf(
                "revision" to rev,
                "status" to if (published) "ready" else "missing",
                "progress" to if (published) 100 else 0,
                "message" to if (published) "Ready" else "Revision not published",
                "error" to null,
            )
        }
        call.respond(mapOf("status" to if (statuses.all { it["status"] == "ready" }) "ready" else "missing", "revisions" to statuses))
    }

    get("/cache/nav") {
        val overrides = ctx.navDisplayNameOverrides
        fun display(id: String, fallback: String) = overrides[id.lowercase()] ?: fallback
        fun section(t: ConfigDiffType<*>): Map<String, Any?> {
            val label = t.navLabel ?: t.sectionId.replaceFirstChar { it.uppercase() }
            return mapOf(
                "id" to t.sectionId,
                "label" to label,
                "displayName" to display(t.sectionId, label),
                "apiType" to t.fileName,
                "gamevalType" to t.navGamevalType?.groupName,
                "gamevalEnum" to t.navGamevalType?.name,
            )
        }
        val archiveMinRev = GameValGroupTypes.entries.filter { it.revision >= 0 }.minOfOrNull { it.revision } ?: 230
        val archives = listOf(
            mapOf("id" to "sprites", "label" to "Sprites", "displayName" to display("sprites", "Sprites")),
            mapOf("id" to "gamevals", "label" to "Gamevals", "displayName" to display("gamevals", "Gamevals"), "minRevision" to archiveMinRev),
            mapOf("id" to "models", "label" to "Models", "displayName" to display("models", "Models")),
        ) + ConfigDiffType.allArchives.map(::section)
        val configs = ConfigDiffType.allConfigs.map(::section)
        call.respond(mapOf("archives" to archives, "configs" to configs, "allArchives" to archives, "allConfigs" to configs))
    }

    get("/cache/gameval/groups") {
        val overrides = ctx.navDisplayNameOverrides
        val groups = GameValGroupTypes.entries
            .map { g ->
                val label = gamevalDisplayName(g.groupName)
                mapOf(
                    "id" to g.groupName,
                    "label" to label,
                    "displayName" to (overrides[g.groupName] ?: label),
                    "minRevision" to if (g.revision < 0) 1 else g.revision.coerceAtLeast(1),
                )
            }
            .distinctBy { it["id"] }
            .sortedBy { it["id"] as String }
        call.respond(mapOf("groups" to groups))
    }
}

fun gamevalDisplayName(group: String): String = when (group) {
    "items" -> "Items"
    "npcs" -> "NPCs"
    "inv" -> "Inventories"
    "varp" -> "Var Players"
    "varbits" -> "Var Bits"
    "varcs" -> "Var Client Scripts"
    "objects" -> "Objects"
    "sequences" -> "Sequences"
    "spotanims" -> "Spot Anims"
    "dbrows" -> "DB Rows"
    "dbtables" -> "DB Tables"
    "jingles" -> "Jingles"
    "sprites" -> "Sprites"
    "components" -> "Interfaces"
    else -> group.replaceFirstChar { it.uppercase() }
}

/** Wire names accepted for a gameval group (`interfaces` and `components` both mean the components group). */
fun gamevalGroupFromParam(param: String): String? = when (param.trim().lowercase()) {
    "items", "itemtypes", "objtypes" -> "items"
    "npcs", "npctypes" -> "npcs"
    "inv", "invtypes" -> "inv"
    "varp", "varptypes" -> "varp"
    "varbits", "varbit", "varbittypes" -> "varbits"
    "varcs", "varclient", "varclients", "varctypes", "varclienttypes" -> "varcs"
    "objects", "loctypes" -> "objects"
    "sequences", "seqtypes" -> "sequences"
    "spotanims", "spottypes" -> "spotanims"
    "dbrows", "rowtypes" -> "dbrows"
    "dbtables", "tabletypes" -> "dbtables"
    "jingles", "soundtypes" -> "jingles"
    "sprites", "spritetypes" -> "sprites"
    "components", "iftypes", "interfaces" -> "components"
    else -> null
}

/** Lowest revision a gameval group exists in (groups without a revision bound are always available). */
fun gamevalMinRevision(group: String): Int {
    val entries = GameValGroupTypes.entries.filter { it.groupName.equals(group, ignoreCase = true) }
    if (entries.isEmpty() || entries.any { it.revision < 0 }) return 1
    return entries.minOf { it.revision }.coerceAtLeast(1)
}
