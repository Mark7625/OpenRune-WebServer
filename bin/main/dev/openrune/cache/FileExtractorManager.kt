package dev.openrune.cache

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.BaseExtractor
import dev.openrune.cache.extractor.osrs.ConfigExtractor
import dev.openrune.cache.extractor.osrs.ConfigsExtractor
import dev.openrune.cache.osrs.OsrsCacheIndex
import dev.openrune.cache.osrs.OsrsConfigsArchive
import dev.openrune.filesystem.Cache
import kotlinx.coroutines.delay
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarBuilder
import me.tongfei.progressbar.ProgressBarStyle
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Manages file extraction process including progress bars and manifest saving
 */
class FileExtractorManager(
    private val config: ServerConfig,
    private val testingMode: Boolean,
    private val verifiedIndices: Set<Int>
) {
    
    companion object {
        private const val PROGRESS_BAR_UPDATE_INTERVAL_MS = 100
        private const val PROGRESS_BAR_CLOSE_DELAY_MS = 100L
    }
    
    /**
     * Create extractors for all needed indices
     */
    fun createExtractors(indicesNeedingExtractors: List<Int>, cache: Cache): Map<Int, BaseExtractor> {
        val extractorCache = mutableMapOf<Int, BaseExtractor>()
        val processingOrder = ExtractorFactory.getProcessingOrder()
        
        // Create extractors in dependency order first
        processingOrder.forEach { index ->
            if (index in indicesNeedingExtractors) {
                ExtractorFactory.createExtractor(index, config, cache)?.let { extractor ->
                    extractorCache[index] = extractor
                }
            }
        }
        
        // Handle any other indices not in the predefined order
        indicesNeedingExtractors.forEach { index ->
            if (index !in extractorCache) {
                ExtractorFactory.createExtractor(index, config, cache)?.let { extractor ->
                    extractorCache[index] = extractor
                }
            }
        }
        
        return extractorCache
    }
    
    /**
     * Filter files based on testing mode
     */
    fun filterFilesForTesting(files: List<FileChecksum>): List<FileChecksum> {
        if (!testingMode || verifiedIndices.isEmpty()) {
            return files
        }
        
        val toProcess = files.filter { it.index !in verifiedIndices }
        val skippedCount = files.size - toProcess.size
        
        if (skippedCount > 0) {
            val skippedIndices = files.filter { it.index in verifiedIndices }
                .map { it.index }
                .distinct()
            logger.info("Testing mode: Skipping extraction for $skippedCount files from verified indices: ${skippedIndices.joinToString(", ")}")
        }
        
        return toProcess
    }
    
    /**
     * Sort files by processing order
     */
    fun sortFiles(files: List<FileChecksum>): List<FileChecksum> {
        val processingOrderMap = ExtractorFactory.getProcessingOrderMap()
        return files.sortedBy { processingOrderMap[it.index] ?: 999 }
    }
    
    /**
     * Initialize progress bars for extractors
     */
    fun initializeProgressBars(
        extractors: Map<Int, BaseExtractor>,
        sortedFiles: List<FileChecksum>
    ) {
        val fileCountsByIndex = sortedFiles.groupingBy { it.index }.eachCount()
        
        extractors.forEach { (index, extractor) ->
            if (extractor is ConfigsExtractor) {
                initializeConfigExtractorProgressBars(extractor, index, sortedFiles)
            } else {
                val fileCount = fileCountsByIndex[index]?.toLong() ?: 0L
                if (fileCount > 0) {
                    val extractorName = getExtractorName(index)
                    extractor.initProgressBar(extractorName, fileCount)
                }
            }
        }
    }
    
    private fun initializeConfigExtractorProgressBars(
        extractor: ConfigsExtractor,
        index: Int,
        sortedFiles: List<FileChecksum>
    ) {
        extractor.getChildExtractors().forEach { childExtractor ->
            val childArchive = (childExtractor as? ConfigExtractor<*>)?.archive ?: return@forEach
            val childFileCount = sortedFiles.count { 
                it.index == index && it.archive == childArchive 
            }.toLong()
            
            if (childFileCount > 0) {
                val archiveEnum = OsrsConfigsArchive.entries.find { it.id == childArchive }
                val extractorName = if (archiveEnum != null) {
                    "Extracting ${archiveEnum.displayName.lowercase()}"
                } else {
                    "Extracting config archive $childArchive"
                }
                childExtractor.initProgressBar(extractorName, childFileCount)
            }
        }
    }
    
    private fun getExtractorName(index: Int): String {
        val enumEntry = OsrsCacheIndex.entries.find { it.id == index }
        return if (enumEntry != null) {
            "Extracting ${enumEntry.displayName.lowercase()}"
        } else {
            "Extracting index $index"
        }
    }
    
    /**
     * Count total manifest files that will be saved
     */
    fun countManifestFiles(extractors: Map<Int, BaseExtractor>): Int {
        var total = 0
        extractors.values.forEach { extractor ->
            if (extractor is ConfigsExtractor) {
                extractor.getChildExtractorsWithDualData().forEach { childExtractor ->
                    total += childExtractor.getManifestSaveCount()
                }
            } else {
                total += extractor.getManifestSaveCount()
            }
        }
        return total
    }
    
    /**
     * Create shared manifest progress bar
     */
    fun createManifestProgressBar(totalManifestFiles: Int): ProgressBar? {
        return if (totalManifestFiles > 0) {
            ProgressBarBuilder()
                .setTaskName("Saving manifests")
                .setInitialMax(totalManifestFiles.toLong())
                .setStyle(ProgressBarStyle.UNICODE_BLOCK)
                .setUpdateIntervalMillis(PROGRESS_BAR_UPDATE_INTERVAL_MS)
                .build()
        } else {
            null
        }
    }
    
    /**
     * Set shared manifest progress bar on all extractors
     */
    fun setSharedManifestProgressBar(extractors: Map<Int, BaseExtractor>, progressBar: ProgressBar?) {
        extractors.values.forEach { extractor ->
            extractor.sharedManifestProgressBar = progressBar
            if (extractor is ConfigsExtractor) {
                extractor.getChildExtractors().forEach { childExtractor ->
                    childExtractor.sharedManifestProgressBar = progressBar
                }
            }
        }
    }
    
    /**
     * Close all extractors and cleanup
     */
    suspend fun closeExtractors(extractors: Map<Int, BaseExtractor>, manifestProgressBar: ProgressBar?) {
        extractors.values.forEach { extractor ->
            try {
                extractor.end()
            } catch (e: Exception) {
                logger.error("Error calling end() on extractor: ${e.message}", e)
            }
        }
        
        manifestProgressBar?.close()
        delay(PROGRESS_BAR_CLOSE_DELAY_MS)
    }
    
    /**
     * Clear extractor data structures to free memory
     */
    fun clearExtractorData(extractors: Map<Int, BaseExtractor>) {
        extractors.values.forEach { extractor ->
            try {
                extractor.clearExtractionData()
            } catch (e: Exception) {
                logger.warn("Error clearing extractor data: ${e.message}")
            }
        }
    }
}

