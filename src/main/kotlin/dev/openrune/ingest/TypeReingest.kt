package dev.openrune.ingest

import mu.KotlinLogging
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Where a re-ingest has got to. Immutable and swapped wholesale, so a reader never sees a half
 * updated run.
 */
class TypeReingestStatus(
    val typeKey: String,
    val from: Int,
    /** Revisions the run will visit, fixed when it starts. */
    val total: Int,
    val done: Int,
    val rev: Int?,
    val stage: String,
    val message: String,
    /** Revisions where this type actually differed from the one before; the useful output. */
    val changedRevs: List<Int>,
    val entities: Int,
    val failed: Map<Int, String>,
    val running: Boolean,
    val cancelled: Boolean,
    val startedAt: Long,
    val finishedAt: Long?,
    val error: String?,
) {
    val percent: Int get() = if (total <= 0) 0 else ((done * 100) / total).coerceIn(0, 100)
}

/**
 * Re-decodes one entity type across every published revision and rewrites just that type.
 *
 * This exists for the case where a decoder was wrong: the revisions are imported and published,
 * and everything except the one broken type is correct, so re-ingesting them whole would be hours
 * of work to rewrite data that is already right. The job is deliberately operator-triggered rather
 * than automatic — it re-downloads a raw cache per revision.
 *
 * One run at a time, on its own thread, so it cannot overlap itself or starve the API.
 */
class TypeReingestRunner(
    private val job: (
        typeKey: String,
        from: Int,
        report: (TypeReingestReport) -> Unit,
        isCancelled: () -> Boolean,
    ) -> Unit,
) : AutoCloseable {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "type-reingest").apply { isDaemon = true }
    }
    private val state = AtomicReference<TypeReingestStatus?>()

    @Volatile
    private var cancelRequested = false

    fun status(): TypeReingestStatus? = state.get()

    fun isRunning(): Boolean = state.get()?.running == true

    /** Returns false when a run is already in flight; the caller turns that into a 409. */
    fun start(typeKey: String, from: Int): Boolean {
        synchronized(this) {
            if (isRunning()) return false
            cancelRequested = false
            state.set(
                TypeReingestStatus(
                    typeKey = typeKey, from = from, total = 0, done = 0, rev = null,
                    stage = "STARTING", message = "Preparing to re-ingest $typeKey from rev $from",
                    changedRevs = emptyList(), entities = 0, failed = emptyMap(),
                    running = true, cancelled = false,
                    startedAt = System.currentTimeMillis(), finishedAt = null, error = null,
                ),
            )
        }
        executor.execute { runJob(typeKey, from) }
        return true
    }

    fun cancel() {
        cancelRequested = true
    }

    private fun runJob(typeKey: String, from: Int) {
        val startedAt = state.get()?.startedAt ?: System.currentTimeMillis()
        try {
            job(typeKey, from, { report -> publish(typeKey, from, startedAt, report, running = true, error = null) }) {
                cancelRequested
            }
            finish(typeKey, from, startedAt, null)
        } catch (e: Exception) {
            logger.error(e) { "Re-ingest of $typeKey from rev $from failed" }
            finish(typeKey, from, startedAt, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun publish(
        typeKey: String,
        from: Int,
        startedAt: Long,
        report: TypeReingestReport,
        running: Boolean,
        error: String?,
    ) {
        state.set(
            TypeReingestStatus(
                typeKey = typeKey, from = from,
                total = report.total, done = report.done, rev = report.rev,
                stage = report.stage, message = report.message,
                changedRevs = report.changedRevs, entities = report.entities, failed = report.failed,
                running = running, cancelled = cancelRequested,
                startedAt = startedAt,
                finishedAt = if (running) null else System.currentTimeMillis(),
                error = error,
            ),
        )
    }

    /** Keeps the last report's totals so the finished state still says what the run achieved. */
    private fun finish(typeKey: String, from: Int, startedAt: Long, error: String?) {
        val last = state.get()
        val stage = when {
            error != null -> "FAILED"
            cancelRequested -> "CANCELLED"
            else -> "DONE"
        }
        val message = when {
            error != null -> error
            cancelRequested -> "Cancelled after ${last?.done ?: 0} of ${last?.total ?: 0} revisions"
            else -> "Re-ingested $typeKey across ${last?.done ?: 0} revision(s)"
        }
        publish(
            typeKey, from, startedAt,
            TypeReingestReport(
                total = last?.total ?: 0, done = last?.done ?: 0, rev = null,
                stage = stage, message = message,
                changedRevs = last?.changedRevs.orEmpty(), entities = last?.entities ?: 0,
                failed = last?.failed.orEmpty(),
            ),
            running = false,
            error = error,
        )
    }

    override fun close() {
        cancelRequested = true
        executor.shutdownNow()
    }
}

/** What the job hands back on each tick; the runner wraps it into a [TypeReingestStatus]. */
class TypeReingestReport(
    val total: Int,
    val done: Int,
    val rev: Int?,
    val stage: String,
    val message: String,
    val changedRevs: List<Int>,
    val entities: Int,
    val failed: Map<Int, String>,
)
