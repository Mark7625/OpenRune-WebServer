package dev.openrune.cache.diff

import dev.openrune.SpriteCdnConfig
import dev.openrune.cache.tools.GameType
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarBuilder
import me.tongfei.progressbar.ProgressBarStyle
import mu.KotlinLogging
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.math.min
import kotlin.random.Random

private val logger = KotlinLogging.logger {}

/** Short CDN path segment (osrs / rs3), matching website hosts. */
fun GameType.cdnSlug(): String = when (this) {
    GameType.OLDSCHOOL -> "osrs"
    GameType.RUNESCAPE -> "rs3"
    else -> name.lowercase()
}

data class SpritePublishResult(
    val rev: Int,
    val total: Int,
    val uploaded: Int,
    val skippedExisting: Int,
    val failedIds: List<Int>,
    val zipUploaded: Boolean,
    /** The zip was deliberately not sent because it is unchanged, as opposed to having failed. */
    val zipSkipped: Boolean = false,
) {
    val ok: Boolean get() = failedIds.isEmpty() && (total == 0 || zipUploaded || zipSkipped)
}

class SpriteCdnPublishException(
    val result: SpritePublishResult,
    message: String,
) : RuntimeException(message)

object SpriteCdn {
    private const val DEFAULT_MAX_ATTEMPTS = 6

    fun spriteObjectKey(game: GameType, rev: Int, id: Int): String =
        "${game.cdnSlug()}/rev/$rev/sprites/$id.png"

    fun spritesZipObjectKey(game: GameType, rev: Int): String =
        "${game.cdnSlug()}/rev/$rev/sprites.zip"

    fun spritesPrefix(game: GameType, rev: Int): String =
        "${game.cdnSlug()}/rev/$rev/sprites/"

    fun texturesZipObjectKey(game: GameType, rev: Int): String =
        "${game.cdnSlug()}/rev/$rev/textures.zip"

    fun publicTexturesZipUrl(cdn: SpriteCdnConfig, game: GameType, rev: Int): String? {
        val base = cdn.baseUrl?.trimEnd('/') ?: return null
        return "$base/${texturesZipObjectKey(game, rev)}"
    }

    /**
     * Resolve texture id -> PNG bytes by following each texture's `fileId` into [sprites].
     * Textures whose sprite is missing are dropped.
     */
    fun textureBytes(
        configs: Map<String, Map<Int, DefinitionSnapshot>>,
        sprites: Map<Int, ByteArray>,
    ): Map<Int, ByteArray> {
        val textures = configs[ConfigDiffType.TEXTURES.fileName] ?: return emptyMap()
        val out = LinkedHashMap<Int, ByteArray>(textures.size)
        for (textureId in textures.keys.sorted()) {
            val fileId = textures[textureId]?.get("fileId")?.value as? Int ?: continue
            val bytes = sprites[fileId] ?: continue
            out[textureId] = bytes
        }
        return out
    }

    fun publicSpriteUrl(cdn: SpriteCdnConfig, game: GameType, rev: Int, id: Int): String? {
        val base = cdn.baseUrl?.trimEnd('/') ?: return null
        return "$base/${spriteObjectKey(game, rev, id)}"
    }

    /** `{game}/rev/{rev}/{folder}/{id}.png` — rendered item and object images. */
    fun imageObjectKey(game: GameType, rev: Int, folder: String, id: Int): String =
        "${game.cdnSlug()}/rev/$rev/$folder/$id.png"

    fun publicImageUrl(cdn: SpriteCdnConfig, game: GameType, rev: Int, folder: String, id: Int): String? {
        val base = cdn.baseUrl?.trimEnd('/') ?: return null
        return "$base/${imageObjectKey(game, rev, folder, id)}"
    }

    /**
     * Uploads a set of rendered PNGs under one folder. Unlike [publishRevisionSprites] there is no
     * zip and no index — these are standalone images the website links directly.
     *
     * Returns the ids that failed, so one bad upload reports without aborting the batch.
     */
    fun publishImages(
        cdn: SpriteCdnConfig,
        game: GameType,
        rev: Int,
        folder: String,
        images: Map<Int, ByteArray>,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        onProgress: (String) -> Unit = {},
    ): List<Int> {
        if (images.isEmpty()) {
            onProgress("CDN: no $folder images to publish for rev $rev")
            return emptyList()
        }
        if (!cdn.canUpload) {
            onProgress("CDN: $folder upload skipped (set OPENRUNE_CDN_BUCKET to enable)")
            return emptyList()
        }
        val bucket = cdn.bucket!!
        val client = s3Client(cdn)
        val failed = mutableListOf<Int>()
        try {
            onProgress("CDN: uploading ${images.size} $folder images to s3://$bucket/${game.cdnSlug()}/rev/$rev/$folder/")
            images.entries.sortedBy { it.key }.forEach { (id, bytes) ->
                val ok = putObjectWithRetry(
                    client = client,
                    bucket = bucket,
                    key = imageObjectKey(game, rev, folder, id),
                    bytes = bytes,
                    contentType = "image/png",
                    cacheControl = "public, max-age=31536000, immutable",
                    maxAttempts = maxAttempts,
                )
                if (!ok) failed += id
            }
            if (failed.isNotEmpty()) {
                onProgress("CDN: rev $rev failed ${failed.size}/${images.size} $folder uploads")
            }
        } finally {
            runCatching { client.close() }
        }
        return failed
    }

    fun publicSpritesZipUrl(cdn: SpriteCdnConfig, game: GameType, rev: Int): String? {
        val base = cdn.baseUrl?.trimEnd('/') ?: return null
        return "$base/${spritesZipObjectKey(game, rev)}"
    }

    /**
     * After a dump / migrate: write local zip mirror, upload every PNG + zip to S3 (when configured).
     *
     * Uploads **one PNG at a time** with a progress bar per revision.
     * When [onlyMissing] is true, lists the CDN prefix first and only PUTs ids that are absent.
     *
     * Throws [SpriteCdnPublishException] if any PNG fails after retries (zip is not marked success).
     */
    fun publishRevisionSprites(
        cdn: SpriteCdnConfig,
        game: GameType,
        rev: Int,
        sprites: Map<Int, ByteArray>,
        onlyMissing: Boolean = false,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        /**
         * When set, only these ids are uploaded as individual PNGs — the rest are unchanged since
         * an earlier revision and already on the CDN under that revision's prefix. `sprites.zip`
         * is still built from the full set, so a zip is always complete for its revision.
         */
        uploadOnly: Set<Int>? = null,
        /**
         * False when the sprite set is unchanged since an earlier revision — the zip would be byte
         * for byte what is already on the CDN under that revision, so there is nothing to send.
         */
        uploadZip: Boolean = true,
        onProgress: (String) -> Unit = {},
    ): SpritePublishResult {
        if (sprites.isEmpty()) {
            onProgress("CDN: no sprites to publish for rev $rev")
            return SpritePublishResult(rev, 0, 0, 0, emptyList(), zipUploaded = false)
        }

        val zipBytes = buildPngZip(sprites)
        val localZip = localZipFile(game, rev, "sprites")
        runCatching {
            localZip.parentFile?.mkdirs()
            localZip.writeBytes(zipBytes)
            onProgress("CDN: wrote local zip ${localZip.name} (${sprites.size} pngs)")
        }.onFailure { e -> logger.warn(e) { "Failed writing local sprites zip" } }

        if (!cdn.canUpload) {
            onProgress("CDN: upload skipped (set OPENRUNE_CDN_BUCKET to enable)")
            return SpritePublishResult(
                rev,
                sprites.size,
                uploaded = 0,
                skippedExisting = 0,
                failedIds = emptyList(),
                zipUploaded = false,
            )
        }

        val bucket = cdn.bucket!!
        val client = s3Client(cdn)
        try {
            val existing: Set<Int> =
                if (onlyMissing) {
                    onProgress("CDN: listing existing sprites for rev $rev…")
                    listUploadedSpriteIds(client, bucket, game, rev).also { have ->
                        onProgress("CDN: rev $rev already has ${have.size}/${sprites.size} png objects")
                    }
                } else {
                    emptySet()
                }

            val toUpload = sprites.entries
                .filter { (id, _) -> id !in existing && (uploadOnly == null || id in uploadOnly) }
                .sortedBy { it.key }
            val skipped = sprites.size - toUpload.size
            onProgress(
                "CDN: uploading ${toUpload.size} sprites" +
                    (if (skipped > 0) " (skipping $skipped unchanged/existing)" else "") +
                    " to s3://$bucket/${spritesPrefix(game, rev)} (1-by-1)",
            )

            var uploaded = 0
            val failedIds = mutableListOf<Int>()

            if (toUpload.isNotEmpty()) {
                ProgressBarBuilder()
                    .setTaskName("rev $rev sprites")
                    .setInitialMax(toUpload.size.toLong())
                    .setStyle(ProgressBarStyle.UNICODE_BLOCK)
                    .setUpdateIntervalMillis(200)
                    .build()
                    .use { bar ->
                        for ((id, bytes) in toUpload) {
                            val ok = putObjectWithRetry(
                                client = client,
                                bucket = bucket,
                                key = spriteObjectKey(game, rev, id),
                                bytes = bytes,
                                contentType = "image/png",
                                cacheControl = "public, max-age=31536000, immutable",
                                maxAttempts = maxAttempts,
                            )
                            if (ok) {
                                uploaded++
                            } else {
                                failedIds += id
                            }
                            bar.step()
                            bar.setExtraMessage("id=$id ok=$uploaded fail=${failedIds.size}")
                        }
                    }
            }

            if (failedIds.isNotEmpty()) {
                val sample = failedIds.take(12).joinToString(",")
                val msg =
                    "CDN: rev $rev failed ${failedIds.size}/${toUpload.size} png uploads " +
                        "(uploaded=$uploaded, skippedExisting=$skipped); sample ids=[$sample]"
                onProgress(msg)
                logger.error { msg }
                val result = SpritePublishResult(
                    rev = rev,
                    total = sprites.size,
                    uploaded = uploaded,
                    skippedExisting = skipped,
                    failedIds = failedIds,
                    zipUploaded = false,
                )
                throw SpriteCdnPublishException(result, msg)
            }

            if (!uploadZip) {
                onProgress("CDN: rev $rev sprites.zip unchanged, not re-uploaded")
                return SpritePublishResult(
                    rev = rev,
                    total = sprites.size,
                    uploaded = uploaded,
                    skippedExisting = skipped,
                    failedIds = emptyList(),
                    zipUploaded = false,
                    zipSkipped = true,
                )
            }

            onProgress("CDN: uploading sprites.zip for rev $rev…")
            val zipOk = putObjectWithRetry(
                client = client,
                bucket = bucket,
                key = spritesZipObjectKey(game, rev),
                bytes = zipBytes,
                contentType = "application/zip",
                cacheControl = "public, max-age=86400",
                maxAttempts = maxAttempts,
            )
            if (!zipOk) {
                val msg = "CDN: rev $rev sprites.zip upload failed after retries"
                onProgress(msg)
                val result = SpritePublishResult(
                    rev = rev,
                    total = sprites.size,
                    uploaded = uploaded,
                    skippedExisting = skipped,
                    failedIds = emptyList(),
                    zipUploaded = false,
                )
                throw SpriteCdnPublishException(result, msg)
            }

            onProgress(
                "CDN: uploaded sprites + zip for rev $rev " +
                    "(pngs=$uploaded, skippedExisting=$skipped, total=${sprites.size})",
            )
            return SpritePublishResult(
                rev = rev,
                total = sprites.size,
                uploaded = uploaded,
                skippedExisting = skipped,
                failedIds = emptyList(),
                zipUploaded = true,
            )
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * Write a local `textures.zip` mirror and upload it to `{osrs|rs3}/rev/{rev}/textures.zip`.
     *
     * [textures] maps texture id -> PNG bytes (the sprite the texture points at via `fileId`).
     * Only the zip is published — textures have no per-id CDN objects.
     *
     * Returns true when the zip reached the CDN; false when upload is disabled or failed after retries.
     */
    fun publishRevisionTextures(
        cdn: SpriteCdnConfig,
        game: GameType,
        rev: Int,
        textures: Map<Int, ByteArray>,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        onProgress: (String) -> Unit = {},
    ): Boolean {
        if (textures.isEmpty()) {
            onProgress("CDN: no textures to publish for rev $rev")
            return false
        }

        val zipBytes = buildPngZip(textures)
        val localZip = localZipFile(game, rev, "textures")
        runCatching {
            localZip.parentFile?.mkdirs()
            localZip.writeBytes(zipBytes)
            onProgress("CDN: wrote local zip ${localZip.name} (${textures.size} pngs)")
        }.onFailure { e -> logger.warn(e) { "Failed writing local textures zip" } }

        if (!cdn.canUpload) {
            onProgress("CDN: textures upload skipped (set OPENRUNE_CDN_BUCKET to enable)")
            return false
        }

        val client = s3Client(cdn)
        try {
            onProgress("CDN: uploading textures.zip for rev $rev (${textures.size} pngs)…")
            val ok = putObjectWithRetry(
                client = client,
                bucket = cdn.bucket!!,
                key = texturesZipObjectKey(game, rev),
                bytes = zipBytes,
                contentType = "application/zip",
                cacheControl = "public, max-age=86400",
                maxAttempts = maxAttempts,
            )
            if (ok) {
                onProgress("CDN: uploaded textures.zip for rev $rev (${textures.size} pngs)")
            } else {
                val msg = "CDN: rev $rev textures.zip upload failed after retries"
                onProgress(msg)
                logger.error { msg }
            }
            return ok
        } finally {
            runCatching { client.close() }
        }
    }

    fun listUploadedSpriteIds(client: S3Client, bucket: String, game: GameType, rev: Int): Set<Int> =
        listUploadedIds(client, bucket, spritesPrefix(game, rev), ".png")

    /** Ids of `{prefix}{id}{suffix}` objects already in the bucket; used to upload only what is missing. */
    fun listUploadedIds(client: S3Client, bucket: String, prefix: String, suffix: String): Set<Int> {
        val ids = HashSet<Int>(4096)
        var token: String? = null
        do {
            val req = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .continuationToken(token)
                .build()
            val resp = client.listObjectsV2(req)
            for (obj in resp.contents()) {
                val key = obj.key() ?: continue
                if (!key.endsWith(suffix)) continue
                key.removePrefix(prefix).removeSuffix(suffix).toIntOrNull()?.let { ids.add(it) }
            }
            token = if (resp.isTruncated) resp.nextContinuationToken() else null
        } while (token != null)
        return ids
    }

    fun fetchSpritePng(cdn: SpriteCdnConfig, game: GameType, rev: Int, id: Int): ByteArray? {
        val url = publicSpriteUrl(cdn, game, rev, id) ?: return null
        return runCatching {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes() }
        }.getOrNull()
    }

    /**
     * @param allowUpscale when false the requested size is a maximum: a sprite already smaller than
     *   it is returned untouched. Callers rendering a thumbnail want this — most cache sprites are
     *   tiny (map icons are 15x15) and blowing them up to fill the box only produces a blurry,
     *   oversized image the browser then has to clamp back down.
     */
    fun resizePng(
        png: ByteArray,
        width: Int?,
        height: Int?,
        keepAspectRatio: Boolean,
        allowUpscale: Boolean = true,
    ): ByteArray {
        if ((width == null || width <= 0) && (height == null || height <= 0)) return png
        val src = ImageIO.read(ByteArrayInputStream(png)) ?: return png
        val sw = src.width.coerceAtLeast(1)
        val sh = src.height.coerceAtLeast(1)
        val targetW = width?.takeIf { it > 0 }
        val targetH = height?.takeIf { it > 0 }
        var tw: Int
        var th: Int
        if (keepAspectRatio) {
            val scale = when {
                targetW != null && targetH != null -> minOf(targetW.toDouble() / sw, targetH.toDouble() / sh)
                targetW != null -> targetW.toDouble() / sw
                targetH != null -> targetH.toDouble() / sh
                else -> return png
            }.let { if (allowUpscale) it else minOf(it, 1.0) }
            tw = (sw * scale).toInt().coerceAtLeast(1)
            th = (sh * scale).toInt().coerceAtLeast(1)
        } else {
            tw = targetW ?: sw
            th = targetH ?: sh
            if (!allowUpscale) {
                tw = tw.coerceAtMost(sw)
                th = th.coerceAtMost(sh)
            }
        }
        if (tw == sw && th == sh) return png
        val scaled = java.awt.image.BufferedImage(tw, th, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g.drawImage(src, 0, 0, tw, th, null)
        g.dispose()
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(scaled, "png", out)
            out.toByteArray()
        }
    }

    internal fun putObjectWithRetry(
        client: S3Client,
        bucket: String,
        key: String,
        bytes: ByteArray,
        contentType: String,
        cacheControl: String,
        maxAttempts: Int,
    ): Boolean {
        var last: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                client.putObject(
                    PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .cacheControl(cacheControl)
                        .build(),
                    RequestBody.fromBytes(bytes),
                )
                return true
            } catch (e: Exception) {
                last = e
                val sleepMs = min(30_000L, (250L shl attempt.coerceAtMost(8)) + Random.nextLong(0, 250))
                logger.warn {
                    "CDN put failed attempt ${attempt + 1}/$maxAttempts key=$key: ${e.message}; retry in ${sleepMs}ms"
                }
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        logger.error(last) { "CDN put gave up after $maxAttempts attempts key=$key" }
        return false
    }

    private fun buildPngZip(pngs: Map<Int, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for (id in pngs.keys.sorted()) {
                val bytes = pngs[id] ?: continue
                zos.putNextEntry(ZipEntry("$id.png"))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun localZipFile(game: GameType, rev: Int, kind: String): File {
        val root = File("cache", "${game.name.lowercase()}/cdn")
        return File(root, "${game.cdnSlug()}-rev-$rev-$kind.zip")
    }

    fun s3Client(cdn: SpriteCdnConfig): S3Client {
        val builder = S3Client.builder().region(Region.of(cdn.region))
        val endpoint = cdn.endpoint?.trim()?.takeIf { it.isNotEmpty() }
        if (endpoint != null) {
            builder
                .endpointOverride(URI.create(endpoint))
                .serviceConfiguration(
                    S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build(),
                )
        }
        val accessKey = cdn.accessKeyId?.trim()?.takeIf { it.isNotEmpty() }
        val secretKey = cdn.secretAccessKey?.trim()?.takeIf { it.isNotEmpty() }
        if (accessKey != null && secretKey != null) {
            builder.credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)),
            )
        }
        return builder.build()
    }
}
