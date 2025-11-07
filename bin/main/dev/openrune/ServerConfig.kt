package dev.openrune

import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType

data class ServerConfig(
    val revision: Int,
    val gameType: GameType,
    val environment: CacheEnvironment,
    val port: Int
)






