package dev.openrune.cache.extractor.osrs.configs

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.ConfigExtractor
import dev.openrune.cache.gameval.GameValHandler
import dev.openrune.cache.gameval.GameValHandler.lookup
import dev.openrune.definition.DefinitionCodec
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.definition.type.ItemType
import dev.openrune.filesystem.Cache

data class ItemManifestEntry(
    val name: String,
    val gameVal: String?,
    val noted: Boolean
)

class ItemExtractor(
    config: ServerConfig,
    cache: Cache,
    archive: Int,
    codec: DefinitionCodec<ItemType>?
) : ConfigExtractor<ItemType>(config, cache, archive, codec) {

    private val gameVals = GameValHandler.readGameVal(GameValGroupTypes.OBJTYPES, cache)

    override fun extractDefinition(id: Int, definition: ItemType) {
        val webDataEntry = ItemManifestEntry(
            name = definition.name,
            gameVal = gameVals.lookup(id)?.name,
            noted = definition.noted
        )
        
        updateData(id, webDataEntry, definition)
    }
}