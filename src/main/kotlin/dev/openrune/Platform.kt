package dev.openrune

import dev.openrune.cache.util.XteaLoader
import dev.openrune.cdn.CdnPublisher
import dev.openrune.db.Database
import dev.openrune.db.DatabaseConfig
import dev.openrune.ingest.IngestResult
import dev.openrune.ingest.IngestionPipeline
import dev.openrune.ingest.IngestionProgress
import dev.openrune.ingest.RawCacheStore
import dev.openrune.ingest.RenderReuse
import dev.openrune.ingest.RevisionDiscovery
import dev.openrune.ingest.RevisionSource
import dev.openrune.ingest.osrs.OsrsRevisionSource
import dev.openrune.metrics.Metrics
import dev.openrune.model.OsrsEntityTypes
import dev.openrune.model.RevisionStatus
import dev.openrune.query.DiffQueries
import dev.openrune.query.EntityQueries
import dev.openrune.query.RevisionCatalog
import dev.openrune.store.GameRegistry
import dev.openrune.store.RevisionRepository
import dev.openrune.store.VersionWriter
import mu.KotlinLogging
import java.net.URL

private val logger = KotlinLogging.logger {}

/** Minimum gap between progress writes during a stage; stage changes bypass it. */
private const val PROGRESS_WRITE_MS = 1_000L

/**
 * Database, registry, repositories, query objects and the ingestion pipeline for one game
 * stream. Shared by the HTTP server ([App]) and the command line tools, which need everything
 * except the server and the background worker.
 */
class Platform(
    val config: ServerConfig,
    dbConfig: DatabaseConfig,
    types: List<dev.openrune.model.EntityTypeDef> = OsrsEntityTypes.all,
) : AutoCloseable {

    val db: Database = Database.connect(dbConfig)
    val game: GameRegistry.RegisteredGame = GameRegistry(db.ingest).register(config.gameType, config.environment, types)
    val metrics = Metrics()
    val revisions = RevisionRepository(db.api)
    val ingestRevisions = RevisionRepository(db.ingest)
    val catalog = RevisionCatalog(revisions, game.game.id)
    val entities = EntityQueries(db.api, game)
    val diffs = DiffQueries(db.api, game)
    val writer = VersionWriter(db.ingest)
    val rawCaches = RawCacheStore(game.game)
    val pipeline = IngestionPipeline(db.ingest, game, ingestRevisions, writer, metrics, catalog)
    val discovery = RevisionDiscovery(game.game, ingestRevisions)

    /**
     * Full pipeline for one revision from OpenRS2: download, decode, import, validate, publish,
     * then the best-effort CDN publish.
     *
     * CDN upload is deliberately the last step and never fails the ingest — the revision is already
     * published and queryable by then, and assets are a separate store. Pass [cdn] = false to skip
     * it entirely, which a backfill does so that uploading thousands of sprites per revision does
     * not sit between imports; it uploads once at the end instead.
     */
    fun ingestRevision(rev: Int, progress: (IngestionProgress) -> Unit = {}, cdn: Boolean = true): IngestResult {
        val gameId = game.game.id
        val row = ingestRevisions.get(gameId, rev) ?: run {
            val build = discovery.build(rev) ?: error("Revision $rev is not on OpenRS2 for ${game.game.slug}")
            ingestRevisions.discover(gameId, rev, build.cacheId, build.timestamp)
            ingestRevisions.get(gameId, rev)!!
        }
        val cacheId = row.sourceCacheId ?: discovery.build(rev)?.cacheId ?: error("No OpenRS2 cache id for rev $rev")
        return ingestWith(rev, progress) {
            ingestRevisions.setStatus(gameId, rev, RevisionStatus.DOWNLOADING, "download")
            progress(IngestionProgress(rev, "DOWNLOADING", 0, "Downloading rev $rev"))
            val cachePath = metrics.time("ingest.download", { "rev $rev" }) {
                rawCaches.ensure(rev, cacheId) { pct, msg -> progress(IngestionProgress(rev, "DOWNLOADING", pct, msg)) }
            }
            val xteas = if (OsrsRevisionSource.xteasAvailable(rev)) downloadXteas(cacheId) else null
            Prepared({ OsrsRevisionSource(rev, cachePath, config.environment, xteas, renderReuse(rev)) }) {
                if (cdn) publishCdn(rev, progress)
            }
        }
    }

    /**
     * Lets the renderer carry unchanged images forward instead of re-rendering them. Queried lazily,
     * so by the time the image types are imported the definitions and models for this revision are
     * already written and the answers are about this revision.
     *
     * Null for the first revision of a game: there is nothing to carry forward, so everything
     * renders.
     */
    private fun renderReuse(rev: Int): RenderReuse? {
        if (catalog.published().none { it < rev }) return null
        return object : RenderReuse {
            override fun changedIds(typeKey: String): Set<Int> =
                game.typeOrNull(typeKey)?.let { entities.changedAt(it, rev).toSet() } ?: emptySet()

            override fun previousImageHashes(typeKey: String): Map<Int, ByteArray> =
                game.typeOrNull(typeKey)?.let { entities.blobHashes(it, rev - 1) } ?: emptyMap()
        }
    }

    /** Ingest a revision from any source (legacy `.bin`, synthetic data) without the download stage. */
    fun ingestFrom(rev: Int, openSource: () -> RevisionSource, progress: (IngestionProgress) -> Unit = {}): IngestResult {
        val gameId = game.game.id
        if (ingestRevisions.get(gameId, rev) == null) ingestRevisions.discover(gameId, rev, null, null)
        return ingestWith(rev, progress) { Prepared(openSource) {} }
    }

    private class Prepared(val open: () -> RevisionSource, val afterPublish: () -> Unit)

    private fun ingestWith(rev: Int, progress: (IngestionProgress) -> Unit, prepare: () -> Prepared): IngestResult {
        val gameId = game.game.id
        ingestRevisions.markStarted(gameId, rev, rawCaches.directory(rev).path)
        val runId = ingestRevisions.startRun(gameId, rev)
        // Every progress tick in the pipeline funnels through here, so persisting once at this
        // point is enough for the API to show live progress for an import in another process.
        val publish = recordingProgress(runId, progress)
        try {
            val prepared = prepare()
            val result = pipeline.ingest(rev, runId, prepared.open) { stage, pct, msg ->
                publish(IngestionProgress(rev, stage, pct, msg))
            }
            // The revision is published and queryable from here; the CDN upload below is the last
            // step and cannot unpublish it.
            prepared.afterPublish()
            ingestRevisions.updateRun(runId, stage = "READY", finished = true)
            publish(IngestionProgress(rev, "READY", 100, "Published revision $rev"))
            return result
        } catch (e: Exception) {
            if (ingestRevisions.get(gameId, rev)?.status != RevisionStatus.FAILED) {
                ingestRevisions.setStatus(gameId, rev, RevisionStatus.FAILED, null, e.message)
                ingestRevisions.updateRun(runId, status = "FAILED", error = e.stackTraceToString().take(4000), finished = true)
            }
            progress(IngestionProgress(rev, "FAILED", 100, e.message ?: "Ingestion failed"))
            throw e
        }
    }

    /**
     * Wraps a progress callback so each tick is also written to `ingest_run`. Ticks arrive far more
     * often than they are worth a round trip, so a write only happens when the stage changes or
     * after [PROGRESS_WRITE_MS]; terminal stages always write so the last state is never lost.
     */
    private fun recordingProgress(runId: Long, downstream: (IngestionProgress) -> Unit): (IngestionProgress) -> Unit {
        var lastStage: String? = null
        var lastWriteAt = 0L
        return { p ->
            downstream(p)
            val now = System.currentTimeMillis()
            val terminal = p.stage == "READY" || p.stage == "FAILED"
            if (terminal || p.stage != lastStage || now - lastWriteAt >= PROGRESS_WRITE_MS) {
                lastStage = p.stage
                lastWriteAt = now
                runCatching { ingestRevisions.updateRunProgress(runId, p.stage, p.percent, p.message) }
                    .onFailure { logger.debug(it) { "Could not record ingest progress for run $runId" } }
            }
        }
    }

    private fun downloadXteas(cacheId: Int): Map<Int, IntArray> {
        val url = "https://archive.openrs2.org/caches/runescape/$cacheId/keys.json"
        return runCatching { XteaLoader.parseXteas(URL(url).readText()) }
            .getOrElse { e -> logger.warn { "Failed downloading xteas from $url: ${e.message}" }; emptyMap() }
    }

    val cdnPublisher = CdnPublisher(config.spriteCdn, game, entities, rawCaches)

    /**
     * Uploads a published revision's raw assets. Best effort by design: a CDN outage must not
     * unpublish a revision, so failures are logged and counted, not rethrown. Re-run them later
     * with `./gradlew publishCdn -PtoolArgs="revs=N repair=true"`.
     */
    /**
     * Uploads one revision's sprites, textures and models. Safe to call long after the import:
     * everything it needs is already in PostgreSQL or the raw cache on disk. Never throws — a CDN
     * outage must not turn a published revision into a failed one.
     */
    /**
     * [onCdn] sees the per-object detail (which asset kind, how many of its files are up); [progress]
     * sees the same thing flattened into the stage/percent/message an `ingest_run` row can hold.
     */
    fun publishCdnFor(
        rev: Int,
        progress: (IngestionProgress) -> Unit = {},
        onCdn: (CdnPublisher.CdnProgress) -> Unit = {},
    ) = publishCdn(rev, progress, onCdn)

    private fun publishCdn(
        rev: Int,
        progress: (IngestionProgress) -> Unit,
        onCdn: (CdnPublisher.CdnProgress) -> Unit = {},
    ) {
        if (!config.spriteCdn.canUpload) return
        try {
            progress(IngestionProgress(rev, "CDN", 0, "Publishing sprites, textures and models to CDN"))
            val report = cdnPublisher.publish(
                rev,
                onProgress = { msg -> logger.info { msg } },
                onStage = { p ->
                    onCdn(p)
                    progress(IngestionProgress(rev, "CDN", p.percent, p.label))
                },
            )
            if (!report.ok) metrics.increment("ingest.cdn.failed")
            logger.info { "rev $rev: CDN publish $report" }
        } catch (e: Exception) {
            logger.error(e) { "rev $rev: CDN publish failed (revision stays published)" }
            metrics.increment("ingest.cdn.failed")
        }
    }

    override fun close() {
        db.close()
    }
}
