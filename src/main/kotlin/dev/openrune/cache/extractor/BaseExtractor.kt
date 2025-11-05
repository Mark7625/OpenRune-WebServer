package dev.openrune.cache.extractor

import dev.openrune.ServerConfig
import dev.openrune.cache.CachePathHelper
import dev.openrune.filesystem.Cache
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import mu.KotlinLogging
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarBuilder
import me.tongfei.progressbar.ProgressBarStyle
import java.io.File
import javax.imageio.ImageIO

/**
 * Base class for extracting and saving cache data to disk
 */
abstract class BaseExtractor(
    protected val config: ServerConfig,
    protected val cache: Cache
) {
    protected val logger = KotlinLogging.logger {}
    protected val json = GsonBuilder().setPrettyPrinting().create()
    
    private var progressBar: ProgressBar? = null
    var sharedManifestProgressBar: ProgressBar? = null
    
    private var dualDataEnabled = false
    private var dualDataCategory: String? = null
    protected val webData = mutableMapOf<Int, Any>()
    protected val cacheData = mutableMapOf<Int, Any>()

    /**
     * Get the base extraction directory for this extractor
     * Format: ./cache/{gameType}/{environment}/{rev}/extracted/{category}/
     */
    protected fun getExtractionDir(category: String): File {
        val baseDir = CachePathHelper.getCacheDirectory(
            config.gameType,
            config.environment,
            config.revision
        )
        return baseDir.resolve("extracted").resolve(category)
    }

    /**
     * Get the file path for a specific extracted item
     * Format: ./cache/{gameType}/{environment}/{rev}/extracted/{category}/{subPath}
     */
    protected fun getExtractionPath(category: String, vararg pathSegments: String): File {
        val dir = getExtractionDir(category)
        return pathSegments.fold(dir) { file, segment -> file.resolve(segment) }
    }

    /**
     * Save JSON data to disk
     * Creates parent directories if needed
     */
    protected fun saveJson(data: Any, category: String, vararg pathSegments: String): File {
        val file = getExtractionPath(category, *pathSegments)
        file.parentFile?.mkdirs()
        file.writeText(json.toJson(data))
        return file
    }

    /**
     * Save JSON data to disk (compact, no pretty printing)
     */
    protected fun saveJsonCompact(data: Any, category: String, vararg pathSegments: String): File {
        val file = getExtractionPath(category, *pathSegments)
        val compactJson = GsonBuilder().create()
        file.parentFile?.mkdirs()
        file.writeText(compactJson.toJson(data))
        return file
    }

    /**
     * Save PNG image to disk
     */
    protected fun savePng(image: java.awt.image.BufferedImage, category: String, vararg pathSegments: String): File {
        val file = getExtractionPath(category, *pathSegments)
        file.parentFile?.mkdirs()
        ImageIO.write(image, "png", file)
        return file
    }

    /**
     * Save raw bytes to disk
     */
    protected fun saveBytes(data: ByteArray, category: String, vararg pathSegments: String): File {
        val file = getExtractionPath(category, *pathSegments)
        file.parentFile?.mkdirs()
        file.writeBytes(data)
        return file
    }

    /**
     * Save text content to disk
     */
    protected fun saveText(content: String, category: String, vararg pathSegments: String): File {
        val file = getExtractionPath(category, *pathSegments)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    /**
     * Get a unique key for tracking processed files
     */
    protected fun getProcessedKey(index: Int, archive: Int, file: Int): String {
        return "$index-$archive-$file"
    }

    /**
     * Abstract method to extract data for a specific index/archive/file combination
     * @param index The cache index
     * @param archive The archive ID within the index
     * @param file The file ID within the archive
     * @param data The raw file data to extract
     */
    abstract fun extract(index: Int, archive: Int, file: Int, data: ByteArray)

    /**
     * Optional: Extract entire archive
     * Override this if you want to handle batch extraction differently
     */
    open fun extractArchive(index: Int, archive: Int, files: Map<Int, ByteArray>) {
        files.forEach { (fileId, fileData) ->
            extract(index, archive, fileId, fileData)
        }
    }
    
    /**
     * Get the manifest file path for a given category
     */
    protected fun getManifestFile(category: String, filename: String = "manifest.json"): File {
        return getExtractionPath(category, filename)
    }

    /**
     * Save manifest to disk
     */
    protected fun saveManifest(data: Any, category: String, filename: String = "manifest.json"): File {
        val manifestFile = getManifestFile(category, filename)
        manifestFile.parentFile?.mkdirs()
        manifestFile.writeText(json.toJson(data))
        return manifestFile
    }
    
    /**
     * Initialize progress bar for extraction tracking
     * @param taskName Name of the task to display (e.g., "Extracting sprites")
     * @param max Maximum number of files to extract
     */
    fun initProgressBar(taskName: String, max: Long) {
        if (max > 0) {
            progressBar = ProgressBarBuilder()
                .setTaskName(taskName)
                .setInitialMax(max)
                .setStyle(ProgressBarStyle.UNICODE_BLOCK)
                .setUpdateIntervalMillis(100)
                .showSpeed()
                .build()
        }
    }
    
    /**
     * Step the progress bar by one
     * Should be called in extract() method after processing each file
     */
    protected fun stepProgress() {
        progressBar?.step()
    }
    
    /**
     * Step the progress bar to a specific value
     * @param current The current progress value
     */
    protected fun stepProgressTo(current: Long) {
        progressBar?.stepTo(current)
    }
    
    /**
     * Close the progress bar
     * Should be called when extraction is complete (e.g., in end() method)
     */
    protected fun closeProgressBar() {
        progressBar?.close()
        progressBar = null
    }
    
    /**
     * Enable dual-data support (web + cache data)
     * Should be called in init block or constructor
     * @param categoryName Category name for data files (e.g., "sprites", "textures", "items")
     */
    protected fun enableDualData(categoryName: String) {
        dualDataEnabled = true
        dualDataCategory = categoryName
        loadDualData(categoryName)
    }
    
    /**
     * Update both web and cache data for a given ID
     * @param id The ID to update
     * @param webDataEntry The web data (simplified)
     * @param cacheDataEntry The cache data (full)
     */
    protected fun updateData(id: Int, webDataEntry: Any, cacheDataEntry: Any) {
        if (!dualDataEnabled) {
            logger.warn("updateData called but dual data is not enabled. Call enableDualData() first.")
            return
        }
        webData[id] = webDataEntry
        cacheData[id] = cacheDataEntry
    }
    
    /**
     * Check if an ID exists in both data maps (skip if already processed)
     */
    protected fun isDataProcessed(id: Int): Boolean {
        return dualDataEnabled && webData.containsKey(id) && cacheData.containsKey(id)
    }
    
    /**
     * Check if this extractor has dual data enabled (will save web + cache manifests)
     */
    fun hasDualData(): Boolean {
        return dualDataEnabled
    }
    
    /**
     * Get the count of manifest files that will actually be saved (0, 1, or 2)
     * Returns 0 if no data, 1 if only web or cache data, 2 if both
     */
    fun getManifestSaveCount(): Int {
        if (!dualDataEnabled) return 0
        
        var count = 0
        if (webData.isNotEmpty()) count++
        if (cacheData.isNotEmpty()) count++
        return count
    }
    
    private fun loadDualData(categoryName: String) {
        loadWebData(categoryName)
        loadCacheData(categoryName)
    }
    
    private fun loadWebData(categoryName: String) {
        val dataFile = getManifestFile(categoryName, "data_web.json")

        if (!dataFile.exists()) {
            dataFile.parentFile?.mkdirs()
            dataFile.writeText("{}")
            return
        }

        try {
            val content = dataFile.readText()
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val loaded: Map<String, Any> = json.fromJson(content, type)
            
            loaded.forEach { (key, entry) ->
                key.toIntOrNull()?.let { id ->
                    webData[id] = entry
                }
            }
            
            logger.debug("Loaded ${webData.size} entries from $categoryName web data")
        } catch (e: Exception) {
            logger.warn("Failed to load $categoryName web data: ${e.message}")
        }
    }
    
    private fun loadCacheData(categoryName: String) {
        val dataFile = getManifestFile(categoryName, "data_cache.json")

        if (!dataFile.exists()) {
            dataFile.parentFile?.mkdirs()
            dataFile.writeText("{}")
            return
        }

        try {
            val content = dataFile.readText()
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val loaded: Map<String, Any> = json.fromJson(content, type)
            
            loaded.forEach { (key, entry) ->
                key.toIntOrNull()?.let { id ->
                    cacheData[id] = entry
                }
            }
            
            logger.debug("Loaded ${cacheData.size} entries from $categoryName cache data")
        } catch (e: Exception) {
            logger.warn("Failed to load $categoryName cache data: ${e.message}")
        }
    }

    private fun saveDualData() {
        val categoryName = dualDataCategory ?: return
        
        val hasWebData = webData.isNotEmpty()
        val hasCacheData = cacheData.isNotEmpty()
        
        if (!hasWebData && !hasCacheData) {
            return
        }
        
        try {
            if (hasWebData) {
                val dataForJson = webData.mapKeys { it.key.toString() }
                saveManifest(dataForJson, categoryName, "data_web.json")
                sharedManifestProgressBar?.step()
            }
            
            if (hasCacheData) {
                val dataForJson = cacheData.mapKeys { it.key.toString() }
                saveManifest(dataForJson, categoryName, "data_cache.json")
                sharedManifestProgressBar?.step()
            }
        } catch (e: Exception) {
            logger.error("Failed to save $categoryName data: ${e.message}", e)
        }
    }
    
    /**
     * Called after all extraction is complete
     * Override this to perform final operations like saving data
     * Note: This base implementation closes the progress bar and saves dual data if enabled
     */
    open fun end() {
        if (dualDataEnabled) {
            saveDualData()
        }

        closeProgressBar()
    }
    
    /**
     * Clear extraction data to free memory
     * Called after extraction is complete and data has been saved
     */
    open fun clearExtractionData() {
        // Clear web and cache data maps to free memory
        webData.clear()
        cacheData.clear()
    }
}

