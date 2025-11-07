package dev.openrune.cache.osrs

import dev.openrune.ServerConfig
import dev.openrune.cache.CacheArchive
import dev.openrune.cache.extractor.osrs.ConfigExtractor
import dev.openrune.cache.extractor.osrs.configs.ItemExtractor
import dev.openrune.cache.extractor.osrs.configs.NPCExtractor
import dev.openrune.cache.extractor.osrs.configs.ObjectsExtractor
import dev.openrune.cache.extractor.osrs.configs.OverlayExtractor
import dev.openrune.cache.extractor.osrs.configs.SequencesExtractor
import dev.openrune.cache.extractor.osrs.configs.SpotAnimsExtractor
import dev.openrune.cache.extractor.osrs.configs.UnderlayExtractor
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
    override val shouldChecksum: Boolean = true,
    private val codecClass: Class<*>? = null,
    private val extractorClass: Class<out ConfigExtractor<*>>? = null
) : CacheArchive {
    UNDERLAY(1, "Underlay", true, UnderlayCodec::class.java, UnderlayExtractor::class.java),
    IDENTKIT(3, "IdentKit", true),
    OVERLAY(4, "Overlay", true, OverlayCodec::class.java, OverlayExtractor::class.java),
    INV(5, "Inv", true),
    OBJECT(6, "Object", true, ObjectCodec::class.java, ObjectsExtractor::class.java),
    ENUM(8, "Enum", true),
    NPC(9, "NPC", true,NPCCodec::class.java, NPCExtractor::class.java),
    ITEM(10, "Item", true, ItemCodec::class.java, ItemExtractor::class.java),
    PARAMS(11, "Params", true),
    SEQUENCE(12, "Sequence", true, SequenceCodec::class.java, SequencesExtractor::class.java),
    SPOTANIM(13, "SpotAnim", true, SpotAnimCodec::class.java, SpotAnimsExtractor::class.java),
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

    fun getCodecClass(): Class<*>? = codecClass
    fun getExtractorClass(): Class<out ConfigExtractor<*>>? = extractorClass
    
    fun createExtractor(config: ServerConfig, cache: Cache): ConfigExtractor<*>? {
        return extractorClass?.let { clazz ->
            try {
                val codec = createCodec(codecClass, config)
                val constructor = clazz.getConstructor(ServerConfig::class.java, Cache::class.java, Int::class.java, DefinitionCodec::class.java)
                constructor.newInstance(config, cache, id, codec) as ConfigExtractor<*>
            } catch (e: NoSuchMethodException) {
                println("ERROR: Failed to create extractor for ${displayName}: Constructor not found. ${e.message}")
                e.printStackTrace()
                null
            } catch (e: Exception) {
                println("ERROR: Failed to create extractor for ${displayName}: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }
    
    @Suppress("UNCHECKED_CAST")
    private fun createCodec(codecClass: Class<*>?, config: ServerConfig): DefinitionCodec<*>? {
        if (codecClass == null) return null
        
        return try {
            val constructor = codecClass.getConstructor(Int::class.java)
            constructor.newInstance(config.revision) as DefinitionCodec<*>
        } catch (e: NoSuchMethodException) {
            try {
                val constructor = codecClass.getConstructor()
                constructor.newInstance() as DefinitionCodec<*>
            } catch (e2: Exception) {
                println("ERROR: Failed to instantiate codec ${codecClass.simpleName}: ${e2.message}")
                e2.printStackTrace()
                null
            }
        } catch (e: Exception) {
            println("ERROR: Failed to instantiate codec ${codecClass.simpleName}: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    companion object {
        fun getArchivesToCrc(): Set<Int> {
            return entries.filter { it.shouldChecksum }.map { it.id }.toSet()
        }

        fun getArchivesToCrc(archives: Set<OsrsConfigsArchive>): Set<Int> {
            return archives.filter { it.shouldChecksum }.map { it.id }.toSet()
        }
    }
}