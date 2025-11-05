package dev.openrune.cache

/**
 * Base interface for cache index types across different games
 */
interface CacheIndex {
    val id: Int
    val displayName: String
    val shouldChecksum: Boolean
    val archives: Set<Int>?
        get() = null // null means check all archives (default behavior)
}
