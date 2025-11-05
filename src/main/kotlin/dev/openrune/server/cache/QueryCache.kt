package dev.openrune.server.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class QueryCache<T>(
    private val defaultTtlMillis: Long = 5 * 60 * 1000L,
    private val maxSize: Int = 1000
) {
    private data class CacheEntry<T>(val value: T, val expiresAt: Long)
    
    private val cache = ConcurrentHashMap<String, CacheEntry<T>>()
    private val mutex = Mutex()
    
    suspend fun get(key: String): T? {
        // Fast path: read without lock (ConcurrentHashMap is thread-safe for reads)
        val entry = cache[key] ?: return null
        val now = System.currentTimeMillis()
        
        // Check expiration without lock first
        if (entry.expiresAt > now) {
            return entry.value
        }
        
        // Expired - need lock to remove
        return mutex.withLock {
            val entryAgain = cache[key]
            if (entryAgain != null && entryAgain.expiresAt <= now) {
                cache.remove(key)
            }
            null
        }
    }
    
    suspend fun put(key: String, value: T) {
        put(key, value, defaultTtlMillis)
    }
    
    suspend fun put(key: String, value: T, ttlMillis: Long) {
        mutex.withLock {
            if (cache.size >= maxSize && !cache.containsKey(key)) {
                evictExpired()
                if (cache.size >= maxSize) {
                    cache.keys.firstOrNull()?.let { cache.remove(it) }
                }
            }
            
            cache[key] = CacheEntry(value, System.currentTimeMillis() + ttlMillis)
        }
    }
    
    suspend fun remove(key: String) {
        mutex.withLock {
            cache.remove(key)
        }
    }
    
    suspend fun clear() {
        mutex.withLock {
            cache.clear()
        }
    }
    
    private fun evictExpired() {
        val now = System.currentTimeMillis()
        // Use iterator to avoid ConcurrentModificationException
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val (key, entry) = iterator.next()
            if (entry.expiresAt <= now) {
                iterator.remove()
            }
        }
    }
    
    suspend fun getStats(): CacheStats {
        return mutex.withLock {
            evictExpired()
            val now = System.currentTimeMillis()
            val values = cache.values
            CacheStats(
                size = cache.size,
                maxSize = maxSize,
                earliestExpirationMs = values.minOfOrNull { it.expiresAt }?.let { it - now },
                latestExpirationMs = values.maxOfOrNull { it.expiresAt }?.let { it - now }
            )
        }
    }
    
    data class CacheStats(
        val size: Int,
        val maxSize: Int,
        val earliestExpirationMs: Long? = null,
        val latestExpirationMs: Long? = null
    )
}
