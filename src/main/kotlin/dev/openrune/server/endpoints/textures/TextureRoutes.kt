package dev.openrune.server.endpoints.textures

import dev.openrune.ServerConfig
import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.cache.diff.DefinitionSnapshot
import dev.openrune.cache.diff.DiffBinaryCache
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

private const val MAX_TEXTURE_REV = 1000

private fun DefinitionSnapshot.intValue(field: String): Int? = this[field]?.value as? Int
private fun DefinitionSnapshot.boolValue(field: String): Boolean? = this[field]?.value as? Boolean

private fun textureConfig(config: ServerConfig, rev: Int): Map<Int, DefinitionSnapshot> =
    DiffBinaryCache.getTypedCombinedConfig(config, ConfigDiffType.TEXTURES.fileName, rev)

/** Texture name comes from the sprite gameval its `fileId` points at. */
private fun textureName(snapshot: DefinitionSnapshot?, spriteNames: Map<Int, String>): String? {
    val fileId = snapshot?.intValue("fileId") ?: return null
    return spriteNames[fileId]
}

private fun texturePayload(
    id: Int,
    rev: Int,
    snapshot: DefinitionSnapshot?,
    usage: TextureUsage,
    spriteNames: Map<Int, String>,
    itemNames: Map<Int, String>,
    npcNames: Map<Int, String>,
    objectNames: Map<Int, String>,
): Map<String, Any?> = mapOf(
    "id" to id,
    "rev" to rev,
    "fileId" to snapshot?.intValue("fileId"),
    "name" to textureName(snapshot, spriteNames),
    "averageRgb" to snapshot?.intValue("averageRgb"),
    "isTransparent" to snapshot?.boolValue("isTransparent"),
    "animationDirection" to snapshot?.intValue("animationDirection"),
    "animationSpeed" to snapshot?.intValue("animationSpeed"),
    "usage" to mapOf(
        "models" to usage.models,
        "overlays" to usage.overlays,
        "items" to namedIds(usage.items, itemNames),
        "npcs" to namedIds(usage.npcs, npcNames),
        "objects" to namedIds(usage.objects, objectNames),
        "total" to usage.total,
    ),
)

fun Route.registerTextureEndpoints(config: ServerConfig) {
    listOf(
        Triple(
            "/textures/usage",
            "Usage counts for every texture at a revision (models, overlays, items, npcs, objects). Query: rev.",
            listOf("/textures/usage?rev=240"),
        ),
        Triple(
            "/textures/{id}/usage",
            "Everything using one texture: models, overlays, and the items/npcs/objects that reach it " +
                "through a model or a retexture pair. Query: rev.",
            listOf("/textures/9/usage?rev=240"),
        ),
    ).forEach { (path, desc, examples) ->
        EndpointRegistry.registerEndpoint("GET", path, desc, "Textures", null, "application/json", examples)
    }

    get("/textures/usage") {
        val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision)
            .coerceIn(1, MAX_TEXTURE_REV)
        val usage = withContext(Dispatchers.Default) { TextureUsageIndex.forRevision(config, rev) }
        val snapshots = textureConfig(config, rev)
        val spriteNames = gamevalNames(config, rev, GamevalGroup.SPRITES)

        // Textures with no usage still belong in the listing, so start from the config set.
        val ids = (snapshots.keys + usage.keys).sorted()
        if (ids.isEmpty()) {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "No texture data for rev $rev"),
            )
            return@get
        }
        val entries = ids.map { id ->
            val u = usage[id] ?: TextureUsage()
            mapOf(
                "id" to id,
                "name" to textureName(snapshots[id], spriteNames),
                "models" to u.models.size,
                "overlays" to u.overlays.size,
                "items" to u.items.size,
                "npcs" to u.npcs.size,
                "objects" to u.objects.size,
                "total" to u.total,
            )
        }
        call.respond(mapOf("rev" to rev, "count" to entries.size, "textures" to entries))
    }

    get("/textures/{id}/usage") {
        val id = call.parameters["id"]?.toIntOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid texture id"))
            return@get
        }
        val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision)
            .coerceIn(1, MAX_TEXTURE_REV)
        val snapshots = textureConfig(config, rev)
        val usage = withContext(Dispatchers.Default) { TextureUsageIndex.forRevision(config, rev) }
        if (id !in snapshots && id !in usage) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Texture $id not found at rev $rev"))
            return@get
        }
        call.respond(
            texturePayload(
                id = id,
                rev = rev,
                snapshot = snapshots[id],
                usage = usage[id] ?: TextureUsage(),
                spriteNames = gamevalNames(config, rev, GamevalGroup.SPRITES),
                itemNames = gamevalNames(config, rev, GamevalGroup.ITEMS),
                npcNames = gamevalNames(config, rev, GamevalGroup.NPCS),
                objectNames = gamevalNames(config, rev, GamevalGroup.OBJECTS),
            ),
        )
    }
}
