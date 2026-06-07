package dev.openrune.cache

import dev.openrune.cache.tools.GameType
import dev.openrune.cache.tools.CacheEnvironment
import java.io.File

object CachePathHelper {

    fun getCacheDirectory(gameType: GameType, environment: CacheEnvironment, revision: Int): File {
        return File("cache")
            .resolve(gameType.name.lowercase())
            .resolve(environment.name.lowercase())
            .resolve(revision.toString())
    }

    /** Parent of all revision dirs: cache/{gameType}/{environment}/ */
    private fun getRevisionsParent(gameType: GameType, environment: CacheEnvironment): File {
        return File("cache")
            .resolve(gameType.name.lowercase())
            .resolve(environment.name.lowercase())
    }

    /**
     * Flat directory for custom binary diff blobs (one file per rev).
     * Path: cache/{gameType}/{environment}/diffs/
     * Files: 100.bin, 101.bin, ...
     */
    fun getDiffBinaryDirectory(gameType: GameType, environment: CacheEnvironment): File {
        return getRevisionsParent(gameType, environment).resolve("diffs")
    }

    /** Binary diff file for a revision: cache/{gameType}/{environment}/diffs/{rev}.bin */
    fun getDiffBinaryFile(gameType: GameType, environment: CacheEnvironment, revision: Int): File {
        return getDiffBinaryDirectory(gameType, environment).resolve("$revision.bin")
    }

}

