package dev.openrune.cdn

import dev.openrune.SpriteCdnConfig
import dev.openrune.cache.MODELS
import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.cache.diff.ModelCdn
import dev.openrune.cache.diff.SpriteCdn
import dev.openrune.cache.diff.SpriteCdnPublishException
import dev.openrune.filesystem.Cache
import dev.openrune.ingest.RawCacheStore
import dev.openrune.model.OsrsEntityTypes
import dev.openrune.query.EntityQueries
import dev.openrune.store.GameRegistry.RegisteredGame
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

enum class CdnKind { SPRITES, TEXTURES, MODELS, ITEMS, OBJECTS;

    companion object {
        val ALL: Set<CdnKind> = values().toSet()

        fun parse(raw: String?): Set<CdnKind> {
            if (raw.isNullOrBlank()) return ALL
            return raw.split(',').mapNotNull { token ->
                val t = token.trim().lowercase()
                entries.firstOrNull { it.name.lowercase() == t || it.name.lowercase().startsWith(t) }
            }.toSet().ifEmpty { ALL }
        }
    }
}

class CdnPublishReport(
    val rev: Int,
    val sprites: Int = 0,
    val spritesFailed: List<Int> = emptyList(),
    val texturesZip: Boolean = false,
    val models: Int = 0,
    val modelsFailed: List<Int> = emptyList(),
    val skipped: String? = null,
    /** Rendered item and object images. */
    val images: Int = 0,
    val imagesFailed: List<Int> = emptyList(),
) {
    val ok: Boolean get() = spritesFailed.isEmpty() && modelsFailed.isEmpty() && imagesFailed.isEmpty()

    override fun toString(): String = when {
        skipped != null -> "rev $rev skipped: $skipped"
        else -> "rev $rev sprites=$sprites texturesZip=$texturesZip models=$models images=$images" +
            (if (spritesFailed.isEmpty()) "" else " spriteFailures=${spritesFailed.size}") +
            (if (modelsFailed.isEmpty()) "" else " modelFailures=${modelsFailed.size}") +
            (if (imagesFailed.isEmpty()) "" else " imageFailures=${imagesFailed.size}")
    }
}

/**
 * Publishes a revision's raw assets to the CDN: sprite PNGs and `sprites.zip`, `textures.zip`, and
 * raw model `.dat` meshes.
 *
 * Sprite and texture bytes come from PostgreSQL (`entity_blob`), so any published revision can be
 * re-uploaded without its cache still being on disk. Model meshes are raw index-7 archive bytes,
 * which are deliberately **not** stored in the database, so those come from the revision's raw
 * cache; [RawCacheStore] downloads it if asked.
 *
 * Used by ingestion after a revision is published, and by `./gradlew publishCdn` for backfill and
 * repair of revisions that are already published.
 */
class CdnPublisher(
    private val cdn: SpriteCdnConfig,
    private val game: RegisteredGame,
    private val entities: EntityQueries,
    private val rawCaches: RawCacheStore,
) {
    class Options(
        val kinds: Set<CdnKind> = CdnKind.ALL,
        /** Report what would be uploaded without contacting the CDN. */
        val dryRun: Boolean = false,
        /** List the CDN prefix first and upload only the objects that are missing. */
        val onlyMissing: Boolean = false,
        /** Download the raw cache when models are requested and it is not on disk. */
        val downloadCacheForModels: Boolean = false,
        /**
         * Upload every asset at this revision rather than only the ones whose content changed here.
         * Normal publishing wants the default: a sprite unchanged since revision 93 already lives
         * under revision 93's prefix, and the API resolves readers to it. Use this to rebuild a
         * revision's prefix in full.
         */
        val uploadUnchanged: Boolean = false,
    )

    /**
     * Coarse progress for the UI. The underlying uploaders report at phase boundaries rather than
     * per object, so this is "which phase, and how far through the three of them" — enough for a
     * bar that moves, without threading a counter through every upload path.
     */
    fun interface StageListener {
        fun onStage(label: String, percent: Int)
    }

    fun publish(
        rev: Int,
        options: Options = Options(),
        sourceCacheId: Int? = null,
        onProgress: (String) -> Unit = { logger.info { it } },
        onStage: StageListener = StageListener { _, _ -> },
    ): CdnPublishReport {
        if (!options.dryRun && !cdn.canUpload) {
            return CdnPublishReport(rev, skipped = "CDN upload not configured (set OPENRUNE_CDN_BUCKET)")
        }
        val gameType = game.game.gameType
        var spriteCount = 0
        var spriteFailures: List<Int> = emptyList()
        var texturesZip = false
        var modelCount = 0
        var modelFailures: List<Int> = emptyList()

        // Sprites are needed for textures.zip too, so load them once when either kind is requested.
        val wantSprites = CdnKind.SPRITES in options.kinds
        val wantTextures = CdnKind.TEXTURES in options.kinds
        val sprites = if (wantSprites || wantTextures) loadSprites(rev) else emptyMap()

        // A zip of the whole set is identical to the previous revision's unless that set changed,
        // so there is nothing to send. textures.zip embeds sprite images, so it also depends on
        // whether any sprite moved.
        val spriteSetChanged = entities.setChangedAt(game.type(OsrsEntityTypes.SPRITES), rev)
        val textureSetChanged = spriteSetChanged ||
            entities.setChangedAt(game.type(ConfigDiffType.TEXTURES.fileName), rev)

        if (wantSprites) {
            onStage.onStage("Uploading ${sprites.size} sprites for rev $rev", 5)
            if (options.dryRun) {
                onProgress("dryRun: rev $rev would upload ${sprites.size} sprites + sprites.zip")
            } else {
                // Partial failures throw with the per-id detail attached; record it and keep going
                // so one rate-limited revision cannot abort a whole backfill.
                // A weekly cache changes a handful of sprites out of thousands; the rest are byte
                // identical to an earlier revision and already uploaded under it.
                val changed = if (options.uploadUnchanged) null else
                    entities.changedAt(game.type(OsrsEntityTypes.SPRITES), rev).toSet()
                if (changed != null) {
                    onProgress("CDN: rev $rev has ${changed.size} changed sprites of ${sprites.size}")
                }
                try {
                    val result = SpriteCdn.publishRevisionSprites(
                        cdn, gameType, rev, sprites,
                        onlyMissing = options.onlyMissing,
                        uploadOnly = changed,
                        uploadZip = options.uploadUnchanged || spriteSetChanged,
                        onProgress = onProgress,
                    )
                    spriteCount = result.uploaded
                    spriteFailures = result.failedIds
                } catch (e: SpriteCdnPublishException) {
                    spriteCount = e.result.uploaded
                    spriteFailures = e.result.failedIds.ifEmpty { listOf(-1) }
                    logger.error { "rev $rev: sprite publish incomplete: ${e.message}" }
                }
            }
        }

        if (wantTextures) {
            if (!options.uploadUnchanged && !textureSetChanged) {
                onProgress("CDN: rev $rev textures.zip unchanged, not re-uploaded")
            } else {
                onStage.onStage("Uploading textures.zip for rev $rev", 45)
                val textures = loadTextures(rev, sprites)
                if (options.dryRun) {
                    onProgress("dryRun: rev $rev would upload textures.zip (${textures.size} textures)")
                } else {
                    texturesZip = SpriteCdn.publishRevisionTextures(cdn, gameType, rev, textures, onProgress = onProgress)
                }
            }
        }

        if (CdnKind.MODELS in options.kinds) {
            val cachePath = modelCachePath(rev, sourceCacheId, options, onProgress)
            if (cachePath == null) {
                onProgress("rev $rev: no raw cache on disk, skipping models (pass downloadCache=true to fetch it)")
            } else {
                val cache = Cache.load(cachePath.toPath())
                try {
                    val all = runCatching { cache.archives(MODELS).toList().sorted() }.getOrElse { e ->
                        logger.warn(e) { "rev $rev: could not list model archives" }
                        emptyList()
                    }
                    // Same reasoning as sprites: only meshes that changed at this revision need a
                    // new object, and `/models/*` hands readers the URL of the revision they did.
                    val ids = if (options.uploadUnchanged) all else {
                        val changed = entities.changedAt(game.type(OsrsEntityTypes.MODELS), rev).toSet()
                        onProgress("CDN: rev $rev has ${changed.size} changed models of ${all.size}")
                        all.filter { it in changed }
                    }
                    onStage.onStage("Uploading ${ids.size} models for rev $rev", 55)
                    if (options.dryRun) {
                        onProgress("dryRun: rev $rev would upload ${ids.size} model .dat files")
                    } else {
                        val result = ModelCdn.publishRevisionModels(
                            cdn, gameType, rev, ids, { id -> cache.data(MODELS, id) },
                            onlyMissing = options.onlyMissing, onProgress = onProgress,
                        )
                        modelCount = result.uploaded
                        modelFailures = result.failedIds
                    }
                } finally {
                    runCatching { cache.close() }
                }
            }
        }

        // Rendered item and object images. Same rule as everything else: only what changed here.
        var imageCount = 0
        var imageFailures: List<Int> = emptyList()
        listOf(
            CdnKind.ITEMS to (OsrsEntityTypes.ITEM_SPRITES to "items"),
            CdnKind.OBJECTS to (OsrsEntityTypes.OBJECT_SPRITES to "objects"),
        ).forEach { (kind, spec) ->
            if (kind !in options.kinds) return@forEach
            val (typeKey, folder) = spec
            val type = game.typeOrNull(typeKey) ?: return@forEach
            onStage.onStage("Uploading $folder images for rev $rev", if (kind == CdnKind.ITEMS) 70 else 85)
            val wanted = if (options.uploadUnchanged) null else entities.changedAt(type, rev).toSet()
            val images = LinkedHashMap<Int, ByteArray>()
            entities.forEachBlob(type, rev) { id, bytes ->
                if (wanted == null || id in wanted) images[id] = bytes
            }
            if (wanted != null) onProgress("CDN: rev $rev has ${images.size} changed $folder images")
            if (options.dryRun) {
                onProgress("dryRun: rev $rev would upload ${images.size} $folder images")
            } else {
                val failed = SpriteCdn.publishImages(cdn, gameType, rev, folder, images, onProgress = onProgress)
                imageCount += images.size - failed.size
                imageFailures = imageFailures + failed
            }
        }

        onStage.onStage("CDN upload complete for rev $rev", 100)
        return CdnPublishReport(
            rev, spriteCount, spriteFailures, texturesZip, modelCount, modelFailures,
            images = imageCount, imagesFailed = imageFailures,
        )
    }

    private fun loadSprites(rev: Int): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        entities.forEachBlob(game.type(OsrsEntityTypes.SPRITES), rev) { id, bytes -> out[id] = bytes }
        return out
    }

    /** Texture id -> the PNG of the sprite its `fileId` points at; textures with no sprite are dropped. */
    private fun loadTextures(rev: Int, sprites: Map<Int, ByteArray>): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        entities.forEachPayload(game.type(ConfigDiffType.TEXTURES.fileName), rev) { id, _, body ->
            val fileId = body.asJsonObject["fileId"]?.takeIf { it.isJsonPrimitive }?.asInt
            if (fileId != null) sprites[fileId]?.let { out[id] = it }
        }
        return out
    }

    private fun modelCachePath(rev: Int, sourceCacheId: Int?, options: Options, onProgress: (String) -> Unit): File? {
        rawCaches.cachePath(rev).takeIf { it.isDirectory }?.let { return it }
        if (!options.downloadCacheForModels || sourceCacheId == null) return null
        onProgress("rev $rev: downloading raw cache $sourceCacheId for model meshes")
        return runCatching { rawCaches.ensure(rev, sourceCacheId) { _, msg -> onProgress(msg) } }
            .getOrElse { e ->
                logger.warn(e) { "rev $rev: raw cache download failed" }
                null
            }
    }
}
