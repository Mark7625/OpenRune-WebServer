package dev.openrune.store

import com.google.gson.Gson
import dev.openrune.db.getIntOrNull
import dev.openrune.db.query
import dev.openrune.db.queryOne
import dev.openrune.db.update
import dev.openrune.db.withConnection
import dev.openrune.model.RevisionStatus
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

data class RevisionRow(
    val gameId: Int,
    val rev: Int,
    val sourceCacheId: Int?,
    val sourceTimestamp: Instant?,
    val status: RevisionStatus,
    val stage: String?,
    val hasData: Boolean,
    val published: Boolean,
    val publishedAt: Instant?,
    val publishStamp: Long,
    val attempts: Int,
    val error: String?,
    val metrics: String?,
    val rawPath: String?,
    val updatedAt: Instant?,
)

data class IngestRunRow(
    val id: Long,
    val gameId: Int,
    val rev: Int,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val status: String,
    val stage: String?,
    val error: String?,
    val metrics: String?,
    val percent: Int?,
    val message: String?,
)

/** Revision rows: the lifecycle state machine and the published set the API is allowed to see. */
class RevisionRepository(private val dataSource: DataSource) {
    private val gson = Gson()

    fun get(gameId: Int, rev: Int): RevisionRow? = dataSource.withConnection { c ->
        c.queryOne("SELECT * FROM revision WHERE game_id = ? AND rev = ?", gameId, rev, map = ::row)
    }

    fun list(gameId: Int): List<RevisionRow> = dataSource.withConnection { c ->
        c.query("SELECT * FROM revision WHERE game_id = ? ORDER BY rev", gameId, map = ::row)
    }

    fun published(gameId: Int): List<Int> = dataSource.withConnection { c ->
        c.query("SELECT rev FROM revision WHERE game_id = ? AND published ORDER BY rev", gameId) { it.getInt(1) }
    }

    fun latestPublished(gameId: Int): Int? = dataSource.withConnection { c ->
        c.queryOne("SELECT MAX(rev) FROM revision WHERE game_id = ? AND published", gameId) { it.getIntOrNull(1) }
    }

    /** Monotonic stamp that changes whenever the published set changes; drives caches and ETags. */
    fun publishStamp(gameId: Int): Long = dataSource.withConnection { c ->
        c.queryOne("SELECT COALESCE(MAX(publish_stamp), 0) FROM revision WHERE game_id = ?", gameId) { it.getLong(1) } ?: 0L
    }

    fun isPublished(gameId: Int, rev: Int): Boolean = dataSource.withConnection { c ->
        c.queryOne("SELECT published FROM revision WHERE game_id = ? AND rev = ?", gameId, rev) { it.getBoolean(1) } ?: false
    }

    /** Next revision after [rev] that holds entity data (published or not); bounds out-of-order inserts. */
    fun nextWithData(gameId: Int, rev: Int): Int? = dataSource.withConnection { c ->
        c.queryOne("SELECT MIN(rev) FROM revision WHERE game_id = ? AND rev > ? AND has_data", gameId, rev) { it.getIntOrNull(1) }
    }

    fun discover(gameId: Int, rev: Int, sourceCacheId: Int?, sourceTimestamp: Instant?): Boolean = dataSource.withConnection { c ->
        c.update(
            """INSERT INTO revision (game_id, rev, source_cache_id, source_timestamp, status)
               VALUES (?, ?, ?, ?, 'DISCOVERED') ON CONFLICT DO NOTHING""",
            gameId, rev, sourceCacheId, sourceTimestamp?.let { Timestamp.from(it) },
        ) > 0
    }

    fun setSource(gameId: Int, rev: Int, sourceCacheId: Int?, sourceTimestamp: Instant?) {
        dataSource.withConnection { c ->
            c.update(
                "UPDATE revision SET source_cache_id = COALESCE(?, source_cache_id), source_timestamp = COALESCE(?, source_timestamp), updated_at = now() WHERE game_id = ? AND rev = ?",
                sourceCacheId, sourceTimestamp?.let { Timestamp.from(it) }, gameId, rev,
            )
        }
    }

    fun setStatus(gameId: Int, rev: Int, status: RevisionStatus, stage: String? = null, error: String? = null) {
        dataSource.withConnection { c ->
            c.update(
                "UPDATE revision SET status = ?, stage = ?, error = ?, updated_at = now() WHERE game_id = ? AND rev = ?",
                status.name, stage, error, gameId, rev,
            )
        }
    }

    fun markStarted(gameId: Int, rev: Int, rawPath: String?) {
        dataSource.withConnection { c ->
            c.update(
                "UPDATE revision SET attempts = attempts + 1, error = NULL, raw_path = COALESCE(?, raw_path), updated_at = now() WHERE game_id = ? AND rev = ?",
                rawPath, gameId, rev,
            )
        }
    }

    fun setHasData(gameId: Int, rev: Int, hasData: Boolean) {
        dataSource.withConnection { c ->
            c.update("UPDATE revision SET has_data = ?, updated_at = now() WHERE game_id = ? AND rev = ?", hasData, gameId, rev)
        }
    }

    fun publish(gameId: Int, rev: Int, metrics: Map<String, Any?>) {
        dataSource.withConnection { c ->
            c.update(
                """UPDATE revision SET status = 'READY', stage = NULL, error = NULL, has_data = true, published = true,
                   published_at = now(), publish_stamp = ?, metrics = ?::jsonb, updated_at = now()
                   WHERE game_id = ? AND rev = ?""",
                System.currentTimeMillis(), gson.toJson(metrics), gameId, rev,
            )
        }
    }

    fun unpublish(gameId: Int, rev: Int) {
        dataSource.withConnection { c ->
            c.update(
                "UPDATE revision SET published = false, published_at = NULL, publish_stamp = ?, updated_at = now() WHERE game_id = ? AND rev = ?",
                System.currentTimeMillis(), gameId, rev,
            )
        }
    }

    fun startRun(gameId: Int, rev: Int): Long = dataSource.withConnection { c ->
        c.queryOne(
            "INSERT INTO ingest_run (game_id, rev, status, stage) VALUES (?, ?, 'RUNNING', 'DOWNLOADING') RETURNING id",
            gameId, rev,
        ) { it.getLong(1) } ?: error("insert returned nothing")
    }

    fun updateRun(runId: Long, stage: String? = null, status: String? = null, error: String? = null, metrics: Map<String, Any?>? = null, finished: Boolean = false) {
        dataSource.withConnection { c ->
            c.update(
                """UPDATE ingest_run SET stage = COALESCE(?, stage), status = COALESCE(?, status), error = ?,
                   metrics = COALESCE(?::jsonb, metrics), finished_at = CASE WHEN ? THEN now() ELSE finished_at END WHERE id = ?""",
                stage, status, error, metrics?.let { gson.toJson(it) }, finished, runId,
            )
        }
    }

    /**
     * Live progress of a running import. Written by whichever process is ingesting so the API can
     * show it without being that process; throttled by the caller, not per tick.
     */
    fun updateRunProgress(runId: Long, stage: String, percent: Int, message: String?) {
        dataSource.withConnection { c ->
            c.update(
                "UPDATE ingest_run SET stage = ?, percent = ?, message = ? WHERE id = ?",
                stage, percent.coerceIn(0, 100), message?.take(500), runId,
            )
        }
    }

    /** The import currently in flight for a game, from any process. Null when nothing is running. */
    fun activeRun(gameId: Int): IngestRunRow? = dataSource.withConnection { c ->
        c.queryOne(
            "SELECT * FROM ingest_run WHERE game_id = ? AND finished_at IS NULL ORDER BY id DESC LIMIT 1",
            gameId,
            map = ::runRow,
        )
    }

    fun runs(gameId: Int, limit: Int): List<IngestRunRow> = dataSource.withConnection { c ->
        c.query("SELECT * FROM ingest_run WHERE game_id = ? ORDER BY id DESC LIMIT ?", gameId, limit, map = ::runRow)
    }

    private fun runRow(rs: java.sql.ResultSet) = IngestRunRow(
        id = rs.getLong("id"),
        gameId = rs.getInt("game_id"),
        rev = rs.getInt("rev"),
        startedAt = rs.getTimestamp("started_at").toInstant(),
        finishedAt = rs.getTimestamp("finished_at")?.toInstant(),
        status = rs.getString("status"),
        stage = rs.getString("stage"),
        error = rs.getString("error"),
        metrics = rs.getString("metrics"),
        percent = rs.getIntOrNull("percent"),
        message = rs.getString("message"),
    )

    fun putArtifact(gameId: Int, rev: Int, kind: String, bodyJson: String) {
        dataSource.withConnection { c ->
            c.update(
                """INSERT INTO revision_artifact (game_id, rev, kind, body) VALUES (?, ?, ?, ?::jsonb)
                   ON CONFLICT (game_id, rev, kind) DO UPDATE SET body = EXCLUDED.body""",
                gameId, rev, kind, bodyJson,
            )
        }
    }

    fun artifact(gameId: Int, rev: Int, kind: String): String? = dataSource.withConnection { c ->
        c.queryOne("SELECT body::text FROM revision_artifact WHERE game_id = ? AND rev = ? AND kind = ?", gameId, rev, kind) { it.getString(1) }
    }

    private fun row(rs: ResultSet) = RevisionRow(
        gameId = rs.getInt("game_id"),
        rev = rs.getInt("rev"),
        sourceCacheId = rs.getIntOrNull("source_cache_id"),
        sourceTimestamp = rs.getTimestamp("source_timestamp")?.toInstant(),
        status = RevisionStatus.valueOf(rs.getString("status")),
        stage = rs.getString("stage"),
        hasData = rs.getBoolean("has_data"),
        published = rs.getBoolean("published"),
        publishedAt = rs.getTimestamp("published_at")?.toInstant(),
        publishStamp = rs.getLong("publish_stamp"),
        attempts = rs.getInt("attempts"),
        error = rs.getString("error"),
        metrics = rs.getString("metrics"),
        rawPath = rs.getString("raw_path"),
        updatedAt = rs.getTimestamp("updated_at")?.toInstant(),
    )
}
