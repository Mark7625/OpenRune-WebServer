package dev.openrune.tools

import dev.openrune.Platform
import dev.openrune.ServerConfig
import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import dev.openrune.cdn.CdnPublisher
import dev.openrune.db.DatabaseConfig
import dev.openrune.envOrProp
import dev.openrune.loadDotEnv
import dev.openrune.store.BackfillRepository
import dev.openrune.store.RevisionRepository
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

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

/**
 * Puts one revision's CDN upload where the dashboard can see it: the `backfill` row's `cdn_*`
 * columns, and the revision's `ingest_run`. Both are read by the API, which is a different process.
 *
 * Every object PUT reports, so this is thousands of calls per revision — a write only happens when
 * the asset kind changes or [writeEveryMs] has passed. A failed write is logged and dropped: a
 * progress row is not worth losing an upload over.
 */
class CdnProgressRecorder(
    private val backfill: BackfillRepository,
    private val revisions: RevisionRepository,
    private val gameId: Int,
    private val rev: Int,
    private val runId: Long,
    private val writeEveryMs: Long = 1_000,
) : (CdnPublisher.CdnProgress) -> Unit {
    private var lastStage: String? = null
    private var lastWriteAt = 0L

    override fun invoke(p: CdnPublisher.CdnProgress) {
        val now = System.currentTimeMillis()
        if (p.stage == lastStage && now - lastWriteAt < writeEveryMs) return
        lastStage = p.stage
        lastWriteAt = now
        runCatching {
            backfill.cdnProgress(gameId, rev, p.stage, p.done, p.total, p.percent)
            revisions.updateRunProgress(runId, "CDN", p.percent, p.label)
        }.onFailure { logger.debug(it) { "Could not record CDN progress for rev $rev" } }
    }
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
