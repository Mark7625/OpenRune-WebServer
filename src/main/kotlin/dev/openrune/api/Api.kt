package dev.openrune.api

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.openrune.SpriteCdnConfig
import dev.openrune.ingest.IngestionProgress
import dev.openrune.ingest.RawCacheStore
import dev.openrune.metrics.Metrics
import dev.openrune.model.EntityKind
import dev.openrune.query.DiffQueries
import dev.openrune.query.EntityQueries
import dev.openrune.query.RevisionCatalog
import dev.openrune.store.GameRegistry.RegisteredGame
import dev.openrune.store.GameRegistry.RegisteredType
import dev.openrune.store.RevisionRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import java.security.MessageDigest

const val MAX_PAGE_LIMIT = 500
const val MAX_UNPAGED_ROWS = 5_000

/** Everything a route needs for one game stream. */
class ApiContext(
    val game: RegisteredGame,
    val catalog: RevisionCatalog,
    val entities: EntityQueries,
    val diffs: DiffQueries,
    val revisions: RevisionRepository,
    val metrics: Metrics,
    val cdn: SpriteCdnConfig,
    val rawCaches: RawCacheStore,
    val navDisplayNameOverrides: Map<String, String>,
    val port: Int,
    val sourceCacheId: Int,
) {
    /** Revisions whose derived views are kept warm (base plus the newest ones); informational. */
    @Volatile
    var hotRevisions: List<Int> = emptyList()

    /** Byte-bounded cache for derived results of published revisions; keys embed the publish stamp. */
    val cache: Cache<String, Any> = Caffeine.newBuilder()
        .maximumWeight(128L * 1024 * 1024)
        .weigher<String, Any> { key, value -> key.length + weightOf(value) }
        .recordStats()
        .build()

    @Volatile
    var ingestionProgress: IngestionProgress? = null

    fun configType(keyOrSection: String): RegisteredType? {
        val k = keyOrSection.lowercase()
        return game.types.firstOrNull { it.def.kind == EntityKind.CONFIG && (it.def.key == k || it.def.sectionId == k) }
    }

    fun type(key: String): RegisteredType = game.type(key)

    fun gamevalType(group: String): RegisteredType? = game.typeOrNull("gameval.$group")

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> cached(key: String, compute: () -> T): T {
        val fullKey = "${catalog.stamp()}|$key"
        cache.getIfPresent(fullKey)?.let { metrics.increment("cache.hit"); return it as T }
        metrics.increment("cache.miss")
        val value = compute()
        cache.put(fullKey, value)
        return value
    }

    private fun weightOf(value: Any): Int = when (value) {
        is String -> value.length * 2
        is ByteArray -> value.size
        is Collection<*> -> 64 + value.size * 16
        is Map<*, *> -> 64 + value.size * 48
        is JsonElement -> value.toString().length * 2
        else -> 256
    }
}

class ApiError(val status: HttpStatusCode, val body: Map<String, Any?>) : RuntimeException(body["error"]?.toString())

fun badRequest(message: String): Nothing = throw ApiError(HttpStatusCode.BadRequest, mapOf("error" to message))

fun notFound(message: String, extra: Map<String, Any?> = emptyMap()): Nothing =
    throw ApiError(HttpStatusCode.NotFound, mapOf("error" to message) + extra)

fun missingRevision(rev: Int): Nothing =
    throw ApiError(HttpStatusCode.NotFound, mapOf("error" to "No diff binary file for rev $rev", "revision" to rev, "status" to "missing"))

fun ApplicationCall.intParam(name: String): Int? = request.queryParameters[name]?.trim()?.toIntOrNull()

fun ApplicationCall.boolParam(name: String): Boolean? = request.queryParameters[name]?.trim()?.toBooleanStrictOrNull()

/** Resolve a `rev` style parameter (`latest`, blank or a number) to a published revision. */
fun ApplicationCall.publishedRev(ctx: ApiContext, name: String = "rev", default: Int? = null): Int {
    val raw = request.queryParameters[name]
    val rev = if (raw.isNullOrBlank() && default != null) default else ctx.catalog.resolve(raw) ?: badRequest("Invalid $name")
    if (!ctx.catalog.isPublished(rev)) missingRevision(rev)
    return rev
}

/** `base` and `rev` for diff routes, ordered so that base <= rev; both must be published. */
fun ApplicationCall.revisionPair(ctx: ApiContext): Pair<Int, Int> {
    val base = publishedRev(ctx, "base", default = ctx.catalog.published().firstOrNull() ?: 1)
    val rev = publishedRev(ctx, "rev")
    return if (base <= rev) base to rev else rev to base
}

fun ApplicationCall.paging(defaultLimit: Int = 50): Pair<Int, Int> {
    val offset = (intParam("offset") ?: 0).coerceAtLeast(0)
    val limit = (intParam("limit") ?: defaultLimit).coerceIn(1, MAX_PAGE_LIMIT)
    return offset to limit
}

fun ApplicationCall.noStore() {
    response.header(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate")
    response.header(HttpHeaders.Pragma, "no-cache")
    response.header(HttpHeaders.Expires, "0")
}

private fun md5(s: String): String =
    MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** ETag for a response that depends only on published data and the given parameters. */
fun etagFor(ctx: ApiContext, vararg parts: Any?): String = md5((listOf("v3", ctx.catalog.stamp()) + parts).joinToString("|"))

/**
 * Conditional response: sets the ETag, answers 304 when the client already has it, otherwise
 * produces the body. Published revisions never change, so no body is computed on a match.
 */
suspend fun ApplicationCall.respondConditional(etag: String, produce: suspend () -> Unit) {
    response.header(HttpHeaders.ETag, "\"$etag\"")
    val inm = request.header(HttpHeaders.IfNoneMatch)?.trim()?.removePrefix("W/")?.removeSurrounding("\"")
    if (inm != null && inm == etag) {
        response.header("X-OpenRune-Cache-Debug", "304;NO_BODY")
        respond(HttpStatusCode.NotModified)
        return
    }
    response.header("X-OpenRune-Cache-Debug", "200;FULL_BODY")
    produce()
}

suspend fun ApplicationCall.respondJson(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(text, ContentType.Application.Json, status)

/** Snapshot payload plus a `gameval` member when the entity has one and the payload lacks it. */
fun withGameval(payload: JsonElement, gameval: String?): JsonElement {
    if (gameval.isNullOrBlank() || !payload.isJsonObject) return payload
    val obj = payload.asJsonObject
    if (obj.has("gameval")) return payload
    val copy = JsonObject()
    obj.entrySet().forEach { (k, v) -> copy.add(k, v) }
    copy.addProperty("gameval", gameval)
    return copy
}
