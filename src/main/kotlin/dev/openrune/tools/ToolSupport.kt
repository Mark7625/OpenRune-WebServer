package dev.openrune.tools

import dev.openrune.Platform
import dev.openrune.ServerConfig
import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import dev.openrune.db.DatabaseConfig
import dev.openrune.envOrProp
import dev.openrune.loadDotEnv

/** `key=value` command line flags shared by the tools. */
class ToolArgs(args: Array<String>) {
    private val flags: Map<String, String> = args.mapNotNull { raw ->
        val token = raw.trim().removePrefix("--")
        val eq = token.indexOf('=')
        if (eq <= 0) null else token.substring(0, eq).trim().lowercase() to token.substring(eq + 1).trim()
    }.toMap()

    operator fun get(key: String): String? = flags[key.lowercase()]

    fun int(key: String): Int? = get(key)?.toIntOrNull()

    fun bool(key: String): Boolean = get(key)?.lowercase() in setOf("1", "true", "yes", "on")

    fun ints(key: String): List<Int> = get(key)?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()

    val gameType: GameType
        get() = when (get("game")?.lowercase()) {
            null, "osrs", "oldschool" -> GameType.OLDSCHOOL
            "rs3", "runescape", "runescape3" -> GameType.RUNESCAPE
            else -> GameType.valueOf(get("game")!!.uppercase())
        }

    val environment: CacheEnvironment
        get() = get("env")?.let { CacheEnvironment.valueOf(it.uppercase()) } ?: CacheEnvironment.LIVE
}

fun openPlatform(
    args: ToolArgs,
    gameType: GameType = args.gameType,
    environment: CacheEnvironment = args.environment,
    types: List<dev.openrune.model.EntityTypeDef> = dev.openrune.model.OsrsEntityTypes.all,
): Platform {
    loadDotEnv()
    val dbConfig = DatabaseConfig.fromEnv(::envOrProp)
        ?: error("OPENRUNE_DATABASE_URL must be set (e.g. jdbc:postgresql://localhost:5432/openrune)")
    val config = ServerConfig(gameType = gameType, cacheID = 0, environment = environment, port = 0)
    return Platform(config, dbConfig, types)
}

fun peakHeapMb(): Long =
    java.lang.management.ManagementFactory.getMemoryPoolMXBeans()
        .filter { it.type == java.lang.management.MemoryType.HEAP }
        .sumOf { it.peakUsage.used } / (1024 * 1024)

fun resetPeakHeap() {
    java.lang.management.ManagementFactory.getMemoryPoolMXBeans().forEach { runCatching { it.resetPeakUsage() } }
    System.gc()
}

fun usedHeapMb(): Long = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)
