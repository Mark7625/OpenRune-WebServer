package dev.openrune.cache.extractor.osrs.configs

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.osrs.ConfigExtractor
import dev.openrune.cache.gameval.GameValHandler
import dev.openrune.cache.gameval.GameValHandler.lookup
import dev.openrune.definition.DefinitionCodec
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.definition.type.ItemType
import dev.openrune.definition.type.ObjectType
import dev.openrune.filesystem.Cache

data class ObjectsManifestEntry(
    val name: String,
    val gameVal: String?,
    val interactive : Boolean
)

class ObjectsExtractor(
    config: ServerConfig,
    cache: Cache,
    archive: Int,
    codec: DefinitionCodec<ObjectType>?
) : ConfigExtractor<ObjectType>(config, cache, archive, codec) {

    private val gameVals = GameValHandler.readGameVal(GameValGroupTypes.LOCTYPES, cache)

    override fun extractDefinition(id: Int, definition: ObjectType) {

        val webDataEntry = ObjectsManifestEntry(
            name = definition.name,
            gameVal = gameVals.lookup(id)?.name,
            interactive = definition.hasActions()
        )

        updateData(id, webDataEntry, definition)
    }
}