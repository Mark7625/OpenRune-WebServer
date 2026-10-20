package dev.openrune.ingest

import dev.openrune.cache.tools.CacheInfo
import dev.openrune.cache.tools.OpenRS2
import dev.openrune.model.Game
import dev.openrune.store.RevisionRepository
import mu.KotlinLogging
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * [stream] is OpenRS2's own `game/environment` label, only filled in by
 * [RevisionDiscovery.byCacheId] — the other paths already filtered on it.
 */
class DiscoveredBuild(val rev: Int, val cacheId: Int, val timestamp: Instant?, val sizeBytes: Long, val stream: String? = null)

/**
 * Finds revisions on OpenRS2 for one game stream. One build per major revision is tracked: the
 * newest one, matching how the website keys everything by major revision number.
 */
class RevisionDiscovery(private val game: Game, private val repository: RevisionRepository) {

    @Volatile
    private var lastLoadedAt = 0L

    private fun caches(maxAgeMs: Long = 5 * 60_000): Array<CacheInfo> {
        val now = System.currentTimeMillis()
        if (OpenRS2.allCaches.isEmpty() || now - lastLoadedAt > maxAgeMs) {
            synchronized(this) {
                if (OpenRS2.allCaches.isEmpty() || now - lastLoadedAt > maxAgeMs) {
                    OpenRS2.allCaches = emptyArray()
                    OpenRS2.loadCaches()
                    lastLoadedAt = System.currentTimeMillis()
                }
            }
        }
        return OpenRS2.allCaches
    }

    fun builds(maxAgeMs: Long = 5 * 60_000): List<DiscoveredBuild> {
        val name = game.gameType.formatName()
        val env = game.environment.name.lowercase()
        return caches(maxAgeMs).asSequence()
            .filter { it.game.contains(name) && it.environment.equals(env, true) && it.builds.isNotEmpty() && it.size > 0 }
            .groupBy { it.builds[0].major }
            .map { (rev, infos) ->
                val newest = infos.maxByOrNull { timestamp(it)?.toEpochMilli() ?: 0L }!!
                DiscoveredBuild(rev, newest.id, timestamp(newest), newest.size)
            }
            .sortedBy { it.rev }
    }

    /** Insert revision rows for builds not seen before; returns the newly discovered revisions. */
    fun discover(minRev: Int = 1): List<Int> {
        val found = builds().filter { it.rev >= minRev }
        val fresh = found.filter { repository.discover(game.id, it.rev, it.cacheId, it.timestamp) }.map { it.rev }
        if (fresh.isNotEmpty()) logger.info { "Discovered ${fresh.size} new revision(s) for ${game.slug}: $fresh" }
        return fresh
    }

    fun build(rev: Int): DiscoveredBuild? = builds().firstOrNull { it.rev == rev }

    /**
     * One catalogue entry by its OpenRS2 id, whatever game or environment it belongs to — a link
     * pasted by hand may be for any of them, and saying so is more use than finding nothing.
     */
    fun byCacheId(cacheId: Int): DiscoveredBuild? =
        caches().firstOrNull { it.id == cacheId && it.builds.isNotEmpty() }
            ?.let { DiscoveredBuild(it.builds[0].major, it.id, timestamp(it), it.size, "${it.game}/${it.environment}") }

    /** True when [stream] is the game and environment this discovery serves, or is simply unknown. */
    fun matchesStream(stream: String?): Boolean {
        if (stream == null) return true
        val parts = stream.split('/', limit = 2)
        return parts[0].contains(game.gameType.formatName(), true) &&
            parts.getOrElse(1) { "" }.equals(game.environment.name, true)
    }

    private fun timestamp(info: CacheInfo): Instant? =
        runCatching { Instant.parse(info.timestamp) }.getOrNull()
}
