package dev.openrune

import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import dev.openrune.db.DatabaseConfig
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val NAV_OVERRIDES_ENV = "OPENRUNE_NAV_DISPLAY_OVERRIDES"

/**
 * `java -jar openrune-server.jar <openRs2CacheId> <game> <environment> <port> [navOverrides]`
 *
 * The cache id only seeds the lowest revision the background worker ingests; what the API serves
 * is whatever is published in PostgreSQL.
 */
fun main(args: Array<String>) {
    loadDotEnv()

    val config = ServerConfig(
        gameType = enumOrDefault(args.getOrNull(1), GameType.OLDSCHOOL),
        cacheID = args.getOrNull(0)?.toIntOrNull() ?: -1,
        environment = enumOrDefault(args.getOrNull(2), CacheEnvironment.LIVE),
        port = args.getOrNull(3)?.toIntOrNull() ?: 8090,
        navDisplayNameOverrides = parseNavDisplayOverrides(args.getOrNull(4) ?: System.getenv(NAV_OVERRIDES_ENV)),
        spriteCdn = SpriteCdnConfig.fromEnv(),
    )

    if (config.spriteCdn.enabled) {
        logger.info {
            "Sprite CDN enabled bucket=${config.spriteCdn.bucket} baseUrl=${config.spriteCdn.baseUrl} " +
                "endpoint=${config.spriteCdn.endpoint ?: "aws-default"}"
        }
    }

    val dbConfig = DatabaseConfig.fromEnv(::envOrProp)
        ?: error("OPENRUNE_DATABASE_URL is required (e.g. jdbc:postgresql://localhost:5432/openrune); see .env.example")

    val app = App(config, dbConfig)
    Runtime.getRuntime().addShutdownHook(Thread(app::close))
    app.start()
    logger.info("Server is running. Press Ctrl+C to stop.")
    Thread.currentThread().join()
}

private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T =
    raw?.let { value -> enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } } ?: default

/** `worldentity=World Entities;spotanim=SpotAnim` — overrides sidebar labels for nav sections. */
private fun parseNavDisplayOverrides(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(';', ',').mapNotNull { part ->
        val token = part.trim()
        val eq = token.indexOf('=')
        if (eq <= 0 || eq >= token.length - 1) return@mapNotNull null
        token.substring(0, eq).trim().lowercase() to token.substring(eq + 1).trim()
    }.filter { (key, value) -> key.isNotEmpty() && value.isNotEmpty() }.toMap()
}
