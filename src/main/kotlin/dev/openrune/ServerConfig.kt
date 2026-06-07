package dev.openrune

import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import java.io.File

data class ServerConfig(
    val gameType: GameType,
    val cacheID: Int,
    val environment: CacheEnvironment,
    val port: Int,
    /** Optional nav display-name overrides keyed by section/group id (e.g. "spotanim" -> "SpotAnim"). */
    val navDisplayNameOverrides: Map<String, String> = emptyMap(),
    /** When set, extractors write to this dir (e.g. diff/) instead of extracted/. Used by DiffDumper. */
    val diffOutputDir: File? = null
) {
    /** Resolved from cacheID via OpenRS2 during startup. -1 until loaded. */
    var revision: Int = -1
}






