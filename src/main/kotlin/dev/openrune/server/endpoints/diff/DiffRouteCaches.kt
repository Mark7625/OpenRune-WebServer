package dev.openrune.server.endpoints.diff

import kotlinx.coroutines.sync.Mutex
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe LRU: [LinkedHashMap] with access-order eviction when [size] exceeds [maxEntries].
 */
internal class LruMutexCache<K : Any, V : Any>(
    private val maxEntries: Int,
    initialCapacity: Int = 16,
) {
    private val lock = Any()
    private val map = object : LinkedHashMap<K, V>(initialCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxEntries
    }

    fun get(key: K): V? = synchronized(lock) { map[key] }

    fun put(key: K, value: V) {
        synchronized(lock) {
            map[key] = value
        }
    }

    fun getOrPut(key: K, defaultValue: () -> V): V = synchronized(lock) {
        map.getOrPut(key) { defaultValue() }
    }
}

internal const val MAX_CONFIG_CONTENT_CACHE_ENTRIES = 500
internal const val MAX_CONFIG_TABLE_CACHE_ENTRIES = 500
internal const val MAX_COMBINED_CONFIG_CACHE_ENTRIES = 500
internal const val MAX_COMBINED_SPRITES_CACHE_ENTRIES = 500
internal const val MAX_DELTA_SPRITES_CACHE_ENTRIES = 500
internal const val MAX_SPRITE_ETAG_CACHE_ENTRIES = 8000
internal const val MAX_CONFIG_DELTA_CACHE_ENTRIES = 500
internal const val MAX_AVAILABLE_REVISIONS_CACHE_ENTRIES = 50
internal const val MAX_REVISIONS_WITH_DATA_CACHE_ENTRIES = 50
internal const val MAX_MANIFEST_HAS_CHANGES_CACHE_ENTRIES = 2000
internal const val MAX_CONFIG_TABLE_SEARCH_CACHE_ENTRIES = 2000
internal const val MAX_CONFIG_ROWS_CACHE_ENTRIES = 200

internal const val AVAILABLE_REVISIONS_CACHE_TTL_MS = 60_000L
internal const val CONFIG_TABLE_SEARCH_CACHE_TTL_MS = 60 * 60 * 1000L

internal data class CachedConfigContent(val etag: String, val jsonText: String)

internal data class ConfigTableRowDto(val id: Int, val sectionId: String, val entries: Map<String, String>)

internal data class CachedConfigTable(val hash: String, val total: Int, val rows: List<ConfigTableRowDto>)

/** Combined config rows + config delta share this key shape (game, env, config type, base, rev). */
internal data class GameEnvTypeBaseRevKey(
    val game: String,
    val environment: String,
    val type: String,
    val base: Int,
    val rev: Int,
)

/** Combined sprite index + sprite delta share this key shape (game, env, base, rev). */
internal data class GameEnvBaseRevKey(
    val game: String,
    val environment: String,
    val base: Int,
    val rev: Int,
)

internal data class AvailableRevisionsCacheKey(
    val game: String,
    val environment: String,
)

internal data class CachedAvailableRevisions(
    val expiresAtMs: Long,
    val revisions: List<Int>,
)

internal data class RevisionsWithDataCacheKey(
    val game: String,
    val environment: String,
    val availableHash: Int,
    val manifestHash: Int,
    val serverRev: Int,
)

internal data class ManifestHasChangesCacheKey(
    val game: String,
    val environment: String,
    val rev: Int,
)

internal data class SpriteEtagCacheKey(
    val game: String,
    val environment: String,
    val sourceRev: Int,
    val id: Int,
)

internal data class ConfigTableSearchCacheKey(
    val game: String,
    val environment: String,
    val type: String,
    val base: Int,
    val rev: Int,
    val mode: String,
    val q: String,
)

internal data class CachedConfigTableSearch(
    val expiresAtMs: Long,
    val matchingIds: List<Int>,
)

/** Pre-built allRows list + id→row lookup, keyed by game/env/type/base/rev. */
internal data class CachedConfigRows(
    val allRows: List<Map<String, Any?>>,
    val rowById: Map<Int, Map<String, Any?>>,
)

internal data class ConfigBlockDto(
    val id: Int,
    val sectionId: String,
    val entries: Map<String, String>,
    val entriesBefore: Map<String, String>? = null,
)

internal data class ConfigDelta(
    val added: List<ConfigBlockDto>,
    val changed: List<ConfigBlockDto>,
    val removed: List<ConfigBlockDto>,
    val addedInRev: Map<Int, Int>,
    val changedInRev: Map<Int, Int>,
    val removedInRev: Map<Int, Int>,
)

internal data class SpriteDelta(
    val added: List<Int>,
    val changed: List<Int>,
    val removed: List<Int>,
    val addedInRev: Map<Int, Int>,
    val changedInRev: Map<Int, Int>,
    val removedInRev: Map<Int, Int>,
)

/** All diff-route bounded in-memory caches + single-flight locks (one place to tune sizes). */
internal object DiffRouteCaches {
    val configTable = LruMutexCache<String, CachedConfigTable>(MAX_CONFIG_TABLE_CACHE_ENTRIES, 128)
    val configTableSearch = LruMutexCache<ConfigTableSearchCacheKey, CachedConfigTableSearch>(
        MAX_CONFIG_TABLE_SEARCH_CACHE_ENTRIES,
        512,
    )
    val configRows = LruMutexCache<GameEnvTypeBaseRevKey, CachedConfigRows>(MAX_CONFIG_ROWS_CACHE_ENTRIES, 64)
    val configContent = LruMutexCache<String, CachedConfigContent>(MAX_CONFIG_CONTENT_CACHE_ENTRIES, 128)
    val configContentComputeMutexes = ConcurrentHashMap<String, Mutex>()

    val spriteEtag = LruMutexCache<SpriteEtagCacheKey, String>(MAX_SPRITE_ETAG_CACHE_ENTRIES, 1024)

    val configDelta = LruMutexCache<GameEnvTypeBaseRevKey, ConfigDelta>(MAX_CONFIG_DELTA_CACHE_ENTRIES, 128)

    val availableRevisions = LruMutexCache<AvailableRevisionsCacheKey, CachedAvailableRevisions>(
        MAX_AVAILABLE_REVISIONS_CACHE_ENTRIES,
        32,
    )
    val availableRevisionsComputeLocks = ConcurrentHashMap<AvailableRevisionsCacheKey, Any>()

    val revisionsWithData = LruMutexCache<RevisionsWithDataCacheKey, Map<String, Any>>(
        MAX_REVISIONS_WITH_DATA_CACHE_ENTRIES,
        32,
    )
    val revisionsWithDataComputeLocks = ConcurrentHashMap<RevisionsWithDataCacheKey, Any>()

    val manifestHasChanges = LruMutexCache<ManifestHasChangesCacheKey, Boolean>(
        MAX_MANIFEST_HAS_CHANGES_CACHE_ENTRIES,
        256,
    )

    val combinedConfig = LruMutexCache<GameEnvTypeBaseRevKey, Map<String, Map<String, String>>>(
        MAX_COMBINED_CONFIG_CACHE_ENTRIES,
        128,
    )

    val combinedSprites = LruMutexCache<GameEnvBaseRevKey, Pair<List<Int>, Map<Int, Int>>>(
        MAX_COMBINED_SPRITES_CACHE_ENTRIES,
        128,
    )
    val combinedSpritesComputeLocks = ConcurrentHashMap<GameEnvBaseRevKey, Any>()

    val deltaSprites = LruMutexCache<GameEnvBaseRevKey, SpriteDelta>(MAX_DELTA_SPRITES_CACHE_ENTRIES, 128)
    val deltaSpritesComputeLocks = ConcurrentHashMap<GameEnvBaseRevKey, Any>()
}
