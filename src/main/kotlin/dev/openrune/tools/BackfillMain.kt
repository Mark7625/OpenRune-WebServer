package dev.openrune.tools

import dev.openrune.Platform
import dev.openrune.store.BackfillRepository
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Imports a list of older revisions, newest first, yielding to newly released caches as they
 * appear.
 *
 * Flags: `revs=238,239,240` or `from=200 to=240`, plus the usual `game=` / `env=`.
 *
 * Before each queued revision it asks OpenRS2 whether anything newer than the latest published
 * revision has shown up; if so that is imported first and the queue resumes afterwards. The check
 * happens between revisions rather than during one: an import is the unit of work that either
 * publishes or does not, and abandoning one half way would throw away the work for no gain.
 *
 * Progress is written to the `backfill` table, so `/admin/overview` can report the queue while
 * this runs in its own process.
 */
fun main(args: Array<String>) {
    val flags = ToolArgs(args)
    openPlatform(flags).use { platform ->
        val gameId = platform.game.game.id
        val backfill = BackfillRepository(platform.db.ingest)

        val queue = requestedRevisions(flags, platform).toMutableList()
        require(queue.isNotEmpty()) { "revs=... or from=/to= is required" }

        backfill.start(gameId, queue)
        logger.info { "Backfill queued ${queue.size} revision(s), newest first: ${queue.take(10)}${if (queue.size > 10) " …" else ""}" }

        // Assets are uploaded once at the end rather than between imports: a revision's sprites and
        // models are thousands of small objects, and that upload sitting between every import would
        // dominate the run. The data is queryable from the moment each revision publishes.
        val uploaded = ArrayList<Int>()
        try {
            while (queue.isNotEmpty()) {
                liveReleases(platform).forEach { rev ->
                    logger.info { "New cache $rev released; importing it before continuing the backfill" }
                    backfill.pauseFor(gameId, rev)
                    // A live release is what people are looking at, so its assets go up immediately
                    // rather than waiting for the backfill to finish.
                    runCatching { ingest(platform, rev, cdn = true) }
                        .onFailure { logger.error(it) { "Live revision $rev failed; continuing the backfill" } }
                    backfill.pauseFor(gameId, null)
                }

                val rev = queue.removeAt(0)
                val ok = runCatching { ingest(platform, rev, cdn = false) }
                    .onFailure { logger.error(it) { "Backfill revision $rev failed; continuing with the rest" } }
                    .isSuccess
                if (ok) uploaded.add(rev)
                backfill.complete(gameId, rev, ok)
            }

            if (uploaded.isNotEmpty()) {
                logger.info { "All revisions imported; uploading assets for ${uploaded.size} revision(s) to the CDN" }
                // Newest first, so the revisions people are most likely to open get their assets
                // back soonest.
                uploaded.sortedDescending().forEach { rev ->
                    // Recorded as a run so the dashboard shows this phase the same way it shows an
                    // import; without it the page would look idle for the length of the upload.
                    val runId = platform.ingestRevisions.startRun(gameId, rev)
                    platform.ingestRevisions.updateRunProgress(runId, "CDN", 0, "Uploading assets for rev $rev")
                    try {
                        platform.publishCdnFor(rev) { p ->
                            platform.ingestRevisions.updateRunProgress(runId, "CDN", p.percent, p.message)
                        }
                    } finally {
                        platform.ingestRevisions.updateRun(runId, stage = "READY", status = "READY", finished = true)
                    }
                }
            }
            logger.info { "Backfill finished" }
        } finally {
            backfill.clear(gameId)
        }
    }
}

/** Highest first, de-duplicated, skipping anything already published. */
private fun requestedRevisions(flags: ToolArgs, platform: Platform): List<Int> {
    val explicit = flags.ints("revs")
    val from = flags.int("from")
    val to = flags.int("to")
    val requested = when {
        explicit.isNotEmpty() -> explicit
        from != null && to != null -> (minOf(from, to)..maxOf(from, to)).toList()
        else -> emptyList()
    }
    val published = platform.catalog.published().toSet()
    return requested.distinct()
        .filter { rev ->
            if (rev in published) logger.info { "rev $rev already published, skipping" }
            rev !in published
        }
        .sortedDescending()
}

/**
 * Revisions on OpenRS2 newer than everything published — a live game update. Returned oldest
 * first so they are imported in release order.
 */
private fun liveReleases(platform: Platform): List<Int> {
    val latest = platform.catalog.latest() ?: return emptyList()
    return runCatching {
        platform.discovery.discover(minRev = latest + 1)
        platform.ingestRevisions.list(platform.game.game.id)
            .filter { !it.published && it.rev > latest }
            .map { it.rev }
            .sorted()
    }.getOrElse {
        logger.warn(it) { "Could not check OpenRS2 for new caches; continuing the backfill" }
        emptyList()
    }
}

private fun ingest(platform: Platform, rev: Int, cdn: Boolean) {
    resetPeakHeap()
    val start = System.nanoTime()
    val result = platform.ingestRevision(rev, { p ->
        if (p.percent % 25 == 0) logger.info { "rev $rev ${p.stage} ${p.percent}% ${p.message}" }
    }, cdn = cdn)
    logger.info {
        "rev $rev: ${result.entities} entities (+${result.added} ~${result.changed} -${result.removed}) " +
            "total=${(System.nanoTime() - start) / 1_000_000}ms peakHeap=${peakHeapMb()}MB"
    }
}
