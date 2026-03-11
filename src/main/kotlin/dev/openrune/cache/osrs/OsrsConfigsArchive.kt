package dev.openrune.cache.osrs

import dev.openrune.ServerConfig
import dev.openrune.cache.CacheArchive
import dev.openrune.definition.DefinitionCodec
import dev.openrune.definition.codec.ItemCodec
import dev.openrune.definition.codec.NPCCodec
import dev.openrune.definition.codec.ObjectCodec
import dev.openrune.definition.codec.OverlayCodec
import dev.openrune.definition.codec.SequenceCodec
import dev.openrune.definition.codec.SpotAnimCodec
import dev.openrune.definition.codec.UnderlayCodec
import dev.openrune.filesystem.Cache

enum class OsrsConfigsArchive(
    override val id: Int,
    override val displayName: String,
    override val shouldChecksum: Boolean = true
) : CacheArchive {
    UNDERLAY(1, "Underlay", true),
    IDENTKIT(3, "IdentKit", true),
    OVERLAY(4, "Overlay", true),
    INV(5, "Inv", true),
    OBJECT(6, "Object", true),
    ENUM(8, "Enum", true),
    NPC(9, "NPC", true),
    ITEM(10, "Item", true),
    PARAMS(11, "Params", true),
    SEQUENCE(12, "Sequence", true),
    SPOTANIM(13, "SpotAnim", true),
    VARBIT(14, "VarBit", true),
    VARCLIENTSTRING(15, "VarClientString", true),
    VARPLAYER(16, "VarPlayer", true),
    VARCLIENT(19, "VarClient", true),
    HITSPLAT(32, "Hitsplat", true),
    HEALTHBAR(33, "Healthbar", true),
    STRUCT(34, "Struct", true),
    AREA(35, "Area", true),
    DBROW(38, "DBRow", true),
    DBTABLE(39, "DBTable", true),
    WATERTYPE(73, "WaterType", true);

    companion object {
        fun getArchivesToCrc(): Set<Int> {
            return entries.filter { it.shouldChecksum }.map { it.id }.toSet()
        }
    }
}