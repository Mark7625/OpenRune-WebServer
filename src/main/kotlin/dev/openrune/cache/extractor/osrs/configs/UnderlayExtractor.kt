package dev.openrune.cache.extractor.osrs.configs

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.ConfigExtractor
import dev.openrune.definition.DefinitionCodec
import dev.openrune.definition.type.OverlayType
import dev.openrune.definition.type.UnderlayType
import dev.openrune.filesystem.Cache

data class UnderlayManifestEntry(val rgb: Int)

class UnderlayExtractor(
    config: ServerConfig,
    cache: Cache,
    archive: Int,
    codec: DefinitionCodec<UnderlayType>?
) : ConfigExtractor<UnderlayType>(config, cache, archive, codec) {

    override fun extractDefinition(id: Int, definition: UnderlayType) {
        val webDataEntry = UnderlayManifestEntry(definition.rgb)
        updateData(id, webDataEntry, definition)
    }
}