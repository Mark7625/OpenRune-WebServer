package dev.openrune.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.cache.diff.ModelCdn
import dev.openrune.cache.diff.ModelMeta
import dev.openrune.ingest.Payloads
import dev.openrune.model.ChangeKind
import dev.openrune.model.OsrsEntityTypes
import dev.openrune.query.Search
import dev.openrune.query.SearchMode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private const val MAX_LIST_RESULTS = 500

private val MODEL_OWNER_TYPES = setOf(ConfigDiffType.ITEMS.fileName, ConfigDiffType.NPCS.fileName, ConfigDiffType.OBJECTS.fileName)

private val ITEM_MODEL_FIELDS = listOf(
    "inventoryModel", "maleModel0", "maleModel1", "maleModel2", "maleHeadModel0", "maleHeadModel1",
    "femaleModel0", "femaleModel1", "femaleModel2", "femaleHeadModel0", "femaleHeadModel1",
)

/** Names of a gameval group at a revision, cached per revision (bounded by the response cache). */
fun ApiContext.gamevalNames(group: String, rev: Int): Map<Int, String> =
    cached("names|$group|$rev") { gamevalType(group)?.let { entities.names(it, rev) } ?: emptyMap() }

fun namedIds(ids: List<Int>, names: Map<Int, String>): List<Map<String, Any?>> = ids.map { mapOf("id" to it, "name" to names[it]) }

/**
 * Which snapshot field each model id came from, in declaration order. Array fields keep their index
 * (`models[2]`) because position is meaningful, and one id can appear under several fields — an
 * item commonly uses the same mesh for its male and female equip slots.
 */
fun modelFieldsForDefinition(typeKey: String, snapshot: JsonObject): Map<Int, List<String>> {
    fun int(field: String): Int? = snapshot[field]?.let { unwrap(it) }?.takeIf { it.isJsonPrimitive }?.asInt
    fun ints(field: String): List<Int> = snapshot[field]?.let { unwrap(it) }?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e -> unwrap(e).takeIf { it.isJsonPrimitive }?.asInt } ?: emptyList()

    val pairs = ArrayList<Pair<Int, String>>()
    fun single(field: String) { int(field)?.let { pairs.add(it to field) } }
    fun array(field: String) { ints(field).forEachIndexed { index, id -> pairs.add(id to "$field[$index]") } }

    when (typeKey) {
        ConfigDiffType.ITEMS.fileName -> ITEM_MODEL_FIELDS.forEach { single(it) }
        ConfigDiffType.NPCS.fileName -> { array("models"); array("chatheadModels") }
        ConfigDiffType.OBJECTS.fileName -> array("objectModels")
    }

    val out = LinkedHashMap<Int, MutableList<String>>()
    pairs.filter { it.first > 0 }.forEach { (id, field) -> out.getOrPut(id) { ArrayList() }.add(field) }
    return out
}

/** Model ids a definition references, read from its stored snapshot (`{value, ref}` wrappers unwrapped). */
fun modelIdsForDefinition(typeKey: String, snapshot: JsonObject): List<Int> =
    modelFieldsForDefinition(typeKey, snapshot).keys.sorted()

fun unwrap(element: JsonElement): JsonElement =
    if (element.isJsonObject && element.asJsonObject.has("ref") && element.asJsonObject.has("value")) element.asJsonObject["value"] else element

fun intList(snapshot: JsonObject, field: String): List<Int> =
    snapshot[field]?.let { unwrap(it) }?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { e -> unwrap(e).takeIf { it.isJsonPrimitive }?.asInt } ?: emptyList()

/**
 * A definition recolours/retextures its models by listing the values to find and the values to use
 * instead, paired by position. Returns those pairs; `to` is null if the replacement list is short.
 */
fun swapPairs(snapshot: JsonObject, findField: String, replaceField: String): List<Pair<Int, Int?>> {
    val find = intList(snapshot, findField)
    val replace = intList(snapshot, replaceField)
    return find.mapIndexed { index, value -> value to replace.getOrNull(index) }
}

fun Route.modelRoutes(ctx: ApiContext) {
    listOf(
        Triple("/models/info", "Aggregate model stats for a revision. Query: rev.", listOf("/models/info?rev=236")),
        Triple("/models", "Model metadata list. Query: rev, ids=1,2,3 or idRange=300..900, limit.", listOf("/models?rev=236&ids=1,2,3")),
        Triple("/models/table", "Paginated model rows. Query: rev, offset, limit, q.", listOf("/models/table?rev=240&offset=0&limit=50")),
        Triple("/models/delta", "Model ids added / removed / changed between two revisions. Query: base, rev.", listOf("/models/delta?base=239&rev=240")),
        Triple("/models/for/{type}/{id}", "Models used by one item / npc / object. Query: rev.", listOf("/models/for/items/4151?rev=240")),
        Triple("/models/{id}", "Model metadata for one id. Query: rev.", listOf("/models/1234?rev=236")),
        Triple("/models/{id}/dat", "Redirects to the raw .dat on the CDN, resolving the revision the mesh last changed in. Query: rev.", listOf("/models/1234/dat?rev=241")),
    ).forEach { (path, desc, examples) -> EndpointRegistry.registerEndpoint("GET", path, desc, "Models", null, "application/json", examples) }

    val models = ctx.type(OsrsEntityTypes.MODELS)
    val textures = ctx.type(ConfigDiffType.TEXTURES.fileName)

    fun modelPayload(rev: Int, id: Int, meta: ModelMeta): Map<String, Any?> = mapOf(
        "id" to id,
        "rev" to rev,
        "vertexCount" to meta.vertexCount,
        "faceCount" to meta.faceCount,
        "texturedFaceCount" to meta.texturedFaceCount,
        "transparentFaceCount" to meta.transparentFaceCount,
        "version" to meta.version,
        "renderPriority" to meta.renderPriority,
        "textures" to meta.textures,
        "colors" to meta.colors,
        "attachments" to mapOf(
            "items" to namedIds(meta.itemIds, ctx.gamevalNames("items", rev)),
            "npcs" to namedIds(meta.npcIds, ctx.gamevalNames("npcs", rev)),
            "objects" to namedIds(meta.objectIds, ctx.gamevalNames("objects", rev)),
            "total" to meta.attachmentCount,
        ),
        // Models are uploaded only at the revision their mesh changed in, so point at that one.
        "dat" to ModelCdn.publicModelUrl(
            ctx.cdn, ctx.game.game.gameType, ctx.entities.sourceRevision(models, rev, id) ?: rev, id,
        ),
    )

    fun textureRefs(rev: Int, ids: Collection<Int>): List<Map<String, Any?>> {
        val spriteNames = ctx.gamevalNames("sprites", rev)
        return ids.sorted().map { id ->
            val fileId = ctx.entities.get(textures, rev, id)?.payload?.asJsonObject?.get("fileId")?.let { unwrap(it) }?.asInt
            mapOf("id" to id, "fileId" to fileId, "name" to fileId?.let { spriteNames[it] })
        }
    }

    get("/models/table") {
        val rev = call.publishedRev(ctx)
        val (offset, limit) = call.paging(50)
        val q = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotEmpty() }
        val search = q?.let { Search(if (it.all { ch -> ch.isDigit() }) SearchMode.NAME else SearchMode.ID, it) }
        val page = ctx.entities.page(models, rev, offset, limit, search, call.intParam("after"), withPayload = true)
        if (page.total == 0 && q == null) notFound("No model data for rev $rev")
        call.respond(
            mapOf(
                "rev" to rev, "total" to page.total, "offset" to offset, "limit" to limit,
                "hasMore" to page.hasMore, "nextCursor" to page.nextCursor,
                "rows" to page.rows.map { row ->
                    val meta = Payloads.modelFromPayload(row.payload!!.asJsonObject)
                    mapOf(
                        "id" to row.id,
                        "vertexCount" to meta.vertexCount,
                        "faceCount" to meta.faceCount,
                        "transparentFaceCount" to meta.transparentFaceCount,
                        "hasTransparency" to (meta.transparentFaceCount > 0),
                    )
                },
            ),
        )
    }

    get("/models/delta") {
        val (base, rev) = call.revisionPair(ctx)
        val entries = ctx.cached("models-delta|$base|$rev") { ctx.diffs.ids(models, base, rev) }
        val added = entries.filter { it.kind == ChangeKind.ADDED }.map { it.id }
        val removed = entries.filter { it.kind == ChangeKind.REMOVED }.map { it.id }
        val changed = entries.filter { it.kind == ChangeKind.CHANGED }.map { it.id }
        call.respond(
            mapOf(
                "base" to base, "rev" to rev, "added" to added, "removed" to removed, "changed" to changed,
                "counts" to mapOf("added" to added.size, "removed" to removed.size, "changed" to changed.size),
            ),
        )
    }

    get("/models/for/{type}/{id}") {
        val typeKey = call.parameters["type"]?.trim()?.lowercase() ?: badRequest("Expected /models/for/{type}/{id}")
        val defId = call.parameters["id"]?.toIntOrNull() ?: badRequest("Expected /models/for/{type}/{id}")
        if (typeKey !in MODEL_OWNER_TYPES) badRequest("Type must be one of ${MODEL_OWNER_TYPES.joinToString(", ")}")
        val rev = call.publishedRev(ctx)
        val def = ctx.entities.get(ctx.type(typeKey), rev, defId) ?: notFound("$typeKey $defId not found at rev $rev")
        val snapshot = def.payload!!.asJsonObject
        val fieldsById = modelFieldsForDefinition(typeKey, snapshot)
        val modelIds = fieldsById.keys.sorted()
        val found = modelIds.mapNotNull { id -> ctx.entities.get(models, rev, id)?.let { id to Payloads.modelFromPayload(it.payload!!.asJsonObject) } }
        val textureIds = sortedSetOf<Int>()
        val colors = sortedSetOf<Int>()
        var vertexCount = 0; var faceCount = 0; var texturedFaceCount = 0; var transparentFaceCount = 0
        found.forEach { (_, meta) ->
            vertexCount += meta.vertexCount; faceCount += meta.faceCount
            texturedFaceCount += meta.texturedFaceCount; transparentFaceCount += meta.transparentFaceCount
            textureIds.addAll(meta.textures); colors.addAll(meta.colors)
        }
        val ownerGroup = when (typeKey) {
            ConfigDiffType.NPCS.fileName -> "npcs"
            ConfigDiffType.OBJECTS.fileName -> "objects"
            else -> "items"
        }
        call.respond(
            mapOf<String, Any?>(
                "type" to typeKey, "id" to defId, "rev" to rev,
                "name" to ctx.gamevalNames(ownerGroup, rev)[defId],
                "modelIds" to modelIds,
                "models" to found.map { (id, meta) ->
                    modelPayload(rev, id, meta) + mapOf<String, Any?>("fields" to fieldsById[id].orEmpty())
                },
                // What this definition swaps on top of the models' own palette.
                "recolours" to swapPairs(snapshot, "originalColours", "modifiedColours")
                    .map { (from, to) -> mapOf("from" to from, "to" to to) },
                "retextures" to swapPairs(snapshot, "originalTextureColours", "modifiedTextureColours").map { (from, to) ->
                    val refs = textureRefs(rev, listOfNotNull(from, to)).associateBy { it["id"] }
                    mapOf("from" to refs[from], "to" to to?.let { refs[it] })
                },
                "totals" to mapOf(
                    "models" to found.size,
                    "missingModels" to (modelIds.size - found.size),
                    "vertexCount" to vertexCount, "faceCount" to faceCount,
                    "texturedFaceCount" to texturedFaceCount, "transparentFaceCount" to transparentFaceCount,
                    "textures" to textureRefs(rev, textureIds),
                    "colors" to colors.toList(),
                ),
            ),
        )
    }

    get("/models/info") {
        val rev = call.publishedRev(ctx)
        val info = ctx.cached("models-info|$rev") {
            val textureIds = HashSet<Int>(); val colorIds = HashSet<Int>()
            var faces = 0L; var verts = 0L; var items = 0; var npcs = 0; var objects = 0; var total = 0
            ctx.entities.forEachPayload(models, rev) { _, _, body ->
                val meta = Payloads.modelFromPayload(body.asJsonObject)
                total++
                faces += meta.faceCount; verts += meta.vertexCount
                textureIds.addAll(meta.textures); colorIds.addAll(meta.colors)
                items += meta.itemIds.size; npcs += meta.npcIds.size; objects += meta.objectIds.size
            }
            mapOf(
                "rev" to rev, "totalModels" to total, "totalFaces" to faces, "totalVerts" to verts,
                "uniqueTextures" to textureIds.size, "uniqueColors" to colorIds.size,
                "attachments" to mapOf("items" to items, "npcs" to npcs, "objects" to objects),
            )
        }
        if (info["totalModels"] == 0) notFound("No model data for rev $rev")
        call.respond(info)
    }

    get("/models") {
        val rev = call.publishedRev(ctx)
        val idsParam = call.request.queryParameters["ids"]
        val idRange = call.request.queryParameters["idRange"]?.let { raw ->
            val parts = when {
                raw.contains("..") -> raw.split("..")
                raw.contains(",") -> raw.split(",")
                raw.contains("-") -> raw.split("-")
                else -> return@let null
            }
            if (parts.size != 2) null else parts[0].trim().toIntOrNull()?.let { a -> parts[1].trim().toIntOrNull()?.let { b -> if (b >= a) a..b else null } }
        }
        val limit = call.intParam("limit")
        if (idsParam.isNullOrBlank() && idRange == null && limit == null) {
            badRequest("One of 'ids', 'idRange' or 'limit' is required; the full model set is too large")
        }
        val selected: List<Int> = when {
            !idsParam.isNullOrBlank() -> idsParam.split(",").mapNotNull { it.trim().toIntOrNull() }
            idRange != null -> ctx.entities.page(models, rev, 0, MAX_LIST_RESULTS, Search(SearchMode.ID, "${idRange.first}..${idRange.last}")).rows.map { it.id }
            else -> ctx.entities.page(models, rev, 0, MAX_LIST_RESULTS).rows.map { it.id }
        }.let { ids -> if (limit != null && limit > 0) ids.take(limit) else ids }.take(MAX_LIST_RESULTS)
        val entries = selected.mapNotNull { id ->
            ctx.entities.get(models, rev, id)?.let { modelPayload(rev, id, Payloads.modelFromPayload(it.payload!!.asJsonObject)) }
        }
        call.respond(mapOf("rev" to rev, "count" to entries.size, "models" to entries))
    }

    get("/models/{id}") {
        val id = call.parameters["id"]?.toIntOrNull() ?: badRequest("Invalid model id")
        val rev = call.publishedRev(ctx)
        val row = ctx.entities.get(models, rev, id) ?: notFound("Model $id not found at rev $rev")
        call.respond(modelPayload(rev, id, Payloads.modelFromPayload(row.payload!!.asJsonObject)))
    }

    /**
     * Mesh bytes live on the CDN under the revision they last changed in, which a caller holding
     * only `(id, rev)` cannot know. This resolves that and redirects, so nothing has to guess a key.
     */
    get("/models/{id}/dat") {
        val id = call.parameters["id"]?.toIntOrNull() ?: badRequest("Invalid model id")
        val rev = call.publishedRev(ctx)
        val source = call.intParam("source")?.takeIf { ctx.catalog.isPublished(it) }
            ?: ctx.entities.sourceRevision(models, rev, id)
            ?: notFound("Model $id not found at rev $rev")
        val url = ModelCdn.publicModelUrl(ctx.cdn, ctx.game.game.gameType, source, id)
            ?: notFound("CDN is not configured, so model $id has no public URL")
        // The mapping is immutable for a published revision, so this can be cached hard.
        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400, stale-while-revalidate=604800")
        call.respondRedirect(url, permanent = false)
    }
}
