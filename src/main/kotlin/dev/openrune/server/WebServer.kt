package dev.openrune.server

import dev.openrune.ServerConfig
import dev.openrune.cache.WebCacheManager
import dev.openrune.cache.tools.OpenRS2
import dev.openrune.server.endpoints.sprites.registerSpriteEndpoints
import dev.openrune.server.endpoints.gamevals.registerGameValEndpoints
import dev.openrune.server.endpoints.models.registerModelEndpoints
import dev.openrune.server.endpoints.textures.registerTextureEndpoints
import dev.openrune.server.endpoints.overlays.registerOverlayEndpoints
import dev.openrune.server.endpoints.underlays.registerUnderlayEndpoints
import dev.openrune.server.endpoints.spotanims.registerSpotAnimEndpoints
import dev.openrune.server.endpoints.npcs.registerNpcEndpoints
import dev.openrune.server.endpoints.objects.registerObjectEndpoints
import dev.openrune.server.endpoints.items.registerItemEndpoints
import dev.openrune.server.endpoints.sequences.registerSequenceEndpoints
import dev.openrune.server.monitor.ActivityMonitor
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
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.http.content.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import mu.KotlinLogging
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay

data class StatusResponse(
    val status: String,
    val game: String,
    val revision: Int,
    val environment: String,
    val port: Int,
    val statusMessage: String? = null,
    val progress: Double? = null
)

class WebServer(
    private val config: ServerConfig
) {
    val zipService = ZipService(config) { type, data ->
        broadcastSseEvent(type, data)
    }
    private val cacheManager = WebCacheManager(config, zipService)

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

    private val sseBroadcast = BroadcastChannel<SseEvent>(Channel.BUFFERED)

    private fun getCurrentStatusResponse(): StatusResponse {
        val message = statusMessage ?: when (serverStatus) {
            ServerStatus.BOOTING -> "Booting"
            ServerStatus.UPDATING -> "Updating"
            else -> null
        }
        return StatusResponse(
            status = serverStatus.name,
            game = config.gameType.name,
            revision = config.revision,
            environment = config.environment.name,
            port = config.port,
            statusMessage = message,
            progress = updateProgress
        )
    }

    private fun broadcastStatus() {
        try {
            val currentProgressInt = updateProgress?.toInt()
            val shouldBroadcast = when {
                // Always broadcast if status changed or message changed
                updateProgress == null -> true
                // Broadcast if progress changed by at least 1%
                currentProgressInt != lastBroadcastedProgress -> true
                // Otherwise don't broadcast (same progress percentage)
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

    /**
     * Broadcast an SSE event of any type
     */
    fun broadcastSseEvent(type: SseEventType, data: Any) {
        try {
            val event = SseEvent(type, data)
            sseBroadcast.trySend(event)
        } catch (e: Exception) {
            logger.warn("Failed to broadcast SSE event ${type.name}: ${e.message}", e)
        }
    }

    /**
     * Check if an exception indicates the connection was closed
     */
    private fun isConnectionClosedError(e: Exception): Boolean {
        // Check for cancellation exceptions by class name (since JobCancellationException is internal)
        val exceptionClassName = e.javaClass.simpleName
        val causeClassName = e.cause?.javaClass?.simpleName ?: ""
        if (exceptionClassName.contains("Cancellation", ignoreCase = true) ||
            causeClassName.contains("Cancellation", ignoreCase = true)) {
            return true
        }
        
        // Check for write timeout (client disconnected)
        if (e.cause?.javaClass?.simpleName?.contains("WriteTimeout", ignoreCase = true) == true) {
            return true
        }
        
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

    @OptIn(ObsoleteCoroutinesApi::class)
    suspend fun start() {
        // Set up activity monitor callback to broadcast SSE events
        ActivityMonitor.setActivityUpdateCallback { stats ->
            broadcastSseEvent(SseEventType.ACTIVITY, stats)
        }

        server = embeddedServer(Netty, port = config.port) {
            install(ContentNegotiation) {
                gson {
                    setPrettyPrinting()
                }
            }

            intercept(ApplicationCallPipeline.Call) {
                val path = call.request.path()

                if (path != "/status" && path != "/sse" && path != "/" &&
                    !path.startsWith("/endpoints/") &&
                    !path.startsWith("/static/")
                ) {
                    if (serverStatus != ServerStatus.LIVE) {
                        call.respond(getCurrentStatusResponse())
                        finish()
                        return@intercept
                    }
                }
            }

            routing {
                staticResources("/static", "static")

                // Handle OPTIONS preflight requests for CORS
                options("/sse") {
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.response.headers.append("Access-Control-Allow-Methods", "GET, OPTIONS")
                    call.response.headers.append("Access-Control-Allow-Headers", "*")
                    call.response.headers.append("Access-Control-Max-Age", "86400")
                    call.respond(HttpStatusCode.OK)
                }

                get("/status") {
                    call.respond(getCurrentStatusResponse())
                }

                get("/sse") {
                    val typeParam = call.request.queryParameters["type"]?.uppercase()
                    val requestedType: SseEventType? = try {
                        typeParam?.let { SseEventType.valueOf(it) }
                    } catch (e: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to "Invalid event type: $typeParam. Valid types: ${
                                    SseEventType.values().joinToString { it.name }
                                }"
                            )
                        )
                        return@get
                    }

                    // CORS headers for cross-origin SSE
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.response.headers.append("Access-Control-Allow-Methods", "GET, OPTIONS")
                    call.response.headers.append("Access-Control-Allow-Headers", "*")
                    call.response.headers.append("Access-Control-Expose-Headers", "*")

                    call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                    call.response.headers.append(HttpHeaders.Connection, "keep-alive")
                    call.respondOutputStream(contentType = ContentType("text", "event-stream")) {
                        val subscription = sseBroadcast.openSubscription()
                        try {
                            // Send initial events
                            try {
                                if (requestedType == null) {
                                    val initialStatus = getCurrentStatusResponse()
                                    val statusEvent = SseEvent(SseEventType.STATUS, initialStatus)
                                    val dataJson = jsonNoPretty.toJson(statusEvent.data)
                                    val eventJson = """{"type":"${statusEvent.type.name}","data":$dataJson}"""
                                    write("data: $eventJson\n\n".toByteArray())
                                    flush()
                                } else {
                                    when (requestedType) {
                                        SseEventType.STATUS -> {
                                            val initialStatus = getCurrentStatusResponse()
                                            val event = SseEvent(SseEventType.STATUS, initialStatus)
                                            val dataJson = jsonNoPretty.toJson(event.data)
                                            val eventJson = """{"type":"${event.type.name}","data":$dataJson}"""
                                            write("data: $eventJson\n\n".toByteArray())
                                            flush()
                                        }

                                        SseEventType.ACTIVITY -> {
                                            val initialActivity = ActivityMonitor.getStats()
                                            val event = SseEvent(SseEventType.ACTIVITY, initialActivity)
                                            val dataJson = jsonNoPretty.toJson(event.data)
                                            val eventJson = """{"type":"${event.type.name}","data":$dataJson}"""
                                            write("data: $eventJson\n\n".toByteArray())
                                            flush()
                                        }
                                        
                                        SseEventType.ZIP_PROGRESS -> {
                                            // No initial event for zip progress
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Client disconnected before we could send initial data
                                if (isConnectionClosedError(e)) {
                                    return@respondOutputStream
                                }
                                throw e
                            }

                            // Subscribe to events and filter by type (if specified)
                            val flow = subscription.receiveAsFlow()
                            val filteredFlow = if (requestedType != null) {
                                flow.filter { it.type == requestedType }
                            } else {
                                flow  // No filter - show all events
                            }

                            filteredFlow.collect { event ->
                                try {
                                    // Manually serialize to ensure data field is properly serialized (use jsonNoPretty for single-line JSON)
                                    val dataJson = jsonNoPretty.toJson(event.data)
                                    val eventJson = """{"type":"${event.type.name}","data":$dataJson}"""
                                    write("data: $eventJson\n\n".toByteArray())
                                    flush()
                                } catch (e: Exception) {
                                    // Check for expected client disconnection scenarios
                                    if (isConnectionClosedError(e)) {
                                        return@collect
                                    }
                                    // Only log unexpected errors
                                    logger.warn("Unexpected error sending SSE event: ${e.message}")
                                    return@collect
                                }
                            }
                        } catch (e: Exception) {
                            // Connection closed or other error - silently handle expected disconnections
                        } finally {
                            try {
                                subscription.cancel()
                            } catch (e: Exception) {
                                // Ignore errors when canceling subscription
                            }
                        }
                    }
                }

                get("/") {
                    call.respond(getCurrentStatusResponse())
                }

                get("/endpoints/data") {
                    val scheme = call.request.origin.scheme
                    val host = call.request.host()
                    val portPart = if (config.port != 80 && config.port != 443) ":${config.port}" else ""
                    val baseUrl = "$scheme://$host$portPart"
                    val data = EndpointRegistry.getEndpointsData(baseUrl)
                    call.respond(data)
                }

                post("/cache/clear") {
                    val endpoint = call.request.queryParameters["endpoint"]
                    if (endpoint == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'endpoint' query parameter"))
                        return@post
                    }

                    val cleared = ActivityMonitor.clearCache(endpoint)
                    if (cleared) {
                        call.respond(mapOf("success" to true, "message" to "Cache cleared for endpoint: $endpoint"))
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Endpoint not found: $endpoint"))
                    }
                }

                registerSpriteEndpoints(config)
                registerGameValEndpoints(config)
                registerModelEndpoints(config)
                registerTextureEndpoints(config)
                registerOverlayEndpoints(config)
                registerUnderlayEndpoints(config)
                registerSpotAnimEndpoints(config)
                registerNpcEndpoints(config)
                registerSequenceEndpoints(config)
                registerObjectEndpoints(config)
                registerItemEndpoints(config)
                registerZipEndpoints(config, zipService)
            }
        }

        server?.start(wait = false)
        logger.info("Server started on port ${config.port}. Status endpoint available at http://localhost:${config.port}/status")
        logger.info("SSE stream available at http://localhost:${config.port}/sse?type=STATUS")
        logger.info("Loading cache... (other endpoints will be available once complete)")
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
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
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
            logger.info("Cache loaded successfully. Server is now LIVE and ready to accept requests.")
        } catch (e: Exception) {
            logger.error("Error loading cache: ${e.message}", e)
            serverStatus = ServerStatus.ERROR
            statusMessage = if (e.message?.contains("Failed to fetch Cache") == true) {
                "Failed to fetch Cache please report"
            } else {
                e.message ?: "Unknown error occurred"
            }
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

