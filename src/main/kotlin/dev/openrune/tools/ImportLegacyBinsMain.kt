package dev.openrune.tools

import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.diff.CacheBinaryFormat
import dev.openrune.ingest.LegacyBinRevisionSource
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Imports every legacy `{rev}.bin` into PostgreSQL. Each revision needs only bins 1 and N, so a
 * full history migrates without re-downloading caches.
 *
 * Flags: `game=OLDSCHOOL env=LIVE from=N to=N force=true revs=1,100,240`
 */
fun main(args: Array<String>) {
    val flags = ToolArgs(args)
    openPlatform(flags).use { platform ->
        val dir = CachePathHelper.getDiffBinaryDirectory(platform.config.gameType, platform.config.environment)
        val bins = dir.listFiles()
            ?.mapNotNull { f -> f.nameWithoutExtension.toIntOrNull()?.takeIf { f.isFile && f.name.endsWith(".bin") }?.let { it to f } }
            ?.sortedBy { it.first }
            .orEmpty()
        require(bins.isNotEmpty()) { "No .bin files in ${dir.absolutePath}" }
        val baseFile = bins.firstOrNull { it.first == 1 }?.second ?: error("Base 1.bin is required to reconstruct legacy state")
        val base = LegacyBinRevisionSource.load(baseFile)
        val only = flags.ints("revs").toSet()
        val from = flags.int("from") ?: 1
        val to = flags.int("to") ?: Int.MAX_VALUE
        val force = flags.bool("force")
        val gameId = platform.game.game.id

        var imported = 0
        var skipped = 0
        for ((rev, file) in bins) {
            if (rev < from || rev > to || (only.isNotEmpty() && rev !in only)) continue
            val existing = platform.ingestRevisions.get(gameId, rev)
            if (existing?.published == true && !force) {
                skipped++
                logger.info { "rev $rev: already published, skip (force=true to re-import)" }
                continue
            }
            if (existing?.published == true) platform.pipeline.unpublish(rev)
            val decoded: CacheBinaryFormat.DecodedRev = if (rev == 1) base else LegacyBinRevisionSource.load(file)
            val start = System.nanoTime()
            val result = platform.ingestFrom(rev, { LegacyBinRevisionSource(rev, base, decoded) })
            platform.ingestRevisions.setSource(gameId, rev, decoded.openRs2CacheId?.toInt(), null)
            imported++
            logger.info {
                "rev $rev: imported ${result.entities} entities (+${result.added} ~${result.changed} -${result.removed}) " +
                    "in ${(System.nanoTime() - start) / 1_000_000}ms, peak heap ${peakHeapMb()}MB"
            }
        }
        logger.info { "Legacy import done: imported=$imported skipped=$skipped" }
    }
}
