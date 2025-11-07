package dev.openrune.cache.extractor.osrs.configs

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.ConfigExtractor
import dev.openrune.definition.DefinitionCodec
import dev.openrune.definition.type.OverlayType
import dev.openrune.filesystem.Cache

data class OverlaysManifestEntry(
    val primaryRgb: Int,
    val secondaryRgb: Int,
    val texture: Int,
    val water: Int,
    val hideUnderlay: Boolean
)

class OverlayExtractor(
    config: ServerConfig,
    cache: Cache,
    archive: Int,
    codec: DefinitionCodec<OverlayType>?
) : ConfigExtractor<OverlayType>(config, cache, archive, codec) {

    override fun extractDefinition(id: Int, definition: OverlayType) {

        val webDataEntry = OverlaysManifestEntry(
            definition.primaryRgb,
            definition.secondaryRgb,
            definition.texture,
            definition.water,
            definition.hideUnderlay
        )
        
        updateData(id, webDataEntry, definition)
    }
}