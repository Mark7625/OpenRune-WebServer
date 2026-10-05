package dev.openrune.ingest

import dev.openrune.cache.CacheDownloader
import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.tools.DownloadListener
import dev.openrune.cache.tools.OpenRS2
import dev.openrune.model.Game
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Raw caches on disk, one directory per revision: `cache/{game}/{env}/{rev}/data/cache`. The
 * directory is the reprocessing source for a revision; OpenRS2 remains the archive of record, so
 * old raw caches can be deleted and re-downloaded ([prune]).
 */
class RawCacheStore(private val game: Game) {

    fun directory(rev: Int): File = CachePathHelper.getCacheDirectory(game.gameType, game.environment, rev)

    fun cachePath(rev: Int): File = File(directory(rev), "data/cache")

    private fun markerFile(rev: Int): File = File(directory(rev), ".openrs2_cache_id")

    fun presentCacheId(rev: Int): Int? =
        if (cachePath(rev).isDirectory) markerFile(rev).takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() else null

    /** Download and extract [cacheId] for [rev] unless that exact cache is already on disk. */
    fun ensure(rev: Int, cacheId: Int, progress: (Int, String) -> Unit): File {
        val dir = directory(rev)
        val path = cachePath(rev)
        if (path.isDirectory && presentCacheId(rev) == cacheId) {
            progress(100, "Cache for rev $rev already on disk")
            return path
        }
        if (dir.exists()) {
            File(dir, "data").deleteRecursively()
            File(dir, "disk.zip").delete()
        }
        dir.mkdirs()
        val error = AtomicReference<Exception>()
        val done = CountDownLatch(1)
        var lastPct = -1
        OpenRS2.downloadByInternalID(cacheId, dir, object : DownloadListener {
            override fun onProgress(progress: Int, max: Long, current: Long) {
                if (max <= 0) return
                val pct = (current * 100 / max).toInt()
                if (pct >= lastPct + 5 || pct == 100) {
                    lastPct = pct
                    progress(pct / 2, "Downloading rev $rev: $pct% (${current / 1_048_576}MB / ${max / 1_048_576}MB)")
                }
            }

            override fun onError(exception: Exception) {
                error.set(exception)
                done.countDown()
            }

            override fun onFinished() = done.countDown()
        }, "disk.zip")
        done.await()
        error.get()?.let { throw IngestionException("Download of rev $rev (OpenRS2 id $cacheId) failed: ${it.message}", it) }

        progress(50, "Extracting rev $rev")
        runBlocking {
            CacheDownloader().unzipCache(dir) { _, p, _ -> p?.let { progress(50 + ((it - 50) / 49.0 * 50).toInt().coerceIn(0, 50), "Extracting rev $rev") } }
        }
        markerFile(rev).writeText(cacheId.toString())
        if (!path.isDirectory) throw IngestionException("Extracted cache for rev $rev has no data/cache directory")
        progress(100, "Cache for rev $rev ready")
        return path
    }

    /** Delete raw caches except the newest [keep] revisions. */
    fun prune(keep: Int) {
        val parent = directory(1).parentFile ?: return
        val revs = parent.listFiles()?.mapNotNull { it.name.toIntOrNull() }?.sortedDescending() ?: return
        revs.drop(keep).forEach { rev ->
            logger.info { "Pruning raw cache for rev $rev" }
            directory(rev).deleteRecursively()
        }
    }
}
