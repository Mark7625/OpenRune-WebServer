package dev.openrune.cache.extractor.osrs.configs

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.ConfigExtractor
import dev.openrune.cache.gameval.GameValHandler
import dev.openrune.cache.gameval.GameValHandler.lookup
import dev.openrune.definition.DefinitionCodec
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.definition.type.ItemType
import dev.openrune.definition.type.NpcType
import dev.openrune.filesystem.Cache

data class NpcManifestEntry(
    val name: String,
    val gameVal: String
)

class NPCExtractor(
    config: ServerConfig,
    cache: Cache,
    archive: Int,
    codec: DefinitionCodec<NpcType>?
) : ConfigExtractor<NpcType>(config, cache, archive, codec) {

    private val gameVals = GameValHandler.readGameVal(GameValGroupTypes.NPCTYPES, cache)

    override fun extractDefinition(id: Int, definition: NpcType) {
        val webDataEntry = NpcManifestEntry(
            name = definition.name,
            gameVal = gameVals.lookup(id)?.name!!
        )
        
        updateData(id, webDataEntry, definition)
    }
}