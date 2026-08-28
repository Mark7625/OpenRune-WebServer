package dev.openrune

import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.diff.CacheBinaryFormat
import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.cache.diff.DefinitionSnapshot
import dev.openrune.cache.diff.SpriteCdn
import dev.openrune.cache.diff.SpriteCdnPublishException
import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Migrate sprite PNGs already stored in local `.bin` diffs up to S3/R2 CDN.
 *
 * Reconstructs the full sprite set at each revision (base + deltas), then calls
 * [SpriteCdn.publishRevisionSprites] — same layout as dump-time uploads:
 * `{osrs|rs3}/rev/{rev}/sprites/{id}.png` + `sprites.zip`.
 *
 * Uploads one PNG at a time with a progress bar per rev.
 *
 * Args (all optional):
 *   game=OLDSCHOOL|RUNESCAPE3   (default OLDSCHOOL)
 *   env=LIVE|BETA|DEV           (default LIVE)
 *   from=N                      (inclusive min rev, default lowest bin)
 *   to=N                        (inclusive max rev, default highest bin)
 *   dryRun=true                 (decode + count only, no upload)
 *   skipUnchanged=true          (skip revs with empty sprite delta; base always published)
 *   repair=true                 (only upload PNGs missing on CDN — fixes partial uploads)
 *
 * Gradle:
 *   ./gradlew migrateSpritesToCdn
 *   ./gradlew migrateSpritesToCdn -PcdnFrom=239 -PcdnTo=239 -PcdnRepair=true
 *   ./gradlew migrateSpritesToCdn -PcdnFrom=1 -PcdnTo=500 -PcdnDryRun=true
 */
fun main(args: Array<String>) {
    loadDotEnv()

    val flags = parseFlags(args)
    val gameType = parseGame(flags["game"] ?: System.getProperty("cdnGame") ?: "OLDSCHOOL")
    val environment = parseEnv(flags["env"] ?: System.getProperty("cdnEnv") ?: "LIVE")
    val fromRev = (flags["from"] ?: System.getProperty("cdnFrom"))?.toIntOrNull()
    val toRev = (flags["to"] ?: System.getProperty("cdnTo"))?.toIntOrNull()
    val dryRun = parseBool(flags["dryRun"] ?: System.getProperty("cdnDryRun"))
    val skipUnchanged = parseBool(flags["skipUnchanged"] ?: System.getProperty("cdnSkipUnchanged"))
    val repair = parseBool(flags["repair"] ?: System.getProperty("cdnRepair"))

    val cdn = SpriteCdnConfig.fromEnv()
    if (!dryRun && !cdn.canUpload) {
        System.err.println(
            "CDN upload not configured. Set R2_* / OPENRUNE_CDN_* in .env " +
                "(need bucket + credentials). Use dryRun=true to preview only.",
        )
        kotlin.system.exitProcess(1)
    }

    val dir = CachePathHelper.getDiffBinaryDirectory(gameType, environment)
    if (!dir.isDirectory) {
        System.err.println("No diffs directory at ${dir.absolutePath}")
        kotlin.system.exitProcess(1)
    }

    val allBins = dir.listFiles()
        ?.mapNotNull { f ->
            if (!f.isFile || !f.name.endsWith(".bin")) return@mapNotNull null
            val rev = f.nameWithoutExtension.toIntOrNull() ?: return@mapNotNull null
            rev to f
        }
        ?.sortedBy { it.first }
        .orEmpty()

    if (allBins.isEmpty()) {
        System.err.println("No .bin files in ${dir.absolutePath}")
        kotlin.system.exitProcess(1)
    }

    val bins = allBins.filter { (rev, _) ->
        (fromRev == null || rev >= fromRev) && (toRev == null || rev <= toRev)
    }
    if (bins.isEmpty()) {
        System.err.println("No .bin files in range from=${fromRev ?: "*"} to=${toRev ?: "*"}")
        kotlin.system.exitProcess(1)
    }

    val baseRev = when {
        bins.any { it.first == 1 } -> 1
        allBins.any { it.first == 1 } && (fromRev == null || fromRev <= 1) -> 1
        else -> bins.first().first
    }

    logger.info {
        "Migrate sprites → CDN game=$gameType env=$environment " +
            "bins=${bins.size} baseRev=$baseRev dryRun=$dryRun repair=$repair " +
            "skipUnchanged=$skipUnchanged " +
            "bucket=${cdn.bucket} baseUrl=${cdn.baseUrl} endpoint=${cdn.endpoint ?: "aws-default"}"
    }

    // Prefer base from disk even if outside from/to filter (needed to reconstruct).
    val baseFile = allBins.firstOrNull { it.first == baseRev }?.second
        ?: bins.first().second.also {
            logger.warn { "Base rev $baseRev bin missing; using ${bins.first().first} as snapshot start" }
        }
    val effectiveBaseRev = allBins.firstOrNull { it.second == baseFile }?.first ?: bins.first().first

    val baseDecoded = CacheBinaryFormat.readFromFile(baseFile)
    if (baseDecoded == null) {
        System.err.println("Failed to read base bin ${baseFile.absolutePath}")
        kotlin.system.exitProcess(1)
    }

    val merged = LinkedHashMap<Int, ByteArray>(baseDecoded.sprites.size.coerceAtLeast(16))
    var appliedPayloads = 0
    baseDecoded.sprites.forEach { (id, bytes) ->
        if (bytes.isNotEmpty()) {
            merged[id] = bytes
            appliedPayloads++
        }
    }
    if (merged.isEmpty()) {
        System.err.println(
            "Base rev $effectiveBaseRev has no sprite PNG payloads in the .bin. " +
                "Cannot migrate (bins may already have OPENRUNE_SPRITES_IN_BIN=false).",
        )
        kotlin.system.exitProcess(1)
    }

    // Textures are published as a zip only; each texture points at a sprite id via `fileId`.
    val texturesType = ConfigDiffType.TEXTURES.fileName
    val mergedTextures = LinkedHashMap<Int, DefinitionSnapshot>()
    baseDecoded.configs[texturesType]?.forEach { (id, snapshot) -> mergedTextures[id] = snapshot }

    logger.info {
        "Base rev $effectiveBaseRev: ${merged.size} sprites with PNG payloads, " +
            "${mergedTextures.size} textures"
    }

    // Publish only revs in [from,to]; still walk every bin up to [to] so merged stays correct.
    val applyThrough = bins.map { it.first }.toSet()

    var published = 0
    var skipped = 0
    var failed = 0
    var repairedPngs = 0
    var texturesPublished = 0

    fun publishTextures(rev: Int, sprites: Map<Int, ByteArray>) {
        val textures = SpriteCdn.textureBytes(mapOf(texturesType to mergedTextures.toMap()), sprites)
        if (textures.isEmpty()) {
            logger.warn { "rev $rev: no texture PNGs resolved — skipping textures.zip" }
            return
        }
        if (dryRun) {
            logger.info { "dryRun: would upload rev $rev textures.zip (${textures.size} pngs)" }
            return
        }
        val ok = runCatching {
            SpriteCdn.publishRevisionTextures(cdn, gameType, rev, textures) { msg -> logger.info { msg } }
        }.getOrElse { e ->
            logger.error(e) { "textures.zip upload failed for rev $rev" }
            false
        }
        if (ok) texturesPublished++
    }

    fun publish(rev: Int, sprites: Map<Int, ByteArray>, reason: String) {
        if (sprites.isEmpty()) {
            logger.warn { "rev $rev: empty sprite set — skip ($reason)" }
            skipped++
            return
        }
        if (dryRun) {
            if (repair && cdn.canUpload) {
                val client = SpriteCdn.s3Client(cdn)
                try {
                    val have = SpriteCdn.listUploadedSpriteIds(client, cdn.bucket!!, gameType, rev)
                    val missing = sprites.keys.count { it !in have }
                    logger.info {
                        "dryRun+repair: rev $rev would upload $missing missing " +
                            "(cdnHas=${have.size}, expected=${sprites.size}) — $reason"
                    }
                } finally {
                    runCatching { client.close() }
                }
            } else {
                logger.info { "dryRun: would upload rev $rev (${sprites.size} pngs) — $reason" }
            }
            publishTextures(rev, sprites)
            published++
            return
        }
        logger.info {
            "Uploading rev $rev (${sprites.size} pngs)" +
                (if (repair) " [repair/onlyMissing]" else "") +
                " — $reason"
        }
        try {
            val result = SpriteCdn.publishRevisionSprites(
                cdn = cdn,
                game = gameType,
                rev = rev,
                sprites = sprites,
                onlyMissing = repair,
                onProgress = { msg -> logger.info { msg } },
            )
            published++
            repairedPngs += result.uploaded
            logger.info {
                "rev $rev ok: uploaded=${result.uploaded} skippedExisting=${result.skippedExisting} " +
                    "zip=${result.zipUploaded}"
            }
        } catch (e: SpriteCdnPublishException) {
            failed++
            repairedPngs += e.result.uploaded
            logger.error(e) {
                "Upload incomplete for rev $rev " +
                    "(uploaded=${e.result.uploaded}, failed=${e.result.failedIds.size})"
            }
        } catch (e: Exception) {
            failed++
            logger.error(e) { "Upload failed for rev $rev" }
        }
        publishTextures(rev, sprites)
    }

    // Apply deltas in order so [merged] stays a full snapshot; only publish when rev is in range.
    if (bins.any { it.first == effectiveBaseRev } || fromRev == null || fromRev <= effectiveBaseRev) {
        if (toRev == null || effectiveBaseRev <= toRev) {
            if (effectiveBaseRev in applyThrough || fromRev == null || fromRev <= effectiveBaseRev) {
                publish(effectiveBaseRev, merged.toMap(), "base snapshot")
            }
        }
    }

    for ((rev, file) in allBins) {
        if (rev <= effectiveBaseRev) continue
        if (toRev != null && rev > toRev) break

        val decoded = CacheBinaryFormat.readFromFile(file)
        if (decoded == null) {
            if (rev in applyThrough) {
                logger.warn { "rev $rev: unreadable bin — skip" }
                skipped++
            }
            continue
        }

        val summary = decoded.manifest.sprites
        val deltaIds = (summary.added + summary.changed).toSet()
        var missingPayloads = 0
        summary.removed.forEach { merged.remove(it) }
        for (id in deltaIds) {
            val bytes = decoded.sprites[id]
            if (bytes != null && bytes.isNotEmpty()) {
                merged[id] = bytes
                appliedPayloads++
            } else {
                missingPayloads++
            }
        }
        // Safety: apply any other non-empty payloads present in the chunk.
        decoded.sprites.forEach { (id, bytes) ->
            if (bytes.isNotEmpty() && id !in deltaIds) {
                merged[id] = bytes
                appliedPayloads++
            }
        }

        // Keep the texture config snapshot current even for revs we do not publish.
        decoded.manifest.configs[texturesType]?.let { textureSummary ->
            textureSummary.removed.forEach { mergedTextures.remove(it) }
            val delta = decoded.configs[texturesType]
            if (delta != null) {
                (textureSummary.added + textureSummary.changed).forEach { id ->
                    delta[id]?.let { mergedTextures[id] = it }
                }
            }
        }

        if (rev !in applyThrough) continue

        val unchanged = deltaIds.isEmpty() && summary.removed.isEmpty()
        // Repair must still visit "unchanged" revs — their CDN folder may be incomplete.
        if (skipUnchanged && unchanged && !repair) {
            logger.info { "rev $rev: no sprite delta — skip (skipUnchanged)" }
            skipped++
            continue
        }
        if (missingPayloads > 0) {
            logger.warn {
                "rev $rev: $missingPayloads sprite id(s) in manifest lack PNG payloads " +
                    "(+${summary.added.size}/~${summary.changed.size}/-${summary.removed.size}); " +
                    "publishing merged set with gaps filled from earlier revs where possible"
            }
        }

        publish(
            rev,
            merged.toMap(),
            "+${summary.added.size}/~${summary.changed.size}/-${summary.removed.size} " +
                "merged=${merged.size}",
        )
    }

    logger.info {
        "Done. published=$published skipped=$skipped failed=$failed " +
            "texturesZipsUploaded=$texturesPublished " +
            "pngsUploadedThisRun=$repairedPngs finalMergedSprites=${merged.size} " +
            "payloadsApplied=$appliedPayloads dryRun=$dryRun repair=$repair"
    }
    if (failed > 0) kotlin.system.exitProcess(2)
}

private fun parseFlags(args: Array<String>): Map<String, String> {
    val out = linkedMapOf<String, String>()
    for (raw in args) {
        val token = raw.trim().removePrefix("--")
        val eq = token.indexOf('=')
        if (eq <= 0) continue
        out[token.substring(0, eq).trim().lowercase()] = token.substring(eq + 1).trim()
    }
    return out
}

private fun parseBool(raw: String?): Boolean =
    when (raw?.trim()?.lowercase()) {
        "1", "true", "yes", "on" -> true
        else -> false
    }

private fun parseGame(raw: String): GameType =
    try {
        GameType.valueOf(raw.trim().uppercase())
    } catch (_: IllegalArgumentException) {
        when (raw.trim().lowercase()) {
            "osrs", "oldschool" -> GameType.OLDSCHOOL
            "rs3", "runescape", "runescape3" -> GameType.RUNESCAPE
            else -> GameType.OLDSCHOOL
        }
    }

private fun parseEnv(raw: String): CacheEnvironment =
    try {
        CacheEnvironment.valueOf(raw.trim().uppercase())
    } catch (_: IllegalArgumentException) {
        CacheEnvironment.LIVE
    }
