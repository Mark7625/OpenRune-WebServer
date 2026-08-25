package dev.openrune.server.zip

import dev.openrune.ServerConfig
import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.diff.DiffBinaryCache
import dev.openrune.cache.diff.DefinitionSnapshot
import dev.openrune.cache.diff.SpriteCdn
import dev.openrune.server.endpoints.diff.getCombinedSprites
import dev.openrune.server.SseEventType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import mu.KotlinLogging
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

enum class ZipType {
    SPRITES,
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
        private val ZIP_TTL_MS = TimeUnit.DAYS.toMillis(3)
    }
    
    private val activeJobs = ConcurrentHashMap<String, ZipJob>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Check if zip file already exists and is ready for download
     * Returns the jobId if zip exists, null otherwise
     */
    fun zipExists(type: ZipType, baseRev: Int? = null, rev: Int? = null): String? {
        cleanupExpiredZips()
        val (resolvedBase, resolvedRev) = resolveRange(baseRev, rev)
        val jobId = buildJobId(type, resolvedBase, resolvedRev)
        val zipFile = getZipFileLocation(jobId)
        return if (zipFile.exists()) jobId else null
    }
    
    /**
     * Create a zip file for the specified type
     * If a zip is already being created, returns the existing job ID
     */
    fun createZip(type: ZipType, baseRev: Int? = null, rev: Int? = null): String {
        cleanupExpiredZips()
        val (resolvedBase, resolvedRev) = resolveRange(baseRev, rev)
        val jobId = buildJobId(type, resolvedBase, resolvedRev)
        
        activeJobs[jobId]?.let { return jobId }
        
        // Create ZipJob with placeholder and add to activeJobs before launching coroutine
        // This prevents race condition where coroutine starts before job is in activeJobs
        val zipJob = ZipJob(jobId, type, job = Job())
        activeJobs[jobId] = zipJob
        
        val job = scope.launch {
            try {
                delay(2000) // Wait 2 seconds before starting to allow SSE connection
                when (type) {
                    ZipType.SPRITES -> createDiffSpritesZipFile(jobId, resolvedBase, resolvedRev)
                    ZipType.TEXTURES -> createDiffTexturesZipFile(jobId, resolvedBase, resolvedRev)
                }
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
        cleanupExpiredZips()
        activeJobs[jobId]?.let { return it.progress.value }
        
        val zipFile = getZipFileLocation(jobId)
        return if (zipFile.exists()) {
            ZipProgress(100.0, "Complete", "/zip/download/$jobId")
        } else {
            null
        }
    }
    
    fun getZipFile(jobId: String): File? {
        cleanupExpiredZips()
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
    
    private fun getZipFileLocation(jobId: String): File {
        return getZipsDirectory().resolve("$jobId.zip")
    }

    private fun resolveRange(baseRev: Int?, rev: Int?): Pair<Int, Int> {
        val resolvedBase = (baseRev ?: 1).coerceIn(1, 1000)
        val resolvedRev = (rev ?: config.revision).coerceIn(1, 1000)
        return resolvedBase to resolvedRev
    }

    private fun buildJobId(type: ZipType, baseRev: Int, rev: Int): String {
        return "${type.name.lowercase()}-${baseRev}-${rev}"
    }

    private fun cleanupExpiredZips() {
        try {
            val zipsDir = getZipsDirectory()
            if (!zipsDir.exists() || !zipsDir.isDirectory) return
            val now = System.currentTimeMillis()
            zipsDir.listFiles()?.forEach { file ->
                if (!file.isFile || !file.name.endsWith(".zip")) return@forEach
                val age = now - file.lastModified()
                if (age > ZIP_TTL_MS) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed zip TTL cleanup: ${e.message}")
        }
    }
    
    private suspend fun createDiffSpritesZipFile(jobId: String, base: Int, rev: Int) = withContext(Dispatchers.IO) {
        activeJobs[jobId] ?: return@withContext

        updateProgress(jobId, ZipType.SPRITES, 0.0, "Preparing diff sprites list...")
        val (ids, sourceRevById) = getCombinedSprites(config, base, rev)
        val totalFiles = ids.size.coerceAtLeast(1)
        var processedFiles = 0
        var skippedFiles = 0

        val zipFile = getZipFileLocation(jobId)
        zipFile.parentFile?.mkdirs()

        var lastProgressUpdate = 0.0
        val progressUpdateInterval = 1.0

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            ids.sorted().forEach { id ->
                val sourceRev = sourceRevById[id] ?: base
                val bytes = DiffBinaryCache.getDecodedRev(config, sourceRev)?.sprites?.get(id)
                    ?: SpriteCdn.fetchSpritePng(config.spriteCdn, config.gameType, sourceRev, id)
                if (bytes != null) {
                    zos.putNextEntry(ZipEntry("$id.png"))
                    zos.write(bytes)
                    zos.closeEntry()
                } else {
                    skippedFiles++
                }
                processedFiles++
                val progress = (processedFiles.toDouble() / totalFiles.toDouble()) * 100.0
                if (progress - lastProgressUpdate >= progressUpdateInterval || processedFiles == totalFiles) {
                    updateProgress(
                        jobId,
                        ZipType.SPRITES,
                        progress,
                        "Adding diff sprites... ($processedFiles/$totalFiles, skipped $skippedFiles)"
                    )
                    lastProgressUpdate = progress
                }
            }
        }

        updateProgress(jobId, ZipType.SPRITES, 100.0, "Complete", "/zip/download/$jobId")
    }

    private suspend fun createDiffTexturesZipFile(jobId: String, base: Int, rev: Int) = withContext(Dispatchers.IO) {
        activeJobs[jobId] ?: return@withContext

        updateProgress(jobId, ZipType.TEXTURES, 0.0, "Preparing textures list...")
        val combinedTextures = getTypedCombinedConfig("textures", rev)
        val textureEntries = combinedTextures.entries
            .mapNotNull { (textureId, snapshot) ->
                val fileId = (snapshot["fileId"]?.value as? Int) ?: return@mapNotNull null
                textureId to fileId
            }
            .sortedBy { it.first }
        val totalFiles = textureEntries.size.coerceAtLeast(1)
        var processedFiles = 0
        var skippedFiles = 0
        val (_, sourceRevById) = getCombinedSprites(config, base, rev)

        val zipFile = getZipFileLocation(jobId)
        zipFile.parentFile?.mkdirs()

        var lastProgressUpdate = 0.0
        val progressUpdateInterval = 1.0

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            textureEntries.forEach { (textureId, fileId) ->
                val sourceRev = sourceRevById[fileId] ?: rev
                val bytes = DiffBinaryCache.getDecodedRev(config, sourceRev)?.sprites?.get(fileId)
                if (bytes != null) {
                    zos.putNextEntry(ZipEntry("$textureId.png"))
                    zos.write(bytes)
                    zos.closeEntry()
                } else {
                    skippedFiles++
                }
                processedFiles++
                val progress = (processedFiles.toDouble() / totalFiles.toDouble()) * 100.0
                if (progress - lastProgressUpdate >= progressUpdateInterval || processedFiles == totalFiles) {
                    updateProgress(
                        jobId,
                        ZipType.TEXTURES,
                        progress,
                        "Adding textures... ($processedFiles/$totalFiles, skipped $skippedFiles)"
                    )
                    lastProgressUpdate = progress
                }
            }
        }

        updateProgress(jobId, ZipType.TEXTURES, 100.0, "Complete", "/zip/download/$jobId")
    }

    private fun getTypedCombinedConfig(type: String, upToRev: Int): Map<Int, DefinitionSnapshot> {
        val base = DiffBinaryCache.getDecodedRev(config, 1)?.configs?.get(type) ?: emptyMap()
        if (upToRev <= 1) return base
        val merged = base.toMutableMap()
        for (r in 2..upToRev) {
            val decoded = DiffBinaryCache.getDecodedRev(config, r) ?: continue
            val summary = decoded.manifest.configs[type] ?: continue
            summary.removed.forEach { id -> merged.remove(id) }
            val delta = decoded.configs[type] ?: continue
            summary.added.forEach { id -> delta[id]?.let { merged[id] = it } }
            summary.changed.forEach { id -> delta[id]?.let { merged[id] = it } }
        }
        return merged
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

