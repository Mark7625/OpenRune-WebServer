package dev.openrune.cache.diff

import dev.openrune.SpriteCdnConfig
import dev.openrune.cache.tools.GameType
import me.tongfei.progressbar.ProgressBarBuilder
import me.tongfei.progressbar.ProgressBarStyle
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

data class ModelPublishResult(
    val rev: Int,
    val total: Int,
    val uploaded: Int,
    val failedIds: List<Int>,
) {
    val ok: Boolean get() = failedIds.isEmpty()
}

/**
 * Raw model meshes on the CDN: `{osrs|rs3}/rev/{rev}/models/{id}.dat`.
 *
 * Every revision holds its own full copy so a rev path is self-contained — the same
 * layout sprites use. Per-model metadata (verts, faces, textures, colours, attachments)
 * is not stored here; it lives in the revision `.bin` as [ModelMeta].
 */
object ModelCdn {
    private const val DEFAULT_MAX_ATTEMPTS = 6

    fun modelObjectKey(game: GameType, rev: Int, id: Int): String =
        "${game.cdnSlug()}/rev/$rev/models/$id.dat"

    fun modelsPrefix(game: GameType, rev: Int): String =
        "${game.cdnSlug()}/rev/$rev/models/"

    fun publicModelUrl(cdn: SpriteCdnConfig, game: GameType, rev: Int, id: Int): String? {
        val base = cdn.baseUrl?.trimEnd('/') ?: return null
        return "$base/${modelObjectKey(game, rev, id)}"
    }

    /**
     * Upload every model `.dat` for [rev], one at a time with a progress bar.
     *
     * [dataFor] is called lazily per id so the whole mesh set never has to be held in memory.
     * Ids whose data is missing are skipped (not counted as failures).
     */
    fun publishRevisionModels(
        cdn: SpriteCdnConfig,
        game: GameType,
        rev: Int,
        ids: List<Int>,
        dataFor: (Int) -> ByteArray?,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        onProgress: (String) -> Unit = {},
    ): ModelPublishResult {
        if (ids.isEmpty()) {
            onProgress("CDN: no models to publish for rev $rev")
            return ModelPublishResult(rev, 0, 0, emptyList())
        }
        if (!cdn.canUpload) {
            onProgress("CDN: model upload skipped (set OPENRUNE_CDN_BUCKET to enable)")
            return ModelPublishResult(rev, ids.size, 0, emptyList())
        }

        val bucket = cdn.bucket!!
        val client = SpriteCdn.s3Client(cdn)
        var uploaded = 0
        val failedIds = mutableListOf<Int>()
        var missing = 0
        try {
            onProgress("CDN: uploading ${ids.size} models to s3://$bucket/${modelsPrefix(game, rev)} (1-by-1)")
            ProgressBarBuilder()
                .setTaskName("rev $rev models")
                .setInitialMax(ids.size.toLong())
                .setStyle(ProgressBarStyle.UNICODE_BLOCK)
                .setUpdateIntervalMillis(200)
                .build()
                .use { bar ->
                    for (id in ids.sorted()) {
                        val bytes = dataFor(id)
                        if (bytes == null) {
                            missing++
                        } else {
                            val ok = SpriteCdn.putObjectWithRetry(
                                client = client,
                                bucket = bucket,
                                key = modelObjectKey(game, rev, id),
                                bytes = bytes,
                                contentType = "application/octet-stream",
                                cacheControl = "public, max-age=31536000, immutable",
                                maxAttempts = maxAttempts,
                            )
                            if (ok) uploaded++ else failedIds += id
                        }
                        bar.step()
                        bar.setExtraMessage("id=$id ok=$uploaded fail=${failedIds.size}")
                    }
                }
        } finally {
            runCatching { client.close() }
        }

        if (failedIds.isNotEmpty()) {
            val sample = failedIds.take(12).joinToString(",")
            val msg = "CDN: rev $rev failed ${failedIds.size}/${ids.size} model uploads " +
                "(uploaded=$uploaded); sample ids=[$sample]"
            onProgress(msg)
            logger.error { msg }
        } else {
            onProgress(
                "CDN: uploaded $uploaded models for rev $rev" +
                    (if (missing > 0) " ($missing id(s) had no data)" else ""),
            )
        }
        return ModelPublishResult(rev, ids.size, uploaded, failedIds)
    }
}
