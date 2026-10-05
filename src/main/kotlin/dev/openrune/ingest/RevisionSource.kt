package dev.openrune.ingest

import dev.openrune.model.EntitySnapshot

/**
 * Everything one revision contributes, produced one entity type at a time so the pipeline never
 * holds more than a single type in memory. Implementations decode a raw cache
 * ([dev.openrune.ingest.osrs.OsrsRevisionSource]) or read a legacy `.bin`
 * ([LegacyBinRevisionSource]).
 */
interface RevisionSource : AutoCloseable {
    val rev: Int

    /** Type keys in the order they should be written. */
    val typeKeys: List<String>

    fun snapshots(typeKey: String): Iterator<EntitySnapshot>

    /** Non-entity side data keyed by artifact kind, as JSON. */
    fun artifacts(): Map<String, String> = emptyMap()
}

object ArtifactKind {
    const val XTEAS = "xteas"
    const val INTERFACE_MANIFEST = "interface-manifest"
}
