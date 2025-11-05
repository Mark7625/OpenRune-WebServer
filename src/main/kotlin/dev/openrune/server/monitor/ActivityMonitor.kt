package dev.openrune.server.monitor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

object ActivityMonitor {
    private val mutex = Mutex()
    private var activityUpdateCallback: (suspend (ActivityStats) -> Unit)? = null
    
    private val totalQueries = AtomicLong(0)
    private val totalCachedQueries = AtomicLong(0)
    private val totalQueryTimeMs = AtomicLong(0)
    private val totalCachedQueryTimeMs = AtomicLong(0)
    private val maxQueryTimeMs = AtomicLong(0)
    private val maxCachedQueryTimeMs = AtomicLong(0)
    private val activeQueries = AtomicInteger(0)
    
    private val recentQueries = mutableListOf<QueryActivity>()
    private const val MAX_RECENT_QUERIES = 100
    private val currentActiveRequests = mutableMapOf<String, ActiveRequest>()
    private val endpointCaches = mutableMapOf<String, dev.openrune.server.cache.QueryCache<*>>()
    
    data class QueryActivity(
        val endpoint: String,
        val queryParams: String?,
        val cached: Boolean,
        val durationMs: Long,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    data class ActiveRequest(
        val endpoint: String,
        val path: String,
        val queryParams: String?,
        val startTime: Long,
        val isCached: Boolean? = null
    )
    
    data class ActivityStats(
        val queries: QueryStats,
        val active: ActiveStats,
        val recentActivity: List<QueryActivity>,
        val cacheStats: Map<String, CacheStats>
    )
    
    data class QueryStats(
        val total: Long,
        val cached: Long,
        val averageTimeMs: Double,
        val cachedAverageTimeMs: Double,
        val maxTimeMs: Long,
        val cachedMaxTimeMs: Long
    )
    
    data class ActiveStats(
        val activeQueries: Int,
        val currentRequests: List<ActiveRequestInfo>
    )
    
    data class ActiveRequestInfo(
        val endpoint: String,
        val queryParams: String?,
        val durationMs: Long,
        val isCached: Boolean?
    )
    
    data class CacheStats(
        val size: Int,
        val maxSize: Int,
        val earliestExpirationMs: Long? = null,
        val latestExpirationMs: Long? = null
    )
    
    fun registerCache(endpoint: String, cache: dev.openrune.server.cache.QueryCache<*>) {
        endpointCaches[endpoint] = cache
    }
    
    suspend fun clearCache(endpoint: String): Boolean {
        val cache = endpointCaches[endpoint] ?: return false
        @Suppress("UNCHECKED_CAST")
        (cache as dev.openrune.server.cache.QueryCache<Any>).clear()
        return true
    }
    
    suspend fun startQuery(requestId: String, endpoint: String, path: String, queryParams: String? = null): Long {
        val startTime = System.currentTimeMillis()
        mutex.withLock {
            activeQueries.incrementAndGet()
            currentActiveRequests[requestId] = ActiveRequest(endpoint, path, queryParams, startTime)
        }
        return startTime
    }
    
    fun setActivityUpdateCallback(callback: (suspend (ActivityStats) -> Unit)?) {
        activityUpdateCallback = callback
    }
    
    suspend fun endQuery(requestId: String, cached: Boolean, startTime: Long) {
        val duration = System.currentTimeMillis() - startTime
        var shouldBroadcast = false
        val stats = mutex.withLock {
            activeQueries.decrementAndGet()
            val request = currentActiveRequests.remove(requestId)
            
            totalQueries.incrementAndGet()
            totalQueryTimeMs.addAndGet(duration)
            maxQueryTimeMs.updateAndGet { max(it, duration) }
            
            if (cached) {
                totalCachedQueries.incrementAndGet()
                totalCachedQueryTimeMs.addAndGet(duration)
                maxCachedQueryTimeMs.updateAndGet { max(it, duration) }
            }
            
            if (request != null) {
                recentQueries.add(QueryActivity(request.endpoint, request.queryParams, cached, duration))
                if (recentQueries.size > MAX_RECENT_QUERIES) {
                    recentQueries.removeAt(0)
                }
                shouldBroadcast = true
            }
            
            if (shouldBroadcast) {
                val totalQueryCount = totalQueries.get()
                val totalCachedCount = totalCachedQueries.get()
                val totalQueryTime = totalQueryTimeMs.get()
                val totalCachedTime = totalCachedQueryTimeMs.get()
                val now = System.currentTimeMillis()
                
                ActivityStats(
                    queries = calculateQueryStats(totalQueryCount, totalCachedCount, totalQueryTime, totalCachedTime),
                    active = ActiveStats(
                        activeQueries = activeQueries.get(),
                        currentRequests = currentActiveRequests.values.map { req ->
                            ActiveRequestInfo(
                                endpoint = req.endpoint,
                                queryParams = req.queryParams,
                                durationMs = now - req.startTime,
                                isCached = req.isCached
                            )
                        }.sortedByDescending { it.durationMs }
                    ),
                    recentActivity = recentQueries.toList(),
                    cacheStats = getCacheStatsUnsafe()
                )
            } else {
                null
            }
        }
        
        if (shouldBroadcast && stats != null) {
            activityUpdateCallback?.invoke(stats)
        }
    }
    
    private suspend fun getCacheStatsUnsafe(): Map<String, CacheStats> {
        return endpointCaches.mapValues { (_, cache) ->
            val stats = cache.getStats()
            CacheStats(stats.size, stats.maxSize, stats.earliestExpirationMs, stats.latestExpirationMs)
        }
    }
    
    suspend fun getStats(): ActivityStats {
        return mutex.withLock {
            val totalQueryCount = totalQueries.get()
            val totalCachedCount = totalCachedQueries.get()
            val totalQueryTime = totalQueryTimeMs.get()
            val totalCachedTime = totalCachedQueryTimeMs.get()
            val now = System.currentTimeMillis()
            
            ActivityStats(
                queries = calculateQueryStats(totalQueryCount, totalCachedCount, totalQueryTime, totalCachedTime),
                active = ActiveStats(
                    activeQueries = activeQueries.get(),
                    currentRequests = currentActiveRequests.values.map { req ->
                        ActiveRequestInfo(
                            endpoint = req.endpoint,
                            queryParams = req.queryParams,
                            durationMs = now - req.startTime,
                            isCached = req.isCached
                        )
                    }.sortedByDescending { it.durationMs }
                ),
                recentActivity = recentQueries.toList(),
                cacheStats = getCacheStatsUnsafe()
            )
        }
    }
    
    private fun calculateQueryStats(
        totalQueryCount: Long,
        totalCachedCount: Long,
        totalQueryTime: Long,
        totalCachedTime: Long
    ): QueryStats {
        return QueryStats(
            total = totalQueryCount,
            cached = totalCachedCount,
            averageTimeMs = if (totalQueryCount > 0) totalQueryTime.toDouble() / totalQueryCount else 0.0,
            cachedAverageTimeMs = if (totalCachedCount > 0) totalCachedTime.toDouble() / totalCachedCount else 0.0,
            maxTimeMs = maxQueryTimeMs.get(),
            cachedMaxTimeMs = maxCachedQueryTimeMs.get()
        )
    }
    
    suspend fun reset() {
        mutex.withLock {
            totalQueries.set(0)
            totalCachedQueries.set(0)
            totalQueryTimeMs.set(0)
            totalCachedQueryTimeMs.set(0)
            maxQueryTimeMs.set(0)
            maxCachedQueryTimeMs.set(0)
            recentQueries.clear()
            currentActiveRequests.clear()
        }
    }
}
