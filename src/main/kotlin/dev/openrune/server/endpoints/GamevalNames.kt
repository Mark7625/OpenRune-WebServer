package dev.openrune.server.endpoints

import dev.openrune.ServerConfig
import dev.openrune.cache.diff.DiffBinaryCache

/** Gameval group names used when labelling attachments. */
object GamevalGroup {
    const val ITEMS = "items"
    const val NPCS = "npcs"
    const val OBJECTS = "objects"
    const val SPRITES = "sprites"
}

/**
 * Gameval names for a group at [rev], falling back to the base revision.
 *
 * A revision's binary carries the full gameval set for that revision (not a delta), so the
 * requested revision alone is enough whenever it has been dumped.
 */
fun gamevalNames(config: ServerConfig, rev: Int, group: String): Map<Int, String> {
    val atRev = DiffBinaryCache.getDecodedRev(config, rev)?.gameval?.get(group)
    val source = atRev?.takeIf { it.isNotEmpty() }
        ?: DiffBinaryCache.getDecodedRev(config, 1)?.gameval?.get(group)
        ?: return emptyMap()
    return source.mapValues { (_, extra) -> extra.searchable }
}

/** `[{id, name}]` for an attachment list. */
fun namedIds(ids: List<Int>, names: Map<Int, String>): List<Map<String, Any?>> =
    ids.map { id -> mapOf("id" to id, "name" to names[id]) }
