package dev.openrune.cache

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.BaseExtractor
import dev.openrune.cache.extractor.osrs.ConfigsExtractor
import dev.openrune.cache.extractor.osrs.GameValExtractor
import dev.openrune.cache.extractor.osrs.MapExtractor
import dev.openrune.cache.extractor.osrs.ModelsExtractor
import dev.openrune.cache.extractor.osrs.SpritesExtractor
import dev.openrune.cache.extractor.osrs.TexturesExtractor
import dev.openrune.cache.osrs.OsrsCacheIndex
import dev.openrune.cache.tools.GameType
import dev.openrune.filesystem.Cache

/**
 * Factory for creating extractors based on cache index
 */
object ExtractorFactory {
    
    /**
     * Create an extractor for a given index
     */
    fun createExtractor(index: Int, config: ServerConfig, cache: Cache): BaseExtractor? {
        return when (config.gameType) {
            GameType.OLDSCHOOL -> when (index) {
                OsrsCacheIndex.SPRITES.id -> SpritesExtractor(config, cache)
                OsrsCacheIndex.GAMEVALS.id -> GameValExtractor(config, cache)
                OsrsCacheIndex.MODELS.id -> ModelsExtractor(config, cache)
                OsrsCacheIndex.TEXTURES.id -> TexturesExtractor(config, cache)
                OsrsCacheIndex.CONFIGS.id -> ConfigsExtractor(config, cache)
                else -> null
            }
            else -> null
        }
    }
    
    /**
     * Get the processing order for indices (dependency order)
     */
    fun getProcessingOrder(): List<Int> = listOf(
        OsrsCacheIndex.SPRITES.id,
        OsrsCacheIndex.GAMEVALS.id,
        OsrsCacheIndex.MODELS.id,
        OsrsCacheIndex.TEXTURES.id,
        OsrsCacheIndex.CONFIGS.id
    )
    
    /**
     * Get processing order map for sorting
     */
    fun getProcessingOrderMap(): Map<Int, Int> = getProcessingOrder()
        .mapIndexed { index, id -> id to (index + 1) }
        .toMap()
}

