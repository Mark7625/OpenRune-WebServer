package dev.openrune

import dev.openrune.cache.util.XteaLoader
import dev.openrune.cdn.CdnPublisher
import dev.openrune.db.Database
import dev.openrune.db.DatabaseConfig
import dev.openrune.ingest.IngestResult
import dev.openrune.ingest.IngestionPipeline
import dev.openrune.ingest.IngestionProgress
import dev.openrune.ingest.ManualCacheLink
import dev.openrune.ingest.RawCacheStore
import dev.openrune.ingest.RenderReuse
import dev.openrune.ingest.RevisionDiscovery
import dev.openrune.ingest.RevisionSource
import dev.openrune.ingest.TypeReingestReport
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
        // A revision filed from a pasted link downloads that link; everything else comes from the
        // OpenRS2 catalogue. A link that happens to point at OpenRS2 still yields a cache id, which
        // is all the XTEA keys need.
        val link = row.sourceUrl
        val cacheId = row.sourceCacheId
            ?: link?.let { ManualCacheLink.openRs2CacheId(it) }
            ?: if (link == null) discovery.build(rev)?.cacheId else null
        if (link == null && cacheId == null) error("No OpenRS2 cache id for rev $rev")
        return ingestWith(rev, progress) {
            ingestRevisions.setStatus(gameId, rev, RevisionStatus.DOWNLOADING, "download")
            progress(IngestionProgress(rev, "DOWNLOADING", 0, "Downloading rev $rev"))
            val cachePath = metrics.time("ingest.download", { "rev $rev" }) {
                rawCacheFor(rev, link, cacheId) { pct, msg -> progress(IngestionProgress(rev, "DOWNLOADING", pct, msg)) }
            }
            val xteas = if (OsrsRevisionSource.xteasAvailable(rev)) xteasFor(rev, cacheId) else null
            Prepared({ OsrsRevisionSource(rev, cachePath, config.environment, xteas, renderReuse(rev)) }) {
                if (cdn) publishCdn(rev, progress)
            }
        }
    }

    /**
     * Files a cache link so the worker ingests it like any discovered revision, and returns the
     * revision it was filed under. [rev] can be left out for an OpenRS2 link — the catalogue says
     * which build that cache is — but is required for a link hosted anywhere else, because nothing
     * in a raw cache states its own revision.
     *
     * An already published revision is unpublished first, exactly as re-ingesting one from the
     * dashboard does: its rows are rolled back and it returns when the new import publishes.
     */
    fun addManualRevision(url: String, rev: Int? = null): Int {
        val link = ManualCacheLink.normalise(url)
        val cacheId = ManualCacheLink.openRs2CacheId(link)
        val catalogued = cacheId?.let { runCatching { discovery.byCacheId(it) }.getOrNull() }
        require(discovery.matchesStream(catalogued?.stream)) {
            "OpenRS2 cache $cacheId is a ${catalogued?.stream} cache; this server serves ${game.game.slug}"
        }
        val resolved = rev ?: catalogued?.rev
            ?: error("Cannot tell which revision $link holds; add the revision number alongside the link")
        require(resolved > 0) { "Revision must be positive, got $resolved" }
        val gameId = game.game.id
        if (ingestRevisions.get(gameId, resolved)?.published == true) pipeline.unpublish(resolved)
        ingestRevisions.setManualSource(gameId, resolved, link, cacheId, catalogued?.timestamp)
        logger.info { "Filed cache link $link as ${game.game.slug} revision $resolved" }
        return resolved
    }

    /** Extracted raw cache for [rev], downloading it from its pasted link or OpenRS2 id if absent. */
    private fun rawCacheFor(rev: Int, link: String?, cacheId: Int?, progress: (Int, String) -> Unit): java.io.File =
        if (link != null) rawCaches.ensureFrom(rev, link, progress) else rawCaches.ensure(rev, cacheId!!, progress)

    /** The same, resolving the source from the revision row the way [ingestRevision] does. */
    private fun rawCacheFor(rev: Int, progress: (Int, String) -> Unit): java.io.File {
        val row = ingestRevisions.get(game.game.id, rev)
        val link = row?.sourceUrl
        val cacheId = row?.sourceCacheId
            ?: link?.let { ManualCacheLink.openRs2CacheId(it) }
            ?: if (link == null) discovery.build(rev)?.cacheId else null
        if (link == null && cacheId == null) error("No cache source recorded for rev $rev")
        return rawCacheFor(rev, link, cacheId, progress)
    }

    /**
     * Re-decodes one entity type across every published revision from [from] upward and rewrites
     * only that type, leaving every other type's rows untouched.
     *
     * This is the repair path for a decoder that was wrong: the revisions are already imported and
     * published, so re-ingesting them whole would spend hours rewriting data that is already
     * correct. It costs one raw cache download per revision instead.
     *
     * The type is cleared first and rebuilt in ascending order. That matters — [VersionWriter.writeType]
     * merges against what is stored, so writing over stale rows would leave them covering their
     * revisions. Each revision is then a plain append (`nextRev = null`), which is what keeps an
     * unchanged entity on one open range rather than one row per revision.
     *
     * A cache this job downloads is deleted again once its revision is written, so a 240 revision
     * pass does not leave 240 raw caches behind; caches that were already on disk are kept.
     */
    fun reingestType(
        typeKey: String,
        from: Int,
        report: (TypeReingestReport) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val gameId = game.game.id
        val type = game.typeOrNull(typeKey) ?: error("Unknown entity type '$typeKey'")
        val revs = catalog.published().filter { it >= from }
        if (revs.isEmpty()) error("No published revisions at or above $from")

        val changedRevs = ArrayList<Int>()
        val failed = LinkedHashMap<Int, String>()
        var done = 0
        var entities = 0
        fun tick(rev: Int?, stage: String, message: String) =
            report(TypeReingestReport(revs.size, done, rev, stage, message, changedRevs.toList(), entities, LinkedHashMap(failed)))

        tick(null, "CLEARING", "Removing the stored $typeKey rows before the rebuild")
        val removed = pipeline.withGameLock { writer.deleteType(gameId, type.id) }
        logger.info { "Re-ingest $typeKey: cleared $removed stored row(s), rebuilding over ${revs.size} revision(s) from ${revs.first()}" }

        for (rev in revs) {
            if (isCancelled()) {
                logger.info { "Re-ingest $typeKey: cancelled before rev $rev" }
                break
            }
            val keepCache = rawCaches.has(rev)
            try {
                tick(rev, "DOWNLOADING", "Fetching the rev $rev cache")
                val cachePath = rawCacheFor(rev) { _, msg -> tick(rev, "DOWNLOADING", msg) }

                tick(rev, "WRITING", "Decoding $typeKey for rev $rev")
                // No xteas and no render reuse: both are for map regions and rendered images, and
                // this only ever pulls one type's snapshots.
                val result = OsrsRevisionSource(rev, cachePath, config.environment, null, null).use { source ->
                    pipeline.withGameLock {
                        writer.writeType(gameId, type.id, rev, null, source.snapshots(typeKey))
                    }
                }
                entities = result.total
                if (result.added + result.changed + result.removed > 0) changedRevs += rev
                logger.info {
                    "Re-ingest $typeKey rev $rev: total=${result.total} +${result.added} ~${result.changed} -${result.removed}"
                }
            } catch (e: Exception) {
                // One unreadable cache should not abandon the other 200 revisions; the revision is
                // recorded and the pass carries on, leaving a gap the operator can see.
                logger.warn(e) { "Re-ingest $typeKey rev $rev failed" }
                failed[rev] = e.message ?: e.javaClass.simpleName
            } finally {
                if (!keepCache) runCatching { rawCaches.delete(rev) }
            }
            done++
            tick(rev, "WRITING", "Re-ingested $typeKey through rev $rev")
        }

        // The rows changed under revisions that are already published, so every cached view and
        // every browser ETag has to be retired explicitly.
        ingestRevisions.bumpPublishStamp(gameId)
        catalog.invalidate()
        tick(null, "PUBLISHING", "Refreshed caches for the rewritten $typeKey rows")
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

    /**
     * XTEA keys come from the cache's OpenRS2 entry, so a link hosted elsewhere has none. Map
     * locations then stay encrypted, which is worth saying out loud rather than ingesting a
     * revision with a silently empty map.
     */
    private fun xteasFor(rev: Int, cacheId: Int?): Map<Int, IntArray>? {
        if (cacheId != null) return downloadXteas(cacheId)
        logger.warn { "rev $rev: no OpenRS2 cache id for this source, so no XTEA keys; map regions will not decode" }
        return null
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
