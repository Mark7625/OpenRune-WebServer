package dev.openrune

import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import java.io.File

/**
 * Optional CloudFront / S3 / Cloudflare R2 sprite CDN settings.
 *
 * Env (OPENRUNE_* preferred; R2_* also accepted):
 * - OPENRUNE_CDN_BASE_URL / R2_PUBLIC_BASE_URL — public CDN origin
 * - OPENRUNE_CDN_BUCKET / R2_BUCKET_NAME — bucket (required to upload on dump)
 * - OPENRUNE_CDN_REGION — AWS/R2 region (default `auto` when R2 endpoint set, else `us-east-1`)
 * - OPENRUNE_CDN_ENDPOINT — S3 API endpoint (R2: https://{accountId}.r2.cloudflarestorage.com)
 * - R2_ACCOUNT_ID — builds the R2 endpoint when OPENRUNE_CDN_ENDPOINT is unset
 * - OPENRUNE_CDN_ACCESS_KEY_ID / R2_ACCESS_KEY_ID / AWS_ACCESS_KEY_ID
 * - OPENRUNE_CDN_SECRET_ACCESS_KEY / R2_SECRET_ACCESS_KEY / AWS_SECRET_ACCESS_KEY
 * - OPENRUNE_CDN_ENABLED — true/false (default: true when bucket or base URL set)
 */
data class SpriteCdnConfig(
    val enabled: Boolean = false,
    val bucket: String? = null,
    val region: String = "us-east-1",
    /** Public CDN origin used by website + API fetch (no trailing slash). */
    val baseUrl: String? = null,
    /** S3-compatible API endpoint (Cloudflare R2, MinIO, …). Null = default AWS. */
    val endpoint: String? = null,
    val accessKeyId: String? = null,
    val secretAccessKey: String? = null,
    /**
     * Redirect full-size sprite requests to the CDN instead of serving the bytes from PostgreSQL —
     * the CDN is where assets belong, so this keeps that traffic off the API and the database.
     *
     * On by default. It assumes a revision's assets have been uploaded; a revision that has never
     * been through `publishCdn` will redirect to an object that is not there. Set
     * `OPENRUNE_CDN_REDIRECT_SPRITES=false` to serve from PostgreSQL instead while backfilling the
     * CDN.
     */
    val redirectSprites: Boolean = true,
) {
    val canUpload: Boolean get() = enabled && !bucket.isNullOrBlank()
    val canServe: Boolean get() = enabled && !baseUrl.isNullOrBlank()

    companion object {
        fun fromEnv(): SpriteCdnConfig {
            val bucket =
                envOrProp("OPENRUNE_CDN_BUCKET")
                    ?: envOrProp("R2_BUCKET_NAME")
            val baseUrl =
                (envOrProp("OPENRUNE_CDN_BASE_URL") ?: envOrProp("R2_PUBLIC_BASE_URL"))
                    ?.trimEnd('/')
            val accountId = envOrProp("R2_ACCOUNT_ID")
            val endpoint =
                envOrProp("OPENRUNE_CDN_ENDPOINT")
                    ?: accountId?.let { "https://$it.r2.cloudflarestorage.com" }
            val region =
                envOrProp("OPENRUNE_CDN_REGION")
                    ?: if (endpoint != null) "auto" else "us-east-1"
            val accessKeyId =
                envOrProp("OPENRUNE_CDN_ACCESS_KEY_ID")
                    ?: envOrProp("R2_ACCESS_KEY_ID")
                    ?: envOrProp("AWS_ACCESS_KEY_ID")
            val secretAccessKey =
                envOrProp("OPENRUNE_CDN_SECRET_ACCESS_KEY")
                    ?: envOrProp("R2_SECRET_ACCESS_KEY")
                    ?: envOrProp("AWS_SECRET_ACCESS_KEY")
            val enabledEnv = envOrProp("OPENRUNE_CDN_ENABLED")?.lowercase()
            val enabled = when (enabledEnv) {
                "1", "true", "yes", "on" -> true
                "0", "false", "no", "off" -> false
                else -> bucket != null || baseUrl != null
            }
            // Opt-out rather than opt-in: redirecting is the intended behaviour, the flag exists
            // for the window before a CDN has been filled.
            val redirectSprites = envOrProp("OPENRUNE_CDN_REDIRECT_SPRITES")?.lowercase() !in
                setOf("0", "false", "no", "off")
            return SpriteCdnConfig(
                redirectSprites = redirectSprites,
                enabled = enabled,
                bucket = bucket,
                region = region,
                baseUrl = baseUrl,
                endpoint = endpoint?.trimEnd('/'),
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
            )
        }
    }
}

data class ServerConfig(
    val gameType: GameType,
    /** OpenRS2 cache id used to seed the lowest revision the worker ingests; 0 or -1 for "newest". */
    val cacheID: Int,
    val environment: CacheEnvironment,
    val port: Int,
    /** Optional nav display-name overrides keyed by section/group id (e.g. "spotanim" -> "SpotAnim"). */
    val navDisplayNameOverrides: Map<String, String> = emptyMap(),
    val spriteCdn: SpriteCdnConfig = SpriteCdnConfig.fromEnv(),
)
