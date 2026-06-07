package dev.openrune.server.endpoints.maps

import dev.openrune.ServerConfig
import dev.openrune.cache.diff.DiffBinaryCache
import dev.openrune.cache.diff.CacheBinaryFormat
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Map JSON is read from **one** on-disk binary per request (`{rev}.bin` via [DiffBinaryCache.getDecodedRev]).
 * Query `rev` selects that file only; routes never merge or chain multiple revision binaries.
 */
internal const val MAX_MAP_DIFF_REV = 1000

internal fun diffBinaryRevisionsSet(config: ServerConfig): Set<Int> =
    DiffBinaryCache.listRevisionsWithBinary(config).toSet()

internal fun mapRevisionFromQuery(call: ApplicationCall, config: ServerConfig): Int? {
    val revParam = call.request.queryParameters["rev"]
    if (revParam.isNullOrBlank() || revParam.equals("latest", ignoreCase = true)) {
        return config.revision.coerceIn(1, MAX_MAP_DIFF_REV)
    }
    return revParam.toIntOrNull()?.coerceIn(1, MAX_MAP_DIFF_REV)
}

/** Single [rev] decode — one `.bin` file, not a multi-revision merge. */
internal fun decodedWithMapPayload(config: ServerConfig, rev: Int): Pair<Int, CacheBinaryFormat.DecodedRev>? {
    val decoded = DiffBinaryCache.getDecodedRev(config, rev) ?: return null
    if (decoded.mapObjects.isEmpty() && decoded.mapRegions.isEmpty()) return null
    return rev to decoded
}

internal suspend fun ApplicationCall.respondMissingDiffBinary(rev: Int) {
    respond(
        HttpStatusCode.NotFound,
        mapOf(
            "error" to "No diff binary file for rev $rev",
            "revision" to rev,
            "status" to "missing"
        )
    )
}

/**
 * Wait until the single binary for [rev] is decoded (or respond 202 while that one file is decoding).
 * Only ever touches revision [rev], never multiple `.bin` files.
 */
internal suspend fun ApplicationCall.ensureDecodedRevisionReadyForMaps(
    config: ServerConfig,
    rev: Int
): Boolean {
    if (rev <= 0) return true
    val ready = DiffBinaryCache.getDecodedRevIfReady(config, rev)
    val status = if (ready != null) {
        DiffBinaryCache.DecodeStatus(revision = rev, status = "ready", progress = 100, message = "Ready")
    } else {
        DiffBinaryCache.getDecodeStatus(config, rev)
    }
    if (status.status == "ready" || status.status == "missing") return true
    val progress = status.progress.coerceIn(0, 99)
    val details = "${status.message} (${status.progress}%)"
    logger.info {
        "map.http 202 decoding: rev=$rev progress=$progress% message=${status.message}"
    }
    respond(
        HttpStatusCode.Accepted,
        mapOf(
            "status" to "decoding",
            "progress" to progress,
            "message" to "Decoding binary for rev $rev. Please wait...",
            "details" to details,
            "revision" to rev,
            "decodeStatus" to mapOf(
                "revision" to status.revision,
                "status" to status.status,
                "progress" to status.progress,
                "message" to status.message,
                "error" to status.error
            )
        )
    )
    return false
}
