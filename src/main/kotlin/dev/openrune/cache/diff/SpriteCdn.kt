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
) {
    val ok: Boolean get() = failedIds.isEmpty() && (total == 0 || zipUploaded)
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
                .filter { (id, _) -> id !in existing }
                .sortedBy { it.key }
            val skipped = sprites.size - toUpload.size
            onProgress(
                "CDN: uploading ${toUpload.size} sprites" +
                    (if (skipped > 0) " (skipping $skipped existing)" else "") +
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

    fun listUploadedSpriteIds(client: S3Client, bucket: String, game: GameType, rev: Int): Set<Int> {
        val prefix = spritesPrefix(game, rev)
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
                if (!key.endsWith(".png")) continue
                val name = key.removePrefix(prefix).removeSuffix(".png")
                name.toIntOrNull()?.let { ids.add(it) }
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

    fun resizePng(png: ByteArray, width: Int?, height: Int?, keepAspectRatio: Boolean): ByteArray {
        if ((width == null || width <= 0) && (height == null || height <= 0)) return png
        val src = ImageIO.read(ByteArrayInputStream(png)) ?: return png
        val sw = src.width.coerceAtLeast(1)
        val sh = src.height.coerceAtLeast(1)
        val tw: Int
        val th: Int
        if (keepAspectRatio) {
            val targetW = width?.takeIf { it > 0 }
            val targetH = height?.takeIf { it > 0 }
            when {
                targetW != null && targetH != null -> {
                    val scale = minOf(targetW.toDouble() / sw, targetH.toDouble() / sh)
                    tw = (sw * scale).toInt().coerceAtLeast(1)
                    th = (sh * scale).toInt().coerceAtLeast(1)
                }
                targetW != null -> {
                    tw = targetW
                    th = (sh * (targetW.toDouble() / sw)).toInt().coerceAtLeast(1)
                }
                targetH != null -> {
                    th = targetH
                    tw = (sw * (targetH.toDouble() / sh)).toInt().coerceAtLeast(1)
                }
                else -> return png
            }
        } else {
            tw = (width?.takeIf { it > 0 } ?: sw)
            th = (height?.takeIf { it > 0 } ?: sh)
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

    private fun putObjectWithRetry(
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
