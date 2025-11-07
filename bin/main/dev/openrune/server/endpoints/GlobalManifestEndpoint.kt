package dev.openrune.server.endpoints

import dev.openrune.ServerConfig
import dev.openrune.server.BaseQueryParams
import dev.openrune.server.QueryHandlerJson
import dev.openrune.server.SearchConfig
import io.ktor.server.application.*
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Configuration for a manifest endpoint
 */
data class ManifestEndpointConfig<T : BaseQueryParams, E : Any>(
    val name: String,
    val endpoint: String,
    val cacheKey: String,
    val loadWebData: (ServerConfig) -> Map<Int, Any>,
    val loadCacheData: ((ServerConfig) -> Map<Int, Any>)? = null,
    val searchConfig: SearchConfig<E>? = null,
    val parseQueryParams: (ApplicationCall) -> T,
    val supportsDataType: Boolean = true,
    val keyType: ManifestKeyType = ManifestKeyType.INT,
    val customHandler: (suspend (ApplicationCall, T, Map<*, Any>, SearchConfig<E>?) -> Boolean)? = null,
    val requireParams: Boolean = false,
    val errorMessageNotFound: String = "Resource not found",
    val errorMessagePrefix: String = "",
    val customFilter: ((Map<Int, E>, T) -> Map<Int, E>)? = null
)

enum class ManifestKeyType {
    INT, STRING
}

/**
 * Global manifest endpoint that handles all manifest types with configurable settings
 */
class GlobalManifestEndpoint<T : BaseQueryParams, E : Any>(
    private val config: ServerConfig,
    private val manifestConfig: ManifestEndpointConfig<T, E>
) : QueryHandlerJson<T>() {

    override val queryCache = dev.openrune.server.cache.QueryCache<Any>(
        defaultTtlMillis = 5 * 60 * 1000L,
        maxSize = 1000
    ).also {
        dev.openrune.server.monitor.ActivityMonitor.registerCache(manifestConfig.endpoint, it)
    }

    private val webData: Map<Int, Any> by lazy {
        manifestConfig.loadWebData(config)
    }

    private val cacheData: Map<Int, Any>? by lazy {
        manifestConfig.loadCacheData?.invoke(config)
    }

    public override fun getQueryParams(call: ApplicationCall): T {
        return manifestConfig.parseQueryParams(call)
    }

    override suspend fun handle(call: ApplicationCall, params: T) {
        try {
            if (manifestConfig.supportsDataType && manifestConfig.loadCacheData != null) {
                val dataType = call.parameters["dataType"] ?: "web"

                if (dataType == "cache") {
                    handleCacheData(call, params, cacheData!!)
                    return
                }
            }

            @Suppress("UNCHECKED_CAST")
            val typedData = webData as Map<Int, E>
            
            if (manifestConfig.customHandler != null) {
                val handled = manifestConfig.customHandler!!.invoke(call, params, typedData, manifestConfig.searchConfig)
                if (handled) return
            }

            // requireParams is handled by customHandler for models

            if (manifestConfig.keyType == ManifestKeyType.INT) {
                handleIntKeyed(call, params, typedData)
            } else {
                // For string keyed, we need to handle differently
                // This is mainly for GameVals which have a different structure
                respondError(call, "String keyed manifests not yet fully supported in global endpoint", 500)
            }
        } catch (e: IllegalArgumentException) {
            respondError(call, e.message ?: "Invalid request", 400)
        } catch (e: Exception) {
            logger.error("Error handling ${manifestConfig.name} request: ${e.message}", e)
            respondError(call, "Internal server error: ${e.message}", 500)
        }
    }

    private suspend fun handleCacheData(call: ApplicationCall, params: T, data: Map<Int, Any>) {
        if (params.id != null) {
            val entry = data[params.id]
            if (entry == null) {
                respondError(call, "${manifestConfig.errorMessagePrefix}${manifestConfig.errorMessageNotFound}", 404)
                return
            }
            call.respond(mapOf("archive" to params.id, "entry" to entry))
            return
        }

        val filtered = data.filterKeys { id ->
            params.idRange?.let { id in it } ?: true
        }

        params.limit?.let { limit ->
            if (limit > 0) {
                val limited = filtered.entries.take(limit).associate { it.key to it.value }
                call.respond(limited.mapKeys { it.key.toString() })
                return
            }
        }

        call.respond(filtered.mapKeys { it.key.toString() })
    }

    private suspend fun handleIntKeyed(call: ApplicationCall, params: T, typedData: Map<Int, E>) {
        val searchConfig = manifestConfig.searchConfig
        if (searchConfig != null) {
            // Explicitly use the SearchConfig overload by passing it as a non-null SearchConfig<E>
            handleSingleEntryIntKey(call, params, typedData, searchConfig, manifestConfig.customFilter) ?: run {
                handleFullManifestIntKey(
                    call,
                    params,
                    typedData,
                    searchConfig,
                    null,
                    manifestConfig.cacheKey,
                    manifestConfig.endpoint,
                    manifestConfig.customFilter
                )
            }
        } else {
            // Fallback for endpoints without search config - use the gameValLookupField version
            val gameValLookup: ((E) -> String?)? = null
            handleSingleEntryIntKey(call, params, typedData, gameValLookup, manifestConfig.customFilter) ?: run {
                val path = call.request.path()
                val queryParamsString = call.request.queryString()
                val filtered = filterManifest(typedData, params, null, manifestConfig.cacheKey, manifestConfig.endpoint, path, queryParamsString, manifestConfig.customFilter)
                call.respond(filtered.mapKeys { it.key.toString() })
            }
        }
    }
}

