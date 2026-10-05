package dev.openrune.api

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonWriter
import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.cache.diff.ConfigSerializer
import dev.openrune.model.ChangeKind
import dev.openrune.query.Search
import dev.openrune.query.SearchMode
import dev.openrune.query.changedFields
import dev.openrune.store.GameRegistry.RegisteredType
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.StringWriter

private val gson = Gson()
private const val GAMEVAL_TYPE_PREFIX = "gamevals_"

/** Fields the website's table reads outside its own columns (textures resolve their image via `fileId`). */
private val TABLE_EXTRA_FIELDS: Map<String, Set<String>> = mapOf(ConfigDiffType.TEXTURES.fileName to setOf("fileId"))

/** `/cache`, `/diff/config/{type}/table|content|props` and `/interface/{id}`. */
fun Route.configRoutes(ctx: ApiContext) {
    EndpointRegistry.registerEndpoint("GET", "/cache", "Typed snapshots for a config type at a revision (a page via offset/limit, or a single id).", "Cache", null, "application/json", listOf("/cache?type=items&rev=237&offset=0&limit=150", "/cache?type=items&id=4151&rev=237"))
    EndpointRegistry.registerEndpoint("GET", "/diff/config/{type}/table", "Paginated config table rows. Query: base, rev, offset, limit, after, q, mode.", "Diff", null, "application/json", listOf("/diff/config/items/table?rev=236&offset=0&limit=50"))
    EndpointRegistry.registerEndpoint("GET", "/diff/config/{type}/content", "Added / removed / changed entities between base and rev, all in one response. Prefer /changes for large diffs. Query: base, rev.", "Diff", null, "application/json", listOf("/diff/config/items/content?base=240&rev=241"))
    EndpointRegistry.registerEndpoint("GET", "/diff/config/{type}/changes", "Paginated changes between base and rev with per-field diffs. Query: base, rev, offset, limit, after, kind=added,changed,removed.", "Diff", null, "application/json", listOf("/diff/config/items/changes?base=1&rev=241&limit=100", "/diff/config/items/changes?base=1&rev=241&kind=added"))
    EndpointRegistry.registerEndpoint("GET", "/diff/config/{type}/props", "Render schema for a config type (fields, table columns, search modes).", "Diff", null, "application/json", listOf("/diff/config/items/props"))
    EndpointRegistry.registerEndpoint("GET", "/diff/config/{type}/names", "Every id to name for a config type at a revision, for client-side name search. Query: rev.", "Diff", null, "application/json", listOf("/diff/config/items/names?rev=241"))
    EndpointRegistry.registerEndpoint("GET", "/diff/entity/{type}/{id}/history", "Revisions in which an entity changed. ", "Diff", null, "application/json", listOf("/diff/entity/items/4151/history"))
    EndpointRegistry.registerEndpoint("GET", "/interface/{id}", "Full interface (all components) by id. Query: rev.", "Diff", null, "application/json", listOf("/interface/0?rev=236"))

    get("/cache") {
        val typeKey = call.request.queryParameters["type"]?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: badRequest("Missing type")
        val type = ctx.configType(typeKey) ?: badRequest("Invalid type")
        val rev = call.publishedRev(ctx)
        val id = call.intParam("id")
        val paged = call.request.queryParameters["offset"] != null || call.request.queryParameters["limit"] != null
        val (offset, limit) = if (paged) call.paging(150) else 0 to MAX_UNPAGED_ROWS
        val after = call.intParam("after")
        call.respondConditional(etagFor(ctx, "cache", type.def.key, rev, id, offset, limit, after)) {
            if (id != null) {
                val row = ctx.entities.get(type, rev, id) ?: notFound("No ${type.def.key} definition for id $id at rev $rev")
                call.respondJson(gson.toJson(mapOf("rev" to rev, "type" to type.def.key, "id" to id, "snapshot" to withGameval(row.payload!!, row.gameval))))
                return@respondConditional
            }
            val page = ctx.metrics.time("api.cache.page", { "${type.def.key}@$rev" }) {
                ctx.entities.page(type, rev, offset, limit, afterId = after, withPayload = true)
            }
            val writer = StringWriter(1 shl 16)
            JsonWriter(writer).use { w ->
                w.beginObject()
                w.name("rev").value(rev).name("type").value(type.def.key)
                w.name("count").value(page.rows.size).name("total").value(page.total)
                w.name("offset").value(offset).name("limit").value(limit).name("hasMore").value(page.hasMore)
                page.nextCursor?.let { w.name("nextCursor").value(it) }
                w.name("snapshots").beginObject()
                page.rows.forEach { row -> w.name(row.id.toString()); gson.toJson(withGameval(row.payload!!, row.gameval), w) }
                w.endObject().endObject()
            }
            call.respondJson(writer.toString())
        }
    }

    get("/diff/config/{type}/props") {
        val type = ctx.configType(call.parameters["type"] ?: badRequest("Missing type")) ?: badRequest("Invalid type")
        val diffType = type.def.config!!
        call.noStore()
        val tableColumns = diffType.tableColumns().map { if (it.size == 1) it.first() else it }.toMutableList<Any>()
        if (diffType.navGamevalType != null && tableColumns.none { it == "gameval" }) tableColumns.add(0, "gameval")
        call.respond(
            mapOf(
                "fields" to diffType.fieldProps(),
                "dumpFields" to ConfigSerializer.dumpFieldNames(diffType),
                "tableColumns" to tableColumns,
                "searchModes" to diffType.searchFields(),
                "hasGameval" to (diffType.navGamevalType != null),
            ),
        )
    }

    /**
     * Every `id -> name` for a config type at one revision, so the website can hold the set and
     * filter name search locally instead of asking the server on each keystroke. Only types whose
     * entities carry a name return anything; for the rest `names` is empty.
     */
    get("/diff/config/{type}/names") {
        val rawType = (call.parameters["type"] ?: badRequest("Missing type")).lowercase()
        val type = ctx.configType(rawType) ?: badRequest("Invalid type")
        val rev = call.publishedRev(ctx)
        call.respondConditional(etagFor(ctx, "config-names", rawType, rev)) {
            val json = ctx.cached("config-names|${type.def.key}|$rev") {
                val writer = StringWriter(1 shl 16)
                JsonWriter(writer).use { w ->
                    w.beginObject().name("rev").value(rev).name("type").value(type.def.key)
                    w.name("names").beginObject()
                    ctx.entities.names(type, rev).forEach { (id, name) ->
                        if (name.isNotBlank()) w.name(id.toString()).value(name)
                    }
                    w.endObject().endObject()
                }
                writer.toString()
            }
            call.respondJson(json)
        }
    }

    get("/diff/config/{type}/table") {
        val rawType = (call.parameters["type"] ?: badRequest("Missing type")).lowercase()
        val gamevalGroup = rawType.takeIf { it.startsWith(GAMEVAL_TYPE_PREFIX) }?.let { gamevalGroupFromParam(it.removePrefix(GAMEVAL_TYPE_PREFIX)) ?: it.removePrefix(GAMEVAL_TYPE_PREFIX) }
        val type: RegisteredType = if (gamevalGroup != null) {
            ctx.gamevalType(gamevalGroup) ?: badRequest("Invalid type")
        } else {
            ctx.configType(rawType) ?: badRequest("Invalid type")
        }
        val (base, rev) = call.revisionPair(ctx)
        val (offset, limit) = call.paging(50)
        val after = call.intParam("after")
        val q = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
        val modeParam = call.request.queryParameters["mode"] ?: call.request.queryParameters["searchMode"]
            ?: call.request.queryParameters["queryType"] ?: call.request.queryParameters["querytype"]
        val mode = modeParam?.let { m -> SearchMode.entries.firstOrNull { it.name.equals(m, ignoreCase = true) } } ?: SearchMode.NAME
        val search = q?.let { Search(mode, it) }
        call.noStore()
        val etag = etagFor(ctx, "table", rawType, base, rev, offset, limit, after, mode.name, q)
        call.respondConditional(etag) {
            val page = ctx.metrics.time("api.table", { "$rawType@$rev q=${q ?: ""}" }) {
                ctx.entities.page(type, rev, offset, limit, search, after, withPayload = gamevalGroup == null)
            }
            val rows = page.rows.map { row ->
                val fields: Map<String, Any?> = if (gamevalGroup != null) {
                    mapOf("name" to row.name)
                } else {
                    slimTableFields(type.def.config!!, withGameval(row.payload!!, row.gameval).asJsonObject)
                }
                mapOf("id" to row.id, "fields" to fields)
            }
            call.respond(
                buildMap {
                    put("base", base); put("rev", rev); put("type", rawType)
                    put("mode", mode.name.lowercase()); put("q", q ?: "")
                    put("offset", offset); put("limit", limit); put("total", page.total); put("rows", rows)
                    put("hasMore", page.hasMore)
                    page.nextCursor?.let { put("nextCursor", it) }
                    put("hash", etag); put("queryHash", etag); put("sourceHash", etag); put("cache", "none")
                },
            )
        }
    }

    get("/diff/config/{type}/content") {
        val rawType = (call.parameters["type"] ?: badRequest("Missing type")).lowercase()
        val gamevalGroup = rawType.takeIf { it.startsWith(GAMEVAL_TYPE_PREFIX) }?.let { gamevalGroupFromParam(it.removePrefix(GAMEVAL_TYPE_PREFIX)) ?: it.removePrefix(GAMEVAL_TYPE_PREFIX) }
        val (base, rev) = call.revisionPair(ctx)
        call.noStore()
        call.respondConditional(etagFor(ctx, "content", rawType, base, rev)) {
            if (gamevalGroup != null) {
                val type = ctx.gamevalType(gamevalGroup) ?: badRequest("Invalid type")
                call.respondTextWriter(ContentType.Application.Json) {
                    JsonWriter(this).use { w -> writeGamevalContent(ctx, w, type, rawType, base, rev) }
                }
            } else {
                val type = ctx.configType(rawType) ?: badRequest("Invalid type")
                call.respondTextWriter(ContentType.Application.Json) {
                    JsonWriter(this).use { w -> writeConfigContent(ctx, w, type, base, rev) }
                }
            }
        }
    }

    /**
     * Paginated form of `/content`: the same added / changed / removed information, one page at a
     * time, with the changed-field set computed in PostgreSQL. `/content` returns every change in
     * one response, which for a base-to-tip diff is tens of thousands of entities; this lets the
     * website render the first screen immediately and fetch the rest as the user scrolls.
     */
    get("/diff/config/{type}/changes") {
        val rawType = (call.parameters["type"] ?: badRequest("Missing type")).lowercase()
        val type = ctx.configType(rawType) ?: badRequest("Invalid type")
        val (base, rev) = call.revisionPair(ctx)
        val (offset, limit) = call.paging(100)
        val after = call.intParam("after")
        val kinds = call.request.queryParameters["kind"]
            ?.split(',')
            ?.mapNotNull { k -> ChangeKind.entries.firstOrNull { it.name.equals(k.trim(), ignoreCase = true) } }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: ChangeKind.entries.toSet()
        call.noStore()
        val etag = etagFor(ctx, "changes", rawType, base, rev, offset, limit, after, kinds.joinToString(",") { it.name })
        call.respondConditional(etag) {
            val counts = ctx.cached("diff-counts|${type.def.key}|$base|$rev") { ctx.diffs.counts(type, base, rev) }
            val page = ctx.metrics.time("api.changes", { "$rawType $base->$rev" }) {
                ctx.diffs.changesPage(type, base, rev, kinds, offset, limit, after)
            }
            val writer = StringWriter(1 shl 16)
            JsonWriter(writer).use { w ->
                w.beginObject()
                w.name("base").value(base).name("rev").value(rev).name("type").value(rawType)
                w.name("offset").value(offset).name("limit").value(limit)
                w.name("counts").beginObject()
                    .name("added").value(counts.added)
                    .name("changed").value(counts.changed)
                    .name("removed").value(counts.removed)
                    .endObject()
                w.name("total").value(counts.added + counts.changed + counts.removed)
                page.nextCursor?.let { w.name("nextCursor").value(it) }
                w.name("hasMore").value(page.hasMore)
                w.name("rows").beginArray()
                page.rows.forEach { row ->
                    w.beginObject()
                    w.name("id").value(row.id)
                    w.name("kind").value(row.kind.name.lowercase())
                    w.name("changedInRev").value(row.changedInRev)
                    row.name?.let { w.name("name").value(it) }
                    row.gameval?.let { w.name("gameval").value(it) }
                    row.body?.let { w.name("fields"); gson.toJson(it, w) }
                    w.endObject()
                }
                w.endArray().endObject()
            }
            call.respondJson(writer.toString())
        }
    }

    get("/diff/entity/{type}/{id}/history") {
        val type = ctx.configType(call.parameters["type"] ?: badRequest("Missing type")) ?: badRequest("Invalid type")
        val id = call.parameters["id"]?.toIntOrNull() ?: badRequest("Invalid id")
        call.noStore()
        val versions = ctx.entities.history(type, id)
        call.respond(
            mapOf(
                "type" to type.def.key,
                "id" to id,
                "versions" to versions.map { v -> mapOf("from" to v.validFrom, "to" to v.validTo, "name" to v.name, "hash" to dev.openrune.model.Hashing.hex(v.payloadHash)) },
            ),
        )
    }

    get("/interface/{id}") {
        val id = call.parameters["id"]?.toIntOrNull() ?: badRequest("Invalid interface id")
        val rev = call.publishedRev(ctx)
        call.noStore()
        val type = ctx.type(ConfigDiffType.INTERFACES.fileName)
        val row = ctx.entities.get(type, rev, id) ?: notFound("Interface $id not found for rev $rev")
        call.respondJson(gson.toJson(expandJsonStrings(row.payload!!)))
    }
}

/** Keep only fields the table lists or searches (name, gameval, table columns, per-type extras). */
fun slimTableFields(type: ConfigDiffType<*>, payload: JsonObject): Map<String, Any?> {
    val keep = LinkedHashSet<String>()
    keep.add("name"); keep.add("gameval")
    type.tableColumns().forEach { keep.addAll(it) }
    TABLE_EXTRA_FIELDS[type.fileName]?.let { keep.addAll(it) }
    val out = LinkedHashMap<String, Any?>()
    if (keep.size <= 2 && type.tableColumns().isEmpty()) {
        payload.entrySet().forEach { (k, v) -> out[k] = v }
        return out
    }
    keep.forEach { k -> payload[k]?.let { out[k] = it } }
    return out
}

/** Interfaces store `components` as an object; older clients sent it double-encoded. Decode any JSON strings. */
private fun expandJsonStrings(payload: JsonElement): JsonElement {
    if (!payload.isJsonObject) return payload
    val out = JsonObject()
    payload.asJsonObject.entrySet().forEach { (k, v) ->
        val expanded = if (v.isJsonPrimitive && v.asJsonPrimitive.isString) {
            runCatching { com.google.gson.JsonParser.parseString(v.asString) }.getOrNull()?.takeIf { it.isJsonObject || it.isJsonArray } ?: v
        } else v
        out.add(k, expanded)
    }
    return out
}

/**
 * Whole-diff form of the response.
 *
 * Deliberately a single streaming pass rather than the paged query `/changes` uses: walking the
 * whole set page by page re-runs the revision range scan for every page and measured 1,828 ms
 * against 1,019 ms in one pass. Paging wins when you only want a page — which is why the website
 * uses `/changes` — and loses when you want all of it.
 *
 * Removed ids and changed bodies are buffered because the response shape puts them after `added`;
 * both are small, holding ids and only the fields that differ.
 */
private fun writeConfigContent(ctx: ApiContext, w: JsonWriter, type: RegisteredType, base: Int, rev: Int) {
    val gamevalType = type.def.gamevalGroup?.let { ctx.gamevalType(it) }
    val revNames = gamevalType?.let { ctx.entities.names(it, rev) } ?: emptyMap()
    val baseNames = if (gamevalType != null && base != rev) ctx.entities.names(gamevalType, base) else emptyMap()

    val removed = ArrayList<Int>()
    val changed = ArrayList<Pair<Int, JsonElement>>()
    w.beginObject()
    w.name("base").value(base).name("rev").value(rev).name("type").value(type.def.key)
    w.name("added").beginObject()
    ctx.metrics.time("api.content", { "${type.def.key} $base->$rev" }) {
        ctx.diffs.forEachEntry(type, base, rev) { entry ->
            when (entry.kind) {
                ChangeKind.ADDED -> {
                    w.name(entry.id.toString())
                    gson.toJson(withGameval(entry.newPayload!!, revNames[entry.id]), w)
                }
                ChangeKind.REMOVED -> removed.add(entry.id)
                ChangeKind.CHANGED -> changed.add(
                    entry.id to changedFields(entry.oldPayload!!.asJsonObject, entry.newPayload!!.asJsonObject),
                )
            }
        }
    }
    w.endObject()
    w.name("removed").beginArray()
    removed.forEach { w.value(it) }
    w.endArray()
    w.name("changed").beginObject()
    changed.forEach { (id, fields) ->
        w.name(id.toString())
        gson.toJson(fields, w)
    }
    w.endObject()
    w.name("gamevals").beginObject()
    val headerNames = LinkedHashMap<Int, String>()
    revNames.forEach { (id, name) -> if (name.isNotBlank()) headerNames[id] = name }
    baseNames.forEach { (id, name) -> if (name.isNotBlank()) headerNames.putIfAbsent(id, name) }
    headerNames.forEach { (id, name) -> w.name(id.toString()).value(name) }
    w.endObject()
    w.endObject()
}

private fun writeGamevalContent(ctx: ApiContext, w: JsonWriter, type: RegisteredType, rawType: String, base: Int, rev: Int) {
    val baseNames = ctx.entities.names(type, base)
    val revNames = ctx.entities.names(type, rev)
    w.beginObject()
    w.name("base").value(base).name("rev").value(rev).name("type").value(rawType)
    w.name("added").beginObject()
    revNames.forEach { (id, name) -> if (id !in baseNames) w.name(id.toString()).value(name) }
    w.endObject()
    w.name("removed").beginArray()
    baseNames.keys.forEach { id -> if (id !in revNames) w.value(id) }
    w.endArray()
    w.name("changed").beginObject()
    revNames.forEach { (id, name) ->
        val before = baseNames[id] ?: return@forEach
        if (before != name) w.name(id.toString()).beginObject().name("from").value(before).name("to").value(name).endObject()
    }
    w.endObject()
    w.endObject()
}
