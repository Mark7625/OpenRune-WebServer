package dev.openrune.cache

import dev.openrune.cache.tools.GameType
import dev.openrune.cache.tools.CacheEnvironment
import java.io.File

object CachePathHelper {
    /**
     * Gets the base cache directory for the given configuration
     * Path format: ./cache/{gameType}/{environment}/{rev}/
     */
    fun getCacheDirectory(gameType: GameType, environment: CacheEnvironment, revision: Int): File {
        return File("cache")
            .resolve(gameType.name.lowercase())
            .resolve(environment.name.lowercase())
            .resolve(revision.toString())
    }

    /**
     * Gets a file within the cache directory
     * Path format: ./cache/{gameType}/{environment}/{rev}/{filePath}
     */
    fun getCacheFile(gameType: GameType, environment: CacheEnvironment, revision: Int, filePath: String): File {
        return getCacheDirectory(gameType, environment, revision)
            .resolve(filePath)
    }

    /**
     * Gets multiple files by their relative paths
     */
    fun getCacheFiles(gameType: GameType, environment: CacheEnvironment, revision: Int, filePaths: List<String>): List<File> {
        val baseDir = getCacheDirectory(gameType, environment, revision)
        return filePaths.map { baseDir.resolve(it) }
    }

    /**
     * Ensures the cache directory exists and creates it if necessary
     */
    fun ensureCacheDirectory(gameType: GameType, environment: CacheEnvironment, revision: Int): File {
        val dir = getCacheDirectory(gameType, environment, revision)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
}

