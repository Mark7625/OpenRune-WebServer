package dev.openrune.model

import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.definition.GameValGroupTypes

enum class EntityKind { CONFIG, ARCHIVE, GAMEVAL }

/**
 * A kind of entity the platform stores for a game. Config types wrap the existing
 * [ConfigDiffType] declarations (table columns, render props, search fields); archive and
 * gameval types are plain.
 */
class EntityTypeDef(
    /** Stable storage key, e.g. `items`, `sprites`, `gameval.items`, `map.regions`. */
    val key: String,
    val kind: EntityKind,
    val displayName: String,
    /** Section id used by the website (`spotanim` for `spotanims`); equals [key] for archives. */
    val sectionId: String = key,
    val config: ConfigDiffType<*>? = null,
    /** Gameval group whose names label entities of this type (`items` -> `items`). */
    val gamevalGroup: String? = null,
) {
    val isConfig: Boolean get() = kind == EntityKind.CONFIG

    override fun toString(): String = key
}

/** Gameval group names keyed by the group type, as used by the website and the legacy API. */
val GAMEVAL_GROUPS: List<String> = GameValGroupTypes.entries.map { it.groupName }.distinct()

fun gamevalTypeKey(group: String): String = "gameval.$group"

object OsrsEntityTypes {
    const val SPRITES = "sprites"
    const val MODELS = "models"
    const val CLIENTSCRIPTS = "clientscripts"
    const val MAP_REGIONS = "map.regions"

    /**
     * Rendered inventory and object images, so the site serves its own rather than linking a third
     * party's. Stored like any other blob entity: content addressed, so a render that comes out
     * byte identical across revisions costs nothing and is never re-uploaded.
     */
    const val ITEM_SPRITES = "item.sprites"
    const val OBJECT_SPRITES = "object.sprites"

    private val extraGamevalGroups = mapOf(
        ConfigDiffType.VARBIT.fileName to "varbits",
        ConfigDiffType.VARCLIENT.fileName to "varcs",
        ConfigDiffType.INTERFACES.fileName to "components",
    )

    val configs: List<EntityTypeDef> = ConfigDiffType.all.map { type ->
        EntityTypeDef(
            key = type.fileName,
            kind = EntityKind.CONFIG,
            displayName = type.navLabel ?: type.sectionId.replaceFirstChar { it.uppercase() },
            sectionId = type.sectionId,
            config = type,
            gamevalGroup = type.navGamevalType?.groupName ?: extraGamevalGroups[type.fileName],
        )
    }

    val archives: List<EntityTypeDef> = listOf(
        EntityTypeDef(SPRITES, EntityKind.ARCHIVE, "Sprites", gamevalGroup = "sprites"),
        EntityTypeDef(MODELS, EntityKind.ARCHIVE, "Models"),
        EntityTypeDef(CLIENTSCRIPTS, EntityKind.ARCHIVE, "Client scripts"),
        EntityTypeDef(MAP_REGIONS, EntityKind.ARCHIVE, "Map regions"),
        EntityTypeDef(ITEM_SPRITES, EntityKind.ARCHIVE, "Item images", gamevalGroup = "items"),
        EntityTypeDef(OBJECT_SPRITES, EntityKind.ARCHIVE, "Object images", gamevalGroup = "objects"),
    )

    val gamevals: List<EntityTypeDef> = GAMEVAL_GROUPS.map { group ->
        EntityTypeDef(gamevalTypeKey(group), EntityKind.GAMEVAL, "Gameval $group")
    }

    val all: List<EntityTypeDef> = configs + archives + gamevals

    private val byKey = all.associateBy { it.key }
    private val bySection = configs.associateBy { it.sectionId }

    fun byKey(key: String): EntityTypeDef? = byKey[key]

    /** Resolve a website section id or storage key (`spotanim` and `spotanims` both work). */
    fun resolveConfig(keyOrSection: String): EntityTypeDef? {
        val k = keyOrSection.lowercase()
        return byKey[k]?.takeIf { it.isConfig } ?: bySection[k]
    }
}
