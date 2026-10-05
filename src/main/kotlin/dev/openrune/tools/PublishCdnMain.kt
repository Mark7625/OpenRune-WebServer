package dev.openrune.tools

import dev.openrune.cdn.CdnKind
import dev.openrune.cdn.CdnPublisher
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Re-publishes raw assets of already-published revisions to the CDN, without touching their data.
 *
 * Sprite and texture bytes come from PostgreSQL, so they need no cache on disk. Raw model `.dat`
 * meshes come from the revision's raw cache; pass `downloadCache=true` to fetch it from OpenRS2
 * when it is no longer on disk.
 *
 * Flags:
 * ```
 * revs=240,241            explicit revisions (default: every published revision)
 * from=1 to=241           inclusive bounds instead of an explicit list
 * kinds=sprites,models    which asset kinds (default: sprites,textures,models)
 * repair=true             list the CDN first and upload only missing objects
 * uploadUnchanged=true    upload every asset at the revision, not only what changed there
 * dryRun=true             report what would be uploaded, contact nothing
 * downloadCache=true      download the raw cache when models are requested and it is absent
 * game=OLDSCHOOL env=LIVE
 * ```
 */
fun main(args: Array<String>) {
    val flags = ToolArgs(args)
    openPlatform(flags).use { platform ->
        val published = platform.catalog.published()
        val explicit = flags.ints("revs")
        val from = flags.int("from") ?: Int.MIN_VALUE
        val to = flags.int("to") ?: Int.MAX_VALUE
        val revs = (if (explicit.isNotEmpty()) explicit else published).filter { it in from..to }.sorted()

        val unpublished = revs.filterNot { it in published }
        require(unpublished.isEmpty()) { "Revisions not published, nothing to upload: $unpublished" }
        require(revs.isNotEmpty()) { "No published revisions selected (published: ${published.size})" }

        val options = CdnPublisher.Options(
            kinds = CdnKind.parse(flags["kinds"]),
            dryRun = flags.bool("dryRun"),
            onlyMissing = flags.bool("repair"),
            downloadCacheForModels = flags.bool("downloadCache"),
            uploadUnchanged = flags.bool("uploadUnchanged"),
        )
        logger.info {
            "Publishing ${revs.size} revision(s) to CDN: kinds=${options.kinds.joinToString { it.name.lowercase() }} " +
                "repair=${options.onlyMissing} uploadUnchanged=${options.uploadUnchanged} " +
                "dryRun=${options.dryRun} revs=$revs"
        }

        var failed = 0
        for (rev in revs) {
            val sourceCacheId = platform.revisions.get(platform.game.game.id, rev)?.sourceCacheId
                ?: platform.discovery.build(rev)?.cacheId
            // One unreachable revision must not abort the batch; report it and continue.
            val report = runCatching {
                platform.cdnPublisher.publish(rev, options, sourceCacheId, onProgress = { msg -> logger.info { msg } })
            }.getOrElse { e ->
                logger.error(e) { "rev $rev: CDN publish failed" }
                null
            }
            if (report == null || !report.ok) failed++
            report?.let { logger.info { it.toString() } }
        }
        logger.info { "Done. ${revs.size} revision(s), $failed with failures." }
        if (failed > 0) kotlin.system.exitProcess(2)
    }
}
