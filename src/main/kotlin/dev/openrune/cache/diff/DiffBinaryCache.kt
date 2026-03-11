package dev.openrune.cache.diff

import dev.openrune.ServerConfig
import dev.openrune.cache.CachePathHelper
import mu.KotlinLogging
import kotlin.math.abs
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadFactory

private val logger = KotlinLogging.logger {}

/**
 * Lazy-loading in-memory cache for decoded diff binary blobs.
 * One decoded rev per (gameType, environment, rev). No eviction (use extra memory for speed).
 */
object DiffBinaryCache {

    private data class DecodedCacheEntry(
        val decoded: CacheBinaryFormat.DecodedRev,
        val lastModified: Long,
        val length: Long
    )

    private val cache = ConcurrentHashMap<String, DecodedCacheEntry>()
    data class DecodeStatus(
        val revision: Int,
        val status: String,
        val progress: Int,
        val message: String,
        val error: String? = null
    )
    private data class DecodeStatusEntry(
        val status: DecodeStatus,
        val lastModified: Long,
        val length: Long
    )
    private val decodeStatuses = ConcurrentHashMap<String, DecodeStatusEntry>()
    private val decodeFutures = ConcurrentHashMap<String, CompletableFuture<CacheBinaryFormat.DecodedRev?>>()
    private val decodeExecutor = Executors.newFixedThreadPool(2, object : ThreadFactory {
        private val idx = java.util.concurrent.atomic.AtomicInteger(1)
        override fun newThread(r: Runnable): Thread {
            return Thread(r, "diff-binary-decode-${idx.getAndIncrement()}").apply {
                isDaemon = true
            }
        }
    })
    private val decodeStatusListeners = CopyOnWriteArrayList<(DecodeStatus) -> Unit>()
    private val lastDecodeBroadcastMs = ConcurrentHashMap<String, Long>()
    private val revisionsListCache = ConcurrentHashMap<String, RevisionListCacheEntry>()
    private val cacheHitCounters = ConcurrentHashMap<String, Long>()
    private val perfLogsEnabled: Boolean = (
        System.getenv("OPENRUNE_PERF_LOGS")?.equals("true", ignoreCase = true) == true ||
            System.getProperty("openrune.perf.logs")?.equals("true", ignoreCase = true) == true
        )

    private data class RevisionListCacheEntry(
        val dirLastModified: Long,
        val fileCount: Int,
        val revisions: List<Int>
    )
    private fun shouldLogCacheHit(k: String): Boolean {
        val count = cacheHitCounters.merge(k, 1L) { old, one -> old + one } ?: 1L
        return count == 1L || count % 250L == 0L
    }


    private fun key(config: ServerConfig, rev: Int): String {
        return "${config.gameType.name}:${config.environment.name}:$rev"
    }

    private fun revisionsListKey(config: ServerConfig): String {
        return "${config.gameType.name}:${config.environment.name}"
    }

    private fun updateDecodeStatus(
        key: String,
        revision: Int,
        lastModified: Long,
        length: Long,
        status: String,
        progress: Int,
        message: String,
        error: String? = null
    ) {
        val statusPayload = DecodeStatus(
            revision = revision,
            status = status,
            progress = progress.coerceIn(0, 100),
            message = message,
            error = error
        )
        val prev = decodeStatuses[key]
        val prevStatus = prev?.status
        val shouldBroadcast = when {
            error != null -> true
            status == "ready" -> true
            prevStatus == null -> true
            prevStatus.status != statusPayload.status -> true
            abs(prevStatus.progress - statusPayload.progress) >= 5 -> true
            prevStatus.message != statusPayload.message -> true
            else -> false
        }
        decodeStatuses[key] = DecodeStatusEntry(
            status = DecodeStatus(
                revision = revision,
                status = status,
                progress = progress.coerceIn(0, 100),
                message = message,
                error = error
            ),
            lastModified = lastModified,
            length = length
        )
        if (!shouldBroadcast) return

        val now = System.currentTimeMillis()
        val last = lastDecodeBroadcastMs[key] ?: 0L
        val throttleMs = 250L
        val force = status == "ready" || error != null
        if (!force && now - last < throttleMs) return
        lastDecodeBroadcastMs[key] = now

        logger.info {
            "diff.decode rev=$revision key=$key status=$status progress=${statusPayload.progress}% message=$message" +
                (error?.let { " error=$it" } ?: "")
        }

        decodeStatusListeners.forEach { listener ->
            runCatching { listener(statusPayload) }
        }
    }

    private fun clearStateForKey(key: String) {
        cache.remove(key)
        decodeFutures.remove(key)
        decodeStatuses.remove(key)
        lastDecodeBroadcastMs.remove(key)
    }

    private fun startDecodeIfNeeded(
        rev: Int,
        key: String,
        file: java.io.File,
        lastModified: Long,
        length: Long
    ): CompletableFuture<CacheBinaryFormat.DecodedRev?> {
        decodeFutures[key]?.let { existing ->
            val status = decodeStatuses[key]
            if (status != null && status.lastModified == lastModified && status.length == length) {
                return existing
            }
            decodeFutures.remove(key, existing)
        }
        decodeFutures[key]?.let { inflight ->
            val status = decodeStatuses[key]
            if (status != null && status.lastModified == lastModified && status.length == length) {
                return inflight
            }
        }
        logger.info {
            "diff.decode start rev=$rev key=$key file=${file.name} bytes=$length (async decode thread)"
        }
        val future = CompletableFuture.supplyAsync({
            updateDecodeStatus(key, rev, lastModified, length, "decoding", 15, "Loading binary")
            val decoded = try {
                CacheBinaryFormat.readFromFile(file)
            } catch (e: Exception) {
                logger.error(e) { "Failed to decode diff binary ${file.absolutePath}" }
                updateDecodeStatus(
                    key,
                    rev,
                    lastModified,
                    length,
                    "error",
                    100,
                    "Decode failed",
                    e.message ?: "Unknown decode error"
                )
                null
            }
            if (decoded != null) {
                updateDecodeStatus(key, rev, lastModified, length, "decoding", 90, "Caching decoded data")
                cache[key] = DecodedCacheEntry(decoded, lastModified, length)
                updateDecodeStatus(key, rev, lastModified, length, "ready", 100, "Ready")
            }
            decoded
        }, decodeExecutor)
        decodeFutures[key] = future
        updateDecodeStatus(key, rev, lastModified, length, "decoding", 5, "Queued for decode")
        future.whenComplete { _, _ ->
            decodeFutures.remove(key, future)
        }
        return future
    }

    /**
     * Returns decoded rev if the .bin file exists; loads and caches on first access.
     * Returns null if no binary file for this rev (caller should use loose files).
     */
    fun getDecodedRev(config: ServerConfig, rev: Int): CacheBinaryFormat.DecodedRev? {
        val k = key(config, rev)
        val file = CachePathHelper.getDiffBinaryFile(config.gameType, config.environment, rev)
        if (!file.exists()) {
            clearStateForKey(k)
            return null
        }
        val lastModified = file.lastModified()
        val length = file.length()
        cache[k]?.let { cached ->
            if (cached.lastModified == lastModified && cached.length == length) {
                if (perfLogsEnabled && shouldLogCacheHit(k)) {
                    logger.info { "perf.diff.decode cache-hit rev=$rev game=${config.gameType.name} env=${config.environment.name}" }
                }
                return cached.decoded
            }
            clearStateForKey(k)
        }
        val startNs = if (perfLogsEnabled) System.nanoTime() else 0L
        val beforeUsed = if (perfLogsEnabled) {
            val rt = Runtime.getRuntime()
            rt.totalMemory() - rt.freeMemory()
        } else 0L
        val decoded = startDecodeIfNeeded(rev, k, file, lastModified, length).get()
        if (perfLogsEnabled) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
            val afterUsed = run {
                val rt = Runtime.getRuntime()
                rt.totalMemory() - rt.freeMemory()
            }
            val deltaKb = (afterUsed - beforeUsed) / 1024
            val spriteCount = decoded?.sprites?.size ?: 0
            val configTypeCount = decoded?.configs?.size ?: 0
            logger.info {
                "perf.diff.decode loaded rev=$rev game=${config.gameType.name} env=${config.environment.name} " +
                    "elapsedMs=${"%.2f".format(elapsedMs)} fileKb=${file.length() / 1024} " +
                    "sprites=$spriteCount configTypes=$configTypeCount memDeltaKb=$deltaKb"
            }
        }
        if (decoded != null) logger.debug { "Decoded and cached diff rev $rev (${file.length() / 1024} KB)" }
        return decoded
    }

    fun getDecodedRevIfReady(config: ServerConfig, rev: Int): CacheBinaryFormat.DecodedRev? {
        val k = key(config, rev)
        val file = CachePathHelper.getDiffBinaryFile(config.gameType, config.environment, rev)
        if (!file.exists()) {
            clearStateForKey(k)
            return null
        }
        val lastModified = file.lastModified()
        val length = file.length()
        cache[k]?.let { cached ->
            if (cached.lastModified == lastModified && cached.length == length) return cached.decoded
            clearStateForKey(k)
        }
        val future = startDecodeIfNeeded(rev, k, file, lastModified, length)
        if (!future.isDone) return null
        return runCatching { future.get() }.getOrNull()
    }

    fun getDecodeStatus(config: ServerConfig, rev: Int): DecodeStatus {
        val k = key(config, rev)
        val file = CachePathHelper.getDiffBinaryFile(config.gameType, config.environment, rev)
        if (!file.exists()) {
            clearStateForKey(k)
            return DecodeStatus(revision = rev, status = "missing", progress = 0, message = "No diff binary file")
        }
        val lastModified = file.lastModified()
        val length = file.length()
        cache[k]?.let { cached ->
            if (cached.lastModified == lastModified && cached.length == length) {
                return DecodeStatus(revision = rev, status = "ready", progress = 100, message = "Ready")
            }
            clearStateForKey(k)
        }
        startDecodeIfNeeded(rev, k, file, lastModified, length)
        val statusEntry = decodeStatuses[k]
        if (statusEntry != null && statusEntry.lastModified == lastModified && statusEntry.length == length) {
            return statusEntry.status
        }
        return DecodeStatus(revision = rev, status = "decoding", progress = 1, message = "Starting decode")
    }

    fun onDecodeStatus(listener: (DecodeStatus) -> Unit) {
        decodeStatusListeners.add(listener)
    }

    /** Optional explicit shutdown for CLI dump tools. */
    fun shutdown() {
        decodeExecutor.shutdownNow()
    }

    /** List revs that have a .bin file in the flat diffs directory. */
    fun listRevisionsWithBinary(config: ServerConfig): List<Int> {
        val dir = CachePathHelper.getDiffBinaryDirectory(config.gameType, config.environment)
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        val key = revisionsListKey(config)
        val dirLastModified = dir.lastModified()
        revisionsListCache[key]?.let { cached ->
            if (cached.dirLastModified == dirLastModified && cached.fileCount == files.size) {
                return cached.revisions
            }
        }

        val revisions = files.mapNotNull { f ->
            if (!f.isFile || !f.name.endsWith(".bin")) return@mapNotNull null
            f.name.removeSuffix(".bin").toIntOrNull()
        }.sorted()

        revisionsListCache[key] = RevisionListCacheEntry(
            dirLastModified = dirLastModified,
            fileCount = files.size,
            revisions = revisions
        )
        return revisions
    }

    fun warmRevisions(config: ServerConfig, revisions: Collection<Int>) {
        revisions
            .asSequence()
            .distinct()
            .filter { it > 0 }
            .forEach { rev ->
                runCatching { getDecodedRev(config, rev) }
                    .onFailure { e -> logger.debug(e) { "Warm-up decode failed for rev $rev" } }
            }
    }
}
