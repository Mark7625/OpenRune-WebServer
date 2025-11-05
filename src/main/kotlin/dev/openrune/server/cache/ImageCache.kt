package dev.openrune.server.cache

import mu.KotlinLogging
import java.awt.image.BufferedImage
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Cached image entry with expiration timestamp
 */
private data class CachedImage(
    val image: BufferedImage,
    val expiresAt: Instant
) {
    fun isExpired(): Boolean = Instant.now().isAfter(expiresAt)
}

/**
 * In-memory image cache with time-based expiration
 * Thread-safe implementation for concurrent web server usage
 */
class ImageCache(
    private val ttlHours: Long = 1
) {
    companion object {
        /**
         * Default shared cache instance (1 hour TTL)
         */
        val default = ImageCache(ttlHours = 1)
    }
    // Cache for resized images: key = "spriteId:indexed:width:height:keepAspectRatio"
    private val resizedCache = ConcurrentHashMap<String, CachedImage>()
    
    // Cache for original images: key = "spriteId:indexed"
    private val originalCache = ConcurrentHashMap<String, CachedImage>()
    
    private fun getExpirationTime(): Instant = Instant.now().plus(Duration.ofHours(ttlHours))
    
    /**
     * Generate cache key for original image
     */
    fun getOriginalKey(spriteId: Int, indexed: Int?): String {
        return if (indexed != null) {
            "$spriteId:$indexed"
        } else {
            spriteId.toString()
        }
    }
    
    /**
     * Generate cache key for resized image
     */
    fun getResizedKey(spriteId: Int, indexed: Int?, width: Int?, height: Int?, keepAspectRatio: Boolean): String {
        val baseKey = getOriginalKey(spriteId, indexed)
        val sizeKey = "${width ?: "null"}:${height ?: "null"}:$keepAspectRatio"
        return "$baseKey:$sizeKey"
    }
    
    /**
     * Get original image from cache
     */
    fun getOriginal(spriteId: Int, indexed: Int?): BufferedImage? {
        val key = getOriginalKey(spriteId, indexed)
        val cached = originalCache[key] ?: return null
        
        return if (cached.isExpired()) {
            originalCache.remove(key)
            logger.debug("Original image cache expired: $key")
            null
        } else {
            cached.image
        }
    }
    
    /**
     * Store original image in cache
     */
    fun putOriginal(spriteId: Int, indexed: Int?, image: BufferedImage) {
        val key = getOriginalKey(spriteId, indexed)
        originalCache[key] = CachedImage(image, getExpirationTime())
        logger.debug("Cached original image: $key")
    }
    
    /**
     * Get resized image from cache
     */
    fun getResized(
        spriteId: Int,
        indexed: Int?,
        width: Int?,
        height: Int?,
        keepAspectRatio: Boolean
    ): BufferedImage? {
        val key = getResizedKey(spriteId, indexed, width, height, keepAspectRatio)
        val cached = resizedCache[key] ?: return null
        
        return if (cached.isExpired()) {
            resizedCache.remove(key)
            logger.debug("Resized image cache expired: $key")
            null
        } else {
            cached.image
        }
    }
    
    /**
     * Store resized image in cache
     */
    fun putResized(
        spriteId: Int,
        indexed: Int?,
        width: Int?,
        height: Int?,
        keepAspectRatio: Boolean,
        image: BufferedImage
    ) {
        val key = getResizedKey(spriteId, indexed, width, height, keepAspectRatio)
        resizedCache[key] = CachedImage(image, getExpirationTime())
        logger.debug("Cached resized image: $key")
    }
    
    /**
     * Clear expired entries from both caches (periodic cleanup)
     */
    fun cleanupExpired() {
        val originalKeysToRemove = originalCache.entries
            .filter { it.value.isExpired() }
            .map { it.key }
        val resizedKeysToRemove = resizedCache.entries
            .filter { it.value.isExpired() }
            .map { it.key }
        
        originalKeysToRemove.forEach { originalCache.remove(it) }
        resizedKeysToRemove.forEach { resizedCache.remove(it) }
        
        if (originalKeysToRemove.isNotEmpty() || resizedKeysToRemove.isNotEmpty()) {
            logger.debug("Cleaned up expired cache entries: original=${originalKeysToRemove.size}, resized=${resizedKeysToRemove.size}")
        }
    }
    
    /**
     * Clear all cache entries
     */
    fun clear() {
        originalCache.clear()
        resizedCache.clear()
        logger.info("Image cache cleared")
    }
    
    /**
     * Get cache statistics
     */
    fun getStats(): Map<String, Any> {
        val now = Instant.now()
        
        val originalActive = originalCache.values.count { !it.isExpired() }
        val resizedActive = resizedCache.values.count { !it.isExpired() }
        
        return mapOf(
            "original" to mapOf(
                "total" to originalCache.size,
                "active" to originalActive,
                "expired" to (originalCache.size - originalActive)
            ),
            "resized" to mapOf(
                "total" to resizedCache.size,
                "active" to resizedActive,
                "expired" to (resizedCache.size - resizedActive)
            ),
            "ttlHours" to ttlHours
        )
    }
}

