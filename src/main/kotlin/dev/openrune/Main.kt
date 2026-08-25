package dev.openrune

import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import dev.openrune.server.WebServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val NAV_OVERRIDES_ENV = "OPENRUNE_NAV_DISPLAY_OVERRIDES"

private fun parseNavDisplayOverrides(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw
        .split(';', ',')
        .mapNotNull { part ->
            val token = part.trim()
            if (token.isEmpty()) return@mapNotNull null
            val idx = token.indexOf('=')
            if (idx <= 0 || idx >= token.length - 1) return@mapNotNull null
            val key = token.substring(0, idx).trim().lowercase()
            val value = token.substring(idx + 1).trim()
            if (key.isEmpty() || value.isEmpty()) return@mapNotNull null
            key to value
        }
        .toMap()
}

fun main(args: Array<String>) {
    loadDotEnv()

    val cacheID = args.getOrNull(0)?.toIntOrNull() ?: -1
    val game = args.getOrNull(1) ?: GameType.OLDSCHOOL.toString()
    val environmentType = args.getOrNull(2) ?: CacheEnvironment.LIVE.toString()
    val networkPort = args.getOrNull(3)?.toIntOrNull() ?: 8090
    val navOverrideArg = args.getOrNull(4)

    val gameType = try {
        GameType.valueOf(game.uppercase())
    } catch (e: IllegalArgumentException) {
        GameType.OLDSCHOOL
    }

    val cacheEnv = try {
        CacheEnvironment.valueOf(environmentType.uppercase())
    } catch (e: IllegalArgumentException) {
        CacheEnvironment.LIVE
    }

    val navDisplayNameOverrides = parseNavDisplayOverrides(
        navOverrideArg ?: System.getenv(NAV_OVERRIDES_ENV)
    )

    if (navDisplayNameOverrides.isNotEmpty()) {
        logger.info { "Loaded ${navDisplayNameOverrides.size} nav display-name override(s) from ${if (navOverrideArg != null) "CLI arg" else NAV_OVERRIDES_ENV}" }
    }

    val config = ServerConfig(
        gameType = gameType,
        cacheID = cacheID,
        environment = cacheEnv,
        port = networkPort,
        navDisplayNameOverrides = navDisplayNameOverrides,
        spriteCdn = SpriteCdnConfig.fromEnv(),
    )

    if (config.spriteCdn.enabled) {
        logger.info {
            "Sprite CDN enabled bucket=${config.spriteCdn.bucket} baseUrl=${config.spriteCdn.baseUrl} " +
                "endpoint=${config.spriteCdn.endpoint ?: "aws-default"} " +
                "spritesInBin=${config.spriteCdn.includeSpritesInBin}"
        }
    }

    runBlocking {
        val server = WebServer(config)
        server.start()
        
        // Keep the server running
        logger.info("Server is running. Press Ctrl+C to stop.")
        while (true) {
            delay(Long.MAX_VALUE)
        }
    }
}

