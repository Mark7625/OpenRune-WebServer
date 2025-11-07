package dev.openrune.cache

import dev.openrune.ServerConfig
import dev.openrune.cache.osrs.OsrsCacheIndex
import dev.openrune.cache.tools.GameType
import dev.openrune.cache.util.XteaLoader
import dev.openrune.filesystem.Cache
import dev.openrune.util.json
import dev.openrune.util.jsonNoPretty
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Manages cache checksum manifests - creation, loading, saving, and comparison
 */
class ChecksumManifestManager(private val config: ServerConfig) {
    
    /**
     * Get the set of indices that should be CRC'd based on the game type
     */
    private fun getIndicesToCrc(): Set<Int> {
        return when (config.gameType) {
            GameType.OLDSCHOOL -> OsrsCacheIndex.getIndicesToCrc()
            else -> OsrsCacheIndex.getIndicesToCrc() // Default to OSRS for now
        }
    }
    
    /**
     * Get the set of archives that should be CRC'd for a given index
     * Returns null if all archives should be checked (default behavior)
     */
    private fun getArchivesToCrc(index: Int): Set<Int>? {
        return when (config.gameType) {
            GameType.OLDSCHOOL -> {
                OsrsCacheIndex.entries.find { it.id == index }?.archives
            }
            else -> null
        }
    }
    
    /**
     * Create a checksum manifest from a loaded cache
     * @param cache The cache to calculate checksums for
     * @return CacheChecksumManifest with all file checksums
     */
    fun createManifest(cache: Cache): CacheChecksumManifest {
        val startTime = System.nanoTime()
        val indicesToCrc = getIndicesToCrc()
        val indices = mutableMapOf<Int, IndexChecksums>()
        val mapsIndex = OsrsCacheIndex.MAPS.id

        cache.indices().filter { it in indicesToCrc && it != mapsIndex }.forEach { index ->
            val archives = mutableMapOf<Int, ArchiveChecksums>()
            val archivesToCrc = getArchivesToCrc(index)

            cache.archives(index).forEach { archive ->
                if (archivesToCrc != null && archive !in archivesToCrc) {
                    return@forEach
                }
                val hasFiles = cache.fileCount(index, archive) != 0
                if (!hasFiles) return@forEach

                val files = mutableMapOf<Int, FileChecksum>()

                cache.files(index, archive).forEach { file ->
                    val fileData = cache.data(index, archive, file) ?: return@forEach
                    val checksum = ChecksumManager.calculateCRC32(fileData)

                    files[file] = FileChecksum(
                        index = index,
                        archive = archive,
                        file = file,
                        crc32 = checksum,
                        size = fileData.size
                    )
                }

                if (files.isNotEmpty()) {
                    archives[archive] = ArchiveChecksums(
                        index = index,
                        archive = archive,
                        files = files
                    )
                }
            }

            if (archives.isNotEmpty()) {
                indices[index] = IndexChecksums(
                    index = index,
                    archives = archives
                )
            }
        }

        val endTime = System.nanoTime()
        val elapsedTimeMs = (endTime - startTime) / 1_000_000.0
        val totalFiles = getTotalFileCount(indices)
        logger.info("Checksum calculation completed in ${String.format("%.2f", elapsedTimeMs)}ms for $totalFiles files")

        return CacheChecksumManifest(
            revision = config.revision,
            gameType = config.gameType.name,
            environment = config.environment.name,
            timestamp = System.currentTimeMillis(),
            indices = indices
        )
    }
    
    /**
     * Load a checksum manifest from a file
     */
    fun loadManifest(manifestFile: File): CacheChecksumManifest? {
        return try {
            if (!manifestFile.exists()) {
                return null
            }
            val manifestJson = manifestFile.readText()
            jsonNoPretty.fromJson(manifestJson, CacheChecksumManifest::class.java)
        } catch (e: Exception) {
            logger.warn("Failed to load checksum manifest from ${manifestFile.path}: ${e.message}")
            null
        }
    }
    
    /**
     * Save a checksum manifest to a file
     */
    fun saveManifest(manifest: CacheChecksumManifest, manifestFile: File) {
        try {
            manifestFile.parentFile?.mkdirs()
            manifestFile.writeText(jsonNoPretty.toJson(manifest))
            logger.debug("Saved checksum manifest to ${manifestFile.path}")
        } catch (e: Exception) {
            logger.error("Failed to save checksum manifest to ${manifestFile.path}: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Find differences between two manifests
     */
    fun findDifferences(old: CacheChecksumManifest, new: CacheChecksumManifest): FileDifferences {
        val added = mutableListOf<FileChecksum>()
        val removed = mutableListOf<FileChecksum>()

        // Find added/changed files
        new.indices.forEach { (index, newIndex) ->
            val oldIndex = old.indices[index]
            if (oldIndex == null) {
                // Entire index is new
                newIndex.archives.values.forEach { archive ->
                    added.addAll(archive.files.values)
                }
            } else {
                // Compare archives
                newIndex.archives.forEach { (archiveId, newArchive) ->
                    val oldArchive = oldIndex.archives[archiveId]

                    if (oldArchive == null) {
                        // Entire archive is new
                        added.addAll(newArchive.files.values)
                    } else {
                        // Compare files
                        newArchive.files.forEach { (fileId, newFile) ->
                            val oldFile = oldArchive.files[fileId]

                            if (oldFile == null) {
                                // File is new
                                added.add(newFile)
                            } else if (oldFile.crc32 != newFile.crc32) {
                                // File has changed (checksum mismatch)
                                added.add(newFile)
                            }
                        }
                    }
                }

                // Find removed files
                oldIndex.archives.forEach { (archiveId, oldArchive) ->
                    val newArchive = newIndex.archives[archiveId]

                    if (newArchive == null) {
                        // Entire archive was removed
                        removed.addAll(oldArchive.files.values)
                    } else {
                        // Find files that were removed
                        oldArchive.files.forEach { (fileId, oldFile) ->
                            if (!newArchive.files.containsKey(fileId)) {
                                removed.add(oldFile)
                            }
                        }
                    }
                }
            }
        }

        // Find removed indices
        old.indices.forEach { (index, oldIndex) ->
            if (!new.indices.containsKey(index)) {
                // Entire index was removed
                oldIndex.archives.values.forEach { archive ->
                    removed.addAll(archive.files.values)
                }
            }
        }

        return FileDifferences(added, removed)
    }
    
    private fun getTotalFileCount(indices: Map<Int, IndexChecksums>): Int {
        return indices.values.sumOf { index ->
            index.archives.values.sumOf { archive ->
                archive.files.size
            }
        }
    }
}

/**
 * Represents differences between two checksum manifests
 */
data class FileDifferences(
    val added: List<FileChecksum>,
    val removed: List<FileChecksum>
)

