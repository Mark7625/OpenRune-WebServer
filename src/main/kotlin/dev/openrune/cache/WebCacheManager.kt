package dev.openrune.cache

import dev.openrune.OsrsCacheProvider
import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.BaseExtractor
import dev.openrune.cache.osrs.OsrsCacheIndex
import dev.openrune.cache.tools.CacheInfo
import dev.openrune.cache.tools.OpenRS2
import dev.openrune.filesystem.Cache
import dev.openrune.util.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.io.File

class WebCacheManager(
    private val config: ServerConfig,
    private val zipService: dev.openrune.server.zip.ZipService? = null
) {
    private val logger = KotlinLogging.logger {}
    private val manifestManager = ChecksumManifestManager(config)
    private val downloader = CacheDownloader()
    private val extractorManager = FileExtractorManager(
        config = config,
        testingMode = TESTING_MODE,
        verifiedIndices = VERIFIED_INDICES
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
        private const val MASTER_CHECKSUMS_FILE = "master-checksums.json"
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

        val cache = Cache.load(File(dataDir, CACHE_DIR).toPath(), false)
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


    /**
     * Process changed files and extract them using the appropriate extractors
     */
    private suspend fun updateFiles(
        cache: Cache,
        changedFiles: List<FileChecksum>,
        baseProgress: Double = 0.0,
        progressRange: Double = 100.0,
        onProgress: ((Boolean, Double?, String?) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        if (changedFiles.isEmpty()) {
            logger.debug("No files to extract")
            return@withContext
        }

        CacheManager.init(OsrsCacheProvider(cache, config.revision))

        val indicesNeedingExtractors = changedFiles.map { it.index }.distinct()
        val extractorCache = extractorManager.createExtractors(indicesNeedingExtractors, cache)
        
        val filesWithExtractors = changedFiles.filter { extractorCache.containsKey(it.index) }
        if (filesWithExtractors.isEmpty()) {
            logger.debug("No files with extractors to process")
            return@withContext
        }

        val filesToProcess = extractorManager.filterFilesForTesting(filesWithExtractors)
        val sortedFiles = extractorManager.sortFiles(filesToProcess)

        logger.info("Extracting ${sortedFiles.size} changed files (sorted by dependency order)...")

        val totalFiles = filesWithExtractors.size
        var processedFiles = 0

        extractorManager.initializeProgressBars(extractorCache, sortedFiles)

        if (TESTING_MODE && sortedFiles.size < totalFiles) {
            val skippedCount = totalFiles - sortedFiles.size
            processedFiles = skippedCount
            updateProgress(baseProgress, progressRange, totalFiles, processedFiles, onProgress)
        }

        processFiles(sortedFiles, extractorCache, cache, baseProgress, progressRange, totalFiles, onProgress) { 
            processedFiles = it 
        }

        val totalManifestFiles = extractorManager.countManifestFiles(extractorCache)
        val manifestProgressBar = extractorManager.createManifestProgressBar(totalManifestFiles)
        extractorManager.setSharedManifestProgressBar(extractorCache, manifestProgressBar)
        extractorManager.closeExtractors(extractorCache, manifestProgressBar)
        
        // Clear extractor data structures to free memory
        extractorManager.clearExtractorData(extractorCache)

        logger.info("Finished extracting ${changedFiles.size} files")
    }
    
    private fun updateProgress(
        baseProgress: Double,
        progressRange: Double,
        totalFiles: Int,
        processedFiles: Int,
        onProgress: ((Boolean, Double?, String?) -> Unit)?
    ) {
        val progress = baseProgress + (processedFiles.toDouble() / totalFiles) * progressRange
        onProgress?.invoke(true, progress, "Unpacking Cache")
    }
    
    private suspend fun processFiles(
        sortedFiles: List<FileChecksum>,
        extractorCache: Map<Int, BaseExtractor>,
        cache: Cache,
        baseProgress: Double,
        progressRange: Double,
        totalFiles: Int,
        onProgress: ((Boolean, Double?, String?) -> Unit)?,
        processedFilesRef: (Int) -> Unit
    ) {
        var processed = 0
        sortedFiles.forEach { fileChecksum ->
            val extractor = extractorCache[fileChecksum.index] ?: return@forEach

            try {
                val fileData = cache.data(fileChecksum.index, fileChecksum.archive, fileChecksum.file)
                if (fileData != null) {
                    extractor.extract(
                        fileChecksum.index,
                        fileChecksum.archive,
                        fileChecksum.file,
                        fileData
                    )
                } else {
                    logger.warn("Failed to load data for index=${fileChecksum.index}, archive=${fileChecksum.archive}, file=${fileChecksum.file}")
                }
            } catch (e: Exception) {
                logger.error("Error extracting file: index=${fileChecksum.index}, archive=${fileChecksum.archive}, file=${fileChecksum.file}", e)
            }

            processed++
            processedFilesRef(processed)
            updateProgress(baseProgress, progressRange, totalFiles, processed, onProgress)
        }
    }


    private fun getCachePath(): File {
        return CachePathHelper.getCacheDirectory(
            config.gameType,
            config.environment,
            config.revision
        )
    }

    private suspend fun checkAndUpdateCacheInfo(onUpdatingDetected: ((Boolean, Double?, String?) -> Unit)? = null) = withContext(Dispatchers.IO) {
        val latestCache = OpenRS2.findRevision(
            rev = config.revision,
            game = config.gameType,
            environment = config.environment
        )

        val cacheDir = getCachePath()
        cacheDir.mkdirs()
        
        val cacheInfoFile = CachePathHelper.getCacheFile(
            config.gameType,
            config.environment,
            config.revision,
            CACHE_INFO_FILE
        )

        if (needsUpdate(latestCache, cacheInfoFile, cacheDir)) {
            onUpdatingDetected?.invoke(true, 0.0, "Updating Server Cache")
            downloader.downloadCache(latestCache, cacheDir, onUpdatingDetected)
            onUpdatingDetected?.invoke(true, 50.0, "Verifying Cache checksum")
            downloader.unzipCache(cacheDir, onUpdatingDetected)
            
            val dataDir = File(cacheDir, DATA_DIR)
            val checksumFile = File(cacheDir, DATA_CHECKSUM_FILE)
            ChecksumManager.saveDirectoryChecksum(dataDir, checksumFile)
            
            cacheInfoFile.writeText(json.toJson(latestCache))
            logger.info("Cache downloaded, extracted, verified, and $CACHE_INFO_FILE saved")
        } else {
            logger.info("Cache is up to date, skipping download")
        }
    }

    private fun needsUpdate(latestCache: CacheInfo, cacheInfoFile: File, cacheDir: File): Boolean {
        if (!cacheInfoFile.exists()) {
            return true
        }

        try {
            val existingCacheInfoJson = cacheInfoFile.readText()
            val existingCacheInfo = json.fromJson(existingCacheInfoJson, CacheInfo::class.java)

            if (existingCacheInfo.id != latestCache.id) {
                return true
            }

            val dataDir = File(cacheDir, DATA_DIR)
            if (!dataDir.exists() || !dataDir.isDirectory) {
                logger.info("Data directory missing, needs extraction")
                return true
            }

            val checksumFile = File(cacheDir, DATA_CHECKSUM_FILE)
            if (!ChecksumManager.verifyDirectoryIntegrity(dataDir, checksumFile)) {
                return true
            }

        } catch (e: Exception) {
            logger.warn("Failed to read existing cache-info.json: ${e.message}")
            return true
        }

        return false
    }


}

