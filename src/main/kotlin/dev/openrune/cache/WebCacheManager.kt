package dev.openrune.cache

import dev.openrune.ServerConfig
import dev.openrune.cache.diff.CacheBinaryFormat
import dev.openrune.cache.diff.DiffDumper
import dev.openrune.cache.diff.DiffBinaryCache
import dev.openrune.cache.tools.CacheInfo
import dev.openrune.cache.tools.OpenRS2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.time.Instant

class WebCacheManager(
    private val config: ServerConfig
) {
    private val logger = KotlinLogging.logger {}
    private data class DiffOpenRs2Stamp(
        val revision: Int,
        val cacheId: Long?,
        val openRs2Timestamp: Long?
    )

    private data class TargetResolution(
        val revision: Int,
        val expectedStamp: DiffOpenRs2Stamp?
    )

    private data class WarmupStep(
        val rev: Int,
        val label: String
    )

    /**
     * Ensures a target revision has an up-to-date diff binary and then warms decoded binaries into memory.
     * The revision is resolved from [targetCacheId] (defaults to [ServerConfig.cacheID]) via OpenRS2.
     */
    suspend fun loadOrUpdate(
        targetCacheId: Int? = config.cacheID,
        onUpdatingDetected: ((Boolean, Double?, String?) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        fun emitUpdating(active: Boolean, progress: Double?, message: String?) {
            println("[onUpdatingDetected] active=$active progress=$progress message=${message ?: ""}")
            onUpdatingDetected?.invoke(active, progress, message)
        }

        emitUpdating(true, 0.0, "Checking OpenRS2 cache index")
        ensureOpenRs2CachesLoaded()

        val target = resolveTarget(targetCacheId)
        config.revision = target.revision
        val stamp = target.expectedStamp
        val cacheIdForDump = stamp?.cacheId?.takeIf { it > 0L }?.toInt()
            ?: error("Resolved stamp has no valid cacheId for cacheID=$targetCacheId")
        val targetDesc = "rev ${target.revision}, OpenRS2 id $cacheIdForDump"
        emitUpdating(true, 5.0, "Target diff: $targetDesc")

        if (needsDiffUpdate(target.revision, target.expectedStamp)) {
            emitUpdating(true, 10.0, "Diff outdated or missing for $targetDesc, updating")
            runDiffDumper(target.revision, cacheIdForDump, ::emitUpdating)
        } else {
            emitUpdating(true, 25.0, "Diff binary is up to date for rev ${target.revision}")
        }

        emitUpdating(true, 30.0, "Loading diff index")

        val availableRevs = DiffBinaryCache.listRevisionsWithBinary(config)
        if (availableRevs.isEmpty()) {
            emitUpdating(true, 100.0, "No diff binaries found")
            emitUpdating(false, null, null)
            return@withContext
        }

        // Keep idle RAM bounded: only warm base + a few newest revs. Other revs decode on demand
        // (HTTP 202 + /diff/decode/status + SSE DECODE_PROGRESS).
        val preloadNewest = System.getenv("OPENRUNE_DIFF_PRELOAD_NEWEST")?.toIntOrNull()?.coerceIn(0, 32) ?: 3
        val newest = availableRevs
            .asSequence()
            .filter { it != 1 }
            .sortedDescending()
            .take(preloadNewest)
            .toList()
        val ordered = buildList {
            if (1 in availableRevs) add(WarmupStep(1, "base"))
            newest.forEach { add(WarmupStep(it, "recent")) }
        }
        logger.info {
            "Diff warmup selected ${ordered.size}/${availableRevs.size} revs " +
                "(base + newest $preloadNewest): ${ordered.map { it.rev }}"
        }

        val total = ordered.size.coerceAtLeast(1)
        ordered.forEachIndexed { index, step ->
            val pct = 30.0 + ((index * 70.0) / total.toDouble())
            emitUpdating(
                true,
                pct,
                "Decoding diff (${index + 1}/$total): ${step.label} ${step.rev}"
            )
            DiffBinaryCache.getDecodedRev(config, step.rev)
        }

        emitUpdating(true, 100.0, "Diff decode warmup complete (${ordered.size} of ${availableRevs.size} revs)")
        emitUpdating(false, null, null)
    }

    private fun ensureOpenRs2CachesLoaded() {
        if (OpenRS2.allCaches.isEmpty()) {
            OpenRS2.loadCaches()
        }
    }

    private suspend fun runDiffDumper(
        rev: Int,
        openRs2CacheId: Int,
        onUpdatingDetected: (Boolean, Double?, String?) -> Unit
    ) {
        var phaseProgress = 10.0
        val dumper = DiffDumper(
            gameType = config.gameType,
            environment = config.environment,
            spriteCdn = config.spriteCdn,
            onProgress = { msg ->
                val pct = Regex("(\\d{1,3})%").find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)
                phaseProgress = if (pct != null) {
                    10.0 + (pct * 0.2) // 10..30 while dumping
                } else {
                    (phaseProgress + 1.0).coerceAtMost(29.0)
                }
                onUpdatingDetected(true, phaseProgress, "Diff update: $msg")
            }
        )
        dumper.run(rev, openRs2CacheId)
        onUpdatingDetected(true, 30.0, "Diff update complete for rev $rev")
    }

    private fun needsDiffUpdate(rev: Int, expectedStamp: DiffOpenRs2Stamp?): Boolean {
        val diffFile = CachePathHelper.getDiffBinaryFile(config.gameType, config.environment, rev)
        if (!diffFile.exists()) return true
        val expectedId = expectedStamp?.cacheId?.takeIf { it > 0L } ?: return true
        val decoded = CacheBinaryFormat.readFromFile(diffFile) ?: return true
        val actualId = decoded.openRs2CacheId?.takeIf { it > 0L } ?: return true
        return actualId != expectedId
    }

    private fun resolveTarget(targetCacheId: Int?): TargetResolution {
        val effectiveCacheId = targetCacheId?.takeIf { it > 0 }
        if (effectiveCacheId != null) {
            val byId = stampFromCacheId(effectiveCacheId)
            if (byId != null) {
                val clampedRev = byId.revision.coerceIn(1, 10_000)
                return TargetResolution(clampedRev, byId)
            }
            logger.warn("Could not resolve OpenRS2 cache id $effectiveCacheId")
        }
        error("cacheID must be set and resolvable via OpenRS2 (got cacheID=$targetCacheId)")
    }

    private fun stampFromCacheId(cacheId: Int): DiffOpenRs2Stamp? {
        val game = config.gameType.name.lowercase()
        val env = config.environment.toString().lowercase()
        val cacheInfo = OpenRS2.allCaches.firstOrNull {
            it.id == cacheId &&
                    it.game.contains(game) &&
                    it.environment.equals(env, true)
        } ?: return null
        return toStamp(cacheInfo)
    }

    private fun toStamp(info: CacheInfo): DiffOpenRs2Stamp? {
        val major = info.builds.firstOrNull()?.major ?: return null
        val ts = try {
            if (info.timestamp.isBlank()) null else Instant.parse(info.timestamp).toEpochMilli()
        } catch (_: Exception) {
            null
        }
        return DiffOpenRs2Stamp(
            revision = major,
            cacheId = info.id.toLong(),
            openRs2Timestamp = ts
        )
    }

}
