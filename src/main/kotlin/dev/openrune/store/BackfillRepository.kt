package dev.openrune.store

import dev.openrune.db.query
import dev.openrune.db.queryOne
import dev.openrune.db.update
import dev.openrune.db.withConnection
import java.sql.ResultSet
import java.time.Instant
import javax.sql.DataSource

/**
 * Progress of a backfill: older revisions queued for import, worked newest first.
 *
 * Held in the database so the API can report it without running the backfill, and so a restarted
 * worker can resume where it left off rather than redoing finished revisions.
 */
data class BackfillRow(
    val gameId: Int,
    /** Still to do, in the order they will be worked. */
    val pending: List<Int>,
    val done: List<Int>,
    val failed: List<Int>,
    /** The newly released revision being imported ahead of the queue, if any. */
    val pausedFor: Int?,
    val startedAt: Instant,
    val updatedAt: Instant,
) {
    val total: Int get() = pending.size + done.size + failed.size
}

class BackfillRepository(private val dataSource: DataSource) {

    fun get(gameId: Int): BackfillRow? = dataSource.withConnection { c ->
        c.queryOne("SELECT * FROM backfill WHERE game_id = ?", gameId, map = ::row)
    }

    /** Replaces any previous queue for the game; a backfill is a single job, not a stack. */
    fun start(gameId: Int, revs: List<Int>) {
        dataSource.withConnection { c ->
            c.update(
                """INSERT INTO backfill (game_id, pending, done, failed, paused_for, started_at, updated_at)
                   VALUES (?, ?, '{}', '{}', NULL, now(), now())
                   ON CONFLICT (game_id) DO UPDATE SET pending = EXCLUDED.pending, done = '{}', failed = '{}',
                       paused_for = NULL, started_at = now(), updated_at = now()""",
                gameId, intArray(c, revs),
            )
        }
    }

    /** Moves [rev] out of the queue into done or failed. */
    fun complete(gameId: Int, rev: Int, ok: Boolean) {
        dataSource.withConnection { c ->
            c.update(
                """UPDATE backfill
                   SET pending = array_remove(pending, ?),
                       done = CASE WHEN ? THEN array_append(done, ?) ELSE done END,
                       failed = CASE WHEN ? THEN failed ELSE array_append(failed, ?) END,
                       paused_for = NULL,
                       updated_at = now()
                   WHERE game_id = ?""",
                rev, ok, rev, ok, rev, gameId,
            )
        }
    }

    /** Records that the queue is standing aside for a newly released revision. */
    fun pauseFor(gameId: Int, rev: Int?) {
        dataSource.withConnection { c ->
            c.update("UPDATE backfill SET paused_for = ?, updated_at = now() WHERE game_id = ?", rev, gameId)
        }
    }

    fun clear(gameId: Int) {
        dataSource.withConnection { c -> c.update("DELETE FROM backfill WHERE game_id = ?", gameId) }
    }

    private fun intArray(c: java.sql.Connection, values: List<Int>) =
        c.createArrayOf("integer", values.toTypedArray())

    private fun row(rs: ResultSet) = BackfillRow(
        gameId = rs.getInt("game_id"),
        pending = ints(rs, "pending"),
        done = ints(rs, "done"),
        failed = ints(rs, "failed"),
        pausedFor = rs.getObject("paused_for")?.let { (it as Number).toInt() },
        startedAt = rs.getTimestamp("started_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )

    private fun ints(rs: ResultSet, column: String): List<Int> {
        val array = rs.getArray(column) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return (array.array as Array<Any?>).mapNotNull { (it as? Number)?.toInt() }
    }
}
