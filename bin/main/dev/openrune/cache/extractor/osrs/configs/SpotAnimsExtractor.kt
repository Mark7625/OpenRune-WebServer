package dev.openrune.cache.extractor.osrs.configs

import dev.openrune.ServerConfig
import dev.openrune.cache.CacheManager
import dev.openrune.cache.extractor.osrs.ConfigExtractor
import dev.openrune.cache.gameval.GameValHandler
import dev.openrune.cache.gameval.GameValHandler.lookup
import dev.openrune.definition.DefinitionCodec
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.definition.type.SpotAnimType
import dev.openrune.filesystem.Cache

data class SpotAnimManifestEntry(
    val gameVal: String,
    val tickDuration : Int
)

class SpotAnimsExtractor(
    config: ServerConfig,
    cache: Cache,
    archive: Int,
    codec: DefinitionCodec<SpotAnimType>?
) : ConfigExtractor<SpotAnimType>(config, cache, archive, codec) {

    private val gameVals = GameValHandler.readGameVal(GameValGroupTypes.SPOTTYPES, cache)

    override fun extractDefinition(id: Int, definition: SpotAnimType) {
        val webDataEntry = SpotAnimManifestEntry(
            gameVal = gameVals.lookup(id)?.name!!,
            tickDuration = CacheManager.getAnim(definition.animationId)?.lengthInCycles?: -1
        )
        
        updateData(id, webDataEntry, definition)
    }
}