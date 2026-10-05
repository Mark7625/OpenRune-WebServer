package dev.openrune.cache.diff

/** A reference from a field value to a named cache entry, e.g. an animation id to its gameval name. */
data class GamevalRef(val group: String, val id: Int, val name: String)

/** One serialized field of a definition: its value plus, when the value names another entry, a [ref]. */
data class FieldEntry(val value: Any?, val ref: GamevalRef? = null)

typealias DefinitionSnapshot = Map<String, FieldEntry>

typealias ConfigTypeSnapshot = Map<Int, DefinitionSnapshot>

/** A gameval entry: the searchable name, the display text, and sub-names (interface components, table columns). */
data class GamevalExtra(
    val searchable: String,
    val text: String,
    val sub: Map<Int, String> = emptyMap(),
)

/** Per-frame metadata of a sprite archive entry. Raster and palette are not retained. */
data class IndexedSpriteMeta(
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val averageColor: Int = -1,
    val subHeight: Int = 0,
    val subWidth: Int = 0,
    val hasAlpha: Boolean = false,
)
