package dev.openrune

import dev.openrune.api.AdminContext
import dev.openrune.api.ApiContext
import dev.openrune.api.ApiServer
import dev.openrune.api.HotRevisions
import dev.openrune.api.IngestionWatch
import dev.openrune.api.SpriteZipService
import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.tools.OpenRS2
import dev.openrune.db.DatabaseConfig
import dev.openrune.ingest.IngestionProgress
import dev.openrune.ingest.IngestionWorker
import dev.openrune.api.SseEventType
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/** The PostgreSQL-backed server: a [Platform] plus the background worker and the HTTP API. */
class App(private val config: ServerConfig, dbConfig: DatabaseConfig) : AutoCloseable {

    val platform = Platform(config, dbConfig)

    val api = ApiContext(
        game = platform.game,
        catalog = platform.catalog,
        entities = platform.entities,
        diffs = platform.diffs,
        revisions = platform.revisions,
        metrics = platform.metrics,
        cdn = config.spriteCdn,
        rawCaches = platform.rawCaches,
        navDisplayNameOverrides = config.navDisplayNameOverrides,
        port = config.port,
        sourceCacheId = config.cacheID,
    )

    private val ingestEnabled = envOrProp("OPENRUNE_INGEST_ENABLED")?.lowercase() !in setOf("0", "false", "no", "off")

    private val worker: IngestionWorker? = if (ingestEnabled) {
        IngestionWorker(
            gameId = platform.game.game.id,
            repository = platform.ingestRevisions,
            discovery = platform.discovery,
            runRevision = { rev, progress -> platform.ingestRevision(rev, progress) },
            pollIntervalMs = envOrProp("OPENRUNE_INGEST_POLL_MS")?.toLongOrNull() ?: 60_000,
            maxAttempts = envOrProp("OPENRUNE_INGEST_MAX_ATTEMPTS")?.toIntOrNull() ?: 3,
            minRevision = minRevision(),
            onProgress = ::onProgress,
        )
    } else null

    private val zips: SpriteZipService = SpriteZipService(
        api,
        File(CachePathHelper.getDiffBinaryDirectory(config.gameType, config.environment).parentFile, "zips"),
    ) { type, data -> server.broadcast(type, data) }

    val server: ApiServer = ApiServer(
        api,
        AdminContext(platform.db, platform.pipeline, worker, platform.discovery, envOrProp("OPENRUNE_ADMIN_TOKEN"), System.currentTimeMillis()),
        zips,
    )

    private val hot = HotRevisions(api, newest = envOrProp("OPENRUNE_HOT_REVISIONS")?.toIntOrNull() ?: 2)

    /**
     * Picks up imports run by the scheduled job in its own process. Harmless when the worker is
     * in-process too: that path already pushes the same progress, and this just agrees with it.
     */
    private val ingestionWatch = IngestionWatch(api, onChange = {
        if (api.ingestionProgress == null) hot.refresh()
        server.broadcast(SseEventType.STATUS, server.status())
    })

    private fun onProgress(p: IngestionProgress) {
        api.ingestionProgress = if (p.stage == "READY" || p.stage == "FAILED") null else p
        if (p.stage == "READY") hot.refresh()
        server.broadcast(SseEventType.STATUS, server.status())
    }

    fun start() {
        api.hotRevisions = hot.select()
        server.start()
        hot.start()
        ingestionWatch.start()
        val latest = platform.catalog.latest()
        if (latest == null) {
            logger.info { "No published revision yet for ${platform.game.game.slug}; serving status until the first ingestion completes" }
        } else {
            logger.info { "Serving ${platform.game.game.slug}: ${platform.catalog.published().size} published revisions, latest $latest" }
        }
        worker?.start()
    }

    /** Lowest revision the worker ingests automatically: configured, else what is published, else the configured cache id's revision. */
    private fun minRevision(): Int {
        envOrProp("OPENRUNE_INGEST_MIN_REV")?.toIntOrNull()?.let { return it }
        platform.revisions.latestPublished(platform.game.game.id)?.let { return it }
        if (config.cacheID > 0) {
            runCatching { OpenRS2.loadCaches() }
            OpenRS2.allCaches.firstOrNull { it.id == config.cacheID }?.builds?.firstOrNull()?.major?.let { return it }
        }
        return runCatching { platform.discovery.builds().lastOrNull()?.rev }.getOrNull() ?: 1
    }

    override fun close() {
        ingestionWatch.close()
        hot.close()
        worker?.close()
        zips.close()
        server.stop()
        platform.close()
    }
}
