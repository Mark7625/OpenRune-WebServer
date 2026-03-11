package dev.openrune

import dev.openrune.cache.CacheDownloader
import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.DownloadListener
import dev.openrune.cache.tools.GameType
import dev.openrune.cache.tools.OpenRS2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import mu.KotlinLogging
import java.io.File
import java.io.IOException

private val logger = KotlinLogging.logger {}

private const val MAX_DOWNLOAD_RETRIES = 3
private const val RETRY_DELAY_MS = 15_000L
private const val DELAY_BETWEEN_DOWNLOADS_MS = 60_000L

/**
 * Downloads and unzips all OSRS caches from rev 1 to latest (e.g. 236), one by one.
 * Skips revs that already have cache/data/cache on disk.
 * Run overnight so caches are ready for dumping in the morning.
 *
 * Usage: run this class as main (e.g. gradle runDownloadAllCaches or IDE run config).
 */
fun main() = runBlocking {
    val gameType = GameType.OLDSCHOOL
    val environment = CacheEnvironment.LIVE
    val downloader = CacheDownloader()

    OpenRS2.loadCaches()
    val game = gameType.name
    val env = environment.name
    val available = OpenRS2.allCaches
        .filter { (it.game ?: "").equals(game, true) && (it.environment ?: "").equals(env, true) }
        .flatMap { c -> (c.builds ?: emptyList()).map { b -> b.major } }
        .distinct()
        .filter { it >= 1 }
        .sorted()

    if (available.isEmpty()) {
        logger.error { "No OSRS revisions found in OpenRS2 for $game / $env" }
        return@runBlocking
    }

    val total = available.size
    val maxRev = available.maxOrNull() ?: return@runBlocking
    logger.info { "Cache download: $total caches to process (rev 1..$maxRev). Existing caches skipped." }

    var skipped = 0
    var downloaded = 0
    var failed = 0
    available.forEachIndexed { index, rev ->
        withContext(Dispatchers.IO) {
            val current = index + 1
            val cacheDir = CachePathHelper.getCacheDirectory(gameType, environment, rev)
            val cachePath = File(cacheDir, "data/cache")
            if (cachePath.exists()) {
                skipped++
                logger.info { "[$current/$total] Rev $rev: already present, skip" }
                return@withContext
            }
            cacheDir.mkdirs()
            var success = false
            var lastError: Exception? = null
            repeat(MAX_DOWNLOAD_RETRIES) { attempt ->
                if (success) return@repeat
                try {
                    if (attempt > 0) {
                        logger.warn { "[$current/$total] Rev $rev: retry $attempt/$MAX_DOWNLOAD_RETRIES after ${RETRY_DELAY_MS / 1000}s (last: ${lastError?.message})" }
                        delay(RETRY_DELAY_MS)
                    }
                    logger.info { "[$current/$total] Rev $rev: downloading..." }
                    suspendCancellableCoroutine<Unit> { cont ->
                        val listener = object : DownloadListener {
                            override fun onProgress(progress: Int, max: Long, currentBytes: Long) {
                                if (max <= 0) return
                                val pct = (currentBytes * 100 / max).toInt().coerceIn(0, 100)
                                if (pct % 10 == 0 || pct == 100) {
                                    val curMb = currentBytes / (1024 * 1024)
                                    val maxMb = max / (1024 * 1024)
                                    logger.info { "[$current/$total] Rev $rev: download $pct% (${curMb}MB / ${maxMb}MB)" }
                                }
                            }
                            override fun onError(exception: Exception) {
                                cont.resumeWithException(exception)
                            }
                            override fun onFinished() {
                                cont.resume(Unit)
                            }
                        }
                        try {
                            OpenRS2.downloadCacheByRevision(rev, cacheDir, gameType, environment, -1, listener)
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    }
                    logger.info { "[$current/$total] Rev $rev: download done, extracting..." }
                    downloader.unzipCache(cacheDir) { _, progress, _ ->
                        progress?.let { p ->
                            val pct = ((p - 50) / 49 * 100).toInt().coerceIn(0, 100)
                            if (pct % 25 == 0 || pct == 100) {
                                logger.info { "[$current/$total] Rev $rev: extract $pct%" }
                            }
                        }
                    }
                    logger.info { "[$current/$total] Rev $rev: done" }
                    downloaded++
                    success = true
                    if (current < total) {
                        logger.info { "Waiting ${DELAY_BETWEEN_DOWNLOADS_MS / 1000}s before next..." }
                        delay(DELAY_BETWEEN_DOWNLOADS_MS)
                    }
                } catch (e: Exception) {
                    lastError = e
                    val isRetryable = e is IOException || e.cause is IOException
                    if (!isRetryable || attempt == MAX_DOWNLOAD_RETRIES - 1) {
                        logger.error(e) { "[$current/$total] Rev $rev: failed (${e.message}). Skipping." }
                        failed++
                        if (current < total) {
                            logger.info { "Waiting ${DELAY_BETWEEN_DOWNLOADS_MS / 1000}s before next..." }
                            delay(DELAY_BETWEEN_DOWNLOADS_MS)
                        }
                        return@repeat
                    }
                    logger.warn { "[$current/$total] Rev $rev: attempt ${attempt + 1} failed (${e.message}), will retry" }
                }
            }
        }
    }

    logger.info { "Cache download complete: $total total, $downloaded downloaded+extracted, $skipped already present, $failed failed." }
}
