package routes.open.cache

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.gson.GsonBuilder
import dev.openrune.cache.gameval.GameValElement
import io.ktor.server.application.*
import io.ktor.server.response.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

data class SearchResult(
    val name: String, val gameval : String, val id : Int,
    val extraData: Map<String, Any> = emptyMap()
)

data class PaginatedResponse<T>(
    val total: Int,
    val results: List<T>
)

class ConfigHandler<T>(
    private val cacheExpireMinutes: Long = 60,
    private val cacheMaxSize: Long = 5000,
    private val gameVals: List<GameValElement>,
    private val getAllConfigs: () -> Map<Int, T>,
    private val getName: (T) -> String?,
    private val getId: (T) -> Int,
    private val getGameValName: (T) -> String?,
    private val availableFilters: Map<String, (T) -> Boolean>,
    private val extractExtraData: (T) -> Map<String, Any> = { emptyMap() }
) {

    private val cache = Caffeine.newBuilder()
        .expireAfterWrite(cacheExpireMinutes, TimeUnit.MINUTES)
        .maximumSize(cacheMaxSize)
        .build<String, PaginatedResponse<SearchResult>> { key ->
            val parts = key.split(":", limit = 7)
            val mode = parts.getOrNull(0) ?: "name"
            val query = parts.getOrNull(1) ?: ""
            val filterKeysCsv = parts.getOrNull(2) ?: ""
            val amt = parts.getOrNull(3)?.toIntOrNull() ?: 10
            val offset = parts.getOrNull(4)?.toIntOrNull() ?: 0
            val filterKeys = if (filterKeysCsv.isBlank()) emptyList() else filterKeysCsv.split(",")
            if (query.isBlank() && filterKeys.isEmpty() && (amt == 50 || amt == 10) && offset == 0) {
                precomputedAllResults.getOrPut(mode) {
                    computePaginated(mode, query, filterKeys, amt, offset)
                }
            } else {
                computePaginated(mode, query, filterKeys, amt, offset)
            }
        }

    private val precomputedAllResults = mutableMapOf<String, PaginatedResponse<SearchResult>>()

    private var quotedListSuperset: List<String>? = null
    private var quotedListSupersetResult: List<T>? = null

    private var lastQuery: String? = null
    private var lastQueryResult: List<T>? = null

    private lateinit var nameTrie: Trie<T>
    private lateinit var gamevalTrie: Trie<T>

    private fun refreshCaches() {
        val allConfigs = getAllConfigs().values
        buildTries(allConfigs)
        precomputedAllResults.clear()
        listOf("name", "gameval", "id", "regex").forEach { mode ->
            precomputedAllResults[mode] = computePaginated(mode, "", emptyList(), 50, 0)
        }
    }

    init {
        refreshCaches()
    }

    suspend fun handleRequest(call: ApplicationCall) {
        val query = call.request.queryParameters["q"] ?: ""
        val mode = call.request.queryParameters["mode"] ?: "name"
        val filterKeysCsv = call.request.queryParameters["filters"] ?: ""
        val amt = call.request.queryParameters["amt"]?.toIntOrNull() ?: 50
        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
        val filterKeys = if (filterKeysCsv.isBlank()) emptyList() else filterKeysCsv.split(",")

        val cacheKey = CacheKeyBuilder.build(mode, query, filterKeysCsv, amt, offset)
        val startLookup = System.nanoTime()
        var cacheHit = true
        var paginatedProfile: PaginatedProfile? = null
        val response = cache.getIfPresent(cacheKey) ?: run {
            cacheHit = false
            val (result, profile) = computePaginatedProfile(mode, query, filterKeys, amt, offset)
            paginatedProfile = profile
            cache.put(cacheKey, result)
            result
        }
        val endLookup = System.nanoTime()
        val totalMs = (endLookup - startLookup) / 1_000_000.0
        val profile = paginatedProfile
        val totalResults = profile?.totalResults ?: (response.results.size)
        println("[PROFILE] mode=$mode, query='$query', filterKeys=$filterKeys, amt=$amt, offset=$offset, totalResults=$totalResults, cacheHit=$cacheHit, totalTime=${"%.4f".format(totalMs)} ms" )
        call.respond(GsonBuilder().setPrettyPrinting().create().toJson(response))
    }

    private fun computePaginated(
        mode: String,
        query: String,
        filterKeys: List<String>,
        amt: Int,
        offset: Int
    ): PaginatedResponse<SearchResult> {
        val start = System.nanoTime()
        val allConfigs = getAllConfigs()
        val filteredInitial = when (mode.lowercase()) {
            "id" -> filterById(allConfigs, query)
            "gameval" -> filterByGameval(allConfigs, query)
            "regex" -> filterByRegex(allConfigs, query)
            else -> filterByName(allConfigs, query)
        }
        val filtered = filteredInitial.asSequence().filter { config ->
            filterKeys.all { key ->
                val predicate = availableFilters[key]
                predicate?.invoke(config) ?: true
            }
        }.sortedBy { getId(it) }.toList()
        val total = filtered.size
        val fromIndex = offset.coerceAtLeast(0)
        val toIndex = (offset + amt).coerceAtMost(total)
        val pageResults = if (fromIndex < toIndex) filtered.subList(fromIndex, toIndex) else emptyList()
        val results = pageResults.asSequence().map {
            val id = getId(it)
            val name = getName(it) ?: ""
            val gameval = getGameValName(it) ?: ""
            val extra = extractExtraData(it)
            SearchResult(id = id, name = name, gameval = gameval, extraData = extra)
        }.toList()
        val end = System.nanoTime()
        println("[PROFILE] computePaginated: mode=$mode, query='$query', filterKeys=$filterKeys, amt=$amt, offset=$offset, totalResults=$total, took "+((end-start)/1_000_000.0)+" ms")
        return PaginatedResponse(total, results)
    }

    private fun filterById(allConfigs: Map<Int, T>, query: String): List<T> {
        if (query.isBlank()) return allConfigs.values.toList()
        val ids = try { parseExpression(query) } catch (e: Exception) { return emptyList() }
        return allConfigs.filter { it.key in ids }.values.toList()
    }

    private fun filterByGameval(allConfigs: Map<Int, T>, query: String): List<T> {
        if (query.isBlank()) return allConfigs.values.toList()
        if (query.endsWith("*")) {
            val prefix = query.removeSuffix("*")
            return gamevalTrie.searchPrefix(prefix)
        }
        val items = QueryParser.parse(query)
        val currentItemsWithQuotes = QueryParser.asSet(items)
        val lastItemsWithQuotes = lastQuery?.let { QueryParser.asSet(QueryParser.parse(it)) }
        return when {
            QueryParser.allQuoted(items) -> {
                val unquoted = QueryParser.values(items)
                if (quotedListSuperset != null && quotedListSupersetResult != null && quotedListSuperset!!.containsAll(unquoted)) {
                    val result = quotedListSupersetResult!!.filter { config ->
                        val gameValName = getGameValName(config) ?: ""
                        unquoted.any { gameValName.equals(it, ignoreCase = true) }
                    }
                    quotedListSuperset = unquoted
                    quotedListSupersetResult = result
                    result
                } else {
                    val result = allConfigs.values.filter { config ->
                        val gameValName = getGameValName(config) ?: ""
                        unquoted.any { gameValName.equals(it, ignoreCase = true) }
                    }
                    quotedListSuperset = unquoted
                    quotedListSupersetResult = result
                    result
                }
            }
            lastQuery != null && lastQueryResult != null && currentItemsWithQuotes == lastItemsWithQuotes -> {
                lastQueryResult!!.filter { config ->
                    val gameValName = getGameValName(config) ?: ""
                    items.any { item ->
                        if (item.quoted) gameValName.equals(item.value, ignoreCase = true)
                        else gameValName.contains(item.value, ignoreCase = true)
                    }
                }.also {
                    lastQuery = query
                    lastQueryResult = it
                }
            }
            else -> {
                allConfigs.values.filter { config ->
                    val gameValName = getGameValName(config) ?: ""
                    items.any { item ->
                        if (item.quoted) gameValName.equals(item.value, ignoreCase = true)
                        else gameValName.contains(item.value, ignoreCase = true)
                    }
                }.also {
                    lastQuery = query
                    lastQueryResult = it
                }
            }
        }
    }

    private fun filterByRegex(allConfigs: Map<Int, T>, query: String): List<T> {
        if (query.isBlank()) return allConfigs.values.toList()
        val regex = runCatching { query.toRegex(RegexOption.IGNORE_CASE) }.getOrNull() ?: return emptyList()
        return allConfigs.values.filter { config ->
            val name = getName(config) ?: ""
            regex.containsMatchIn(name)
        }
    }

    private fun filterByName(allConfigs: Map<Int, T>, query: String): List<T> {
        if (query.isBlank()) return allConfigs.values.toList()
        if (query.endsWith("*")) {
            val prefix = query.removeSuffix("*")
            return nameTrie.searchPrefix(prefix)
        }
        val items = QueryParser.parse(query)
        val currentItemsWithQuotes = QueryParser.asSet(items)
        val lastItemsWithQuotes = lastQuery?.let { QueryParser.asSet(QueryParser.parse(it)) }
        return when {
            QueryParser.allQuoted(items) -> {
                val unquoted = QueryParser.values(items)
                allConfigs.values.filter { config ->
                    val name = getName(config) ?: ""
                    unquoted.any { name.equals(it, ignoreCase = true) }
                }
            }
            lastQuery != null && lastQueryResult != null && currentItemsWithQuotes == lastItemsWithQuotes -> {
                lastQueryResult!!.filter { config ->
                    val name = getName(config) ?: ""
                    items.any { item ->
                        if (item.quoted) name.equals(item.value, ignoreCase = true)
                        else name.contains(item.value, ignoreCase = true)
                    }
                }.also {
                    lastQuery = query
                    lastQueryResult = it
                }
            }
            else -> {
                allConfigs.values.filter { config ->
                    val name = getName(config) ?: ""
                    items.any { item ->
                        if (item.quoted) name.equals(item.value, ignoreCase = true)
                        else name.contains(item.value, ignoreCase = true)
                    }
                }.also {
                    lastQuery = query
                    lastQueryResult = it
                }
            }
        }
    }

    private fun parseExpression(expr: String): IntRange {
        val tokens = expr.split(" ")
        if (tokens.isEmpty()) throw IllegalArgumentException("Empty expression")
        val base = tokens.first().toIntOrNull() ?: throw IllegalArgumentException("Invalid base value: ${tokens.first()}")
        var result = base
        var operator = "+"
        for (i in 1 until tokens.size) {
            val token = tokens[i]
            when (token) {
                "+" -> operator = "+"
                "-" -> operator = "-"
                else -> {
                    val value = token.toIntOrNull() ?: throw IllegalArgumentException("Invalid token: $token")
                    result = when (operator) {
                        "+" -> result + value
                        "-" -> result - value
                        else -> throw IllegalStateException("Unexpected operator: $operator")
                    }
                }
            }
        }
        val end = result.coerceAtLeast(0)
        return base..end
    }

    private fun buildTries(configs: Collection<T>) {
        nameTrie = Trie(getName)
        gamevalTrie = Trie(getGameValName)
        for (item in configs) {
            nameTrie.insert(item)
            gamevalTrie.insert(item)
        }
    }

    data class PaginatedProfile(val totalResults: Int)

    private suspend fun parallelFilterConfigs(configs: List<T>, filterKeys: List<String>): List<T> {
        if (configs.size <= 2000 || filterKeys.isEmpty()) {
            return configs.filter { config ->
                filterKeys.all { key ->
                    val predicate = availableFilters[key]
                    predicate?.invoke(config) ?: true
                }
            }
        }
        val chunkSize = 500
        return coroutineScope {
            configs.chunked(chunkSize).map { chunk ->
                async(Dispatchers.Default) {
                    chunk.filter { config ->
                        filterKeys.all { key ->
                            val predicate = availableFilters[key]
                            predicate?.invoke(config) ?: true
                        }
                    }
                }
            }.flatMap { it.await() }
        }
    }

    private suspend fun computePaginatedProfile(
        mode: String,
        query: String,
        filterKeys: List<String>,
        amt: Int,
        offset: Int
    ): Pair<PaginatedResponse<SearchResult>, PaginatedProfile> {
        val start = System.nanoTime()
        val allConfigs = getAllConfigs()
        val filteredInitial = when (mode.lowercase()) {
            "id" -> filterById(allConfigs, query)
            "gameval" -> filterByGameval(allConfigs, query)
            "regex" -> filterByRegex(allConfigs, query)
            else -> filterByName(allConfigs, query)
        }
        val filtered = runBlocking { parallelFilterConfigs(filteredInitial, filterKeys) }.sortedBy { getId(it) }
        val total = filtered.size
        val fromIndex = offset.coerceAtLeast(0)
        val toIndex = (offset + amt).coerceAtMost(total)
        val pageResults = if (fromIndex < toIndex) filtered.subList(fromIndex, toIndex) else emptyList()
        val results = pageResults.asSequence().map {
            val id = getId(it)
            val name = getName(it) ?: ""
            val gameval = getGameValName(it) ?: ""
            val extra = extractExtraData(it)
            SearchResult(id = id, name = name, gameval = gameval, extraData = extra)
        }.toList()
        val end = System.nanoTime()
        return PaginatedResponse(total, results) to PaginatedProfile(total)
    }
}

// Trie node and Trie class for fast prefix search
class TrieNode<T> {
    val children = mutableMapOf<Char, TrieNode<T>>()
    val items = mutableListOf<T>()
}
class Trie<T>(private val getKey: (T) -> String?) {
    private val root = TrieNode<T>()
    fun insert(item: T) {
        val key = getKey(item)?.lowercase() ?: return
        var node = root
        for (c in key) {
            node = node.children.getOrPut(c) { TrieNode() }
        }
        node.items.add(item)
    }
    fun searchPrefix(prefix: String): List<T> {
        var node = root
        for (c in prefix.lowercase()) {
            node = node.children[c] ?: return emptyList()
        }
        return collectAll(node)
    }
    private fun collectAll(node: TrieNode<T>): List<T> {
        val result = mutableListOf<T>()
        result.addAll(node.items)
        for (child in node.children.values) {
            result.addAll(collectAll(child))
        }
        return result
    }
}

object QueryParser {
    data class QueryItem(val value: String, val quoted: Boolean)

    fun parse(query: String): List<QueryItem> =
        query.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .map { QueryItem(it.removeSurrounding("\""), it.startsWith("\"") && it.endsWith("\"")) }

    fun allQuoted(items: List<QueryItem>): Boolean = items.all { it.quoted }
    fun values(items: List<QueryItem>): List<String> = items.map { it.value }
    fun asSet(items: List<QueryItem>): Set<Pair<String, Boolean>> = items.map { it.value to it.quoted }.toSet()
}

/**
 * Utility for cache key construction.
 */
object CacheKeyBuilder {
    fun build(mode: String, query: String, filterKeysCsv: String, amt: Int, offset: Int): String =
        "$mode:$query:$filterKeysCsv:$amt:$offset"
}
