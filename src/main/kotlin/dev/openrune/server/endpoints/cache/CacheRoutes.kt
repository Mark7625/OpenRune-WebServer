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

/** Config fileName → gameval dump group (matches DiffRoutes). */
private fun gamevalGroupNameForConfigType(type: String): String? = when (type.lowercase()) {
    "items" -> "items"
    "npcs" -> "npcs"
    "objects" -> "objects"
    "inv" -> "inv"
    "sequences" -> "sequences"
    "spotanims" -> "spotanims"
    "varp" -> "varp"
    "varbit" -> "varbits"
    "varclient", "varcs" -> "varcs"
    "interfaces" -> "components"
    else -> null
}

private fun loadIdToSearchableGamevals(config: ServerConfig, rev: Int, groupName: String): Map<Int, String> =
    DiffBinaryCache.getDecodedRev(config, rev.coerceAtLeast(1))
        ?.gameval
        ?.get(groupName)
        .orEmpty()
        .mapValues { (_, extra) -> extra.searchable }

/** Attach `gameval` onto snapshot JSON so text headers don't need a second round-trip. */
private fun snapshotToJsonWithGameval(
    snapshot: DefinitionSnapshot,
    id: Int,
    idToGameval: Map<Int, String>?,
): Map<String, Any?> {
    val json = snapshotToJson(snapshot).toMutableMap()
    if (!json.containsKey("gameval")) {
        idToGameval?.get(id)?.takeIf { it.isNotBlank() }?.let { json["gameval"] = it }
    }
    return json
}

private fun getTypedCombinedConfig(config: ServerConfig, type: String, upToRev: Int): Map<Int, DefinitionSnapshot> =
    DiffBinaryCache.getTypedCombinedConfig(config, type, upToRev)

/** Max entities per /cache page when offset/limit are provided. */
private const val CACHE_PAGE_LIMIT_MAX = 500
private const val CACHE_PAGE_LIMIT_DEFAULT = 150

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
        description = "Typed cache snapshots for a config type at revision (all ids, a page via offset/limit, or a single id).",
        category = "Cache",
        queryParamsClass = null,
        responseType = "application/json",
        examples = listOf(
            "/cache?type=items&rev=237",
            "/cache?type=items&rev=237&offset=0&limit=150",
            "/cache?type=items&id=4151&rev=237",
        )
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
        val offsetParam = call.request.queryParameters["offset"]?.toIntOrNull()
        val limitParam = call.request.queryParameters["limit"]?.toIntOrNull()
        val paged = offsetParam != null || limitParam != null
        val offset = (offsetParam ?: 0).coerceAtLeast(0)
        val limit = (limitParam ?: CACHE_PAGE_LIMIT_DEFAULT).coerceIn(1, CACHE_PAGE_LIMIT_MAX)

        val decoded = DiffBinaryCache.getDecodedRev(config, rev)
        if (decoded == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Missing diff binary for rev $rev"))
            return@get
        }

        val merged = getTypedCombinedConfig(config, type, rev)
        val gamevalGroup = gamevalGroupNameForConfigType(type)
        val idToGameval = gamevalGroup?.let { loadIdToSearchableGamevals(config, rev, it) }
        val payload: Map<String, Any?> = if (id != null) {
            val single = merged[id]
            if (single == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No $type definition for id $id at rev $rev"))
                return@get
            }
            mapOf(
                "rev" to rev,
                "type" to type,
                "id" to id,
                "snapshot" to snapshotToJsonWithGameval(single, id, idToGameval),
            )
        } else {
            val sorted = merged.entries.sortedBy { it.key }
            val total = sorted.size
            val slice = if (paged) sorted.drop(offset).take(limit) else sorted
            val snapshots = slice.associate { (defId, snap) ->
                defId.toString() to snapshotToJsonWithGameval(snap, defId, idToGameval)
            }
            buildMap {
                put("rev", rev)
                put("type", type)
                put("count", if (paged) slice.size else total)
                put("total", total)
                if (paged) {
                    put("offset", offset)
                    put("limit", limit)
                    put("hasMore", offset + slice.size < total)
                }
                put("snapshots", snapshots)
            }
        }

        val binFile = CachePathHelper.getDiffBinaryFile(config.gameType, config.environment, rev)
        val etagSeed = when {
            id != null -> "${binFile.lastModified()}:${binFile.length()}:$type:$id"
            paged -> "${binFile.lastModified()}:${binFile.length()}:$type:page:$offset:$limit"
            else -> "${binFile.lastModified()}:${binFile.length()}:$type"
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
