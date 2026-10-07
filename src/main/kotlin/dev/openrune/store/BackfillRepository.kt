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
    val cdn: BackfillCdn,
    val startedAt: Instant,
    val updatedAt: Instant,
) {
    val total: Int get() = pending.size + done.size + failed.size
}

/**
 * The upload phase that follows the imports: every imported revision's assets go to the CDN once the
 * last one is in, worked oldest first. Empty until that phase starts.
 */
data class BackfillCdn(
    val pending: List<Int>,
    val done: List<Int>,
    /** The revision uploading right now, null between revisions and before the phase starts. */
    val rev: Int?,
    /** Asset kind in flight: sprites, textures, models, items, objects. */
    val stage: String?,
    /** Objects of [stage] sent, and how many it will send. */
    val files: Int,
    val filesTotal: Int,
    /** How far through [rev]'s whole upload, 0-100. */
    val percent: Int,
) {
    val total: Int get() = pending.size + done.size
    val running: Boolean get() = total > 0
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
                       paused_for = NULL, cdn_pending = '{}', cdn_done = '{}', cdn_rev = NULL,
                       cdn_stage = NULL, cdn_files = 0, cdn_files_total = 0, cdn_percent = 0,
                       started_at = now(), updated_at = now()""",
                gameId, intArray(c, revs),
            )
        }
    }

    /** Opens the upload phase: the imports are done and [revs] have assets to send, oldest first. */
    fun startCdn(gameId: Int, revs: List<Int>) {
        dataSource.withConnection { c ->
            c.update(
                """UPDATE backfill SET cdn_pending = ?, cdn_done = '{}', cdn_rev = NULL, cdn_stage = NULL,
                   cdn_files = 0, cdn_files_total = 0, cdn_percent = 0, updated_at = now()
                   WHERE game_id = ?""",
                intArray(c, revs), gameId,
            )
        }
    }

    /**
     * Opens an upload-only job: a CDN publish of revisions that are already imported, with no import
     * queue behind it. `/admin/overview` then reports it exactly like the upload phase of a backfill.
     *
     * Only the `cdn_*` columns are written, so if a real backfill owns the row its queue survives.
     */
    fun startCdnOnly(gameId: Int, revs: List<Int>) {
        dataSource.withConnection { c ->
            c.update(
                """INSERT INTO backfill (game_id, pending, done, failed, cdn_pending, started_at, updated_at)
                   VALUES (?, '{}', '{}', '{}', ?, now(), now())
                   ON CONFLICT (game_id) DO UPDATE SET cdn_pending = EXCLUDED.cdn_pending, cdn_done = '{}',
                       cdn_rev = NULL, cdn_stage = NULL, cdn_files = 0, cdn_files_total = 0, cdn_percent = 0,
                       updated_at = now()""",
                gameId, intArray(c, revs),
            )
        }
    }

    /**
     * Ends an upload-only job, dropping the row when no import is still queued behind it.
     *
     * An empty `pending` means either this tool's own row or the corpse of a backfill that died
     * after its last import — which is the usual reason to run this tool at all, and exactly the row
     * that would otherwise have the dashboard reporting a backfill that ended hours ago. A backfill
     * that is still importing keeps its queue; the Publish CDN workflow refuses to start while one
     * is running, so the two cannot be in the upload phase at once.
     */
    fun finishCdnOnly(gameId: Int) {
        dataSource.withConnection { c ->
            c.update("DELETE FROM backfill WHERE game_id = ? AND pending = '{}'", gameId)
            c.update(
                """UPDATE backfill SET cdn_pending = '{}', cdn_done = '{}', cdn_rev = NULL, cdn_stage = NULL,
                   cdn_files = 0, cdn_files_total = 0, cdn_percent = 0, updated_at = now()
                   WHERE game_id = ?""",
                gameId,
            )
        }
    }

    /**
     * Where one revision's upload has got to. Called often enough that the caller throttles it —
     * a revision is thousands of objects and every one of them reports.
     */
    fun cdnProgress(gameId: Int, rev: Int, stage: String, files: Int, filesTotal: Int, percent: Int) {
        dataSource.withConnection { c ->
            c.update(
                """UPDATE backfill SET cdn_rev = ?, cdn_stage = ?, cdn_files = ?, cdn_files_total = ?,
                   cdn_percent = ?, updated_at = now() WHERE game_id = ?""",
                rev, stage, files, filesTotal, percent.coerceIn(0, 100), gameId,
            )
        }
    }

    /** Moves [rev] out of the upload queue, whether its assets all made it or not. */
    fun completeCdn(gameId: Int, rev: Int) {
        dataSource.withConnection { c ->
            c.update(
                """UPDATE backfill
                   SET cdn_pending = array_remove(cdn_pending, ?), cdn_done = array_append(cdn_done, ?),
                       cdn_rev = NULL, cdn_stage = NULL, cdn_files = 0, cdn_files_total = 0, cdn_percent = 0,
                       updated_at = now()
                   WHERE game_id = ?""",
                rev, rev, gameId,
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
        cdn = BackfillCdn(
            pending = ints(rs, "cdn_pending"),
            done = ints(rs, "cdn_done"),
            rev = rs.getObject("cdn_rev")?.let { (it as Number).toInt() },
            stage = rs.getString("cdn_stage"),
            files = rs.getInt("cdn_files"),
            filesTotal = rs.getInt("cdn_files_total"),
            percent = rs.getInt("cdn_percent"),
        ),
        startedAt = rs.getTimestamp("started_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )

    private fun ints(rs: ResultSet, column: String): List<Int> {
        val array = rs.getArray(column) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return (array.array as Array<Any?>).mapNotNull { (it as? Number)?.toInt() }
    }
}
