package dev.openrune.cache.diff

/**
 * Per-model summary stored in the revision binary (`MDLM` trailer).
 *
 * The mesh itself is not kept here — raw `.dat` bytes live on the CDN at
 * `{osrs|rs3}/rev/{rev}/models/{id}.dat` (see [ModelCdn]). This is only what the
 * website needs to describe a model next to the item / npc / object that uses it.
 *
 * [itemIds], [npcIds] and [objectIds] are the reverse attachments: which definitions
 * reference this model at that revision. Names are resolved from gamevals at request time.
 */
data class ModelMeta(
    val vertexCount: Int = 0,
    val faceCount: Int = 0,
    val texturedFaceCount: Int = 0,
    /** Faces with a non-zero alpha, i.e. anything not fully opaque. */
    val transparentFaceCount: Int = 0,
    val version: Int = 0,
    val renderPriority: Int = 0,
    /** Distinct texture ids referenced by faces (`-1` faces excluded). */
    val textures: List<Int> = emptyList(),
    /** Distinct HSL face colours. */
    val colors: List<Int> = emptyList(),
    val itemIds: List<Int> = emptyList(),
    val npcIds: List<Int> = emptyList(),
    val objectIds: List<Int> = emptyList(),
) {
    val attachmentCount: Int
        get() = itemIds.size + npcIds.size + objectIds.size
}
