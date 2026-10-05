package dev.openrune.api

import com.google.gson.JsonObject
import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.ingest.Payloads
import dev.openrune.model.OsrsEntityTypes
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

class TextureUsage(
    val models: List<Int> = emptyList(),
    val overlays: List<Int> = emptyList(),
    val items: List<Int> = emptyList(),
    val npcs: List<Int> = emptyList(),
    val objects: List<Int> = emptyList(),
) {
    val total: Int get() = models.size + overlays.size + items.size + npcs.size + objects.size
}

/**
 * Texture id -> everything using it at a revision: models with the texture on a face and the
 * items / npcs / objects attached to them, definitions that retexture from or to it, and
 * overlays naming it directly. Built once per revision from streamed payloads and cached.
 */
fun textureUsage(ctx: ApiContext, rev: Int): Map<Int, TextureUsage> = ctx.cached("texture-usage|$rev") {
    ctx.metrics.time("api.textures.usage.build", { "rev $rev" }) { buildTextureUsage(ctx, rev) }
}

private fun buildTextureUsage(ctx: ApiContext, rev: Int): Map<Int, TextureUsage> {
    val models = HashMap<Int, MutableSet<Int>>()
    val items = HashMap<Int, MutableSet<Int>>()
    val npcs = HashMap<Int, MutableSet<Int>>()
    val objects = HashMap<Int, MutableSet<Int>>()
    val overlays = HashMap<Int, MutableSet<Int>>()

    ctx.entities.forEachPayload(ctx.type(OsrsEntityTypes.MODELS), rev) { modelId, _, body ->
        val meta = Payloads.modelFromPayload(body.asJsonObject)
        meta.textures.forEach { textureId ->
            models.getOrPut(textureId) { HashSet() }.add(modelId)
            if (meta.itemIds.isNotEmpty()) items.getOrPut(textureId) { HashSet() }.addAll(meta.itemIds)
            if (meta.npcIds.isNotEmpty()) npcs.getOrPut(textureId) { HashSet() }.addAll(meta.npcIds)
            if (meta.objectIds.isNotEmpty()) objects.getOrPut(textureId) { HashSet() }.addAll(meta.objectIds)
        }
    }
    fun intList(body: JsonObject, field: String): List<Int> =
        body[field]?.let { unwrap(it) }?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e -> unwrap(e).takeIf { it.isJsonPrimitive }?.asInt } ?: emptyList()
    fun retexture(typeKey: String, target: HashMap<Int, MutableSet<Int>>) {
        ctx.entities.forEachPayload(ctx.type(typeKey), rev) { defId, _, body ->
            val obj = body.asJsonObject
            (intList(obj, "originalTextureColours") + intList(obj, "modifiedTextureColours")).forEach { textureId ->
                if (textureId >= 0) target.getOrPut(textureId) { HashSet() }.add(defId)
            }
        }
    }
    retexture(ConfigDiffType.ITEMS.fileName, items)
    retexture(ConfigDiffType.NPCS.fileName, npcs)
    retexture(ConfigDiffType.OBJECTS.fileName, objects)
    ctx.entities.forEachPayload(ctx.type(ConfigDiffType.OVERLAY.fileName), rev) { overlayId, _, body ->
        val textureId = body.asJsonObject["texture"]?.let { unwrap(it) }?.takeIf { it.isJsonPrimitive }?.asInt ?: return@forEachPayload
        if (textureId >= 0) overlays.getOrPut(textureId) { HashSet() }.add(overlayId)
    }

    val ids = HashSet<Int>()
    ids.addAll(models.keys); ids.addAll(overlays.keys); ids.addAll(items.keys); ids.addAll(npcs.keys); ids.addAll(objects.keys)
    return ids.sorted().associateWith { id ->
        TextureUsage(
            models = models[id]?.sorted().orEmpty(),
            overlays = overlays[id]?.sorted().orEmpty(),
            items = items[id]?.sorted().orEmpty(),
            npcs = npcs[id]?.sorted().orEmpty(),
            objects = objects[id]?.sorted().orEmpty(),
        )
    }
}

fun Route.textureRoutes(ctx: ApiContext) {
    EndpointRegistry.registerEndpoint("GET", "/textures/usage", "Usage counts for every texture at a revision. Query: rev.", "Textures", null, "application/json", listOf("/textures/usage?rev=240"))
    EndpointRegistry.registerEndpoint("GET", "/textures/{id}/usage", "Everything using one texture at a revision. Query: rev.", "Textures", null, "application/json", listOf("/textures/9/usage?rev=240"))

    val textures = ctx.type(ConfigDiffType.TEXTURES.fileName)

    fun usage(rev: Int): Map<Int, TextureUsage> = textureUsage(ctx, rev)

    fun fileId(snapshot: JsonObject?): Int? = snapshot?.get("fileId")?.let { unwrap(it) }?.takeIf { it.isJsonPrimitive }?.asInt

    get("/textures/usage") {
        val rev = call.publishedRev(ctx)
        val usage = usage(rev)
        val spriteNames = ctx.gamevalNames("sprites", rev)
        val snapshots = LinkedHashMap<Int, JsonObject>()
        ctx.entities.forEachPayload(textures, rev) { id, _, body -> snapshots[id] = body.asJsonObject }
        val ids = (snapshots.keys + usage.keys).sorted()
        if (ids.isEmpty()) notFound("No texture data for rev $rev")
        call.respond(
            mapOf(
                "rev" to rev,
                "count" to ids.size,
                "textures" to ids.map { id ->
                    val u = usage[id] ?: TextureUsage()
                    mapOf(
                        "id" to id,
                        "name" to fileId(snapshots[id])?.let { spriteNames[it] },
                        "models" to u.models.size, "overlays" to u.overlays.size, "items" to u.items.size,
                        "npcs" to u.npcs.size, "objects" to u.objects.size, "total" to u.total,
                    )
                },
            ),
        )
    }

    get("/textures/{id}/usage") {
        val id = call.parameters["id"]?.toIntOrNull() ?: badRequest("Invalid texture id")
        val rev = call.publishedRev(ctx)
        val snapshot = ctx.entities.get(textures, rev, id)?.payload?.asJsonObject
        val u = usage(rev)[id]
        if (snapshot == null && u == null) notFound("Texture $id not found at rev $rev")
        val use = u ?: TextureUsage()
        fun int(field: String) = snapshot?.get(field)?.let { unwrap(it) }?.takeIf { it.isJsonPrimitive }?.asInt
        fun bool(field: String) = snapshot?.get(field)?.let { unwrap(it) }?.takeIf { it.isJsonPrimitive }?.asBoolean
        call.respond(
            mapOf(
                "id" to id, "rev" to rev,
                "fileId" to int("fileId"),
                "name" to fileId(snapshot)?.let { ctx.gamevalNames("sprites", rev)[it] },
                "averageRgb" to int("averageRgb"),
                "isTransparent" to bool("isTransparent"),
                "animationDirection" to int("animationDirection"),
                "animationSpeed" to int("animationSpeed"),
                "usage" to mapOf(
                    "models" to use.models, "overlays" to use.overlays,
                    "items" to namedIds(use.items, ctx.gamevalNames("items", rev)),
                    "npcs" to namedIds(use.npcs, ctx.gamevalNames("npcs", rev)),
                    "objects" to namedIds(use.objects, ctx.gamevalNames("objects", rev)),
                    "total" to use.total,
                ),
            ),
        )
    }
}
