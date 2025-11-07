package dev.openrune.cache.extractor.osrs

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.BaseExtractor
import dev.openrune.cache.osrs.OsrsConfigsArchive
import dev.openrune.filesystem.Cache
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class ConfigsExtractor(config: ServerConfig, cache: Cache) : BaseExtractor(config, cache) {
    
    private val extractors = mutableMapOf<Int, ConfigExtractor<*>>()
    
    /**
     * Get all child extractors that have dual data enabled
     */
    fun getChildExtractorsWithDualData(): List<BaseExtractor> {
        return extractors.values.filter { it.hasDualData() }
    }
    
    /**
     * Get all child extractors
     */
    fun getChildExtractors(): List<BaseExtractor> {
        return extractors.values.toList()
    }
    
    init {
        OsrsConfigsArchive.entries.forEach { archive ->
            if (archive.getExtractorClass() != null) {
                archive.createExtractor(config, cache)?.let { extractor ->
                    extractors[archive.id] = extractor
                    logger.debug("Registered extractor for ${archive.displayName} (archive ${archive.id})")
                } ?: run {
                    logger.warn("Failed to create extractor for ${archive.displayName} (archive ${archive.id})")
                }
            }
        }
        logger.info("Initialized ${extractors.size} config extractors")
    }
    
    override fun extract(index: Int, archive: Int, file: Int, data: ByteArray) {
        extractors[archive]?.extract(index, archive, file,data) ?: run {
            stepProgress()
        }
    }
    
    override fun end() {
        // Pass shared manifest progress bar to child extractors
        extractors.values.forEach { childExtractor ->
            childExtractor.sharedManifestProgressBar = sharedManifestProgressBar
        }
        extractors.values.forEach { it.end() }
        super.end()
    }
}
