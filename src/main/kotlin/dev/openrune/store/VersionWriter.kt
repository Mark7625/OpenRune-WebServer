package dev.openrune.store

import dev.openrune.db.execute
import dev.openrune.db.inTransaction
import dev.openrune.db.query
import dev.openrune.db.queryOne
import dev.openrune.db.update
import dev.openrune.model.EntitySnapshot
import dev.openrune.model.Hashing
import org.postgresql.PGConnection
import org.postgresql.copy.CopyIn
import java.sql.Connection
import javax.sql.DataSource

data class TypeWriteResult(
    val total: Int,
    val added: Int,
    val changed: Int,
    val removed: Int,
    val payloadsWritten: Int,
    val blobsWritten: Int,
)

/**
 * Writes one entity type of one revision into `entity_version` as validity ranges.
 *
 * Snapshots are streamed into temp tables with COPY, then merged set-wise in a single transaction:
 * versions whose entity changed or disappeared at [rev] are closed, new versions are opened, and
 * payloads / blobs are inserted once per distinct content. Appending the newest revision touches
 * only rows of entities that changed; inserting an older revision between two existing ones also
 * re-opens the following state as a copy (see docs/ARCHITECTURE.md 2.4 and 4.4).
 */
class VersionWriter(private val dataSource: DataSource) {

    fun writeType(
        gameId: Int,
        typeId: Int,
        rev: Int,
        nextRev: Int?,
        snapshots: Iterator<EntitySnapshot>,
    ): TypeWriteResult = dataSource.inTransaction { c ->
        val covering = HashMap<Int, Covering>()
        c.query(
            """SELECT entity_id, payload_hash, blob_hash FROM entity_version
               WHERE game_id = ? AND type_id = ? AND valid_from < ? AND (valid_to IS NULL OR valid_to > ?)""",
            gameId, typeId, rev, rev,
        ) { rs -> covering[rs.getInt(1)] = Covering(rs.getBytes(2), rs.getBytes(3)) }

        // One staging table and one COPY stream: a connection can run only a single COPY at a time,
        // and buffering a whole type's payloads in the JVM is exactly what this design avoids.
        c.execute(
            """CREATE TEMP TABLE stage_entity (entity_id int PRIMARY KEY, payload_hash bytea NOT NULL, blob_hash bytea, name text, body text, bytes bytea) ON COMMIT DROP;
               CREATE TEMP TABLE stage_ref (entity_id int, ref_id int) ON COMMIT DROP;"""
        )

        val counts = copySnapshots(c, snapshots, covering)

        val payloadsWritten = c.update(
            """INSERT INTO entity_payload (hash, body, size)
               SELECT payload_hash, body::jsonb, length(body)
               FROM (SELECT DISTINCT ON (payload_hash) payload_hash, body FROM stage_entity WHERE body IS NOT NULL) s
               ON CONFLICT (hash) DO NOTHING"""
        )
        val blobsWritten = c.update(
            """INSERT INTO entity_blob (hash, bytes, size)
               SELECT blob_hash, bytes, length(bytes)
               FROM (SELECT DISTINCT ON (blob_hash) blob_hash, bytes FROM stage_entity WHERE bytes IS NOT NULL) s
               ON CONFLICT (hash) DO NOTHING"""
        )

        val removed = c.queryOne(
            """SELECT count(*) FROM entity_version v
               WHERE v.game_id = ? AND v.type_id = ? AND v.valid_from < ? AND (v.valid_to IS NULL OR v.valid_to > ?)
                 AND NOT EXISTS (SELECT 1 FROM stage_entity s WHERE s.entity_id = v.entity_id)""",
            gameId, typeId, rev, rev,
        ) { it.getInt(1) } ?: 0

        // Close every covering version that is not confirmed unchanged by the stage, and when an
        // older revision is being inserted between two others, re-open the closed state from the
        // next revision onwards as a copy so later revisions keep their content.
        val closedCount = c.queryOne(
            """WITH closed AS (
                   UPDATE entity_version v SET valid_to = ?, closed_by_rev = ?
                   FROM (SELECT x.entity_id, x.valid_from, x.valid_to AS old_to FROM entity_version x
                         WHERE x.game_id = ? AND x.type_id = ? AND x.valid_from < ? AND (x.valid_to IS NULL OR x.valid_to > ?)
                           AND NOT EXISTS (SELECT 1 FROM stage_entity s WHERE s.entity_id = x.entity_id AND s.payload_hash = x.payload_hash)) prev
                   WHERE v.game_id = ? AND v.type_id = ? AND v.entity_id = prev.entity_id AND v.valid_from = prev.valid_from
                   RETURNING v.entity_id, v.payload_hash, v.blob_hash, v.name, v.valid_from AS src_from, prev.old_to
               ),
               copies AS (
                   INSERT INTO entity_version (game_id, type_id, entity_id, valid_from, valid_to, payload_hash, blob_hash, name, ingest_rev)
                   SELECT ?, ?, entity_id, ?::int, old_to, payload_hash, blob_hash, name, ?
                   FROM closed WHERE ?::int IS NOT NULL AND (old_to IS NULL OR old_to > ?::int)
                   RETURNING entity_id
               ),
               copied_refs AS (
                   INSERT INTO entity_ref (game_id, type_id, entity_id, valid_from, ref_id)
                   SELECT r.game_id, r.type_id, r.entity_id, ?::int, r.ref_id
                   FROM closed c JOIN entity_ref r ON r.game_id = ? AND r.type_id = ? AND r.entity_id = c.entity_id AND r.valid_from = c.src_from
                   WHERE ?::int IS NOT NULL AND (c.old_to IS NULL OR c.old_to > ?::int)
               )
               SELECT count(*) FROM closed""",
            rev, rev, gameId, typeId, rev, rev, gameId, typeId,
            gameId, typeId, nextRev, rev, nextRev, nextRev,
            nextRev, gameId, typeId, nextRev, nextRev,
        ) { it.getInt(1) } ?: 0

        val inserted = c.update(
            """INSERT INTO entity_version (game_id, type_id, entity_id, valid_from, valid_to, payload_hash, blob_hash, name, ingest_rev)
               SELECT ?, ?, s.entity_id, ?, ?::int, s.payload_hash, s.blob_hash, s.name, ?
               FROM stage_entity s
               WHERE NOT EXISTS (
                   SELECT 1 FROM entity_version v
                   WHERE v.game_id = ? AND v.type_id = ? AND v.entity_id = s.entity_id
                     AND v.valid_from < ? AND (v.valid_to IS NULL OR v.valid_to > ?) AND v.payload_hash = s.payload_hash)""",
            gameId, typeId, rev, nextRev, rev, gameId, typeId, rev, rev,
        )

        c.update(
            """INSERT INTO entity_ref (game_id, type_id, entity_id, valid_from, ref_id)
               SELECT DISTINCT ?, ?, r.entity_id, ?, r.ref_id FROM stage_ref r
               WHERE EXISTS (SELECT 1 FROM entity_version v WHERE v.game_id = ? AND v.type_id = ? AND v.entity_id = r.entity_id AND v.valid_from = ?)""",
            gameId, typeId, rev, gameId, typeId, rev,
        )

        val changed = closedCount - removed
        TypeWriteResult(
            total = counts.entities,
            added = inserted - changed,
            changed = changed,
            removed = removed,
            payloadsWritten = payloadsWritten,
            blobsWritten = blobsWritten,
        )
    }

    /**
     * Undo everything ingestion of [rev] wrote: delete rows it created (new versions and copies)
     * and re-open the rows it closed to their previous bound.
     */
    fun rollback(gameId: Int, rev: Int, nextRev: Int?) = dataSource.inTransaction { c ->
        c.update(
            """DELETE FROM entity_ref r USING entity_version v
               WHERE v.game_id = ? AND v.ingest_rev = ? AND r.game_id = v.game_id AND r.type_id = v.type_id
                 AND r.entity_id = v.entity_id AND r.valid_from = v.valid_from""",
            gameId, rev,
        )
        // Rows that had a copy re-opened at the next revision take the copy's upper bound back.
        c.update(
            """UPDATE entity_version v SET closed_by_rev = NULL,
                   valid_to = (SELECT cp.valid_to FROM entity_version cp
                               WHERE cp.game_id = v.game_id AND cp.type_id = v.type_id AND cp.entity_id = v.entity_id
                                 AND cp.ingest_rev = ? AND cp.valid_from > ?)
               WHERE v.game_id = ? AND v.closed_by_rev = ?
                 AND EXISTS (SELECT 1 FROM entity_version cp
                             WHERE cp.game_id = v.game_id AND cp.type_id = v.type_id AND cp.entity_id = v.entity_id
                               AND cp.ingest_rev = ? AND cp.valid_from > ?)""",
            rev, rev, gameId, rev, rev, rev,
        )
        c.update(
            "UPDATE entity_version SET valid_to = ?::int, closed_by_rev = NULL WHERE game_id = ? AND closed_by_rev = ?",
            nextRev, gameId, rev,
        )
        c.update("DELETE FROM entity_version WHERE game_id = ? AND ingest_rev = ?", gameId, rev)
    }

    fun hasRowsFor(gameId: Int, rev: Int): Boolean = dataSource.inTransaction { c ->
        c.queryOne(
            "SELECT 1 FROM entity_version WHERE game_id = ? AND (ingest_rev = ? OR closed_by_rev = ?) LIMIT 1",
            gameId, rev, rev,
        ) { true } ?: false
    }

    private class Covering(val payloadHash: ByteArray, val blobHash: ByteArray?)

    private class CopyCounts(val entities: Int)

    /**
     * Streams every snapshot into `stage_entity`. Payload bodies and blob bytes are only sent for
     * entities whose content differs from the version covering [rev]; refs of those entities are
     * collected (ints only) and copied afterwards.
     */
    private fun copySnapshots(c: Connection, snapshots: Iterator<EntitySnapshot>, covering: Map<Int, Covering>): CopyCounts {
        val copy = c.unwrap(PGConnection::class.java).copyAPI
        var entities = 0
        val refs = ArrayList<IntArray>()
        CopyStream(copy.copyIn("COPY stage_entity (entity_id, payload_hash, blob_hash, name, body, bytes) FROM STDIN")).use { out ->
            while (snapshots.hasNext()) {
                val s = snapshots.next()
                entities++
                val existing = covering[s.entityId]
                val payloadNew = existing == null || !existing.payloadHash.contentEquals(s.payloadHash)
                val blob = s.blob
                val blobNew = blob != null && (existing?.blobHash == null || !existing.blobHash.contentEquals(s.blobHash!!))
                out.row(
                    s.entityId.toString(),
                    bytea(s.payloadHash),
                    s.blobHash?.let { bytea(it) },
                    s.name,
                    if (payloadNew) s.payloadJson else null,
                    if (blobNew) bytea(blob!!) else null,
                )
                if (payloadNew) s.refs?.let { r -> if (r.isNotEmpty()) refs.add(intArrayOf(s.entityId, *r)) }
            }
        }
        if (refs.isNotEmpty()) {
            CopyStream(copy.copyIn("COPY stage_ref (entity_id, ref_id) FROM STDIN")).use { out ->
                refs.forEach { r -> for (i in 1 until r.size) out.row(r[0].toString(), r[i].toString()) }
            }
        }
        return CopyCounts(entities)
    }

    /** bytea hex input form; [CopyStream.escape] doubles the backslash for the COPY text format. */
    private fun bytea(bytes: ByteArray): String = "\\x" + Hashing.hex(bytes)

    /** COPY text format writer: tab separated columns, `\N` for null, backslash escapes. */
    private class CopyStream(private val copyIn: CopyIn) : AutoCloseable {
        private val buffer = StringBuilder(1 shl 16)

        fun row(vararg columns: String?) {
            columns.forEachIndexed { i, value ->
                if (i > 0) buffer.append('\t')
                if (value == null) buffer.append("\\N") else escape(value)
            }
            buffer.append('\n')
            if (buffer.length >= (1 shl 16)) flush()
        }

        private fun escape(value: String) {
            for (ch in value) {
                when (ch) {
                    '\\' -> buffer.append("\\\\")
                    '\t' -> buffer.append("\\t")
                    '\n' -> buffer.append("\\n")
                    '\r' -> buffer.append("\\r")
                    else -> buffer.append(ch)
                }
            }
        }

        private fun flush() {
            if (buffer.isEmpty()) return
            val bytes = buffer.toString().toByteArray(Charsets.UTF_8)
            copyIn.writeToCopy(bytes, 0, bytes.size)
            buffer.setLength(0)
        }

        override fun close() {
            flush()
            copyIn.endCopy()
        }
    }
}
