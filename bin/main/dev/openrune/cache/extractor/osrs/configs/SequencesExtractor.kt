package dev.openrune.cache.extractor.osrs.configs

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.ConfigExtractor
import dev.openrune.cache.gameval.GameValHandler
import dev.openrune.cache.gameval.GameValHandler.lookup
import dev.openrune.definition.DefinitionCodec
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.definition.type.SequenceType
import dev.openrune.filesystem.Cache

data class SequenceManifestEntry(
    val gameVal: String,
    val tickDuration : Int
)

class SequencesExtractor(
    config: ServerConfig,
    cache: Cache,
    archive: Int,
    codec: DefinitionCodec<SequenceType>?
) : ConfigExtractor<SequenceType>(config, cache, archive, codec) {

    private val gameVals = GameValHandler.readGameVal(GameValGroupTypes.SEQTYPES, cache)

    override fun extractDefinition(id: Int, definition: SequenceType) {
        val webDataEntry = SequenceManifestEntry(
            gameVal = gameVals.lookup(id)?.name!!,
            tickDuration = definition.lengthInCycles
        )
        
        updateData(id, webDataEntry, definition)
    }
}