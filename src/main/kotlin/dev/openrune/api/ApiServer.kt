package dev.openrune.api

import dev.openrune.cache.diff.cdnSlug
import dev.openrune.util.json
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val startNanosKey = AttributeKey<Long>("openrune.start")

/** Comfortably inside Cloudflare's ~100s idle timeout for a proxied stream. */
private const val SSE_HEARTBEAT_MS = 25_000L

/**
 * Ktor front for the PostgreSQL-backed platform. Status is derived from the published catalog and
 * the ingestion worker, so the server is LIVE as soon as a published revision exists and stays
 * LIVE while new revisions are processed.
 */
class ApiServer(
    private val ctx: ApiContext,
    private val admin: AdminContext,
    private val zips: SpriteZipService,
) {
    private val sse = MutableSharedFlow<SseEvent>(replay = 0, extraBufferCapacity = 128)
    private var engine: NettyApplicationEngine? = null

    fun broadcast(type: SseEventType, data: Any) {
        sse.tryEmit(SseEvent(type, data))
    }

    fun status(): Map<String, Any?> {
        val latest = ctx.catalog.latest()
        val progress = ctx.ingestionProgress
        val status = if (latest != null) "LIVE" else "UPDATING"
        return mapOf(
            "status" to status,
            "game" to ctx.game.game.gameType.name,
            "revision" to (latest ?: -1),
            "cacheID" to ctx.sourceCacheId,
            "environment" to ctx.game.game.environment.name,
            "port" to ctx.port,
            "statusMessage" to if (latest == null) (progress?.message ?: "No published revision yet") else null,
            "progress" to if (latest == null) progress?.percent?.toDouble() else null,
            "spritesCdnBase" to ctx.cdn.baseUrl?.takeIf { ctx.cdn.canServe },
            "spritesCdnGame" to ctx.game.game.gameType.cdnSlug().takeIf { ctx.cdn.canServe },
            "ingestion" to progress?.let { mapOf("revision" to it.rev, "stage" to it.stage, "progress" to it.percent, "message" to it.message) },
            "hotRevisions" to ctx.hotRevisions,
        )
    }

    fun start() {
        engine = embeddedServer(Netty, port = ctx.port) {
            install(Compression) {
                excludeContentType(ContentType.Text.EventStream)
                gzip { priority = 1.0; minimumSize(1024) }
                deflate { priority = 0.9; minimumSize(1024) }
            }
            install(ContentNegotiation) { gson { } }
            install(CORS) {
                allowHost("openrune.dev", schemes = listOf("https"))
                allowHost("www.openrune.dev", schemes = listOf("https"))
                allowHost("localhost:3000", schemes = listOf("http"))
                allowHost("127.0.0.1:3000", schemes = listOf("http"))
                allowHost("localhost:3001", schemes = listOf("http"))
                allowHost("127.0.0.1:3001", schemes = listOf("http"))
                allowHeaders { true }
                allowNonSimpleContentTypes = true
                maxAgeInSeconds = 86_400
                exposeHeader("X-OpenRune-Cache-Debug")
                exposeHeader(HttpHeaders.ETag)
                exposeHeader(HttpHeaders.ContentDisposition)
            }
            install(StatusPages) {
                exception<ApiError> { call, e -> call.respond(e.status, e.body) }
                exception<Throwable> { call, e ->
                    logger.error(e) { "Unhandled error on ${call.request.path()}" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
                }
            }
            intercept(io.ktor.server.application.ApplicationCallPipeline.Setup) {
                call.attributes.put(startNanosKey, System.nanoTime())
            }
            sendPipeline.intercept(io.ktor.server.response.ApplicationSendPipeline.After) {
                val start = call.attributes.getOrNull(startNanosKey) ?: return@intercept
                val route = call.request.path().replace(Regex("/\\d+"), "/{id}")
                ctx.metrics.record("http $route", System.nanoTime() - start) { call.request.uri }
            }

            routing {
                // The server's own surface, previously undocumented because it is what serves the
                // documentation. Listing it means the API docs page describes every route.
                EndpointRegistry.registerEndpoint("GET", "/status", "Server status: game, environment, latest published revision, hot revisions.", "Server", null, "application/json", listOf("/status"))
                EndpointRegistry.registerEndpoint("GET", "/sse", "Server-sent events. Query: type=STATUS|ZIP_PROGRESS to filter; a `: ping` comment every 25s keeps the stream open.", "Server", null, "text/event-stream", listOf("/sse", "/sse?type=STATUS"))
                EndpointRegistry.registerEndpoint("GET", "/endpoints/data", "This endpoint list, as JSON. Also served at /api and /docs.", "Server", null, "application/json", listOf("/endpoints/data"))

                get("/") { call.respond(status()) }
                get("/status") { call.respond(status()) }
                get("/sse") { serveSse(call) }
                get("/endpoints/data") { call.respond(EndpointRegistry.getEndpointsData(baseUrl(call))) }
                get("/api") { call.respond(EndpointRegistry.getEndpointsData(baseUrl(call))) }
                get("/docs") { call.respond(EndpointRegistry.getEndpointsData(baseUrl(call))) }

                metaRoutes(ctx)
                gamevalRoutes(ctx)
                configRoutes(ctx)
                diffRoutes(ctx)
                spriteRoutes(ctx)
                modelRoutes(ctx)
                textureRoutes(ctx)
                mapRoutes(ctx)
                zipRoutes(ctx, zips)
                adminRoutes(ctx, admin)
            }
        }.also { it.start(wait = false) }
        logger.info { "API listening on port ${ctx.port} (PostgreSQL mode, game ${ctx.game.game.slug})" }
    }

    fun stop() {
        engine?.stop(1_000, 5_000)
    }

    private fun baseUrl(call: ApplicationCall): String {
        val portPart = if (ctx.port != 80 && ctx.port != 443) ":${ctx.port}" else ""
        return "${call.request.local.scheme}://${call.request.host()}$portPart"
    }

    private suspend fun serveSse(call: ApplicationCall) {
        val typeParam = call.request.queryParameters["type"]?.uppercase()
        val requested = typeParam?.let { t ->
            SseEventType.entries.firstOrNull { it.name == t } ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid event type: $t. Valid types: ${SseEventType.entries.joinToString { it.name }}"))
                return
            }
        }
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header(HttpHeaders.Connection, "keep-alive")
        // Tells proxies in front of us (Cloudflare Tunnel, nginx) to stream rather than buffer;
        // without it events can sit in the proxy until the response ends, which for SSE is never.
        call.response.header("X-Accel-Buffering", "no")
        call.respondOutputStream(contentType = ContentType("text", "event-stream")) {
            fun write(event: SseEvent) {
                write("data: {\"type\":\"${event.type.name}\",\"data\":${json.toJson(event.data)}}\n\n".toByteArray())
                flush()
            }
            fun ping() {
                // A comment line: valid SSE that clients ignore, but it keeps the connection from
                // being reaped as idle. Cloudflare closes an idle proxied stream after ~100s.
                write(": ping\n\n".toByteArray())
                flush()
            }
            try {
                if (requested == null || requested == SseEventType.STATUS) write(SseEvent(SseEventType.STATUS, status()))
                val events = if (requested != null) sse.filter { it.type == requested } else sse
                val heartbeat = flow<SseEvent?> {
                    while (true) {
                        delay(SSE_HEARTBEAT_MS)
                        emit(null)
                    }
                }
                merge(events, heartbeat).collect { event -> if (event == null) ping() else write(event) }
            } catch (e: Exception) {
                // client disconnected
            }
        }
    }
}
