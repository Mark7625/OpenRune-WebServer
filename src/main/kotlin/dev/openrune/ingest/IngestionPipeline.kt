package dev.openrune.ingest

import dev.openrune.db.query
import dev.openrune.db.queryOne
import dev.openrune.db.withConnection
import dev.openrune.metrics.Metrics
import dev.openrune.model.RevisionStatus
import dev.openrune.query.RevisionCatalog
import dev.openrune.store.GameRegistry.RegisteredGame
import dev.openrune.store.RevisionRepository
import dev.openrune.store.TypeWriteResult
import dev.openrune.store.VersionWriter
import mu.KotlinLogging
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

class IngestResult(
    val rev: Int,
    val durationMs: Long,
    val stageMs: Map<String, Long>,
    val types: Map<String, TypeWriteResult>,
) {
    val entities: Int get() = types.values.sumOf { it.total }
    val added: Int get() = types.values.sumOf { it.added }
    val changed: Int get() = types.values.sumOf { it.changed }
    val removed: Int get() = types.values.sumOf { it.removed }
}

class IngestionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Runs one revision through PROCESSING → IMPORTING → VALIDATING → READY and publishes it. Any
 * failure rolls the revision's rows back and leaves every other revision untouched.
 *
 * Only one ingestion per game runs at a time (PostgreSQL advisory lock on the game id).
 */
class IngestionPipeline(
    private val dataSource: DataSource,
    private val game: RegisteredGame,
    private val revisions: RevisionRepository,
    private val writer: VersionWriter,
    private val metrics: Metrics,
    private val catalog: RevisionCatalog? = null,
) {
    private val gameId = game.game.id

    /**
     * [openSource] is invoked after the DOWNLOADING stage completes; the caller owns download.
     * [progress] receives (stage, percent, message).
     */
    fun ingest(
        rev: Int,
        runId: Long,
        openSource: () -> RevisionSource,
        progress: (String, Int, String) -> Unit = { _, _, _ -> },
    ): IngestResult {
        val start = System.nanoTime()
        val stageMs = LinkedHashMap<String, Long>()
        val typeResults = LinkedHashMap<String, TypeWriteResult>()
        var stageStart = System.nanoTime()
        fun endStage(name: String) {
            stageMs[name] = (System.nanoTime() - stageStart) / 1_000_000
            stageStart = System.nanoTime()
        }

        return try {
            withGameLock {
                val nextRev = revisions.nextWithData(gameId, rev)
                if (writer.hasRowsFor(gameId, rev)) {
                    logger.info { "rev $rev: rolling back rows from an earlier attempt" }
                    writer.rollback(gameId, rev, nextRev)
                    revisions.setHasData(gameId, rev, false)
                }

                revisions.setStatus(gameId, rev, RevisionStatus.PROCESSING, "opening source")
                revisions.updateRun(runId, stage = "PROCESSING")
                progress("PROCESSING", 0, "Opening revision $rev")
                openSource().use { source ->
                    endStage("open")
                    revisions.setStatus(gameId, rev, RevisionStatus.IMPORTING, null)
                    revisions.updateRun(runId, stage = "IMPORTING")
                    val keys = source.typeKeys
                    keys.forEachIndexed { index, key ->
                        val type = game.type(key)
                        val pct = (index * 90) / keys.size.coerceAtLeast(1)
                        progress("IMPORTING", pct, "Importing $key")
                        revisions.setStatus(gameId, rev, RevisionStatus.IMPORTING, key)
                        val typeStart = System.nanoTime()
                        val result = metrics.time("ingest.type", { "$key@$rev" }) {
                            writer.writeType(gameId, type.id, rev, nextRev, source.snapshots(key))
                        }
                        typeResults[key] = result
                        logger.info {
                            "rev $rev: $key total=${result.total} +${result.added} ~${result.changed} -${result.removed} " +
                                "payloads=${result.payloadsWritten} blobs=${result.blobsWritten} " +
                                "(${(System.nanoTime() - typeStart) / 1_000_000}ms)"
                        }
                    }
                    source.artifacts().forEach { (kind, json) -> revisions.putArtifact(gameId, rev, kind, json) }
                }
                revisions.setHasData(gameId, rev, true)
                endStage("import")

                revisions.setStatus(gameId, rev, RevisionStatus.VALIDATING, null)
                revisions.updateRun(runId, stage = "VALIDATING")
                progress("VALIDATING", 92, "Validating revision $rev")
                validate(rev, typeResults)
                endStage("validate")

                val durationMs = (System.nanoTime() - start) / 1_000_000
                val result = IngestResult(rev, durationMs, stageMs, typeResults)
                val metricsJson = resultMetrics(result)
                revisions.publish(gameId, rev, metricsJson)
                // Status and metrics are final here, but the run is left open: the caller may still
                // upload assets to the CDN, and that stage should be visible on the dashboard like
                // any other. The caller closes the run when it is genuinely done.
                revisions.updateRun(runId, stage = "READY", status = "READY", metrics = metricsJson)
                catalog?.invalidate()
                metrics.increment("ingest.published")
                metrics.record("ingest.revision", durationMs * 1_000_000) { "rev $rev" }
                progress("READY", 100, "Published revision $rev")
                logger.info { "rev $rev: published in ${durationMs}ms (${result.entities} entities, +${result.added} ~${result.changed} -${result.removed})" }
                result
            }
        } catch (e: Exception) {
            logger.error(e) { "rev $rev: ingestion failed" }
            metrics.increment("ingest.failed")
            runCatching {
                withGameLock {
                    writer.rollback(gameId, rev, revisions.nextWithData(gameId, rev))
                    revisions.setHasData(gameId, rev, false)
                }
            }.onFailure { logger.error(it) { "rev $rev: rollback after failure also failed" } }
            revisions.setStatus(gameId, rev, RevisionStatus.FAILED, null, e.message ?: e.javaClass.simpleName)
            revisions.updateRun(runId, status = "FAILED", error = e.stackTraceToString().take(4000), finished = true)
            progress("FAILED", 100, e.message ?: "Ingestion failed")
            throw if (e is IngestionException) e else IngestionException("Ingestion of rev $rev failed: ${e.message}", e)
        }
    }

    /** Remove a revision's data and visibility; it can be ingested again afterwards. */
    fun unpublish(rev: Int) {
        withGameLock {
            revisions.unpublish(gameId, rev)
            writer.rollback(gameId, rev, revisions.nextWithData(gameId, rev))
            revisions.setHasData(gameId, rev, false)
            revisions.setStatus(gameId, rev, RevisionStatus.DISCOVERED)
        }
        catalog?.invalidate()
    }

    private fun validate(rev: Int, results: Map<String, TypeWriteResult>) {
        dataSource.withConnection { c ->
            val counts = c.query(
                """SELECT type_id, count(*) FROM entity_version
                   WHERE game_id = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?) GROUP BY type_id""",
                gameId, rev, rev,
            ) { rs -> rs.getInt(1) to rs.getInt(2) }.toMap()
            results.forEach { (key, result) ->
                val stored = counts[game.type(key).id] ?: 0
                if (stored != result.total) {
                    throw IngestionException("Validation failed for $key at rev $rev: decoded ${result.total} entities, stored $stored")
                }
            }
            val overlaps = c.queryOne(
                """SELECT count(*) FROM entity_version a
                   JOIN entity_version b ON b.game_id = a.game_id AND b.type_id = a.type_id AND b.entity_id = a.entity_id AND b.valid_from > a.valid_from
                   WHERE a.game_id = ? AND (a.ingest_rev = ? OR a.closed_by_rev = ? OR b.ingest_rev = ? OR b.closed_by_rev = ?)
                     AND (a.valid_to IS NULL OR a.valid_to > b.valid_from)""",
                gameId, rev, rev, rev, rev,
            ) { it.getLong(1) } ?: 0L
            if (overlaps > 0) throw IngestionException("Validation failed at rev $rev: $overlaps overlapping version ranges")
            val missingPayloads = c.queryOne(
                """SELECT count(*) FROM entity_version v LEFT JOIN entity_payload p ON p.hash = v.payload_hash
                   WHERE v.game_id = ? AND v.ingest_rev = ? AND p.hash IS NULL""",
                gameId, rev,
            ) { it.getLong(1) } ?: 0L
            if (missingPayloads > 0) throw IngestionException("Validation failed at rev $rev: $missingPayloads versions without payload")
        }
    }

    private fun resultMetrics(result: IngestResult): Map<String, Any?> = mapOf(
        "durationMs" to result.durationMs,
        "stages" to result.stageMs,
        "entities" to result.entities,
        "added" to result.added,
        "changed" to result.changed,
        "removed" to result.removed,
        "types" to result.types.mapValues { (_, r) ->
            mapOf("total" to r.total, "added" to r.added, "changed" to r.changed, "removed" to r.removed)
        },
    )

    private fun <T> withGameLock(block: () -> T): T = dataSource.withConnection { c ->
        c.queryOne("SELECT pg_advisory_lock(?)", gameId.toLong()) { }
        try {
            block()
        } finally {
            c.queryOne("SELECT pg_advisory_unlock(?)", gameId.toLong()) { }
        }
    }
}
