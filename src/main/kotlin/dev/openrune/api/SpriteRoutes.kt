package dev.openrune.api

import com.google.gson.Gson
import dev.openrune.cache.diff.SpriteCdn
import dev.openrune.model.Hashing
import dev.openrune.model.OsrsEntityTypes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val gson = Gson()

/** Sprite images, metadata, the combined id index and sprite deltas. */
fun Route.spriteRoutes(ctx: ApiContext) {
    listOf(
        Triple("/sprites", "Sprite PNG. Query: id, rev, source, width, height, keepAspectRatio, upscale (false makes width/height a maximum).", listOf("/sprites?id=447&rev=236")),
        Triple("/sprites/raw", "Sprite frame metadata. Query: id, rev, source.", listOf("/sprites/raw?id=447&rev=236")),
        Triple("/items/{id}/image", "Rendered inventory image, resolved to the revision the render last changed in. Rendered at 512; `size` scales down (never up). Query: rev, size, source.", listOf("/items/4151/image?rev=241", "/items/4151/image?rev=241&size=32")),
        Triple("/objects/{id}/image", "Rendered object image, resolved to the revision the render last changed in. Rendered at 512; `size` scales down (never up). Query: rev, size, source.", listOf("/objects/1276/image?rev=241", "/objects/1276/image?rev=241&size=32")),
        Triple("/diff/sprite/{id}", "Sprite PNG. Query: base, rev, source, width, height.", listOf("/diff/sprite/0?rev=100")),
        Triple("/diff/sprite/{id}/raw", "Sprite frame metadata. Query: base, rev, source.", listOf("/diff/sprite/0/raw?rev=100")),
        Triple("/diff/combined/sprites", "Sprite ids at rev with the revision each last changed in. Query: base, rev.", listOf("/diff/combined/sprites?base=1&rev=236")),
        Triple("/diff/delta/sprites", "Sprite ids added / changed / removed between base and rev.", listOf("/diff/delta/sprites?base=240&rev=241")),
        Triple("/diff/delta/sprites/summary", "Sprite delta counts between base and rev.", listOf("/diff/delta/sprites/summary?base=240&rev=241")),
        Triple("/textures", "Texture image (the sprite its fileId points at). Query: id, rev, width, height.", listOf("/textures?id=9&rev=240")),
    ).forEach { (path, desc, examples) -> EndpointRegistry.registerEndpoint("GET", path, desc, "Diff", null, if (path.endsWith("raw") || path.contains("delta") || path.contains("combined")) "application/json" else "image/png", examples) }

    val sprites = ctx.type(OsrsEntityTypes.SPRITES)

    suspend fun ApplicationCall.respondSprite(id: Int) {
        val rev = publishedRev(ctx)
        // Assets are only uploaded to the CDN at the revision their content changed in, so an
        // unchanged sprite lives under an older revision's prefix. Resolve that here unless the
        // caller already knows it; `rev` itself is the fallback when the id is unknown.
        val source = intParam("source")?.takeIf { ctx.catalog.isPublished(it) }
            ?: ctx.entities.sourceRevision(sprites, rev, id)
            ?: rev
        val width = intParam("width")?.takeIf { it > 0 }
        val height = intParam("height")?.takeIf { it > 0 }
        val keepAspect = boolParam("keepAspectRatio") ?: boolParam("keepAspect") ?: true
        // `upscale=false` makes width/height a maximum, so thumbnails of small sprites stay crisp.
        val allowUpscale = boolParam("upscale") ?: true
        val resize = width != null || height != null

        // Full-size sprites are an asset the CDN exists to serve, and the client now asks us rather
        // than guessing a key. Hand the browser straight to the resolved object so the bytes do not
        // flow through here. See `SpriteCdnConfig.redirectSprites` for when to turn this off.
        if (!resize && ctx.cdn.redirectSprites && ctx.cdn.canServe) {
            SpriteCdn.publicSpriteUrl(ctx.cdn, ctx.game.game.gameType, source, id)?.let { url ->
                response.header(HttpHeaders.CacheControl, "public, max-age=86400, stale-while-revalidate=604800")
                respondRedirect(url, permanent = false)
                return
            }
        }

        val bytes = ctx.entities.blobForEntity(sprites, source, id)
            ?: run {
                if (!resize && ctx.cdn.canServe) {
                    SpriteCdn.publicSpriteUrl(ctx.cdn, ctx.game.game.gameType, source, id)?.let { url ->
                        response.header(HttpHeaders.CacheControl, "public, max-age=86400, stale-while-revalidate=604800")
                        respondRedirect(url, permanent = false)
                        return
                    }
                }
                if (ctx.cdn.canServe) SpriteCdn.fetchSpritePng(ctx.cdn, ctx.game.game.gameType, source, id) else null
            }
            ?: notFound("Sprite $id not found")
        val out = if (resize) SpriteCdn.resizePng(bytes, width, height, keepAspect, allowUpscale) else bytes
        val etag = Hashing.hex(Hashing.hash16(out))
        response.header(HttpHeaders.CacheControl, "public, max-age=86400, stale-while-revalidate=604800")
        respondConditional(etag) { respondBytes(out, ContentType.Image.PNG) }
    }

    suspend fun ApplicationCall.respondSpriteRaw(id: Int) {
        val rev = publishedRev(ctx)
        val source = intParam("source")?.takeIf { ctx.catalog.isPublished(it) } ?: rev
        val row = ctx.entities.get(sprites, source, id) ?: notFound("Sprite $id not found for source rev $source")
        noStore()
        respond(
            mapOf(
                "id" to id,
                "base" to (intParam("base") ?: ctx.catalog.published().firstOrNull() ?: 1),
                "rev" to rev,
                "source" to source,
                "sprites" to (row.payload!!.asJsonObject["frames"]?.let { gson.fromJson(it, List::class.java) } ?: emptyList<Any>()),
                "pixels" to row.payload.asJsonObject["pixels"]?.asString,
            ),
        )
    }

    /**
     * Rendered item / object images. Same resolution rule as sprites: the image lives under the
     * revision its render last changed in, so the caller only has to say which revision it is
     * looking at. Falls back to the bytes in PostgreSQL when the CDN is not serving them.
     */
    suspend fun ApplicationCall.respondRenderedImage(typeKey: String, folder: String, id: Int) {
        val type = ctx.game.typeOrNull(typeKey) ?: notFound("No $folder images for this game")
        val rev = publishedRev(ctx)
        val source = intParam("source")?.takeIf { ctx.catalog.isPublished(it) }
            ?: ctx.entities.sourceRevision(type, rev, id)
            ?: notFound("No $folder image for id $id at rev $rev")

        // Stored at RENDER_SIZE; any smaller size is produced here. `size` is a maximum, so asking
        // for more than was rendered gives the original rather than a blurry enlargement.
        val size = intParam("size")?.takeIf { it > 0 }

        if (size == null && ctx.cdn.redirectSprites && ctx.cdn.canServe) {
            SpriteCdn.publicImageUrl(ctx.cdn, ctx.game.game.gameType, source, folder, id)?.let { url ->
                response.header(HttpHeaders.CacheControl, "public, max-age=86400, stale-while-revalidate=604800")
                respondRedirect(url, permanent = false)
                return
            }
        }

        val stored = ctx.entities.blobForEntity(type, source, id) ?: notFound("No $folder image for id $id")
        val bytes = if (size == null) stored
        else SpriteCdn.resizePng(stored, size, size, keepAspectRatio = true, allowUpscale = false)
        response.header(HttpHeaders.CacheControl, "public, max-age=86400, stale-while-revalidate=604800")
        respondConditional(Hashing.hex(Hashing.hash16(bytes))) { respondBytes(bytes, ContentType.Image.PNG) }
    }

    get("/items/{id}/image") {
        val id = call.parameters["id"]?.toIntOrNull() ?: badRequest("Invalid item id")
        call.respondRenderedImage(OsrsEntityTypes.ITEM_SPRITES, "items", id)
    }

    get("/objects/{id}/image") {
        val id = call.parameters["id"]?.toIntOrNull() ?: badRequest("Invalid object id")
        call.respondRenderedImage(OsrsEntityTypes.OBJECT_SPRITES, "objects", id)
    }

    get("/sprites") { call.respondSprite(call.intParam("id") ?: badRequest("Invalid sprite id")) }
    get("/diff/sprite/{id}") { call.respondSprite(call.parameters["id"]?.toIntOrNull() ?: badRequest("Invalid sprite id")) }
    get("/sprites/raw") { call.respondSpriteRaw(call.intParam("id") ?: badRequest("Invalid sprite id")) }
    get("/diff/sprite/{id}/raw") { call.respondSpriteRaw(call.parameters["id"]?.toIntOrNull() ?: badRequest("Invalid sprite id")) }

    get("/textures") {
        val id = call.intParam("id") ?: badRequest("Invalid texture id")
        val rev = call.publishedRev(ctx)
        val texture = ctx.entities.get(ctx.type(dev.openrune.cache.diff.ConfigDiffType.TEXTURES.fileName), rev, id)
            ?: notFound("Texture $id not found at rev $rev")
        val fileId = texture.payload!!.asJsonObject["fileId"]?.asInt ?: notFound("Texture $id has no sprite")
        val width = call.intParam("width")?.takeIf { it > 0 }
        val height = call.intParam("height")?.takeIf { it > 0 }
        val bytes = ctx.entities.blobForEntity(sprites, rev, fileId)
            ?: (if (ctx.cdn.canServe) SpriteCdn.fetchSpritePng(ctx.cdn, ctx.game.game.gameType, rev, fileId) else null)
            ?: notFound("Sprite $fileId for texture $id not found")
        val out = if (width != null || height != null)
            SpriteCdn.resizePng(bytes, width, height, call.boolParam("keepAspectRatio") ?: true, call.boolParam("upscale") ?: true)
        else bytes
        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400, stale-while-revalidate=604800")
        call.respondConditional(Hashing.hex(Hashing.hash16(out))) { call.respondBytes(out, ContentType.Image.PNG) }
    }

    get("/diff/combined/sprites") {
        val (base, rev) = call.revisionPair(ctx)
        call.noStore()
        call.respondConditional(etagFor(ctx, "combined-sprites", base, rev)) {
            call.respondJson(Views.combinedSpritesJson(ctx, base, rev))
        }
    }

    get("/diff/delta/sprites") {
        val (base, rev) = call.revisionPair(ctx)
        call.noStore()
        call.respondConditional(etagFor(ctx, "delta-sprites", base, rev)) {
            call.respondJson(Views.deltaSpritesJson(ctx, base, rev))
        }
    }

    get("/diff/delta/sprites/summary") {
        val (base, rev) = call.revisionPair(ctx)
        call.respondConditional(etagFor(ctx, "delta-sprites-summary", base, rev)) {
            val counts = Views.deltaSpritesCounts(ctx, base, rev)
            call.respond(mapOf("base" to base, "rev" to rev, "added" to counts.added, "changed" to counts.changed, "removed" to counts.removed))
        }
    }
}
