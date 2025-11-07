package dev.openrune.server.zip

import dev.openrune.ServerConfig
import dev.openrune.cache.CachePathHelper
import dev.openrune.server.SseEventType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import mu.KotlinLogging
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

enum class ZipType {
    SPRITES,
    MODELS,
    TEXTURES
}

data class ZipJob(
    val id: String,
    val type: ZipType,
    val progress: MutableStateFlow<ZipProgress> = MutableStateFlow(ZipProgress(0.0, "Initializing...", null)),
    var job: Job
)

data class ZipProgress(
    val progress: Double,
    val message: String,
    val downloadUrl: String?
)

/**
 * Service for creating zip files of extracted cache data
 * Handles concurrent requests, progress tracking, and automatic cleanup
 */
class ZipService(
    private val config: ServerConfig,
    private val broadcastSse: (SseEventType, Map<String, Any?>) -> Unit
) {
    
    companion object {
        private const val ZIPS_DIR = "zips"
    }
    
    private val activeJobs = ConcurrentHashMap<String, ZipJob>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Check if zip file already exists and is ready for download
     * Returns the jobId if zip exists, null otherwise
     */
    fun zipExists(type: ZipType): String? {
        val jobId = "${type.name.lowercase()}-${config.revision}"
        val zipFile = getZipFileLocation(jobId)
        return if (zipFile.exists()) jobId else null
    }
    
    /**
     * Create a zip file for the specified type
     * If a zip is already being created, returns the existing job ID
     */
    fun createZip(type: ZipType): String {
        val jobId = "${type.name.lowercase()}-${config.revision}"
        
        activeJobs[jobId]?.let { return jobId }
        
        // Create ZipJob with placeholder and add to activeJobs before launching coroutine
        // This prevents race condition where coroutine starts before job is in activeJobs
        val zipJob = ZipJob(jobId, type, job = Job())
        activeJobs[jobId] = zipJob
        
        val job = scope.launch {
            try {
                delay(2000) // Wait 2 seconds before starting to allow SSE connection
                createZipFile(jobId, type)
            } catch (e: Exception) {
                logger.error("Error creating zip for $type: ${e.message}", e)
                broadcastSse(SseEventType.ZIP_PROGRESS, mapOf(
                    "jobId" to jobId,
                    "type" to type.name,
                    "progress" to 0.0,
                    "message" to "Error: ${e.message}",
                    "downloadUrl" to null
                ))
            } finally {
                activeJobs.remove(jobId)
            }
        }
        
        zipJob.job = job
        return jobId
    }
    
    fun getProgress(jobId: String): ZipProgress? {
        activeJobs[jobId]?.let { return it.progress.value }
        
        val zipFile = getZipFileLocation(jobId)
        return if (zipFile.exists()) {
            ZipProgress(100.0, "Complete", "/zip/download/$jobId")
        } else {
            null
        }
    }
    
    fun getZipFile(jobId: String): File? {
        val zipFile = getZipFileLocation(jobId)
        return if (zipFile.exists()) zipFile else null
    }
    
    fun deleteAllZips() {
        try {
            val zipsDir = getZipsDirectory()
            if (zipsDir.exists() && zipsDir.isDirectory) {
                zipsDir.deleteRecursively()
                zipsDir.mkdirs()
            }
        } catch (e: Exception) {
            logger.error("Error deleting zip directory: ${e.message}", e)
        }
    }
    
    fun cancelJob(jobId: String): Boolean {
        val job = activeJobs.remove(jobId) ?: return false
        job.job.cancel()
        return true
    }
    
    fun shutdown() {
        scope.cancel()
    }
    
    private fun getZipsDirectory(): File {
        val baseDir = CachePathHelper.getCacheDirectory(
            config.gameType,
            config.environment,
            config.revision
        )
        return baseDir.resolve(ZIPS_DIR)
    }
    
    private fun getExtractDirectory(type: ZipType): File {
        val baseDir = CachePathHelper.getCacheDirectory(
            config.gameType,
            config.environment,
            config.revision
        )
        return baseDir.resolve("extracted").resolve(type.name.lowercase())
    }
    
    private fun getZipFileLocation(jobId: String): File {
        return getZipsDirectory().resolve("$jobId.zip")
    }
    
    private fun countFiles(directory: File): Int {
        return directory.walkTopDown().count { it.isFile }
    }
    
    private suspend fun createZipFile(jobId: String, type: ZipType) = withContext(Dispatchers.IO) {
        val extractDir = getExtractDirectory(type)
        
        if (!extractDir.exists() || !extractDir.isDirectory) {
            throw IllegalStateException("Extract directory does not exist: ${extractDir.path}")
        }
        
        activeJobs[jobId] ?: return@withContext
        
        val totalFiles = countFiles(extractDir)
        var processedFiles = 0
        
        updateProgress(jobId, type, 0.0, "Counting files... ($totalFiles found)")
        
        val zipFile = getZipFileLocation(jobId)
        zipFile.parentFile?.mkdirs()
        
        var lastProgressUpdate = 0.0
        val progressUpdateInterval = 1.0
        
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            extractDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val relativePath = file.relativeTo(extractDir).path.replace("\\", "/")
                    try {
                        zos.putNextEntry(java.util.zip.ZipEntry(relativePath))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        
                        processedFiles++
                        val progress = (processedFiles.toDouble() / totalFiles) * 100.0
                        
                        if (progress - lastProgressUpdate >= progressUpdateInterval || processedFiles == totalFiles) {
                            updateProgress(jobId, type, progress, "Adding files... ($processedFiles/$totalFiles)")
                            lastProgressUpdate = progress
                        }
                    } catch (e: Exception) {
                        logger.error("Error adding file $relativePath to zip: ${e.message}", e)
                    }
                }
            }
        }
        
        updateProgress(jobId, type, 100.0, "Complete", "/zip/download/$jobId")
    }
    
    private fun updateProgress(jobId: String, type: ZipType, progress: Double, message: String, downloadUrl: String? = null) {
        val job = activeJobs[jobId] ?: return
        job.progress.value = ZipProgress(progress, message, downloadUrl)
        
        try {
            broadcastSse(SseEventType.ZIP_PROGRESS, mapOf(
                "jobId" to jobId,
                "type" to type.name,
                "progress" to progress,
                "message" to message,
                "downloadUrl" to downloadUrl
            ))
        } catch (e: Exception) {
            logger.error("Failed to broadcast SSE event: ${e.message}", e)
        }
    }
}

