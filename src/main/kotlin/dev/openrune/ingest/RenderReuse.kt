package dev.openrune.ingest

/**
 * Lets a revision source skip re-rendering images whose inputs have not changed.
 *
 * Rendering every item and object is by far the most expensive part of an import — roughly 20ms
 * each, so tens of minutes for a full set — while a weekly cache changes a handful. The inputs to
 * an item's render are its definition and the meshes it uses; if neither moved at this revision,
 * last revision's image is still correct and is carried forward by hash without re-rendering or
 * re-storing it.
 *
 * Supplied by the platform, which has the database. Null means render everything, which is right
 * for the first revision and for any caller without a database to compare against.
 */
interface RenderReuse {
    /** Entity ids of [typeKey] whose content changed at the revision being imported. */
    fun changedIds(typeKey: String): Set<Int>

    /** `id -> image hash` as of the previous revision. An id absent here has no image to reuse. */
    fun previousImageHashes(typeKey: String): Map<Int, ByteArray>
}
