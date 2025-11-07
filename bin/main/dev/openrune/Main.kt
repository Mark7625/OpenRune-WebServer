package dev.openrune

import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import dev.openrune.server.WebServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    val rev = args.getOrNull(0)?.toIntOrNull() ?: -1
    val game = args.getOrNull(1) ?: GameType.OLDSCHOOL.toString()
    val environmentType = args.getOrNull(2) ?: CacheEnvironment.LIVE.toString()
    val networkPort = args.getOrNull(3)?.toIntOrNull() ?: 8090

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

    val config = ServerConfig(
        revision = rev,
        gameType = gameType,
        environment = cacheEnv,
        port = networkPort
    )

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

