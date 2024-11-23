package routes.open.cache

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import com.google.gson.Gson
import io.ktor.client.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import java.net.URL
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.sessions.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit



data class CacheInfo(
    val id: Int,
    val scope: String,
    val game: String,
    val environment: String,
    val language: String,
    val builds: List<CacheInfoBuilds>,
    val timestamp: String?,
    val sources: List<String>,
    val valid_indexes: Int?,
    val indexes: Int?,
    val valid_groups: Int?,
    val groups: Int?,
    val valid_keys: Int?,
    val keys: Int?,
    val size: Long?,
    val blocks: Int?,
    val disk_store_valid: Boolean?
) {
    data class CacheInfoBuilds(
        val major: Int,
        val minor: Int?
    )
}

enum class GameType {
    OLDSCHOOL,
    RUNESCAPE,
    DARKSCAPE,
    DOTD,
    ALL
}

enum class WikiEndpoints(val path: String) {
    READ_ARCHIVE_GROUP("/read/{archive}/{group}"),
    HASHES("/hashes"),
    HASHES_ARCHIVE("/hashes/{archive}"),
    DUMP_INV("/dump/inv"),
    DUMP_OBJ("/dump/obj"),
    COUNT_ARCHIVE_GROUP("/count/{archive}/{group}");

    companion object {
        fun from(type: String): WikiEndpoints? {
            return entries.find { endpoint ->
                val regex = endpoint.path.toRegexPattern()
                regex.matches(type.trim('/'))
            }
        }
    }


    fun constructUrl(openrs2: Int, parameters: Map<String, String?>): String {
        var url = "https://api.runewiki.org$path?openrs2=$openrs2"
        parameters.forEach { (key, value) ->
            url = url.replace("{$key}", value ?: "")
        }
        return url
    }
}

fun String.toRegexPattern(): Regex {
    val pattern = this
        .replace("{archive}", "\\w+")
        .replace("{group}", "\\w+")
        .replace("{.*}", "\\w+")
        .trim('/')

    return "^$pattern$".toRegex()
}

object CachesHandler {

    private var allCaches: Array<CacheInfo> = emptyArray()
    private val caches: MutableMap<GameType, List<CacheInfo>> = mutableMapOf()


    private val responseCache: LoadingCache<String, String> = Caffeine.newBuilder()
        .expireAfterWrite(2, TimeUnit.DAYS)
        .build { key ->
            runBlocking {
                fetchApiResponse(key)
            }
        }

    fun loadCaches() {
        allCaches =
            Gson().fromJson(URL("https://archive.openrs2.org/caches.json").readText(), Array<CacheInfo>::class.java).filter { it.builds.isNotEmpty() }.filter { it.timestamp != null }
                .toTypedArray()

        caches.clear()
        caches.putAll(
            allCaches.groupBy { cache ->
                when (cache.game.uppercase()) {
                    "OLDSCHOOL" -> GameType.OLDSCHOOL
                    "RUNESCAPE" -> GameType.RUNESCAPE
                    "DARKSCAPE" -> GameType.DARKSCAPE
                    "DOTD" -> GameType.DOTD
                    else -> GameType.ALL
                }
            }.filterKeys { it != GameType.ALL }
        )
    }

    suspend fun handle(call: ApplicationCall) {
        if (allCaches.isEmpty()) {
            call.respond(HttpStatusCode.NotFound, "No caches available")
            return
        }

        val game = GameType.valueOf(call.request.queryParameters["game"]?.uppercase() ?: "ALL")
        val rev = call.request.queryParameters["rev"]?.toIntOrNull() ?: -1
        val minor = call.request.queryParameters["minor"]?.toIntOrNull() ?: -1
        val id = call.request.queryParameters["id"]?.toIntOrNull() ?: -1

        val filterList = filterCaches(game, rev, minor, id)
        if (filterList.isNullOrEmpty()) {
            call.respond(HttpStatusCode.NotFound, "No caches found for this result")
            return
        }
        val openrs2 = filterList.first().id
        val fullPath = call.request.path()
        val type = fullPath.removePrefix("/public/caches/")

        val endpoint = WikiEndpoints.from(type)

        if (endpoint != null) {
            val parameters = mapOf(
                "archive" to call.parameters["archive"],
                "group" to call.parameters["group"]
            )
            val apiUrl = endpoint.constructUrl(openrs2, parameters)

            val response = responseCache.get(apiUrl)
            val contentType = determineContentType(endpoint)
            call.respondText(response, contentType)
        } else {
            val jsonResponse = Gson().toJson(filterList)
            call.respondText(jsonResponse, ContentType.Application.Json)
        }
    }

    private fun determineContentType(endpoint: WikiEndpoints): ContentType {
        return when (endpoint) {
            WikiEndpoints.READ_ARCHIVE_GROUP, WikiEndpoints.COUNT_ARCHIVE_GROUP -> ContentType.Text.Plain
            else -> ContentType.Application.Json
        }
    }

    private suspend fun fetchApiResponse(apiUrl: String): String {
        val client = HttpClient(CIO)
        return client.use {
            it.get(apiUrl).bodyAsText()
        }
    }

    private fun filterCaches(
        gameType: GameType,
        rev: Int,
        minor: Int,
        id: Int
    ): List<CacheInfo>? {
        return when (gameType) {
            GameType.OLDSCHOOL -> caches[GameType.OLDSCHOOL]
            GameType.RUNESCAPE -> caches[GameType.RUNESCAPE]
            GameType.DARKSCAPE -> caches[GameType.DARKSCAPE]
            GameType.DOTD -> caches[GameType.DOTD]
            GameType.ALL -> allCaches.toList()
        }?.filter { cache ->
            (id == -1 || cache.id == id) &&
                    (rev == -1 || cache.builds.any { build ->
                        build.major == rev && (minor == -1 || build.minor == minor)
                    })
        }
    }
}
