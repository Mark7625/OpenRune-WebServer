package dev.openrune.api

import com.zaxxer.hikari.HikariDataSource
import dev.openrune.db.Database
import dev.openrune.db.query
import dev.openrune.db.withConnection
import dev.openrune.ingest.IngestionPipeline
import dev.openrune.ingest.IngestionWorker
import dev.openrune.ingest.RevisionDiscovery
import dev.openrune.model.RevisionStatus
import dev.openrune.store.RevisionRow
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

class AdminContext(
    val db: Database,
    val pipeline: IngestionPipeline,
    val worker: IngestionWorker?,
    val discovery: RevisionDiscovery?,
    val token: String?,
    val startedAt: Long,
)

/**
 * Developer / operator endpoints under `/admin`. Gated by `OPENRUNE_ADMIN_TOKEN` (header
 * `X-Admin-Token` or `?token=`); without a configured token only loopback clients are allowed.
 */
fun Route.adminRoutes(ctx: ApiContext, admin: AdminContext) {
    // Listed so the docs page describes the whole surface. They are gated by OPENRUNE_ADMIN_TOKEN
    // (header `X-Admin-Token` or `?token=`), so documenting them exposes nothing.
    listOf(
        Triple("GET /admin/overview", "Revision counts, the ingestion in flight, any backfill queue, worker state.", "/admin/overview"),
        Triple("GET /admin/revisions", "Every revision with status, source cache id and metrics.", "/admin/revisions"),
        Triple("GET /admin/revisions/{rev}", "One revision plus its ingest run history.", "/admin/revisions/241"),
        Triple("POST /admin/revisions/{rev}/ingest", "Queue a revision for ingestion.", "/admin/revisions/241/ingest"),
        Triple("POST /admin/revisions/{rev}/unpublish", "Hide a published revision from the API.", "/admin/revisions/241/unpublish"),
        Triple("GET /admin/runs", "Recent ingest runs. Query: limit.", "/admin/runs?limit=30"),
        Triple("GET /admin/metrics", "Timers and counters: slowest endpoints and slow operations.", "/admin/metrics"),
        Triple("GET /admin/db", "Connection pool state and table sizes.", "/admin/db"),
        Triple("GET /admin/memory", "Heap, threads and peak usage.", "/admin/memory"),
        Triple("GET /admin/cache", "Response cache entry count and hit rate.", "/admin/cache"),
        Triple("POST /admin/worker/{action}", "Pause or resume the background ingestion worker.", "/admin/worker/pause"),
    ).forEach { (methodAndPath, description, example) ->
        val (method, path) = methodAndPath.split(' ', limit = 2)
        EndpointRegistry.registerEndpoint(method, path, description, "Admin", null, "application/json", listOf(example))
    }

    // Read-only here; the backfill tool in its own process owns the queue.
    val backfill = dev.openrune.store.BackfillRepository(admin.db.api)

    suspend fun ApplicationCall.authorized(): Boolean {
        val token = admin.token
        val ok = if (token.isNullOrBlank()) {
            val host = request.local.remoteHost
            host == "127.0.0.1" || host == "::1" || host == "localhost" || host == "0:0:0:0:0:0:0:1"
        } else {
            request.header("X-Admin-Token") == token || request.queryParameters["token"] == token
        }
        if (!ok) respond(HttpStatusCode.Forbidden, mapOf("error" to "admin access denied"))
        return ok
    }

    fun revisionJson(r: RevisionRow): Map<String, Any?> = mapOf(
        "rev" to r.rev,
        "status" to r.status.name,
        "stage" to r.stage,
        "published" to r.published,
        "publishedAt" to r.publishedAt?.toString(),
        "hasData" to r.hasData,
        "attempts" to r.attempts,
        "error" to r.error,
        "sourceCacheId" to r.sourceCacheId,
        "sourceTimestamp" to r.sourceTimestamp?.toString(),
        "rawPath" to r.rawPath,
        "metrics" to r.metrics?.let { com.google.gson.JsonParser.parseString(it) },
        "updatedAt" to r.updatedAt?.toString(),
    )

    route("/admin") {
        get("/overview") {
            if (!call.authorized()) return@get
            val rows = ctx.revisions.list(ctx.game.game.id)
            val active = ctx.ingestionProgress
            call.respond(
                mapOf(
                    "game" to mapOf("id" to ctx.game.game.id, "slug" to ctx.game.game.slug, "name" to ctx.game.game.name, "environment" to ctx.game.game.environment.name),
                    "latestPublished" to ctx.catalog.latest(),
                    "latestDiscovered" to rows.maxOfOrNull { it.rev },
                    "published" to rows.count { it.published },
                    "byStatus" to rows.groupingBy { it.status.name }.eachCount(),
                    "ingestion" to active?.let { mapOf("rev" to it.rev, "stage" to it.stage, "percent" to it.percent, "message" to it.message, "at" to it.at) },
                    "backfill" to runCatching { backfill.get(ctx.game.game.id) }.getOrNull()?.let {
                        mapOf(
                            "pending" to it.pending, "done" to it.done, "failed" to it.failed,
                            "total" to it.total, "pausedFor" to it.pausedFor,
                            // Null until the imports finish and the upload phase opens.
                            "cdn" to it.cdn.takeIf { c -> c.running }?.let { c ->
                                mapOf(
                                    "pending" to c.pending, "done" to c.done, "total" to c.total,
                                    "rev" to c.rev, "stage" to c.stage,
                                    "files" to c.files, "filesTotal" to c.filesTotal, "percent" to c.percent,
                                )
                            },
                            "startedAt" to it.startedAt.toString(), "updatedAt" to it.updatedAt.toString(),
                        )
                    },
                    "workerPaused" to (admin.worker?.isPaused() ?: true),
                    "hotRevisions" to ctx.hotRevisions,
                    "uptimeMs" to (System.currentTimeMillis() - admin.startedAt),
                    "types" to ctx.game.types.map { mapOf("id" to it.id, "key" to it.def.key, "kind" to it.def.kind.name) },
                ),
            )
        }

        get("/revisions") {
            if (!call.authorized()) return@get
            val builds = runCatching { admin.discovery?.builds()?.associateBy { it.rev } }.getOrNull().orEmpty()
            call.respond(
                mapOf(
                    "revisions" to ctx.revisions.list(ctx.game.game.id).map { r ->
                        revisionJson(r) + mapOf("latestSourceCacheId" to builds[r.rev]?.cacheId, "sizeBytes" to builds[r.rev]?.sizeBytes)
                    },
                ),
            )
        }

        get("/revisions/{rev}") {
            if (!call.authorized()) return@get
            val rev = call.parameters["rev"]?.toIntOrNull() ?: badRequest("Invalid rev")
            val row = ctx.revisions.get(ctx.game.game.id, rev) ?: notFound("Unknown revision $rev")
            val runs = ctx.revisions.runs(ctx.game.game.id, 200).filter { it.rev == rev }
            call.respond(
                mapOf(
                    "revision" to revisionJson(row),
                    "runs" to runs.map { run ->
                        mapOf(
                            "id" to run.id, "startedAt" to run.startedAt.toString(), "finishedAt" to run.finishedAt?.toString(),
                            "status" to run.status, "stage" to run.stage, "error" to run.error,
                            "metrics" to run.metrics?.let { com.google.gson.JsonParser.parseString(it) },
                        )
                    },
                ),
            )
        }

        get("/runs") {
            if (!call.authorized()) return@get
            call.respond(
                mapOf(
                    "runs" to ctx.revisions.runs(ctx.game.game.id, (call.intParam("limit") ?: 50).coerceIn(1, 500)).map { run ->
                        mapOf(
                            "id" to run.id, "rev" to run.rev, "startedAt" to run.startedAt.toString(), "finishedAt" to run.finishedAt?.toString(),
                            "status" to run.status, "stage" to run.stage, "error" to run.error?.lineSequence()?.firstOrNull(),
                            "metrics" to run.metrics?.let { com.google.gson.JsonParser.parseString(it) },
                        )
                    },
                ),
            )
        }

        post("/revisions/{rev}/ingest") {
            if (!call.authorized()) return@post
            val rev = call.parameters["rev"]?.toIntOrNull() ?: badRequest("Invalid rev")
            val row = ctx.revisions.get(ctx.game.game.id, rev)
            if (row == null) {
                val build = admin.discovery?.build(rev) ?: notFound("Revision $rev is not on OpenRS2 for this game")
                ctx.revisions.discover(ctx.game.game.id, rev, build.cacheId, build.timestamp)
            } else if (row.published) {
                admin.pipeline.unpublish(rev)
            } else {
                ctx.revisions.setStatus(ctx.game.game.id, rev, RevisionStatus.DISCOVERED)
            }
            admin.worker?.wake()
            call.respond(mapOf("rev" to rev, "status" to "queued"))
        }

        post("/revisions/{rev}/unpublish") {
            if (!call.authorized()) return@post
            val rev = call.parameters["rev"]?.toIntOrNull() ?: badRequest("Invalid rev")
            admin.pipeline.unpublish(rev)
            call.respond(mapOf("rev" to rev, "status" to "unpublished"))
        }

        post("/worker/{action}") {
            if (!call.authorized()) return@post
            val worker = admin.worker ?: notFound("Ingestion worker disabled")
            when (call.parameters["action"]) {
                "pause" -> worker.pause(true)
                "resume" -> worker.pause(false)
                "wake" -> worker.wake()
                else -> badRequest("Unknown action")
            }
            call.respond(mapOf("paused" to worker.isPaused()))
        }

        get("/metrics") {
            if (!call.authorized()) return@get
            call.respond(ctx.metrics.snapshot())
        }

        get("/cache") {
            if (!call.authorized()) return@get
            val stats = ctx.cache.stats()
            call.respond(
                mapOf(
                    "entries" to ctx.cache.estimatedSize(),
                    "hitCount" to stats.hitCount(), "missCount" to stats.missCount(), "hitRate" to stats.hitRate(),
                    "evictionCount" to stats.evictionCount(), "evictionWeight" to stats.evictionWeight(),
                ),
            )
        }

        get("/memory") {
            if (!call.authorized()) return@get
            val rt = Runtime.getRuntime()
            val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
            call.respond(
                mapOf(
                    "heapUsedMb" to (rt.totalMemory() - rt.freeMemory()) / 1048576,
                    "heapCommittedMb" to rt.totalMemory() / 1048576,
                    "heapMaxMb" to rt.maxMemory() / 1048576,
                    "heapPeakMb" to pools.sumOf { it.peakUsage.used } / 1048576,
                    "threads" to ManagementFactory.getThreadMXBean().threadCount,
                ),
            )
        }

        get("/db") {
            if (!call.authorized()) return@get
            val tables = admin.db.api.withConnection { c ->
                c.query(
                    """SELECT c.relname, pg_total_relation_size(c.oid) AS bytes, c.reltuples::bigint AS rows
                       FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                       WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'p') ORDER BY bytes DESC""",
                ) { rs -> mapOf("table" to rs.getString(1), "bytes" to rs.getLong(2), "estimatedRows" to rs.getLong(3)) }
            }
            val latest = ctx.catalog.latest()
            val perType = if (latest != null) ctx.entities.countsAt(ctx.game.types, latest) else emptyMap()
            fun pool(ds: javax.sql.DataSource): Map<String, Any?> = (ds as? HikariDataSource)?.hikariPoolMXBean?.let {
                mapOf("active" to it.activeConnections, "idle" to it.idleConnections, "total" to it.totalConnections, "waiting" to it.threadsAwaitingConnection)
            } ?: emptyMap()
            call.respond(
                mapOf(
                    "tables" to tables,
                    "entitiesAtLatest" to ctx.game.types.associate { it.def.key to (perType[it.id] ?: 0) },
                    "pools" to mapOf("api" to pool(admin.db.api), "ingest" to pool(admin.db.ingest)),
                ),
            )
        }
    }
}
