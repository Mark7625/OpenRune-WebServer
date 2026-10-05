package dev.openrune.api

import dev.openrune.ingest.IngestionProgress
import mu.KotlinLogging
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Surfaces the ingestion currently in flight, wherever it is running.
 *
 * Progress used to be an in-memory field set by the worker inside this process, so an import run by
 * the scheduled job was invisible to the dashboard until it published. The ingesting process now
 * records progress on its `ingest_run` row, and this polls that row — so the ingestion page shows
 * the same live stage and percentage whether the import is in this process or another one, and the
 * API still never needs restarting to pick a revision up.
 *
 * Polling rather than listening keeps the two processes sharing nothing but the database.
 */
class IngestionWatch(
    private val ctx: ApiContext,
    private val onChange: () -> Unit,
    private val intervalMs: Long = 2_000,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ingestion-watch").apply { isDaemon = true }
    }

    /** Last value pushed out, so a quiet import does not broadcast every tick. */
    private var last: IngestionProgress? = null

    fun start() {
        executor.scheduleWithFixedDelay(::poll, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    private fun poll() {
        val next = runCatching { read() }.getOrElse { e ->
            logger.debug(e) { "Could not read active ingestion" }
            return
        }
        if (same(next, last)) return
        last = next
        ctx.ingestionProgress = next
        runCatching(onChange).onFailure { logger.debug(it) { "Ingestion change listener failed" } }
    }

    private fun read(): IngestionProgress? {
        val run = ctx.revisions.activeRun(ctx.game.game.id) ?: return null
        val stage = run.stage ?: run.status
        if (stage == "READY" || stage == "FAILED") return null
        return IngestionProgress(
            rev = run.rev,
            stage = stage,
            percent = run.percent ?: 0,
            message = run.message ?: "Ingesting revision ${run.rev}",
        )
    }

    private fun same(a: IngestionProgress?, b: IngestionProgress?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> a.rev == b.rev && a.stage == b.stage && a.percent == b.percent && a.message == b.message
    }

    override fun close() {
        executor.shutdownNow()
    }
}
