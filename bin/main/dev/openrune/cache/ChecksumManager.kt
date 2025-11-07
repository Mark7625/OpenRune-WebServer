package dev.openrune.cache

import mu.KotlinLogging
import java.io.File
import java.util.zip.CRC32

private val logger = KotlinLogging.logger {}

/**
 * Handles all checksum calculations for cache files
 */
object ChecksumManager {
    
    /**
     * Calculate CRC32 checksum for a ByteArray
     */
    fun calculateCRC32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }
    
    /**
     * Calculate CRC32 checksum for a directory (including all files and their paths)
     * This creates a deterministic checksum that changes when files are added/removed/modified
     */
    fun calculateDirectoryCRC32(directory: File): Long {
        val crc = CRC32()
        if (directory.exists() && directory.isDirectory) {
            directory.walkTopDown().sorted().forEach { file ->
                if (file.isFile) {
                    // Include relative path in checksum
                    val relativePath = file.relativeTo(directory).path.replace("\\", "/")
                    crc.update(relativePath.toByteArray())
                    // Include file contents in checksum
                    file.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            crc.update(buffer, 0, bytesRead)
                        }
                    }
                }
            }
        }
        return crc.value
    }
    
    /**
     * Verify directory integrity by comparing calculated checksum with stored checksum
     * @param directory The directory to verify
     * @param checksumFile The file containing the stored checksum
     * @return true if checksums match, false otherwise
     */
    fun verifyDirectoryIntegrity(directory: File, checksumFile: File): Boolean {
        if (!checksumFile.exists()) {
            logger.warn("Checksum file missing: ${checksumFile.path}")
            return false
        }
        
        val storedChecksum = checksumFile.readText().toLongOrNull()
        if (storedChecksum == null) {
            logger.warn("Invalid checksum file format: ${checksumFile.path}")
            return false
        }
        
        val calculatedChecksum = calculateDirectoryCRC32(directory)
        val isValid = calculatedChecksum == storedChecksum
        
        if (!isValid) {
            logger.warn("Cache integrity check failed: stored=$storedChecksum, calculated=$calculatedChecksum")
        }
        
        return isValid
    }
    
    /**
     * Save directory checksum to a file
     */
    fun saveDirectoryChecksum(directory: File, checksumFile: File): Long {
        val checksum = calculateDirectoryCRC32(directory)
        checksumFile.writeText(checksum.toString())
        logger.debug("Saved directory checksum: ${checksumFile.path} = $checksum")
        return checksum
    }
}

