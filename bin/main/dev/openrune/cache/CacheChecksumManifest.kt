package dev.openrune.cache

/**
 * Represents checksums for all files in an OSRS cache
 */
data class CacheChecksumManifest(
    val revision: Int,
    val gameType: String,
    val environment: String,
    val timestamp: Long = System.currentTimeMillis(),
    val indices: Map<Int, IndexChecksums>
)

data class IndexChecksums(
    val index: Int,
    val archives: Map<Int, ArchiveChecksums>
)

data class ArchiveChecksums(
    val index: Int,
    val archive: Int,
    val files: Map<Int, FileChecksum>
)

data class FileChecksum(
    val index: Int,
    val archive: Int,
    val file: Int,
    val crc32: Long,
    val size: Int
)






