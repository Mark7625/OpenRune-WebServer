package dev.openrune.tools

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Ingests revisions from OpenRS2 caches through the full pipeline (download, decode, import,
 * validate, publish).
 *
 * Flags: `revs=1,240,241 game=OLDSCHOOL env=LIVE force=true`, or `new=true` to ask OpenRS2 for
 * anything newer than what is already published and ingest that. `new=true` is what the scheduled
 * job runs; it exits 0 having done nothing when there is no new cache, so "nothing to do" is not
 * reported as a failure.
 */
fun main(args: Array<String>) {
    val flags = ToolArgs(args)
    val wantsNew = flags.bool("new")
    val explicit = flags.ints("revs")
    require(wantsNew || explicit.isNotEmpty()) { "revs=... or new=true is required" }

    openPlatform(flags).use { platform ->
        val gameId = platform.game.game.id

        val revs = if (explicit.isNotEmpty()) explicit else {
            val latest = platform.revisions.latestPublished(gameId)
            // Only look past what is published; re-ingesting history is an explicit `revs=` job.
            platform.discovery.discover(minRev = (latest ?: 0) + 1)
            val pending = platform.ingestRevisions.list(gameId)
                .filter { !it.published && (latest == null || it.rev > latest) }
                .map { it.rev }
                .sorted()
            if (pending.isEmpty()) {
                logger.info { "No new ${platform.game.game.slug} cache on OpenRS2 (latest published: ${latest ?: "none"})" }
                return@use
            }
            logger.info { "New ${platform.game.game.slug} revision(s) to ingest: $pending" }
            pending
        }

        revs.forEach { rev ->
            val existing = platform.ingestRevisions.get(gameId, rev)
            if (existing?.published == true) {
                if (!flags.bool("force")) {
                    logger.info { "rev $rev: already published, skip (force=true to re-ingest)" }
                    return@forEach
                }
                platform.pipeline.unpublish(rev)
            }
            resetPeakHeap()
            val start = System.nanoTime()
            val result = platform.ingestRevision(rev, { p ->
                if (p.percent % 25 == 0) logger.info { "rev $rev ${p.stage} ${p.percent}% ${p.message}" }
            })
            logger.info {
                "rev $rev: ${result.entities} entities (+${result.added} ~${result.changed} -${result.removed}) " +
                    "stages=${result.stageMs} total=${(System.nanoTime() - start) / 1_000_000}ms peakHeap=${peakHeapMb()}MB"
            }
        }
    }
}
