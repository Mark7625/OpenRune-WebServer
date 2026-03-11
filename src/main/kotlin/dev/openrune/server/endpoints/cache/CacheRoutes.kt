package dev.openrune.server.endpoints.cache

import dev.openrune.ServerConfig
import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.cache.diff.DefinitionSnapshot
import dev.openrune.cache.diff.DiffBinaryCache
import dev.openrune.cache.diff.FieldEntry
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.server.EndpointRegistry
import dev.openrune.util.json
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.security.MessageDigest

private fun md5HexUtf8(s: String): String {
    val md = MessageDigest.getInstance("MD5")
    return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private fun parseIfNoneMatch(raw: String?): String? =
    raw?.trim()?.removePrefix("W/")?.removeSurrounding("\"")

private fun rawValueToJson(value: Any?): Any? = when (value) {
    is FieldEntry -> fieldEntryToJson(value)
    is Map<*, *> -> value.entries.associate { (key, nested) -> key.toString() to rawValueToJson(nested) }
    is List<*> -> value.map { rawValueToJson(it) }
    else -> value
}

private fun fieldEntryToJson(entry: FieldEntry): Any? {
    val value = when (val raw = entry.value) {
        is Map<*, *> -> if (raw.entries.all { it.key is Int && (it.value == null || it.value is FieldEntry) }) {
            @Suppress("UNCHECKED_CAST")
            (raw as Map<Int, FieldEntry>).entries.associate { (k, e) -> k.toString() to fieldEntryToJson(e) }
        } else rawValueToJson(raw)
        is List<*> -> raw.map { rawValueToJson(it) }
        else -> raw
    }
    val ref = entry.ref
    return if (ref == null) value else mapOf(
        "value" to value,
        "ref" to mapOf("group" to ref.group, "id" to ref.id, "name" to ref.name)
    )
}

private fun snapshotToJson(snapshot: DefinitionSnapshot): Map<String, Any?> =
    snapshot.entries.associate { (k, e) -> k to fieldEntryToJson(e) }

private fun getTypedCombinedConfig(config: ServerConfig, type: String, upToRev: Int): Map<Int, DefinitionSnapshot> {
    val base = DiffBinaryCache.getDecodedRev(config, 1)?.configs?.get(type) ?: emptyMap()
    if (upToRev <= 1) return base
    val merged = base.toMutableMap()
    for (r in 2..upToRev) {
        val decoded = DiffBinaryCache.getDecodedRev(config, r) ?: continue
        val summary = decoded.manifest.configs[type] ?: continue
        summary.removed.forEach { id -> merged.remove(id) }
        val delta = decoded.configs[type] ?: continue
        summary.added.forEach { id -> delta[id]?.let { merged[id] = it } }
        summary.changed.forEach { id -> delta[id]?.let { merged[id] = it } }
    }
    return merged
}

private fun gamevalDisplayName(groupName: String): String = when (groupName) {
    "items"     -> "Items"
    "npcs"      -> "NPCs"
    "inv"       -> "Inventories"
    "varp"      -> "Var Players"
    "varbits"   -> "Var Bits"
    "varcs"     -> "Var Client Scripts"
    "objects"   -> "Objects"
    "sequences" -> "Sequences"
    "spotanims" -> "Spot Anims"
    "dbrows"    -> "DB Rows"
    "dbtables"  -> "DB Tables"
    "jingles"   -> "Jingles"
    "sprites"   -> "Sprites"
    "components"-> "Interfaces"
    else        -> groupName.replaceFirstChar { it.uppercase() }
}

private fun configNavLabel(type: ConfigDiffType<*>): String =
    type.navLabel ?: type.sectionId.replaceFirstChar { it.uppercase() }

private fun navDisplayNameFor(
    id: String,
    fallbackLabel: String,
    overrides: Map<String, String>,
): String = overrides[id.trim().lowercase()] ?: fallbackLabel

fun Route.registerCacheEndpoints(config: ServerConfig) {
    EndpointRegistry.registerEndpoint(
        method = "GET",
        path = "/cache",
        description = "Typed cache snapshots for a config type at revision (all ids or a single id).",
        category = "Cache",
        queryParamsClass = null,
        responseType = "application/json",
        examples = listOf("/cache?type=items&rev=237", "/cache?type=items&id=4151&rev=237")
    )
    EndpointRegistry.registerEndpoint(
        method = "GET",
        path = "/cache/revisions",
        description = "Revisions that currently have a dumped .bin file.",
        category = "Cache",
        queryParamsClass = null,
        responseType = "application/json",
        examples = listOf("/cache/revisions")
    )
    EndpointRegistry.registerEndpoint(
        method = "GET",
        path = "/cache/types",
        description = "Supported typed config cache types.",
        category = "Cache",
        queryParamsClass = null,
        responseType = "application/json",
        examples = listOf("/cache/types")
    )
    EndpointRegistry.registerEndpoint(
        method = "GET",
        path = "/cache/gameval",
        description = "Raw gameval payload for one gameval group at a revision.",
        category = "Cache",
        queryParamsClass = null,
        responseType = "application/json",
        examples = listOf("/cache/gameval?type=items&rev=237")
    )
    EndpointRegistry.registerEndpoint(
        method = "GET",
        path = "/cache/nav",
        description = "Navigation sections (archives and config types) for the diff sidebar.",
        category = "Cache",
        queryParamsClass = null,
        responseType = "application/json",
        examples = listOf("/cache/nav")
    )
    EndpointRegistry.registerEndpoint(
        method = "GET",
        path = "/cache/gameval/groups",
        description = "All gameval groups with display names and minimum supported revision.",
        category = "Cache",
        queryParamsClass = null,
        responseType = "application/json",
        examples = listOf("/cache/gameval/groups")
    )

    get("/cache/revisions") {
        val revs = DiffBinaryCache.listRevisionsWithBinary(config).sorted()
        call.respond(mapOf("revisions" to revs, "serverRevision" to config.revision))
    }

    get("/cache/types") {
        call.respond(mapOf("types" to ConfigDiffType.diffTypeNames))
    }

    get("/cache/gameval") {
        val type = call.request.queryParameters["type"]?.trim()?.lowercase()
        if (type.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing type"))
            return@get
        }
        val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision).coerceAtLeast(1)
        val decoded = DiffBinaryCache.getDecodedRev(config, rev)
        if (decoded == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Missing diff binary for rev $rev"))
            return@get
        }
        val payload = decoded.gameval[type] ?: emptyMap<Int, Any?>()
        val jsonText = json.toJson(mapOf("rev" to rev, "type" to type, "entries" to payload))
        val binFile = CachePathHelper.getDiffBinaryFile(config.gameType, config.environment, rev)
        val etag = md5HexUtf8("${binFile.lastModified()}:${binFile.length()}:gameval:$type")
        val inm = parseIfNoneMatch(call.request.headers[HttpHeaders.IfNoneMatch])
        call.response.headers.append(HttpHeaders.ETag, "\"$etag\"")
        if (inm != null && inm == etag) {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }
        call.respondText(jsonText, ContentType.Application.Json)
    }

    get("/cache") {
        val type = call.request.queryParameters["type"]?.trim()?.lowercase()
        if (type.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing type"))
            return@get
        }
        if (type !in ConfigDiffType.diffTypeNames) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid type"))
            return@get
        }

        val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision).coerceAtLeast(1)
        val id = call.request.queryParameters["id"]?.toIntOrNull()

        val decoded = DiffBinaryCache.getDecodedRev(config, rev)
        if (decoded == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Missing diff binary for rev $rev"))
            return@get
        }

        val merged = getTypedCombinedConfig(config, type, rev)
        val payload: Map<String, Any?> = if (id != null) {
            val single = merged[id]
            if (single == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No $type definition for id $id at rev $rev"))
                return@get
            }
            mapOf("rev" to rev, "type" to type, "id" to id, "snapshot" to snapshotToJson(single))
        } else {
            mapOf("rev" to rev, "type" to type, "count" to merged.size, "snapshots" to merged.entries.sortedBy { it.key }
                .associate { (defId, snap) -> defId.toString() to snapshotToJson(snap) })
        }

        val binFile = CachePathHelper.getDiffBinaryFile(config.gameType, config.environment, rev)
        val etagSeed = if (id == null) {
            "${binFile.lastModified()}:${binFile.length()}:$type"
        } else {
            "${binFile.lastModified()}:${binFile.length()}:$type:$id"
        }
        val etag = md5HexUtf8(etagSeed)
        val inm = parseIfNoneMatch(call.request.headers[HttpHeaders.IfNoneMatch])
        call.response.headers.append(HttpHeaders.ETag, "\"$etag\"")
        if (inm != null && inm == etag) {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }

        call.respondText(json.toJson(payload), ContentType.Application.Json)
    }

    get("/cache/nav") {
        val navOverrides = config.navDisplayNameOverrides
        val archiveMinRev = GameValGroupTypes.entries.filter { g -> g.revision >= 0 }.minOfOrNull { it.revision } ?: 230
        val typeArchives = ConfigDiffType.allArchives
            .map { t ->
                val defaultLabel = configNavLabel(t)
                val displayName = navDisplayNameFor(t.sectionId, defaultLabel, navOverrides)
                mapOf(
                    "id"      to t.sectionId,
                    "label"   to defaultLabel,
                    "displayName" to displayName,
                    "apiType" to t.fileName,
                    "gamevalType" to t.navGamevalType?.groupName,
                    "gamevalEnum" to t.navGamevalType?.name,
                )
            }
        val archives = listOf(
            mapOf(
                "id" to "sprites",
                "label" to "Sprites",
                "displayName" to navDisplayNameFor("sprites", "Sprites", navOverrides),
            ),
            mapOf(
                "id" to "gamevals",
                "label" to "Gamevals",
                "displayName" to navDisplayNameFor("gamevals", "Gamevals", navOverrides),
                "minRevision" to archiveMinRev,
            ),
        ) + typeArchives
        val configs = ConfigDiffType.allConfigs
            .map { t ->
                val defaultLabel = configNavLabel(t)
                val displayName = navDisplayNameFor(t.sectionId, defaultLabel, navOverrides)
                mapOf(
                    "id"      to t.sectionId,
                    "label"   to defaultLabel,
                    "displayName" to displayName,
                    "apiType" to t.fileName,
                    "gamevalType" to t.navGamevalType?.groupName,
                    "gamevalEnum" to t.navGamevalType?.name,
                )
            }
        call.respond(
            mapOf(
                "archives" to archives,
                "configs" to configs,
                "allArchives" to archives,
                "allConfigs" to configs,
            )
        )
    }

    get("/cache/gameval/groups") {
        val navOverrides = config.navDisplayNameOverrides
        val groups = GameValGroupTypes.entries
            .map { g ->
                val defaultLabel = gamevalDisplayName(g.groupName)
                val displayName = navDisplayNameFor(g.groupName, defaultLabel, navOverrides)
                mapOf(
                    "id"          to g.groupName,
                    "label"       to defaultLabel,
                    "displayName" to displayName,
                    "minRevision" to if (g.revision < 0) 1 else g.revision.coerceAtLeast(1),
                )
            }
            .sortedBy { it["id"] as String }
        call.respond(mapOf("groups" to groups))
    }
}
