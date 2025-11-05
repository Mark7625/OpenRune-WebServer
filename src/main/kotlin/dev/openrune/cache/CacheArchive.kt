package dev.openrune.cache

/**
 * Base interface for cache archive types within indices
 */
interface CacheArchive {
    val id: Int
    val displayName: String
    val shouldChecksum: Boolean
}
