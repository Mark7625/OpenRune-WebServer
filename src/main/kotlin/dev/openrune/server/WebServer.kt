package dev.openrune.server

import dev.openrune.ServerConfig
import dev.openrune.cache.WebCacheManager
import dev.openrune.cache.tools.OpenRS2
import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.cache.diff.DiffBinaryCache
import dev.openrune.server.endpoints.diff.getCombinedSprites
import dev.openrune.server.endpoints.diff.getRevisionsWithData
import dev.openrune.server.endpoints.diff.registerDiffEndpoints
import dev.openrune.server.endpoints.cache.registerCacheEndpoints
import dev.openrune.server.endpoints.maps.registerMapEndpoints
import dev.openrune.server.zip.ZipService
import dev.openrune.server.zip.registerZipEndpoints
import dev.openrune.util.json
import dev.openrune.util.jsonNoPretty
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.http.ContentType
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.gson.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.http.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import mu.KotlinLogging
import java.io.File

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName


data class StatusResponse(
    val status: String,
    val game: String,
    val revision: Int,
    val cacheID : Int,
    val environment: String,
    val port: Int,
    val statusMessage: String? = null,
    val progress: Double? = null,
)

class WebServer(
    private val config: ServerConfig
) {
    val zipService = ZipService(config) { type, data ->
        broadcastSseEvent(type, data)
    }
    private val cacheManager = WebCacheManager(config)

    companion object {
        val logger = KotlinLogging.logger {}
    }

    @Volatile
    private var serverStatus = ServerStatus.BOOTING

    @Volatile
    private var statusMessage: String? = null

    @Volatile
    private var updateProgress: Double? = null

    @Volatile
    private var lastBroadcastedProgress: Int? = null
    private var server: NettyApplicationEngine? = null

    private val sseBroadcast = MutableSharedFlow<SseEvent>(
        replay = 0,
        extraBufferCapacity = 128
    )

    init {
        DiffBinaryCache.onDecodeStatus { status ->
            val payload = mapOf(
                "revision" to status.revision,
                "status" to status.status,
                "progress" to status.progress,
                "message" to status.message,
                "error" to status.error
            )
            broadcastSseEvent(SseEventType.DECODE_PROGRESS, payload)
        }
    }

    private fun getCurrentStatusResponse(): StatusResponse {
        val message = statusMessage ?: when (serverStatus) {
            ServerStatus.BOOTING -> "Booting"
            ServerStatus.UPDATING -> "Updating"
            else -> null
        }
        val rev = config.revision.coerceIn(1, 1000)
        return StatusResponse(
            status = serverStatus.name,
            game = config.gameType.name,
            revision = config.revision,
            cacheID = config.cacheID,
            environment = config.environment.name,
            port = config.port,
            statusMessage = message,
            progress = updateProgress,
        )
    }

    private fun broadcastStatus() {
        try {
            val currentProgressInt = updateProgress?.toInt()
            val shouldBroadcast = when {
                updateProgress == null -> true
                currentProgressInt != lastBroadcastedProgress -> true
                else -> false
            }
            if (shouldBroadcast) {
                val response = getCurrentStatusResponse()
                broadcastSseEvent(SseEventType.STATUS, response)
                lastBroadcastedProgress = currentProgressInt
            }
        } catch (e: Exception) {
            logger.warn("Failed to broadcast status update: ${e.message}")
        }
    }

    private fun broadcastStatusForce() {
        try {
            val response = getCurrentStatusResponse()
            broadcastSseEvent(SseEventType.STATUS, response)
            lastBroadcastedProgress = updateProgress?.toInt()
        } catch (e: Exception) {
            logger.warn("Failed to broadcast status update: ${e.message}")
        }
    }

    fun broadcastSseEvent(type: SseEventType, data: Any) {
        try {
            val event = SseEvent(type, data)
            sseBroadcast.tryEmit(event)
        } catch (e: Exception) {
            logger.warn("Failed to broadcast SSE event ${type.name}: ${e.message}", e)
        }
    }

    private fun isConnectionClosedError(e: Exception): Boolean {
        val exceptionClassName = e.javaClass.simpleName
        val causeClassName = e.cause?.javaClass?.simpleName ?: ""
        if (exceptionClassName.contains("Cancellation", ignoreCase = true) ||
            causeClassName.contains("Cancellation", ignoreCase = true)) return true
        if (e.cause?.javaClass?.simpleName?.contains("WriteTimeout", ignoreCase = true) == true) return true
        val message = e.message ?: ""
        val causeMessage = e.cause?.message ?: ""
        return message.contains("Broken pipe", ignoreCase = true) ||
                message.contains("Connection reset", ignoreCase = true) ||
                message.contains("Connection closed", ignoreCase = true) ||
                message.contains("Cannot write to a channel", ignoreCase = true) ||
                message.contains("Cancelling", ignoreCase = true) ||
                message.contains("WriteTimeout", ignoreCase = true) ||
                causeMessage.contains("StacklessClosedChannelException", ignoreCase = true) ||
                causeMessage.contains("WriteTimeout", ignoreCase = true) ||
                e.cause?.javaClass?.simpleName?.contains("ClosedChannel", ignoreCase = true) == true
    }

    private fun getBaseUrl(call: ApplicationCall): String {
        val scheme = call.request.local.scheme
        val host = call.request.host()
        val portPart = if (config.port != 80 && config.port != 443) ":${config.port}" else ""
        return "$scheme://$host$portPart"
    }

    private fun ApplicationResponse.appendSseCorsHeaders() {
        headers.append("Access-Control-Allow-Origin", "*")
        headers.append("Access-Control-Allow-Methods", "GET, OPTIONS")
        headers.append("Access-Control-Allow-Headers", "*")
        headers.append("Access-Control-Expose-Headers", "*")
        headers.append(HttpHeaders.CacheControl, "no-cache")
        headers.append(HttpHeaders.Connection, "keep-alive")
    }

    private fun ApplicationResponse.appendOptionsCorsHeaders() {
        headers.append("Access-Control-Allow-Origin", "*")
        headers.append("Access-Control-Allow-Methods", "GET, OPTIONS")
        headers.append("Access-Control-Allow-Headers", "*")
        headers.append("Access-Control-Max-Age", "86400")
    }

    private fun buildSsePayload(event: SseEvent): String {
        val dataJson = jsonNoPretty.toJson(event.data)
        return """{"type":"${event.type.name}","data":$dataJson}"""
    }

    suspend fun start() {
        server = embeddedServer(Netty, port = config.port) {
            install(Compression) {
                excludeContentType(ContentType.Text.EventStream)
                gzip {
                    priority = 1.0
                    minimumSize(1024)
                }
                deflate {
                    priority = 0.9
                    minimumSize(1024)
                }
            }
            install(ContentNegotiation) {
                gson { }
            }

            intercept(ApplicationCallPipeline.Call) {
                val path = call.request.path()
                if (path != "/status" && path != "/sse" && path != "/" &&
                    path != "/api" && path != "/docs" && path != "/revisions" && path != "/config-types" &&
                    !path.startsWith("/endpoints/") &&
                    !path.startsWith("/diff") &&
                    !path.startsWith("/cache") &&
                    !path.startsWith("/map") &&
                    !path.startsWith("/zip")
                ) {
                    if (serverStatus != ServerStatus.LIVE) {
                        call.respond(getCurrentStatusResponse())
                        finish()
                        return@intercept
                    }
                }
            }

            routing {
                options("/sse") {
                    call.response.appendOptionsCorsHeaders()
                    call.respond(HttpStatusCode.OK)
                }

                get("/status") {
                    call.respond(getCurrentStatusResponse())
                }

                get("/sse") {
                    val typeParam = call.request.queryParameters["type"]?.uppercase()
                    val revsFilter = call.request.queryParameters["revs"]
                        ?.split(",")
                        ?.mapNotNull { it.trim().toIntOrNull() }
                        ?.toSet()
                        ?: emptySet()
                    val requestedType: SseEventType? = try {
                        typeParam?.let { SseEventType.valueOf(it) }
                    } catch (e: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to "Invalid event type: $typeParam. Valid types: ${SseEventType.entries.joinToString { it.name }}"
                            )
                        )
                        return@get
                    }

                    call.response.appendSseCorsHeaders()
                    call.respondOutputStream(contentType = ContentType("text", "event-stream")) {
                        try {
                            try {
                                when {
                                    requestedType == null -> {
                                        val initialStatus = getCurrentStatusResponse()
                                        val event = SseEvent(SseEventType.STATUS, initialStatus)
                                        val eventJson = buildSsePayload(event)
                                        write("data: $eventJson\n\n".toByteArray())
                                        flush()
                                    }
                                    requestedType == SseEventType.STATUS -> {
                                        val initialStatus = getCurrentStatusResponse()
                                        val event = SseEvent(SseEventType.STATUS, initialStatus)
                                        val eventJson = buildSsePayload(event)
                                        write("data: $eventJson\n\n".toByteArray())
                                        flush()
                                    }
                                    requestedType == SseEventType.ZIP_PROGRESS -> { /* no initial event */ }
                                    requestedType == SseEventType.DECODE_PROGRESS -> {
                                        if (revsFilter.isNotEmpty()) {
                                            revsFilter.sorted().forEach { rev ->
                                                val status = DiffBinaryCache.getDecodeStatus(config, rev)
                                                val event = SseEvent(
                                                    SseEventType.DECODE_PROGRESS,
                                                    mapOf(
                                                        "revision" to status.revision,
                                                        "status" to status.status,
                                                        "progress" to status.progress,
                                                        "message" to status.message,
                                                        "error" to status.error
                                                    )
                                                )
                                                val eventJson = buildSsePayload(event)
                                                write("data: $eventJson\n\n".toByteArray())
                                            }
                                            flush()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                if (isConnectionClosedError(e)) return@respondOutputStream
                                throw e
                            }

                            val filteredFlow = if (requestedType != null) {
                                sseBroadcast
                                    .filter { it.type == requestedType }
                                    .filter { event ->
                                        if (requestedType != SseEventType.DECODE_PROGRESS || revsFilter.isEmpty()) return@filter true
                                        val revision = (event.data as? Map<*, *>)?.get("revision") as? Int
                                        revision != null && revision in revsFilter
                                    }
                            } else sseBroadcast

                            filteredFlow.collect { event ->
                                try {
                                    val eventJson = buildSsePayload(event)
                                    write("data: $eventJson\n\n".toByteArray())
                                    flush()
                                } catch (e: Exception) {
                                    if (isConnectionClosedError(e)) return@collect
                                    logger.warn("Unexpected error sending SSE event: ${e.message}")
                                    return@collect
                                }
                            }
                        } catch (e: Exception) {
                            if (!isConnectionClosedError(e)) {
                                logger.warn("SSE stream terminated: ${e.message}")
                            }
                        }
                    }
                }

                get("/") {
                    call.respond(getCurrentStatusResponse())
                }

                get("/endpoints/data") {
                    val baseUrl = getBaseUrl(call)
                    val data = EndpointRegistry.getEndpointsData(baseUrl)
                    call.respond(data)
                }

                get("/api") {
                    val baseUrl = getBaseUrl(call)
                    call.respond(EndpointRegistry.getEndpointsData(baseUrl))
                }

                get("/docs") {
                    val baseUrl = getBaseUrl(call)
                    call.respond(EndpointRegistry.getEndpointsData(baseUrl))
                }

                get("/revisions") {
                    call.respond(getRevisionsWithData(config))
                }
                get("/config-types") {
                    call.respond(mapOf(
                        "types" to ConfigDiffType.diffTypeNames,
                        "pathSegments" to ConfigDiffType.httpExposed.map { it.pathSegment }
                    ))
                }
                EndpointRegistry.registerEndpoint(
                    method = "GET",
                    path = "/config-types",
                    description = "List config types from server (types for diff viewer, pathSegments for config API URLs). Single source of truth when you add ConfigDiffType.",
                    category = "Meta",
                    queryParamsClass = null,
                    responseType = "application/json",
                    examples = listOf("/config-types")
                )
                EndpointRegistry.registerEndpoint(
                    method = "GET",
                    path = "/revisions",
                    description = "List revisions that have diff data (revisions + serverRevision). Use for rev dropdowns and other UIs.",
                    category = "Meta",
                    queryParamsClass = null,
                    responseType = "application/json",
                    examples = listOf("/revisions")
                )

                registerDiffEndpoints(config)
                registerCacheEndpoints(config)
                registerMapEndpoints(config)
                registerZipEndpoints(zipService)
            }
        }

        server?.start(wait = false)
        logger.info("Server started on port ${config.port}. Status: http://localhost:${config.port}/status")
        logger.info("SSE: http://localhost:${config.port}/sse?type=STATUS")
        logger.info("Loading cache...")
        broadcastStatusForce()

        OpenRS2.loadCaches()

        if (OpenRS2.allCaches.isEmpty()) {
            serverStatus = ServerStatus.ERROR
            statusMessage = "Failed to fetch Caches"
            broadcastStatusForce()
            logger.error("Failed to fetch Caches. No caches found.")
            return
        }

        try {
            val cacheDir = File("cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val cachesFile = File(cacheDir, "caches_all.json")
            cachesFile.writeText(json.toJson(OpenRS2.allCaches))
        } catch (e: Exception) {
            logger.warn("Failed to save caches to JSON: ${e.message}", e)
        }

        try {
            cacheManager.loadOrUpdate { isUpdating, progress, message ->
                if (isUpdating) {
                    serverStatus = ServerStatus.UPDATING
                    updateProgress = progress
                    statusMessage = message
                } else {
                    updateProgress = null
                    statusMessage = null
                }
                broadcastStatus()
            }
            serverStatus = ServerStatus.LIVE
            statusMessage = null
            updateProgress = null
            lastBroadcastedProgress = null
            broadcastStatusForce()
            logger.info("Cache loaded. Server is LIVE.")
            CoroutineScope(Dispatchers.Default).launch {
                runCatching {
                    val revisionsPayload = getRevisionsWithData(config)
                    val currentRev = (revisionsPayload["serverRevision"] as? Int ?: config.revision).coerceAtLeast(1)
                    getCombinedSprites(config, 1, currentRev)
                    logger.info("Startup precompute complete: revisions + combined sprites (base=1, rev=$currentRev)")
                }.onFailure { e ->
                    logger.warn("Startup precompute failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            logger.error("Error loading cache: ${e.message}", e)
            serverStatus = ServerStatus.ERROR
            statusMessage = e.message ?: "Unknown error occurred"
            lastBroadcastedProgress = null
            broadcastStatusForce()
        }
    }
}

enum class ServerStatus {
    BOOTING,
    UPDATING,
    LIVE,
    ERROR
}
