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

    companion object {
        private const val TESTING_MODE = false
        private val VERIFIED_INDICES = setOf(
            OsrsCacheIndex.GAMEVALS.id,
            OsrsCacheIndex.MODELS.id,
            OsrsCacheIndex.SPRITES.id,
            OsrsCacheIndex.TEXTURES.id,
            OsrsCacheIndex.CONFIGS.id
        )
        private const val CACHE_INFO_FILE = "cache-info.json"
        private const val MASTER_CHECKSUMS_FILE = "cache-master-checksums.json"
        private const val DATA_CHECKSUM_FILE = "data.checksum"
        private const val DATA_DIR = "data"
        private const val CACHE_DIR = "cache"
    }

    suspend fun loadOrUpdate(onUpdatingDetected: ((Boolean, Double?, String?) -> Unit)? = null) = withContext(Dispatchers.IO) {
        checkAndUpdateCacheInfo(onUpdatingDetected)
        lookingForChanges(onUpdatingDetected)
    }

    suspend fun lookingForChanges(onUpdatingDetected: ((Boolean, Double?, String?) -> Unit)? = null) = withContext(Dispatchers.IO) {
        val cacheDir = getCachePath()
        val dataDir = File(cacheDir, DATA_DIR)

        if (!dataDir.exists() || !dataDir.isDirectory) {
            logger.warn("Data directory does not exist, cannot scan for changes")
            return@withContext
        }

        logger.info("Scanning cache files for changes...")
        onUpdatingDetected?.invoke(true, null, "Verifying Cache checksum")

        val cache = Cache.load(File(dataDir, CACHE_DIR).toPath())
        try {
            val manifest = manifestManager.createManifest(cache)

            val masterChecksumFile = File(cacheDir, MASTER_CHECKSUMS_FILE)
            val cacheNeedsDownload = !File(cacheDir, CACHE_INFO_FILE).exists()
            val baseProgress = if (cacheNeedsDownload) 50.0 else 0.0
            val progressRange = if (cacheNeedsDownload) 50.0 else 100.0

            val existingManifest = manifestManager.loadManifest(masterChecksumFile)
            
            if (existingManifest == null) {
                // First time - extract all files
                val totalFiles = manifest.indices.values.flatMap { it.archives.values.flatMap { archive -> archive.files.values } }
                
                if (!TESTING_MODE) {
                    manifestManager.saveManifest(manifest, masterChecksumFile)
                    logger.info("Master checksum calculated and saved for ${totalFiles.size} files")
                } else {
                    logger.info("Master checksum calculated for ${totalFiles.size} files (saving disabled for testing)")
                }
                
                updateFiles(cache, totalFiles, baseProgress, progressRange, onUpdatingDetected)
            } else {
                // Compare with existing manifest
                val differences = manifestManager.findDifferences(existingManifest, manifest)
                if (differences.added.isNotEmpty() || differences.removed.isNotEmpty()) {
                    logger.info("Found ${differences.added.size} added/changed files and ${differences.removed.size} removed files")
                    
                    // Delete all zip files since cache has changed
                    deleteZipFiles()
                    
                    if (!TESTING_MODE) {
                        manifestManager.saveManifest(manifest, masterChecksumFile)
                        logger.debug("Updated checksum manifest saved")
                    }
                    
                    updateFiles(cache, differences.added, baseProgress, progressRange, onUpdatingDetected)
                } else {
                    logger.info("No changes detected in cache files")
                    
                    // Update manifest timestamp even if no changes (to track when last checked)
                    if (!TESTING_MODE) {
                        manifestManager.saveManifest(manifest, masterChecksumFile)
                        logger.debug("Checksum manifest timestamp updated")
                    }
                }
            }
        } finally {
            // Always cleanup cache, even if extraction failed
            cleanupAfterExtraction(cache)
        }
        
        onUpdatingDetected?.invoke(false, null, null)
    }
    
    /**
     * Delete all zip files when cache is updated
     */
    private fun deleteZipFiles() {
        zipService?.deleteAllZips()
    }
    
    /**
     * Clean up resources after extraction is complete
     * - Closes cache file handles
     * - Clears temporary data structures
     * - Forces garbage collection
     * - Ensures threads are cleaned up
     */
    private suspend fun cleanupAfterExtraction(cache: Cache) = withContext(Dispatchers.IO) {
        try {
            logger.info("Cleaning up extraction resources...")
            
            // Close cache file handles if cache implements Closeable/AutoCloseable
            try {
                if (cache is AutoCloseable) {
                    cache.close()
                    logger.debug("Cache file handles closed")
                } else if (cache is java.io.Closeable) {
                    cache.close()
                    logger.debug("Cache file handles closed")
                }
            } catch (e: Exception) {
                logger.warn("Error closing cache: ${e.message}")
            }
            
            // Wait for any pending coroutines to complete
            delay(50)
            
            // Force garbage collection to free up memory
            System.gc()
            
            // Give GC and threads a moment to finish
            delay(50)
            
            logger.info("Cleanup completed")
        } catch (e: Exception) {
            logger.warn("Error during cleanup: ${e.message}", e)
        }
    }

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

        val ordered = buildList {
            if (1 in availableRevs) add(WarmupStep(1, "base"))
            if (config.revision in availableRevs && config.revision != 1) add(WarmupStep(config.revision, "server"))
            availableRevs
                .filterNot { it == 1 || it == config.revision }
                .sorted()
                .forEach { add(WarmupStep(it, "rev")) }
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

        emitUpdating(true, 100.0, "Diff decode warmup complete")
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

