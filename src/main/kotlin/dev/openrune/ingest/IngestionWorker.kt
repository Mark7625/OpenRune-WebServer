package dev.openrune.ingest

import dev.openrune.model.RevisionStatus
import dev.openrune.store.RevisionRepository
import dev.openrune.store.RevisionRow
import mu.KotlinLogging
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

class IngestionProgress(val rev: Int, val stage: String, val percent: Int, val message: String, val at: Long = System.currentTimeMillis())

/**
 * Background loop: discover new revisions, then ingest the lowest pending one. A single thread
 * per game stream bounds CPU and memory use; the API keeps serving published revisions meanwhile.
 */
class IngestionWorker(
    private val gameId: Int,
    private val repository: RevisionRepository,
    private val discovery: RevisionDiscovery?,
    private val runRevision: (rev: Int, progress: (IngestionProgress) -> Unit) -> Unit,
    private val pollIntervalMs: Long = 60_000,
    private val maxAttempts: Int = 3,
    private val minRevision: Int = 1,
    private val onProgress: (IngestionProgress) -> Unit = {},
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ingestion-$gameId").apply { isDaemon = true } }
    private val current = AtomicReference<IngestionProgress?>()

    @Volatile
    private var paused = false

    fun start() {
        executor.scheduleWithFixedDelay(::tick, 2_000, pollIntervalMs, TimeUnit.MILLISECONDS)
    }

    /** Run one cycle now (admin trigger). */
    fun wake() {
        executor.execute(::tick)
    }

    fun pause(value: Boolean) {
        paused = value
    }

    fun isPaused(): Boolean = paused

    fun active(): IngestionProgress? = current.get()

    private fun tick() {
        if (paused) return
        try {
            discovery?.discover(minRevision)
            val next = nextPending() ?: return
            run(next.rev)
        } catch (e: Exception) {
            logger.error(e) { "Ingestion cycle failed" }
        }
    }

    private fun nextPending(): RevisionRow? =
        repository.list(gameId).firstOrNull { r ->
            r.rev >= minRevision && !r.published && (r.status == RevisionStatus.DISCOVERED || (r.status == RevisionStatus.FAILED && r.attempts < maxAttempts))
        }

    private fun run(rev: Int) {
        try {
            runRevision(rev) { p ->
                current.set(p)
                onProgress(p)
            }
        } catch (e: Exception) {
            logger.warn { "rev $rev: ${e.message}" }
        } finally {
            current.set(null)
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
