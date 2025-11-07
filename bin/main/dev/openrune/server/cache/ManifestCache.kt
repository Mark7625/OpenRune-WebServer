package dev.openrune.server.cache

import dev.openrune.ServerConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Cache for manifest files with automatic invalidation based on file modification time
 * This ensures manifests are reloaded when the underlying files change
 */
class ManifestCache<T>(
    private val loadFunction: (ServerConfig) -> T,
    private val getManifestFile: (ServerConfig) -> File,
    private val defaultTtlMillis: Long = 10 * 60 * 1000L // 10 minutes default
) {
    private data class CachedManifest<T>(
        val value: T,
        val fileModifiedTime: Long,
        val expiresAt: Long // System.currentTimeMillis() + ttl
    )
    
    private var cached: CachedManifest<T>? = null
    private val mutex = Mutex()
    
    /**
     * Get the manifest, loading from disk if necessary or if file has changed
     */
    suspend fun get(config: ServerConfig): T {
        return mutex.withLock {
            val manifestFile = getManifestFile(config)
            val currentModifiedTime = if (manifestFile.exists()) manifestFile.lastModified() else 0L
            val now = System.currentTimeMillis()
            
            val cachedManifest = cached
            val shouldReload = when {
                cachedManifest == null -> true
                cachedManifest.fileModifiedTime != currentModifiedTime -> {
                    logger.debug("Manifest file modified, reloading: ${manifestFile.path}")
                    true
                }
                cachedManifest.expiresAt <= now -> {
                    logger.debug("Manifest cache expired, reloading: ${manifestFile.path}")
                    true
                }
                else -> false
            }
            
            if (shouldReload) {
                logger.debug("Loading manifest: ${manifestFile.path}")
                val loaded = loadFunction(config)
                cached = CachedManifest(
                    value = loaded,
                    fileModifiedTime = currentModifiedTime,
                    expiresAt = now + defaultTtlMillis
                )
                loaded
            } else {
                cachedManifest!!.value
            }
        }
    }
    
    /**
     * Clear the cache, forcing a reload on next access
     */
    suspend fun clear() {
        mutex.withLock {
            cached = null
        }
    }
    
    /**
     * Check if cache is valid (not expired and file hasn't changed)
     */
    suspend fun isValid(config: ServerConfig): Boolean {
        return mutex.withLock {
            val manifestFile = getManifestFile(config)
            val currentModifiedTime = if (manifestFile.exists()) manifestFile.lastModified() else 0L
            val now = System.currentTimeMillis()
            
            val cachedManifest = cached
            cachedManifest != null &&
                    cachedManifest.fileModifiedTime == currentModifiedTime &&
                    cachedManifest.expiresAt > now
        }
    }
}

