package dev.openrune.model

/**
 * One entity as decoded from a revision, ready to be written. [payloadJson] is canonical JSON
 * (sorted keys, integral numbers rendered as integers) and [payloadHash] is its 16-byte hash.
 * [blob] carries opaque bytes (sprite PNG, client script) that are stored once per content.
 */
class EntitySnapshot(
    val entityId: Int,
    val name: String?,
    val payloadJson: String,
    val payloadHash: ByteArray,
    val blob: ByteArray? = null,
    /** Ids this entity references (stored in `entity_ref`, e.g. objects placed in a map region). */
    val refs: IntArray? = null,
    val blobHash: ByteArray? = if (blob == null) null else Hashing.hash16(blob),
) {
    companion object {
        fun of(
            entityId: Int,
            name: String?,
            payload: Map<String, Any?>,
            blob: ByteArray? = null,
            refs: IntArray? = null,
        ): EntitySnapshot {
            val json = CanonicalJson.encode(payload)
            return EntitySnapshot(entityId, name, json, Hashing.hash16(json.toByteArray(Charsets.UTF_8)), blob, refs)
        }
    }
}

enum class RevisionStatus {
    DISCOVERED, DOWNLOADING, PROCESSING, IMPORTING, VALIDATING, READY, FAILED
}

enum class ChangeKind { ADDED, CHANGED, REMOVED }
