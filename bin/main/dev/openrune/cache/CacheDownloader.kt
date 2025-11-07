package dev.openrune.cache

import dev.openrune.cache.tools.CacheInfo
import dev.openrune.cache.tools.DownloadListener
import dev.openrune.cache.tools.OpenRS2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import mu.KotlinLogging
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarBuilder
import me.tongfei.progressbar.ProgressBarStyle
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

private val logger = KotlinLogging.logger {}

/**
 * Handles downloading and extracting cache files
 */
class CacheDownloader {
    
    companion object {
        private const val DISK_ZIP = "disk.zip"
        private const val XTEAS_JSON = "xteas.json"
        private const val DATA_DIR = "data"
        private const val PROGRESS_UPDATE_THRESHOLD = 1.0
        private const val DOWNLOAD_PROGRESS_RANGE = 40.0
        private const val XTEAS_PROGRESS_RANGE = 5.0
        private const val EXTRACT_PROGRESS_RANGE = 49.0
        private const val EXTRACT_BASE_PROGRESS = 50.0
        private const val DOWNLOAD_BASE_PROGRESS = 5.0
        private const val XTEAS_BASE_PROGRESS = 45.0
    }
    
    /**
     * Download cache files (disk.zip and xteas.json)
     */
    suspend fun downloadCache(
        cacheInfo: CacheInfo,
        directory: File,
        onProgress: ((Boolean, Double?, String?) -> Unit)?
    ) = withContext(Dispatchers.IO) {
        onProgress?.invoke(true, DOWNLOAD_BASE_PROGRESS, "Updating Server Cache - Downloading $DISK_ZIP")
        downloadFile(cacheInfo.id, directory, DISK_ZIP) { current, max ->
            val progress = DOWNLOAD_BASE_PROGRESS + (current.toDouble() / max.toDouble()) * DOWNLOAD_PROGRESS_RANGE
            onProgress?.invoke(true, progress, "Updating Server Cache - Downloading $DISK_ZIP")
        }
        
        onProgress?.invoke(true, XTEAS_BASE_PROGRESS, "Updating Server Cache - Downloading $XTEAS_JSON")
        downloadFile(cacheInfo.id, directory, XTEAS_JSON) { current, max ->
            val progress = XTEAS_BASE_PROGRESS + (current.toDouble() / max.toDouble()) * XTEAS_PROGRESS_RANGE
            onProgress?.invoke(true, progress, "Updating Server Cache - Downloading $XTEAS_JSON")
        }
    }
    
    /**
     * Extract disk.zip to data directory
     */
    suspend fun unzipCache(
        cacheDir: File,
        onProgress: ((Boolean, Double?, String?) -> Unit)?
    ) = withContext(Dispatchers.IO) {
        val diskZip = File(cacheDir, DISK_ZIP)
        if (!diskZip.exists()) {
            logger.error("$DISK_ZIP not found, cannot extract cache")
            throw Exception("Failed to fetch Cache please report: $DISK_ZIP not found")
        }

        val dataDir = File(cacheDir, DATA_DIR)
        dataDir.mkdirs()

        logger.info("Extracting $DISK_ZIP to data directory...")
        
        val zipSize = diskZip.length()
        var extractedBytes = 0L
        var lastProgressUpdate = 0.0
        
        ZipInputStream(FileInputStream(diskZip)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val file = File(dataDir, entry.name)
                
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { output ->
                        zip.copyTo(output)
                        extractedBytes += entry.size
                    }
                    
                    val progress = EXTRACT_BASE_PROGRESS + (extractedBytes.toDouble() / zipSize.toDouble()) * EXTRACT_PROGRESS_RANGE
                    if (progress - lastProgressUpdate >= PROGRESS_UPDATE_THRESHOLD) {
                        onProgress?.invoke(true, progress, "Unpacking Cache")
                        lastProgressUpdate = progress
                    }
                }
                
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        
        logger.info("Cache extraction completed")
        
        if (diskZip.delete()) {
            logger.info("Deleted $DISK_ZIP after extraction")
        } else {
            logger.warn("Failed to delete $DISK_ZIP")
        }
    }
    
    private suspend fun downloadFile(
        id: Int,
        directory: File,
        filename: String,
        onProgress: ((Long, Long) -> Unit)?
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        var isCompleted = false
        var progressBar: ProgressBar? = null
        
        val listener = object : DownloadListener {
            override fun onProgress(progress: Int, max: Long, current: Long) {
                if (progressBar == null && max > 0) {
                    progressBar = ProgressBarBuilder()
                        .setTaskName(filename)
                        .setInitialMax(max)
                        .setStyle(ProgressBarStyle.UNICODE_BLOCK)
                        .setUpdateIntervalMillis(100)
                        .showSpeed()
                        .build()
                }
                progressBar?.maxHint(max)
                progressBar?.stepTo(current)
                onProgress?.invoke(current, max)
            }

            override fun onError(exception: Exception) {
                progressBar?.close()
                logger.error("Error downloading $filename: ${exception.message}", exception)
                if (!isCompleted) {
                    isCompleted = true
                    continuation.resumeWithException(Exception("Failed to fetch Cache please report", exception))
                }
            }

            override fun onFinished() {
                progressBar?.close()
                logger.info("Finished downloading $filename")
                if (!isCompleted) {
                    isCompleted = true
                    continuation.resume(Unit)
                }
            }
        }

        try {
            OpenRS2.downloadByInternalID(id, directory, listener, filename)
        } catch (e: Exception) {
            progressBar?.close()
            if (!isCompleted) {
                isCompleted = true
                continuation.resumeWithException(Exception("Failed to fetch Cache please report", e))
            }
        }
    }
}

