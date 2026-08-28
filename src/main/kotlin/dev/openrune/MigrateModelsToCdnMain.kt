package dev.openrune

import dev.openrune.cache.CacheManager
import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.MODELS
import dev.openrune.cache.diff.CacheBinaryFormat
import dev.openrune.cache.diff.SpriteCdn
import dev.openrune.cache.filestore.definition.SpriteDecoder
import dev.openrune.definition.type.SpriteType
import dev.openrune.cache.diff.ConfigDiffSummary
import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.cache.diff.DefinitionSnapshot
import dev.openrune.cache.diff.ModelCdn
import dev.openrune.cache.diff.ModelExtractor
import dev.openrune.cache.diff.ModelMeta
import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import dev.openrune.filesystem.Cache
import mu.KotlinLogging
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

private val logger = KotlinLogging.logger {}

/**
 * Backfill model data for revisions that were dumped before models were extracted.
 *
 * Per revision it: loads the on-disk cache, decodes every mesh into [ModelMeta], uploads the raw
 * `.dat` bytes to `{osrs|rs3}/rev/{rev}/models/{id}.dat`, and rewrites the revision `.bin` with the
 * model metadata trailer. Attachments (which items / npcs / objects use a model) come from the
 * config snapshots already stored in the bins, so the config archives are not decoded again.
 *
 * The cache for a revision must already be on disk (`./gradlew runDownloadAllCaches`); revisions
 * without one are skipped.
 *
 * Args (all optional):
 *   game=OLDSCHOOL|RUNESCAPE   (default OLDSCHOOL)
 *   env=LIVE|BETA|DEV          (default LIVE)
 *   from=N                     (inclusive min rev)
 *   to=N                       (inclusive max rev)
 *   dryRun=true                (decode + count only, no upload, no bin rewrite)
 *   skipCdn=true               (metadata only — do not upload .dat files)
 *   skipBin=true               (upload only — do not rewrite .bin files)
 *   force=true                 (rebuild models.json instead of reusing the sidecar)
 *   skipTextures=true          (do not publish textures.zip)
 *
 * Gradle:
 *   ./gradlew migrateModelsToCdn
 *   ./gradlew migrateModelsToCdn -PcdnFrom=240 -PcdnTo=240
 *   ./gradlew migrateModelsToCdn -PcdnFrom=1 -PcdnTo=1 -PcdnSkipCdn=true
 */
fun main(args: Array<String>) {
    loadDotEnv()

    val flags = parseModelFlags(args)
    val gameType = parseModelGame(flags["game"] ?: System.getProperty("cdnGame") ?: "OLDSCHOOL")
    val environment = parseModelEnv(flags["env"] ?: System.getProperty("cdnEnv") ?: "LIVE")
    val fromRev = (flags["from"] ?: System.getProperty("cdnFrom"))?.toIntOrNull()
    val toRev = (flags["to"] ?: System.getProperty("cdnTo"))?.toIntOrNull()
    val dryRun = parseModelBool(flags["dryrun"] ?: System.getProperty("cdnDryRun"))
    val skipCdn = parseModelBool(flags["skipcdn"] ?: System.getProperty("cdnSkipCdn"))
    val skipBin = parseModelBool(flags["skipbin"] ?: System.getProperty("cdnSkipBin"))
    val force = parseModelBool(flags["force"] ?: System.getProperty("cdnForce"))
    val skipTextures = parseModelBool(flags["skiptextures"] ?: System.getProperty("cdnSkipTextures"))

    val cdn = SpriteCdnConfig.fromEnv()
    if (!dryRun && !skipCdn && !cdn.canUpload) {
        System.err.println(
            "CDN upload not configured. Set R2_* / OPENRUNE_CDN_* in .env (need bucket + credentials). " +
                "Use dryRun=true to preview, or skipCdn=true to only write model metadata into the bins.",
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

    logger.info {
        "Migrate models -> CDN game=$gameType env=$environment bins=${allBins.size} " +
            "from=${fromRev ?: "*"} to=${toRev ?: "*"} dryRun=$dryRun skipCdn=$skipCdn skipBin=$skipBin " +
            "force=$force skipTextures=$skipTextures " +
            "bucket=${cdn.bucket} baseUrl=${cdn.baseUrl}"
    }

    // Config snapshots merged forward: items/npcs/objects drive model attachments,
    // textures resolve each texture id to the sprite it renders.
    val trackedTypes = listOf(
        ConfigDiffType.ITEMS.fileName,
        ConfigDiffType.NPCS.fileName,
        ConfigDiffType.OBJECTS.fileName,
        ConfigDiffType.TEXTURES.fileName,
    )
    val mergedConfigs: MutableMap<String, MutableMap<Int, DefinitionSnapshot>> =
        trackedTypes.associateWith { mutableMapOf<Int, DefinitionSnapshot>() }.toMutableMap()

    var baseModels: Map<Int, ModelMeta> = emptyMap()
    var processed = 0
    var skippedNoCache = 0
    var failed = 0
    var uploaded = 0
    var texturesPublished = 0

    for ((rev, binFile) in allBins) {
        if (toRev != null && rev > toRev) break

        val decoded = CacheBinaryFormat.readFromFile(binFile)
        if (decoded == null) {
            logger.warn { "rev $rev: unreadable bin — skip" }
            continue
        }

        applyConfigDelta(mergedConfigs, decoded, trackedTypes, isBase = rev == 1)

        if (rev == 1 && decoded.models.isNotEmpty()) baseModels = decoded.models
        if (fromRev != null && rev < fromRev) continue

        val cachePath = File(CachePathHelper.getCacheDirectory(gameType, environment, rev), "data/cache")
        if (!cachePath.exists()) {
            skippedNoCache++
            logger.warn { "rev $rev: no cache at ${cachePath.absolutePath} — skip (run runDownloadAllCaches)" }
            continue
        }

        val loaded = runCatching { Cache.load(cachePath.toPath()) }
        val cache = loaded.getOrNull()
        if (cache == null) {
            failed++
            logger.error(loaded.exceptionOrNull()) { "rev $rev: failed loading cache" }
            continue
        }

        try {
            val ids = ModelExtractor.modelIds(cache)
            if (ids.isEmpty()) {
                logger.warn { "rev $rev: model index empty — skip" }
                continue
            }
            val models = ModelExtractor.loadOrExtract(
                cache = cache,
                gameType = gameType,
                environment = environment,
                rev = rev,
                attachments = { ModelExtractor.attachmentsFromSnapshots(mergedConfigs) },
                ids = ids,
                force = force,
            ) { msg -> logger.info { "rev $rev: $msg" } }

            if (dryRun) {
                logger.info { "dryRun: rev $rev would upload ${ids.size} .dat and store ${models.size} model entries" }
                if (!skipTextures) {
                    val count = mergedConfigs[ConfigDiffType.TEXTURES.fileName]?.size ?: 0
                    logger.info { "dryRun: rev $rev would upload textures.zip ($count textures)" }
                }
                processed++
                if (rev == 1) baseModels = models
                continue
            }

            // Bin first: it is what the API serves, and it must not depend on a long upload
            // finishing. Uploads can be retried later without redoing the metadata.
            if (!skipBin) {
                val summary = if (rev == 1) {
                    ConfigDiffSummary(models.keys.sorted(), emptyList(), emptyList())
                } else {
                    ModelExtractor.diff(baseModels, models)
                }
                val stored = if (rev == 1) {
                    models
                } else {
                    val deltaIds = (summary.added + summary.changed).toSet()
                    models.filterKeys { it in deltaIds }
                }
                if (rev > 1 && baseModels.isEmpty()) {
                    logger.warn {
                        "rev $rev: base rev 1 has no model metadata — storing the full set as 'added'. " +
                            "Run this task for rev 1 first to keep deltas small."
                    }
                }
                CacheBinaryFormat.writeToFile(
                    file = binFile,
                    revision = rev,
                    openRs2CacheId = decoded.openRs2CacheId,
                    manifest = decoded.manifest,
                    configs = decoded.configs,
                    gameval = decoded.gameval,
                    sprites = decoded.sprites,
                    spriteMetadata = decoded.spriteMetadata,
                    mapObjects = decoded.mapObjects,
                    mapRegions = decoded.mapRegions,
                    xteasByRegion = decoded.xteasByRegion,
                    interfaceManifest = decoded.interfaceManifest,
                    clientScripts = decoded.clientScripts,
                    models = stored,
                    modelSummary = summary,
                )
                logger.info {
                    "rev $rev: bin updated (+${summary.added.size}/~${summary.changed.size}/-${summary.removed.size}, " +
                        "stored=${stored.size})"
                }
            }

            if (!skipTextures) {
                if (publishTextures(cdn, gameType, rev, cache, mergedConfigs)) texturesPublished++
            }

            if (!skipCdn) {
                val result = ModelCdn.publishRevisionModels(
                    cdn = cdn,
                    game = gameType,
                    rev = rev,
                    ids = ids,
                    dataFor = { id -> cache.data(MODELS, id) },
                ) { msg -> logger.info { msg } }
                uploaded += result.uploaded
                if (!result.ok) failed++
            }

            if (rev == 1) baseModels = models
            processed++
        } catch (e: Exception) {
            failed++
            logger.error(e) { "rev $rev: model migration failed" }
        } finally {
            runCatching { cache.close() }
        }
    }

    logger.info {
        "Done. processed=$processed skippedNoCache=$skippedNoCache failed=$failed " +
            "datUploaded=$uploaded texturesZips=$texturesPublished dryRun=$dryRun"
    }
    if (failed > 0) kotlin.system.exitProcess(2)
}

/**
 * Build and upload `textures.zip` for [rev] from the merged texture config.
 *
 * Each texture points at a sprite through `fileId`. Those PNGs are pulled from the CDN first —
 * sprites are already published per revision — and only if some are missing does this decode the
 * revision's sprite index, which is the expensive path.
 */
private fun publishTextures(
    cdn: SpriteCdnConfig,
    gameType: GameType,
    rev: Int,
    cache: Cache,
    mergedConfigs: Map<String, Map<Int, DefinitionSnapshot>>,
): Boolean {
    val textureSnapshots = mergedConfigs[ConfigDiffType.TEXTURES.fileName].orEmpty()
    if (textureSnapshots.isEmpty()) {
        logger.warn { "rev $rev: no texture config — skipping textures.zip" }
        return false
    }
    val fileIds = textureSnapshots.values
        .mapNotNull { it["fileId"]?.value as? Int }
        .filter { it >= 0 }
        .distinct()
    if (fileIds.isEmpty()) {
        logger.warn { "rev $rev: texture config has no fileIds — skipping textures.zip" }
        return false
    }

    val sprites = HashMap<Int, ByteArray>(fileIds.size)
    fileIds.forEach { fileId ->
        SpriteCdn.fetchSpritePng(cdn, gameType, rev, fileId)?.let { sprites[fileId] = it }
    }
    val missing = fileIds.filter { it !in sprites }
    if (missing.isNotEmpty()) {
        logger.info { "rev $rev: ${missing.size}/${fileIds.size} texture sprites not on CDN — decoding from cache" }
        runCatching {
            CacheManager.init(OsrsCacheProvider(cache, rev))
            val spriteTypes = mutableMapOf<Int, SpriteType>()
            SpriteDecoder().load(cache, spriteTypes)
            missing.forEach { fileId ->
                val type = spriteTypes[fileId] ?: return@forEach
                ByteArrayOutputStream().use { out ->
                    ImageIO.write(type.getSprite(true), "png", out)
                    sprites[fileId] = out.toByteArray()
                }
            }
        }.onFailure { e -> logger.warn(e) { "rev $rev: sprite decode failed" } }
    }

    val textures = SpriteCdn.textureBytes(mergedConfigs, sprites)
    if (textures.isEmpty()) {
        logger.warn { "rev $rev: no texture PNGs resolved — skipping textures.zip" }
        return false
    }
    return SpriteCdn.publishRevisionTextures(cdn, gameType, rev, textures) { msg -> logger.info { msg } }
}

/** Rev 1 bins carry the full config set; later bins carry only the delta plus a summary. */
private fun applyConfigDelta(
    merged: MutableMap<String, MutableMap<Int, DefinitionSnapshot>>,
    decoded: CacheBinaryFormat.DecodedRev,
    types: List<String>,
    isBase: Boolean,
) {
    types.forEach { type ->
        val target = merged.getOrPut(type) { mutableMapOf() }
        val payload = decoded.configs[type] ?: emptyMap()
        if (isBase) {
            target.clear()
            target.putAll(payload)
            return@forEach
        }
        val summary = decoded.manifest.configs[type] ?: return@forEach
        summary.removed.forEach { target.remove(it) }
        (summary.added + summary.changed).forEach { id -> payload[id]?.let { target[id] = it } }
    }
}

private fun parseModelFlags(args: Array<String>): Map<String, String> {
    val out = linkedMapOf<String, String>()
    for (raw in args) {
        val token = raw.trim().removePrefix("--")
        val eq = token.indexOf('=')
        if (eq <= 0) continue
        out[token.substring(0, eq).trim().lowercase()] = token.substring(eq + 1).trim()
    }
    return out
}

private fun parseModelBool(raw: String?): Boolean =
    when (raw?.trim()?.lowercase()) {
        "1", "true", "yes", "on" -> true
        else -> false
    }

private fun parseModelGame(raw: String): GameType =
    try {
        GameType.valueOf(raw.trim().uppercase())
    } catch (_: IllegalArgumentException) {
        when (raw.trim().lowercase()) {
            "osrs", "oldschool" -> GameType.OLDSCHOOL
            "rs3", "runescape", "runescape3" -> GameType.RUNESCAPE
            else -> GameType.OLDSCHOOL
        }
    }

private fun parseModelEnv(raw: String): CacheEnvironment =
    try {
        CacheEnvironment.valueOf(raw.trim().uppercase())
    } catch (_: IllegalArgumentException) {
        CacheEnvironment.LIVE
    }
