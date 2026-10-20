package dev.openrune.ingest

import dev.openrune.cache.CacheDownloader
import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.tools.DownloadListener
import dev.openrune.cache.tools.OpenRS2
import dev.openrune.model.Game
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/** Redirect hops followed before a pasted link is called broken. */
private const val MAX_REDIRECTS = 5

private const val USER_AGENT = "OpenRune-WebServer"

/** Written next to an extracted cache to say which source produced it. */
private const val CACHE_ID_MARKER = ".openrs2_cache_id"
private const val SOURCE_URL_MARKER = ".source_url"

/**
 * Raw caches on disk, one directory per revision: `cache/{game}/{env}/{rev}/data/cache`. The
 * directory is the reprocessing source for a revision; OpenRS2 remains the archive of record, so
 * old raw caches can be deleted and re-downloaded ([prune]).
 */
class RawCacheStore(private val game: Game) {

    fun directory(rev: Int): File = CachePathHelper.getCacheDirectory(game.gameType, game.environment, rev)

    fun cachePath(rev: Int): File = File(directory(rev), "data/cache")

    private fun markerFile(rev: Int): File = File(directory(rev), CACHE_ID_MARKER)

    /** Which pasted link produced the cache on disk; the [markerFile] equivalent for [ensureFrom]. */
    private fun urlMarkerFile(rev: Int): File = File(directory(rev), SOURCE_URL_MARKER)

    fun presentCacheId(rev: Int): Int? =
        if (cachePath(rev).isDirectory) markerFile(rev).takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() else null

    /** Download and extract [cacheId] for [rev] unless that exact cache is already on disk. */
    fun ensure(rev: Int, cacheId: Int, progress: (Int, String) -> Unit): File {
        val dir = directory(rev)
        if (cachePath(rev).isDirectory && presentCacheId(rev) == cacheId) {
            progress(100, "Cache for rev $rev already on disk")
            return cachePath(rev)
        }
        clean(dir)
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

        return extract(rev, dir, progress) { markerFile(rev).writeText(cacheId.toString()) }
    }

    /**
     * Same as [ensure] but for a cache link an operator pasted ([ManualCacheLink]) rather than an
     * OpenRS2 catalogue id. The URL is remembered alongside the extracted cache, so re-ingesting
     * the same link reuses what is on disk and changing the link re-downloads.
     */
    fun ensureFrom(rev: Int, url: String, progress: (Int, String) -> Unit): File {
        val dir = directory(rev)
        val present = urlMarkerFile(rev).takeIf { it.isFile }?.readText()?.trim()
        if (cachePath(rev).isDirectory && present == url) {
            progress(100, "Cache for rev $rev already on disk")
            return cachePath(rev)
        }
        clean(dir)
        download(rev, url, File(dir, "disk.zip"), progress)
        return extract(rev, dir, progress) { urlMarkerFile(rev).writeText(url) }
    }

    /** Clear anything an earlier attempt left behind, including which source it came from. */
    private fun clean(dir: File) {
        if (dir.exists()) {
            File(dir, "data").deleteRecursively()
            File(dir, "disk.zip").delete()
            File(dir, CACHE_ID_MARKER).delete()
            File(dir, SOURCE_URL_MARKER).delete()
        }
        dir.mkdirs()
    }

    /** Unpack `disk.zip`, then record where it came from so a repeat call can skip the download. */
    private fun extract(rev: Int, dir: File, progress: (Int, String) -> Unit, mark: () -> Unit): File {
        progress(50, "Extracting rev $rev")
        runBlocking {
            CacheDownloader().unzipCache(dir) { _, p, _ -> p?.let { progress(50 + ((it - 50) / 49.0 * 50).toInt().coerceIn(0, 50), "Extracting rev $rev") } }
        }
        mark()
        val path = cachePath(rev)
        if (!path.isDirectory) throw IngestionException("Extracted cache for rev $rev has no data/cache directory")
        progress(100, "Cache for rev $rev ready")
        return path
    }

    /** Streams [url] to [target], reporting into the same 0-50 band the OpenRS2 download uses. */
    private fun download(rev: Int, url: String, target: File, progress: (Int, String) -> Unit) {
        logger.info { "rev $rev: downloading cache from $url" }
        val connection = open(rev, url)
        try {
            val total = connection.contentLengthLong
            var read = 0L
            var lastReport = -1L
            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        // Content-Length is absent on a chunked response, and then a percentage
                        // would be a lie; report megabytes instead so a stalled download still shows.
                        if (total > 0) {
                            val pct = read * 100 / total
                            if (pct >= lastReport + 5) {
                                lastReport = pct
                                progress((pct / 2).toInt(), "Downloading rev $rev: $pct% (${read / 1_048_576}MB / ${total / 1_048_576}MB)")
                            }
                        } else {
                            val chunk = read / 16_777_216
                            if (chunk > lastReport) {
                                lastReport = chunk
                                progress(25, "Downloading rev $rev: ${read / 1_048_576}MB")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            throw IngestionException("Download of rev $rev from $url failed: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
        if (target.length() == 0L) throw IngestionException("Download of rev $rev from $url returned an empty file")
    }

    /**
     * Opens [url] for reading, following redirects by hand because `HttpURLConnection` drops them
     * when the scheme changes and a `http://` link redirecting to `https://` is the common case.
     */
    private fun open(rev: Int, url: String): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = connection.responseCode
            if (code !in 300..399) {
                if (code !in 200..299) {
                    connection.disconnect()
                    throw IngestionException("Download of rev $rev from $current failed: HTTP $code")
                }
                return connection
            }
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) throw IngestionException("Redirect from $current has no Location header")
            current = URL(current, location)
        }
        throw IngestionException("Download of rev $rev from $url followed more than $MAX_REDIRECTS redirects")
    }

    /** True when [rev] is already extracted, whatever source it came from. */
    fun has(rev: Int): Boolean = cachePath(rev).isDirectory

    /** Delete one revision's raw cache; OpenRS2 still has it if it is wanted again. */
    fun delete(rev: Int) {
        directory(rev).deleteRecursively()
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
