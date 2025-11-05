package dev.openrune.cache.extractor.osrs

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.BaseExtractor
import dev.openrune.cache.osrs.OsrsConfigsArchive
import dev.openrune.definition.Definition
import dev.openrune.definition.DefinitionCodec
import dev.openrune.filesystem.Cache
import java.io.File

abstract class ConfigExtractor<T : Definition>(
    config: ServerConfig,
    cache: Cache,
    val archive: Int,
    val codec: DefinitionCodec<T>?
) : BaseExtractor(config, cache) {

    init {
        enableDualData(OsrsConfigsArchive.entries.find { it.id == archive }?.displayName?.lowercase() ?: "config_$archive")
    }
    

    protected fun updateData(id: Int, webDataEntry: Any, definition: T) {
        updateData(id, webDataEntry, definition as Any)
    }
    
    override fun extract(index: Int, archive: Int, file: Int, data: ByteArray) {
        if (archive != this.archive) return
        
        try {
            val definition = codec?.loadData(file, data) ?: return
            extractDefinition(file, definition)
        } catch (e: Exception) {
            logger.warn("Error extracting config definition $archive:$file - ${e.message}")
        }

        stepProgress()
    }

    abstract fun extractDefinition(id: Int, definition: T)
}

