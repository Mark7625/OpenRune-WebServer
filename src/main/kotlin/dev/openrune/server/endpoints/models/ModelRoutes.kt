package dev.openrune.server.endpoints.models

import dev.openrune.ServerConfig
import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.cache.diff.DiffBinaryCache
import dev.openrune.cache.diff.ModelCdn
import dev.openrune.cache.diff.ModelExtractor
import dev.openrune.cache.diff.ModelMeta
import dev.openrune.server.EndpointRegistry
import dev.openrune.server.endpoints.GamevalGroup
import dev.openrune.server.endpoints.gamevalNames
import dev.openrune.server.endpoints.namedIds
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_MODEL_REV = 1000
private const val MAX_LIST_RESULTS = 500

/** Config types whose definitions reference models. */
private val MODEL_OWNER_TYPES = setOf(
    ConfigDiffType.ITEMS.fileName,
    ConfigDiffType.NPCS.fileName,
    ConfigDiffType.OBJECTS.fileName,
)

private fun modelPayload(
    config: ServerConfig,
    rev: Int,
    id: Int,
    meta: ModelMeta,
    itemNames: Map<Int, String>,
    npcNames: Map<Int, String>,
    objectNames: Map<Int, String>,
): Map<String, Any?> = mapOf(
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
        "items" to namedIds(meta.itemIds, itemNames),
        "npcs" to namedIds(meta.npcIds, npcNames),
        "objects" to namedIds(meta.objectIds, objectNames),
        "total" to meta.attachmentCount,
    ),
    "dat" to ModelCdn.publicModelUrl(config.spriteCdn, config.gameType, rev, id),
)

/**
 * Texture ids resolved to `{id, fileId, name}` — the sprite the texture renders and its gameval,
 * so a client can show an image and a label without a request per texture.
 */
private fun textureRefs(config: ServerConfig, rev: Int, textureIds: Collection<Int>): List<Map<String, Any?>> {
    if (textureIds.isEmpty()) return emptyList()
    val snapshots = DiffBinaryCache.getTypedCombinedConfig(config, ConfigDiffType.TEXTURES.fileName, rev)
    val spriteNames = gamevalNames(config, rev, GamevalGroup.SPRITES)
    return textureIds.sorted().map { id ->
        val fileId = snapshots[id]?.get("fileId")?.value as? Int
        mapOf(
            "id" to id,
            "fileId" to fileId,
            "name" to fileId?.let { spriteNames[it] },
        )
    }
}

/**
 * ID search, matching the site's shared behaviour (`diff-id-search.ts`):
 *  - digits only (`34`) matches any id whose decimal string contains it (`134`, `340`, …)
 *  - `,` separates union groups
 *  - `a+b` with exactly two operands is an inclusive range; three or more is a union
 *  - `a-b` inside a token is an inclusive range
 */
private fun idSearchMatches(id: Int, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    if (q.all { it.isDigit() }) return id.toString().contains(q)
    return id in parseIdSearchToIds(q)
}

private fun parseIdSearchToIds(query: String): Set<Int> {
    val out = HashSet<Int>()
    for (segment in query.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
        val plusParts = segment.split('+').map { it.trim() }.filter { it.isNotEmpty() }
        if (plusParts.size == 2 && plusParts.all { part -> part.all { it.isDigit() } }) {
            val a = plusParts[0].toIntOrNull()
            val b = plusParts[1].toIntOrNull()
            if (a != null && b != null) {
                for (i in minOf(a, b)..maxOf(a, b)) out.add(i)
                continue
            }
        }
        for (part in plusParts) {
            if (part.contains('-')) {
                val bounds = part.split('-').map { it.trim().toIntOrNull() }
                val a = bounds.getOrNull(0)
                val b = bounds.getOrNull(1)
                if (a != null && b != null) {
                    for (i in minOf(a, b)..maxOf(a, b)) out.add(i)
                }
            } else {
                part.toIntOrNull()?.let { out.add(it) }
            }
        }
    }
    return out
}

/** Accepts `300..900`, `300-900` or `300,900`. */
private fun parseIdRange(raw: String?): IntRange? {
    if (raw.isNullOrBlank()) return null
    val parts = when {
        raw.contains("..") -> raw.split("..")
        raw.contains(",") -> raw.split(",")
        raw.contains("-") -> raw.split("-")
        else -> return null
    }
    if (parts.size != 2) return null
    val from = parts[0].trim().toIntOrNull() ?: return null
    val to = parts[1].trim().toIntOrNull() ?: return null
    if (to < from) return null
    return from..to
}

fun Route.registerModelEndpoints(config: ServerConfig) {
    listOf(
        Triple(
            "/models/info",
            "Aggregate model stats for a revision (counts, unique textures/colours, attachment totals). Query: rev.",
            listOf("/models/info?rev=236"),
        ),
        Triple(
            "/models",
            "Model metadata list. Query: rev, ids=1,2,3 or idRange=300..900, limit.",
            listOf("/models?rev=236&ids=1,2,3", "/models?rev=236&idRange=300..900&limit=100"),
        ),
        Triple(
            "/models/table",
            "Paginated model rows for the archive table (id, verts, triangles, transparency). Query: rev, offset, limit, q.",
            listOf("/models/table?rev=240&offset=0&limit=50", "/models/table?rev=240&q=1234"),
        ),
        Triple(
            "/models/delta",
            "Model ids added / removed / changed between two revisions. Query: base, rev.",
            listOf("/models/delta?base=239&rev=240"),
        ),
        Triple(
            "/models/for/{type}/{id}",
            "Models used by one item / npc / object, each with its metadata, plus combined totals. Query: rev.",
            listOf("/models/for/objects/1276?rev=240", "/models/for/items/4151?rev=240"),
        ),
        Triple(
            "/models/{id}",
            "Model metadata for one id: verts, faces, textures, colours, attached items/npcs/objects, CDN .dat url. Query: rev.",
            listOf("/models/1234?rev=236"),
        ),
    ).forEach { (path, desc, examples) ->
        EndpointRegistry.registerEndpoint("GET", path, desc, "Models", null, "application/json", examples)
    }

    get("/models/table") {
        val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision)
            .coerceIn(1, MAX_MODEL_REV)
        val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, MAX_LIST_RESULTS)
        val q = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotEmpty() }

        val models = withContext(Dispatchers.Default) { DiffBinaryCache.getCombinedModels(config, rev) }
        if (models.isEmpty()) {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "No model data for rev $rev (run migrateModelsToCdn)"),
            )
            return@get
        }
        // Ids only — the table shows nothing that needs a name lookup.
        val matching = if (q == null) {
            models.keys.sorted()
        } else {
            models.keys.filter { idSearchMatches(it, q) }.sorted()
        }
        val page = matching.drop(offset).take(limit)
        call.respond(
            mapOf(
                "rev" to rev,
                "total" to matching.size,
                "offset" to offset,
                "limit" to limit,
                "rows" to page.mapNotNull { id ->
                    val meta = models[id] ?: return@mapNotNull null
                    mapOf(
                        "id" to id,
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
        val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision)
            .coerceIn(1, MAX_MODEL_REV)
        val base = (call.request.queryParameters["base"]?.toIntOrNull() ?: 1).coerceIn(1, rev)
        val (baseModels, revModels) = withContext(Dispatchers.Default) {
            DiffBinaryCache.getCombinedModels(config, base) to DiffBinaryCache.getCombinedModels(config, rev)
        }
        if (baseModels.isEmpty() && revModels.isEmpty()) {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "No model data for revs $base..$rev (run migrateModelsToCdn)"),
            )
            return@get
        }
        val added = (revModels.keys - baseModels.keys).sorted()
        val removed = (baseModels.keys - revModels.keys).sorted()
        val changed = (baseModels.keys intersect revModels.keys)
            .filter { baseModels[it] != revModels[it] }
            .sorted()
        call.respond(
            mapOf(
                "base" to base,
                "rev" to rev,
                "added" to added,
                "removed" to removed,
                "changed" to changed,
                "counts" to mapOf(
                    "added" to added.size,
                    "removed" to removed.size,
                    "changed" to changed.size,
                ),
            ),
        )
    }

    get("/models/for/{type}/{id}") {
        val type = call.parameters["type"]?.trim()?.lowercase()
        val defId = call.parameters["id"]?.toIntOrNull()
        if (type.isNullOrEmpty() || defId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Expected /models/for/{type}/{id}"))
            return@get
        }
        if (type !in MODEL_OWNER_TYPES) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Type must be one of ${MODEL_OWNER_TYPES.joinToString(", ")}"),
            )
            return@get
        }
        val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision)
            .coerceIn(1, MAX_MODEL_REV)

        val snapshot = DiffBinaryCache.getTypedCombinedConfig(config, type, rev)[defId] ?: run {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "$type $defId not found at rev $rev"))
            return@get
        }
        val modelIds = ModelExtractor.modelIdsForDefinition(type, snapshot)
        val allModels = withContext(Dispatchers.Default) { DiffBinaryCache.getCombinedModels(config, rev) }

        val itemNames = gamevalNames(config, rev, GamevalGroup.ITEMS)
        val npcNames = gamevalNames(config, rev, GamevalGroup.NPCS)
        val objectNames = gamevalNames(config, rev, GamevalGroup.OBJECTS)
        val ownerNames = when (type) {
            ConfigDiffType.NPCS.fileName -> npcNames
            ConfigDiffType.OBJECTS.fileName -> objectNames
            else -> itemNames
        }

        val found = modelIds.mapNotNull { id -> allModels[id]?.let { id to it } }
        val textures = sortedSetOf<Int>()
        val colors = sortedSetOf<Int>()
        var vertexCount = 0
        var faceCount = 0
        var texturedFaceCount = 0
        var transparentFaceCount = 0
        found.forEach { (_, meta) ->
            vertexCount += meta.vertexCount
            faceCount += meta.faceCount
            texturedFaceCount += meta.texturedFaceCount
            transparentFaceCount += meta.transparentFaceCount
            textures.addAll(meta.textures)
            colors.addAll(meta.colors)
        }

        call.respond(
            mapOf(
                "type" to type,
                "id" to defId,
                "rev" to rev,
                "name" to ownerNames[defId],
                // Ids the definition points at, including any with no metadata in this revision.
                "modelIds" to modelIds,
                "models" to found.map { (id, meta) ->
                    modelPayload(config, rev, id, meta, itemNames, npcNames, objectNames)
                },
                "totals" to mapOf(
                    "models" to found.size,
                    "missingModels" to (modelIds.size - found.size),
                    "vertexCount" to vertexCount,
                    "faceCount" to faceCount,
                    "texturedFaceCount" to texturedFaceCount,
                    "transparentFaceCount" to transparentFaceCount,
                    // Resolved so the UI can render each texture with its image and gameval.
                    "textures" to textureRefs(config, rev, textures),
                    "colors" to colors.toList(),
                ),
            ),
        )
    }

    get("/models/info") {
        val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision)
            .coerceIn(1, MAX_MODEL_REV)
        val models = withContext(Dispatchers.Default) { DiffBinaryCache.getCombinedModels(config, rev) }
        if (models.isEmpty()) {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "No model data for rev $rev (re-dump or run migrateModelsToCdn)"),
            )
            return@get
        }
        val textures = HashSet<Int>()
        val colors = HashSet<Int>()
        var faces = 0L
        var verts = 0L
        var items = 0
        var npcs = 0
        var objects = 0
        models.values.forEach { meta ->
            faces += meta.faceCount
            verts += meta.vertexCount
            textures.addAll(meta.textures)
            colors.addAll(meta.colors)
            items += meta.itemIds.size
            npcs += meta.npcIds.size
            objects += meta.objectIds.size
        }
        call.respond(
            mapOf(
                "rev" to rev,
                "totalModels" to models.size,
                "totalFaces" to faces,
                "totalVerts" to verts,
                "uniqueTextures" to textures.size,
                "uniqueColors" to colors.size,
                "attachments" to mapOf(
                    "items" to items,
                    "npcs" to npcs,
                    "objects" to objects,
                ),
            ),
        )
    }

    get("/models") {
        val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision)
            .coerceIn(1, MAX_MODEL_REV)
        val idsParam = call.request.queryParameters["ids"]
        val idRange = parseIdRange(call.request.queryParameters["idRange"])
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()

        if (idsParam.isNullOrBlank() && idRange == null && limit == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "One of 'ids', 'idRange' or 'limit' is required; the full model set is too large"),
            )
            return@get
        }

        val models = withContext(Dispatchers.Default) { DiffBinaryCache.getCombinedModels(config, rev) }
        val selected = when {
            !idsParam.isNullOrBlank() -> idsParam.split(",").mapNotNull { it.trim().toIntOrNull() }
            idRange != null -> models.keys.filter { it in idRange }.sorted()
            else -> models.keys.sorted()
        }.let { ids -> if (limit != null && limit > 0) ids.take(limit) else ids }
            .take(MAX_LIST_RESULTS)

        val itemNames = gamevalNames(config, rev, GamevalGroup.ITEMS)
        val npcNames = gamevalNames(config, rev, GamevalGroup.NPCS)
        val objectNames = gamevalNames(config, rev, GamevalGroup.OBJECTS)

        val entries = selected.mapNotNull { id ->
            models[id]?.let { modelPayload(config, rev, id, it, itemNames, npcNames, objectNames) }
        }
        call.respond(mapOf("rev" to rev, "count" to entries.size, "models" to entries))
    }

    get("/models/{id}") {
        val id = call.parameters["id"]?.toIntOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid model id"))
            return@get
        }
        val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision)
            .coerceIn(1, MAX_MODEL_REV)
        val meta = withContext(Dispatchers.Default) { DiffBinaryCache.getCombinedModels(config, rev)[id] } ?: run {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Model $id not found at rev $rev"))
            return@get
        }
        call.respond(
            modelPayload(
                config,
                rev,
                id,
                meta,
                gamevalNames(config, rev, GamevalGroup.ITEMS),
                gamevalNames(config, rev, GamevalGroup.NPCS),
                gamevalNames(config, rev, GamevalGroup.OBJECTS),
            ),
        )
    }
}
