package dev.openrune

import dev.openrune.cache.diff.DiffDumper
import dev.openrune.cache.diff.DiffBinaryCache
import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import dev.openrune.cache.tools.OpenRS2
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val gameType = GameType.OLDSCHOOL
    val environment = CacheEnvironment.LIVE

    OpenRS2.loadCaches()
    try {
        runBlocking {
            DiffDumper(gameType, environment).run(1, 241)
            DiffDumper(gameType, environment).run(100, 210)
            DiffDumper(gameType, environment).run(236, 2499)
            DiffDumper(gameType, environment).run(237, 2518)
        }
    } finally {
        DiffBinaryCache.shutdown()
    }
}
