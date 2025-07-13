package routes.open.cache

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.gson.GsonBuilder
import dev.openrune.cache.gameval.GameValElement
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import java.util.concurrent.TimeUnit

data class SearchResult(
    val name: String, val gameval : String, val id : Int,
    val extraData: Map<String, Any> = emptyMap()
)

data class PaginatedResponse<T>(
    val total: Int,
    val results: List<T>
)

class ConfigHandler<T>(
    private val cacheExpireMinutes: Long = 10,
    private val cacheMaxSize: Long = 500,
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

            computePaginated(mode, query, filterKeys, amt, offset)
        }

    suspend fun handleRequest(call: ApplicationCall) {
        val query = call.request.queryParameters["q"] ?: ""
        val mode = call.request.queryParameters["mode"] ?: "name"
        val filterKeysCsv = call.request.queryParameters["filters"] ?: ""
        val amt = call.request.queryParameters["amt"]?.toIntOrNull() ?: 50
        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

        val filterKeys = if (filterKeysCsv.isBlank()) emptyList() else filterKeysCsv.split(",")

        val cacheKey = "$mode:$query:$filterKeysCsv:$amt:$offset"
        val response = cache.get(cacheKey) {
            computePaginated(mode, query, filterKeys, amt, offset)
        }

        call.respond(GsonBuilder().setPrettyPrinting().create().toJson(response))
    }

    private fun computePaginated(
        mode: String,
        query: String,
        filterKeys: List<String>,
        amt: Int,
        offset: Int
    ): PaginatedResponse<SearchResult> {
        val allConfigs = getAllConfigs()

        val filteredInitial = when (mode.lowercase()) {
            "id" -> {
                val ids = try {
                    parseExpression(query)
                } catch (e: Exception) {
                    return PaginatedResponse(0, emptyList())
                }
                allConfigs.filter { it.key in ids }.values.toList()
            }

            "gameval" -> {
                val queryStr = query.trim()
                allConfigs.values.filter { config ->
                    val gameValName = getGameValName(config) ?: ""
                    gameValName.contains(queryStr, ignoreCase = true)
                }
            }

            "regex" -> {
                val regex = runCatching { query.toRegex(RegexOption.IGNORE_CASE) }.getOrNull()
                    ?: return PaginatedResponse(0, emptyList())
                allConfigs.values.filter { config ->
                    val name = getName(config) ?: ""
                    regex.containsMatchIn(name)
                }
            }

            else -> { // default to name
                val lowered = query.lowercase()
                allConfigs.values.filter {
                    (getName(it)?.lowercase()?.contains(lowered) ?: false)
                }
            }
        }

        val filtered = filteredInitial.filter { config ->
            filterKeys.all { key ->
                val predicate = availableFilters[key]
                predicate?.invoke(config) ?: true // If no such filter key, ignore it (or could be false)
            }
        }.sortedBy { getId(it) }

        val total = filtered.size
        val fromIndex = offset.coerceAtLeast(0)
        val toIndex = (offset + amt).coerceAtMost(total)
        val pageResults = if (fromIndex < toIndex) filtered.subList(fromIndex, toIndex) else emptyList()

        val results = pageResults.map {
            SearchResult(
                id = getId(it),
                name = getName(it) ?: "",
                gameval = getGameValName(it) ?: "",
                extraData = extractExtraData(it)
            )
        }

        return PaginatedResponse(total, results)
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
}
