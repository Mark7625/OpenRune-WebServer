package dev.openrune.model

import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType

/**
 * One queryable stream of revisions: a game plus a cache environment. Every stored row carries
 * the stream's id so OSRS, OSRS beta and RS3 can share one database without touching each other.
 */
data class Game(
    val id: Int,
    val slug: String,
    val name: String,
    val gameType: GameType,
    val environment: CacheEnvironment,
) {
    /** Path segment used on the CDN and in the website (`osrs` / `rs3`). */
    val cdnSlug: String
        get() = when (gameType) {
            GameType.OLDSCHOOL -> "osrs"
            GameType.RUNESCAPE -> "rs3"
            else -> gameType.name.lowercase()
        }

    companion object {
        fun slugFor(gameType: GameType, environment: CacheEnvironment): String {
            val base = when (gameType) {
                GameType.OLDSCHOOL -> "osrs"
                GameType.RUNESCAPE -> "rs3"
                else -> gameType.name.lowercase()
            }
            return if (environment == CacheEnvironment.LIVE) base else "$base-${environment.name.lowercase()}"
        }

        fun displayName(gameType: GameType, environment: CacheEnvironment): String {
            val base = when (gameType) {
                GameType.OLDSCHOOL -> "Old School RuneScape"
                GameType.RUNESCAPE -> "RuneScape 3"
                else -> gameType.name
            }
            return if (environment == CacheEnvironment.LIVE) base else "$base (${environment.name.lowercase()})"
        }
    }
}
