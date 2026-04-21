package dev.openrune.cache.diff

import dev.openrune.definition.GameValGroupTypes
import dev.openrune.definition.Recolourable
import dev.openrune.definition.type.InventoryType
import dev.openrune.definition.type.ItemType
import dev.openrune.definition.type.EnumType
import dev.openrune.definition.type.HealthBarType
import dev.openrune.definition.type.MapElementType
import dev.openrune.definition.type.NpcType
import dev.openrune.definition.type.ObjectType
import dev.openrune.definition.type.OverlayType
import dev.openrune.definition.type.ParamType
import dev.openrune.definition.type.SequenceType
import dev.openrune.definition.type.SpotAnimType
import dev.openrune.definition.type.StructType
import dev.openrune.definition.type.TextureType
import dev.openrune.definition.type.UnderlayType
import dev.openrune.definition.type.VarBitType
import dev.openrune.definition.type.VarClanType
import dev.openrune.definition.type.VarClientType
import dev.openrune.definition.type.VarpType
import dev.openrune.definition.type.WorldEntityType
import dev.openrune.definition.type.WorldMapAreaType
import java.util.LinkedHashMap
import kotlin.reflect.KProperty1

enum class NavCategory { ARCHIVE, CONFIG }

enum class PROPTYPE { COLOUR, SPRITE, TEXTURE }

enum class SearchMode(val wireName: String) {
    NAME("name"),
    REGEX("regex"),
}

sealed class ConfigDiffType<T>(
    val fileName: String,
    val sectionId: String = fileName,
    val navLabel: String? = null,
    val navGamevalType: GameValGroupTypes? = null,
    setup: TypeSetup<T>.() -> Unit = {},
) {
    class TypeSetup<T> {
        data class PropDescriptor(val type: PROPTYPE)

        internal val customKeys = mutableMapOf<String, String>()
        internal val ignoredProps = mutableSetOf<String>()
        internal val gamevalBindings = mutableMapOf<String, GameValGroupTypes>()
        internal val colorFields = mutableSetOf<String>()
        internal val propDescriptors = mutableMapOf<String, PropDescriptor>()
        internal val tableColumnOrder = mutableListOf<List<String>>()
        internal val searchFieldByMode = mutableMapOf<SearchMode, String>()

        fun key(outputKey: String, propName: String) { customKeys[propName] = outputKey }
        fun key(prop: KProperty1<*, *>, outputKey: String) { customKeys[prop.name] = outputKey }
        fun ignore(prop: KProperty1<*, *>) { ignoredProps.add(prop.name) }
        fun gameval(prop: KProperty1<*, *>, group: GameValGroupTypes) { gamevalBindings[prop.name] = group }
        fun color(prop: KProperty1<*, *>) {
            colorFields.add(prop.name)
            prop(PROPTYPE.COLOUR, prop)
        }
        fun prop(type: PROPTYPE, prop: KProperty1<*, *>) {
            propDescriptors[prop.name] = PropDescriptor(type = type)
        }
        /** Ordered table columns (displayName -> backing field). */
        fun inTable(vararg pairs: Pair<String, KProperty1<*, *>>) {
            pairs.forEach { (explicitName, prop) ->
                tableColumnOrder.add(listOf(explicitName, prop.name))
            }
        }

        fun searchBy(field: KProperty1<*, *>, vararg modes: SearchMode) {
            val targetModes = if (modes.isEmpty()) SearchMode.entries else modes.asList()
            targetModes.forEach { mode -> searchFieldByMode[mode] = field.name }
        }

        fun recolourable() {
            prop(PROPTYPE.COLOUR, Recolourable::originalColours)
            prop(PROPTYPE.COLOUR, Recolourable::modifiedColours)
            prop(PROPTYPE.TEXTURE, Recolourable::originalTextureColours)
            prop(PROPTYPE.TEXTURE, Recolourable::modifiedTextureColours)
        }
    }

    val typeSetup: TypeSetup<T> = TypeSetup<T>().also { setup(it) }

    /**
     * Returns frontend render metadata keyed by serialized output field name.
     * Output names respect custom key mappings (e.g. primaryRgb -> colour).
     */
    fun fieldProps(): Map<String, String> {
        val setup = typeSetup
        val out = LinkedHashMap<String, String>()
        setup.propDescriptors.forEach { (propName, descriptor) ->
            val outputKey = setup.customKeys[propName] ?: propName
            out[outputKey] = descriptor.type.name.lowercase()
        }
        return out
    }

    /** Ordered table columns; each entry is a list of acceptable aliases for one logical column. */
    fun tableColumns(): List<List<String>> {
        val setup = typeSetup
        val out = ArrayList<List<String>>()
        val seen = HashSet<String>()
        setup.tableColumnOrder.forEach { candidates ->
            val resolved = candidates
                .map { candidate -> setup.customKeys[candidate] ?: candidate }
                .filter { it.isNotBlank() }
                .distinct()
            if (resolved.isEmpty()) return@forEach
            val fp = resolved.joinToString("\u0001")
            if (seen.add(fp)) out.add(resolved)
        }
        return out
    }

    /** Search mode to backing field mapping (name/regex). Empty unless explicitly configured. */
    fun searchFields(): Map<String, String> {
        val setup = typeSetup
        return setup.searchFieldByMode.entries.associate { (mode, field) ->
            mode.wireName to (setup.customKeys[field] ?: field)
        }
    }

    open val pathSegment: String get() = fileName

    data object INV : ConfigDiffType<InventoryType>(
        fileName = "inv",
        navLabel = "Inventories",
        navGamevalType = GameValGroupTypes.INVTYPES,
        setup = {
            inTable("size" to InventoryType::size)
        }
    )

    data object OVERLAY : ConfigDiffType<OverlayType>(
        fileName = "overlay",
        setup = {
            key(OverlayType::hideUnderlay, "occlude")
            key(OverlayType::water, "water")
            key(OverlayType::primaryRgb, "colour")
            key(OverlayType::secondaryRgb, "mapcolour")
            inTable(
                "colour" to OverlayType::primaryRgb,
                "mapcolour" to OverlayType::secondaryRgb,
                "texture" to OverlayType::texture,
                "occlude" to OverlayType::hideUnderlay,
                "water" to OverlayType::water,
            )
            prop(PROPTYPE.TEXTURE, OverlayType::texture)
            color(OverlayType::primaryRgb)
            color(OverlayType::secondaryRgb)
            ignore(OverlayType::hue)
            ignore(OverlayType::saturation)
            ignore(OverlayType::lightness)
            ignore(OverlayType::secondaryHue)
            ignore(OverlayType::secondarySaturation)
            ignore(OverlayType::secondaryLightness)
        }
    ) {
        override val pathSegment: String = "overlays"
    }

    data object UNDERLAY : ConfigDiffType<UnderlayType>(
        fileName = "underlay",
        setup = {
            key(UnderlayType::rgb, "rgb")
            inTable(
                "rgb" to UnderlayType::rgb,
            )
            color(UnderlayType::rgb)
            ignore(UnderlayType::hue)
            ignore(UnderlayType::saturation)
            ignore(UnderlayType::lightness)
            ignore(UnderlayType::hueMultiplier)
            ignore(UnderlayType::rawHue)
        }
    ) {
        override val pathSegment: String = "underlays"
    }

    data object TEXTURES : ConfigDiffType<TextureType>(
        fileName = "textures",
        setup = {
            key(TextureType::averageRgb, "averageRgb")
            inTable(
                "isTransparent" to TextureType::isTransparent,
                "averageRgb" to TextureType::averageRgb,
                "animationDirection" to TextureType::animationDirection,
                "animationSpeed" to TextureType::animationSpeed,
            )
            color(TextureType::averageRgb)
        }
    )

    data object NPCS : ConfigDiffType<NpcType>(
        fileName = "npcs",
        navLabel = "NPCs",
        navGamevalType = GameValGroupTypes.NPCTYPES,
        setup = {
            inTable("name" to NpcType::name)
            searchBy(NpcType::name)
            recolourable()
            gameval(NpcType::standAnim, GameValGroupTypes.SEQTYPES)
            gameval(NpcType::rotateLeftAnim, GameValGroupTypes.SEQTYPES)
            gameval(NpcType::rotateRightAnim, GameValGroupTypes.SEQTYPES)
            gameval(NpcType::walkAnim, GameValGroupTypes.SEQTYPES)
            gameval(NpcType::rotateBackAnim, GameValGroupTypes.SEQTYPES)
            gameval(NpcType::walkLeftAnim, GameValGroupTypes.SEQTYPES)
            gameval(NpcType::walkRightAnim, GameValGroupTypes.SEQTYPES)
            gameval(NpcType::runSequence, GameValGroupTypes.SEQTYPES)
            gameval(NpcType::runBackSequence, GameValGroupTypes.SEQTYPES)
            gameval(NpcType::runRightSequence, GameValGroupTypes.SEQTYPES)
            gameval(NpcType::runLeftSequence, GameValGroupTypes.SEQTYPES)
            gameval(NpcType::crawlSequence, GameValGroupTypes.SEQTYPES)
            gameval(NpcType::crawlBackSequence, GameValGroupTypes.SEQTYPES)
            gameval(NpcType::crawlRightSequence, GameValGroupTypes.SEQTYPES)
            gameval(NpcType::crawlLeftSequence, GameValGroupTypes.SEQTYPES)

            gameval(NpcType::multiVarBit, GameValGroupTypes.VARBITTYPES)
            gameval(NpcType::multiVarp, GameValGroupTypes.VARPTYPES)
            gameval(NpcType::multiDefault, GameValGroupTypes.NPCTYPES)
            gameval(NpcType::transforms, GameValGroupTypes.NPCTYPES)

            gameval(NpcType::headIconGraphics, GameValGroupTypes.SPRITETYPES)
            gameval(NpcType::headIconIndexes, GameValGroupTypes.SPRITETYPES)

            gameval(NpcType::headIconGraphics, GameValGroupTypes.SPRITETYPES)
            gameval(NpcType::headIconIndexes, GameValGroupTypes.SPRITETYPES)

        }
    )

    data object ITEMS : ConfigDiffType<ItemType>(
        fileName = "items",
        navGamevalType = GameValGroupTypes.OBJTYPES,
        setup = {
            inTable(
                "name" to ItemType::name,
                "noted" to ItemType::noted,
            )
            searchBy(ItemType::name)
            recolourable()
            gameval(ItemType::countObj, GameValGroupTypes.OBJTYPES)
            gameval(ItemType::noteLinkId, GameValGroupTypes.OBJTYPES)
            gameval(ItemType::noteTemplateId, GameValGroupTypes.OBJTYPES)
            gameval(ItemType::unnotedId, GameValGroupTypes.OBJTYPES)
            gameval(ItemType::notedId, GameValGroupTypes.OBJTYPES)
            gameval(ItemType::placeholderLink, GameValGroupTypes.OBJTYPES)
            gameval(ItemType::placeholderTemplate, GameValGroupTypes.OBJTYPES)
        }
    )

    data object OBJECTS : ConfigDiffType<ObjectType>(
        fileName = "objects",
        navGamevalType = GameValGroupTypes.LOCTYPES,
        setup = {
            inTable("name" to ObjectType::name)
            searchBy(ObjectType::name)
            recolourable()
            gameval(ObjectType::animationId, GameValGroupTypes.SEQTYPES)
            gameval(ObjectType::multiVarBit, GameValGroupTypes.VARBITTYPES)
            gameval(ObjectType::multiVarp, GameValGroupTypes.VARPTYPES)
            gameval(ObjectType::multiDefault, GameValGroupTypes.LOCTYPES)
            gameval(ObjectType::transforms, GameValGroupTypes.LOCTYPES)
        }
    )

    data object PARAMS : ConfigDiffType<ParamType>(
        fileName = "params",
        sectionId = "param",
        navLabel = "Params",
        setup = {
            inTable(
                "type" to ParamType::type,
                "default" to ParamType::defaultInt,
                "ismembers" to ParamType::isMembers,
            )
        },
    )

    data object SEQUENCE : ConfigDiffType<SequenceType>(
        fileName = "sequences",
        navLabel = "Sequences",
        navGamevalType = GameValGroupTypes.SEQTYPES,
        setup = {
            inTable("tickDuration" to SequenceType::lengthInCycles,)
            gameval(SequenceType::leftHandItem, GameValGroupTypes.OBJTYPES)
            gameval(SequenceType::rightHandItem, GameValGroupTypes.OBJTYPES)
        }
    )

    data object SPOTANIMS : ConfigDiffType<SpotAnimType>(
        fileName = "spotanims",
        sectionId = "spotanim",
        navLabel = "Spot Animations",
        navGamevalType = GameValGroupTypes.SPOTTYPES,
        setup = {
            inTable("animationId" to SpotAnimType::animationId,)
            recolourable()
            gameval(SpotAnimType::animationId, GameValGroupTypes.SEQTYPES)
        }
    )

    data object ENUMS : ConfigDiffType<EnumType>(
        fileName = "enum",
        navLabel = "Enums",
        setup = {
            inTable(
                "key" to EnumType::keyType,
                "value" to EnumType::valueType,
                "default" to EnumType::defaultInt,
                "valuesCount" to EnumType::values,
            )
        },
    )

    data object HEALTHBARS : ConfigDiffType<HealthBarType>(
        fileName = "healthbar",
        navLabel = "Health Bar",
        setup = {
            inTable(
                "frontSpriteId" to HealthBarType::frontSpriteId,
                "backSpriteId" to HealthBarType::backSpriteId,
                "width" to HealthBarType::width,
            )
            gameval(HealthBarType::frontSpriteId, GameValGroupTypes.SPRITETYPES)
            gameval(HealthBarType::backSpriteId, GameValGroupTypes.SPRITETYPES)
            prop(PROPTYPE.SPRITE, HealthBarType::frontSpriteId)
            prop(PROPTYPE.SPRITE, HealthBarType::backSpriteId)
        }
    )

    data object MAPELEMENTS : ConfigDiffType<MapElementType>(
        fileName = "mapelement",
        navLabel = "Map Elements",
        setup = {
            inTable(
                "sprite1" to MapElementType::sprite1,
                "sprite2" to MapElementType::sprite2,
                "fontColor" to MapElementType::fontColor,
                "textSize" to MapElementType::textSize,
            )
            gameval(MapElementType::sprite1, GameValGroupTypes.SPRITETYPES)
            gameval(MapElementType::sprite2, GameValGroupTypes.SPRITETYPES)
            prop(PROPTYPE.SPRITE, MapElementType::sprite1)
            prop(PROPTYPE.SPRITE, MapElementType::sprite2)
            color(MapElementType::fontColor)
        }
    )

    data object VARP : ConfigDiffType<VarpType>(
        fileName = "varp",
        navLabel = "Varps",
        navGamevalType = GameValGroupTypes.VARPTYPES,
        setup = {
            inTable("configType" to VarpType::configType)
        },
    )

    data object VARBIT : ConfigDiffType<VarBitType>(
        fileName = "varbit",
        navLabel = "Varbits",
        navGamevalType = GameValGroupTypes.VARBITTYPES,
        setup = {
            inTable(
                "varp" to VarBitType::varp,
                "startBit" to VarBitType::startBit,
                "endBit" to VarBitType::endBit,
            )
            gameval(VarBitType::varp, GameValGroupTypes.VARPTYPES)
        }
    )

    data object WORLDENTITY : ConfigDiffType<WorldEntityType>(
        fileName = "worldentity",
        navLabel = "World Entities",
        setup = {
            inTable(
                "anim" to WorldEntityType::anim,
                "minimapIcon" to WorldEntityType::minimapIcon
            )
            gameval(WorldEntityType::anim, GameValGroupTypes.SEQTYPES)
            gameval(WorldEntityType::minimapIcon, GameValGroupTypes.SPRITETYPES)
            prop(PROPTYPE.SPRITE, WorldEntityType::minimapIcon)
            color(WorldEntityType::rgb)
        }
    )

    data object WORLDMAPAREA : ConfigDiffType<WorldMapAreaType>(
        fileName = "worldmaparea",
        navLabel = "World Map Area",

        setup = {
            searchBy(WorldMapAreaType::externalName)
            inTable("name" to WorldMapAreaType::externalName)
        }
    )

    data object STRUCTS : ConfigDiffType<StructType>(
        fileName = "struct",
    )

    data object VARCLAN : ConfigDiffType<VarClanType>(
        fileName = "varclan",
        navLabel = "Var Clan",
        setup = {
            inTable(
                "type" to VarClanType::type,
                "lifetime" to VarClanType::lifetime
            )
        },
    )

    data object VARCLIENT : ConfigDiffType<VarClientType>(
        fileName = "varclient",
        navLabel = "Varcs",
        navGamevalType = GameValGroupTypes.VARCS,
        setup = { inTable("persist" to VarClientType::persist,) },
    )

    companion object {
        val allConfigs: List<ConfigDiffType<*>>
            get() = listOf(
                INV,
                OVERLAY,
                UNDERLAY,
                NPCS,
                ITEMS,
                OBJECTS,
                PARAMS,
                SEQUENCE,
                SPOTANIMS,
                ENUMS,
                HEALTHBARS,
                MAPELEMENTS,
                VARP,
                VARBIT,
                WORLDENTITY,
                WORLDMAPAREA,
                STRUCTS,
                VARCLAN,
                VARCLIENT,
            )

        val allArchives: List<ConfigDiffType<*>>
            get() = listOf(TEXTURES)

        val all: List<ConfigDiffType<*>>
            get() = allConfigs + allArchives

        val diffTypeNames: List<String>
            get() = all.map { it.fileName }

        val httpExposed: List<ConfigDiffType<*>>
            get() = all

    }
}
