package dev.openrune.server

import dev.openrune.server.monitor.ActivityMonitor
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.*
import java.util.UUID
import java.util.regex.Pattern

abstract class QueryHandler<T : BaseQueryParams> {
    protected abstract fun getQueryParams(call: ApplicationCall): T
    abstract suspend fun handle(call: ApplicationCall, params: T)
    
    protected suspend fun respondError(call: ApplicationCall, message: String, statusCode: Int = 400) {
        call.respond(HttpStatusCode.fromValue(statusCode), ErrorResponse(error = message))
    }
    
    protected open fun <E> resolveArchiveId(
        params: T, 
        manifest: Map<Int, E>,
        gameValLookupField: ((E) -> String?)? = null
    ): Int {
        params.validateIdOrGameVal()
        
        params.id?.let { id ->
            if (manifest.containsKey(id)) return id
            throw IllegalArgumentException("Resource not found for id: $id")
        }
        
        val gameVal = params.gameVal ?: throw IllegalArgumentException("Either 'id' or 'gameVal' parameter is required.")
        
        gameValLookupField?.let { lookup ->
            manifest.entries.firstOrNull { (_, entry) ->
                lookup(entry)?.equals(gameVal, ignoreCase = true) == true
            }?.let { return it.key }
        }
        
        gameVal.toIntOrNull()?.let { id ->
            if (manifest.containsKey(id)) return id
        }
        
        throw IllegalArgumentException("Resource not found for gameVal: $gameVal")
    }
}

abstract class QueryHandlerJson<T : BaseQueryParams> : QueryHandler<T>() {
    protected open val queryCache: dev.openrune.server.cache.QueryCache<Any>? = null
    
    private fun parseIdSearch(query: String): Set<Int> {
        val ids = mutableSetOf<Int>()
        query.split(",").forEach { part ->
            val trimmed = part.trim()
            if (trimmed.contains("+")) {
                val parts = trimmed.split("+")
                if (parts.size == 2) {
                    val start = parts[0].trim().toIntOrNull()
                    val end = parts[1].trim().toIntOrNull()
                    if (start != null && end != null) {
                        ids.addAll(start..end)
                        return@forEach
                    }
                }
            }
            trimmed.toIntOrNull()?.let { ids.add(it) }
        }
        return ids
    }
    
    private data class GameValSearchTerm(val value: String, val exactMatch: Boolean)
    
    private val gameValRegex = Regex("""["']([^"']+)["']|([^,]+)""")
    
    private fun parseGameValSearch(query: String): List<GameValSearchTerm> {
        val gamevals = mutableListOf<GameValSearchTerm>()
        gameValRegex.findAll(query).forEach { match ->
            val quoted = match.groupValues[1]
            val unquoted = match.groupValues[2]
            if (quoted.isNotEmpty()) {
                gamevals.add(GameValSearchTerm(quoted, exactMatch = true))
            } else {
                val value = unquoted.trim()
                if (value.isNotEmpty()) {
                    gamevals.add(GameValSearchTerm(value, exactMatch = false))
                }
            }
        }
        return gamevals
    }
    
    private fun <E> applySearchFilter(
        entries: Map<Int, E>,
        params: T,
        searchConfig: SearchConfig<E>?
    ): Map<Int, E> {
        val q = params.q?.trim() ?: return entries
        val searchMode = params.searchMode ?: return entries
        val config = searchConfig ?: return entries
        
        if (q.isEmpty()) return entries
        
        config.getValidSearchModes()?.let { validModes ->
            if (searchMode !in validModes) {
                throw IllegalArgumentException("Search mode '$searchMode' is not supported. Valid modes: ${validModes.joinToString(", ")}")
            }
        }
        
        return when (searchMode) {
            SearchMode.ID -> {
                val searchIds = parseIdSearch(q)
                entries.filterKeys { it in searchIds }
            }
            SearchMode.GAMEVAL -> {
                val searchTerms = parseGameValSearch(q)
                entries.filter { (_, entry) ->
                    val gameValField = config.getGameValField(entry)?.lowercase() ?: return@filter false
                    searchTerms.any { term ->
                        val termLower = term.value.lowercase()
                        if (term.exactMatch) {
                            gameValField == termLower
                        } else {
                            gameValField.contains(termLower)
                        }
                    }
                }
            }
            SearchMode.REGEX -> {
                try {
                    val pattern = Pattern.compile(q, Pattern.CASE_INSENSITIVE)
                    entries.filter { (_, entry) ->
                        config.getRegexField(entry)?.let { pattern.matcher(it).find() } == true
                    }
                } catch (e: Exception) {
                    emptyMap()
                }
            }
            SearchMode.NAME -> {
                val queryLower = q.lowercase()
                entries.filter { (_, entry) ->
                    config.getNameField(entry)?.lowercase()?.contains(queryLower) == true
                }
            }
        }
    }
    
    protected suspend fun <E> filterManifest(
        manifest: Map<Int, E>,
        params: T,
        searchConfig: SearchConfig<E>? = null,
        cacheKey: String? = null,
        endpoint: String? = null,
        requestPath: String? = null,
        queryParamsString: String? = null,
        refilter: ((Map<Int, E>, T) -> Map<Int, E>)? = null
    ): Map<Int, E> {
        val requestId = UUID.randomUUID().toString()
        val queryStartTime = endpoint?.let { 
            ActivityMonitor.startQuery(requestId, it, requestPath ?: "", queryParamsString)
        } ?: System.currentTimeMillis()
        
        var wasCached = false
        
        try {
            val cache = queryCache
            val fullCacheKey = cacheKey?.let { 
                dev.openrune.server.cache.CacheKeyHelper.generateKey(params, it)
            }
            
            if (cache != null && fullCacheKey != null) {
                @Suppress("UNCHECKED_CAST")
                (cache.get(fullCacheKey) as? Map<Int, E>)?.let {
                    wasCached = true
                    return it
                }
            }
            
            var filtered = manifest
            
            if (params.searchMode != null && params.q != null) {
                filtered = applySearchFilter(filtered, params, searchConfig)
            } else {
                params.idRange?.let { range ->
                    filtered = filtered.filterKeys { it in range }
                }
            }
            
            // Apply custom refilter (after search/range filters but before limit)
            if (refilter != null) {
                filtered = refilter(filtered, params)
            }
            
            params.limit?.let { limit ->
                if (limit > 0 && filtered.size > limit) {
                    filtered = filtered.entries.take(limit).associate { it.key to it.value }
                }
            }
            
            if (cache != null && fullCacheKey != null) {
                @Suppress("UNCHECKED_CAST")
                cache.put(fullCacheKey, filtered as Any)
            }
            
            return filtered
        } finally {
            endpoint?.let {
                ActivityMonitor.endQuery(requestId, wasCached, queryStartTime)
            }
        }
    }
    
    protected suspend fun <E> filterManifest(
        manifest: Map<String, E>,
        params: T,
        getId: (E) -> Int,
        searchConfig: SearchConfig<E>? = null,
        cacheKey: String? = null,
        endpoint: String? = null,
        requestPath: String? = null,
        queryParamsString: String? = null
    ): Map<String, E> {
        val requestId = UUID.randomUUID().toString()
        val queryStartTime = endpoint?.let { 
            ActivityMonitor.startQuery(requestId, it, requestPath ?: "", queryParamsString)
        } ?: System.currentTimeMillis()
        
        var wasCached = false
        
        try {
            val cache = queryCache
            val fullCacheKey = cacheKey?.let { 
                dev.openrune.server.cache.CacheKeyHelper.generateKey(params, it)
            }
            
            if (cache != null && fullCacheKey != null) {
                @Suppress("UNCHECKED_CAST")
                (cache.get(fullCacheKey) as? Map<String, E>)?.let {
                    wasCached = true
                    return it
                }
            }
            
            var filtered = manifest
            
            if (params.searchMode != null && params.q != null) {
                val intKeyed = filtered.entries.associate { getId(it.value) to it.value }
                val intKeyedFiltered = applySearchFilter(intKeyed, params, searchConfig)
                filtered = filtered.filter { (_, entry) -> getId(entry) in intKeyedFiltered.keys }
            } else {
                params.idRange?.let { range ->
                    filtered = filtered.filterValues { getId(it) in range }
                }
            }
            
            params.limit?.let { limit ->
                if (limit > 0 && filtered.size > limit) {
                    filtered = filtered.entries.take(limit).associate { it.key to it.value }
                }
            }
            
            if (cache != null && fullCacheKey != null) {
                @Suppress("UNCHECKED_CAST")
                cache.put(fullCacheKey, filtered as Any)
            }
            
            return filtered
        } finally {
            endpoint?.let {
                ActivityMonitor.endQuery(requestId, wasCached, queryStartTime)
            }
        }
    }
    
    protected fun <E> resolveArchiveId(
        params: T,
        manifest: Map<Int, E>,
        searchConfig: SearchConfig<E>
    ): Int {
        params.validateIdOrGameVal()
        
        params.id?.let { id ->
            if (manifest.containsKey(id)) return id
            throw IllegalArgumentException("Resource not found for id: $id")
        }
        
        val gameVal = params.gameVal ?: throw IllegalArgumentException("Either 'id' or 'gameVal' parameter is required.")
        
        manifest.entries.firstOrNull { (_, entry) ->
            searchConfig.getGameValField(entry)?.equals(gameVal, ignoreCase = true) == true
        }?.let { return it.key }
        
        gameVal.toIntOrNull()?.let { id ->
            if (manifest.containsKey(id)) return id
        }
        
        throw IllegalArgumentException("Resource not found for gameVal: $gameVal")
    }
    
    protected suspend fun <E> handleSingleEntryIntKey(
        call: ApplicationCall,
        params: T,
        manifest: Map<Int, E>,
        searchConfig: SearchConfig<E>,
        refilter: ((Map<Int, E>, T) -> Map<Int, E>)? = null,
        buildResponse: (Int, E) -> Map<String, Any?> = { key, entry -> mapOf("archive" to key, "entry" to entry) }
    ): Pair<Int, E>? {
        if (params.id == null && params.gameVal == null) return null
        
        val key = try {
            resolveArchiveId(params, manifest, searchConfig)
        } catch (e: IllegalArgumentException) {
            respondError(call, e.message ?: "Invalid request", 400)
            return null
        }
        
        val entry = manifest[key] ?: run {
            respondError(call, "Entry not found", 404)
            return null
        }
        
        // Apply refilter if provided - check if entry passes the filter
        if (refilter != null) {
            val filtered = refilter(mapOf(key to entry), params)
            if (!filtered.containsKey(key)) {
                respondError(call, "Entry not found", 404)
                return null
            }
        }
        
        call.respond(buildResponse(key, entry))
        return key to entry
    }
    
    protected suspend fun <E> handleSingleEntryIntKey(
        call: ApplicationCall,
        params: T,
        manifest: Map<Int, E>,
        gameValLookupField: ((E) -> String?)? = null,
        refilter: ((Map<Int, E>, T) -> Map<Int, E>)? = null,
        buildResponse: (Int, E) -> Map<String, Any?> = { key, entry -> mapOf("archive" to key, "entry" to entry) }
    ): Pair<Int, E>? {
        if (params.id == null && params.gameVal == null) return null
        
        val key = try {
            resolveArchiveId(params, manifest, gameValLookupField)
        } catch (e: IllegalArgumentException) {
            respondError(call, e.message ?: "Invalid request", 400)
            return null
        }
        
        val entry = manifest[key] ?: run {
            respondError(call, "Entry not found", 404)
            return null
        }
        
        // Apply refilter if provided - check if entry passes the filter
        if (refilter != null) {
            val filtered = refilter(mapOf(key to entry), params)
            if (!filtered.containsKey(key)) {
                respondError(call, "Entry not found", 404)
                return null
            }
        }
        
        call.respond(buildResponse(key, entry))
        return key to entry
    }
    
    protected suspend fun <E> handleSingleEntryStringKey(
        call: ApplicationCall,
        params: T,
        manifest: Map<String, E>,
        getId: (E) -> Int,
        buildResponse: (String, E) -> Map<String, Any?> = { key, entry -> mapOf("name" to key, "entry" to entry) }
    ): Pair<String, E>? {
        if (params.id == null && params.gameVal == null) return null
        
        params.validateIdOrGameVal()
        
        val key = when {
            params.id != null -> {
                manifest.entries.find { getId(it.value) == params.id }?.key ?: run {
                    respondError(call, "Resource not found for id: ${params.id}", 404)
                    return null
                }
            }
            params.gameVal != null -> {
                manifest.keys.find { it.equals(params.gameVal, ignoreCase = true) } ?: run {
                    respondError(call, "Resource not found for gameVal: ${params.gameVal}", 404)
                    return null
                }
            }
            else -> return null
        }
        
        val entry = manifest[key]!!
        call.respond(buildResponse(key, entry))
        return key to entry
    }
    
    protected suspend fun <E> handleFullManifestIntKey(
        call: ApplicationCall,
        params: T,
        manifest: Map<Int, E>,
        searchConfig: SearchConfig<E>? = null,
        transformResponse: ((Map<Int, E>) -> Any)? = null,
        cacheKey: String? = null,
        endpoint: String? = null,
        refilter: ((Map<Int, E>, T) -> Map<Int, E>)? = null
    ) {
        val path = call.request.path()
        val queryParamsString = call.request.queryString()
        val filtered = filterManifest(manifest, params, searchConfig, cacheKey, endpoint, path, queryParamsString, refilter)
        val response = transformResponse?.invoke(filtered) ?: filtered.mapKeys { it.key.toString() }
        call.respond(response)
    }
    
    protected suspend fun <E> handleFullManifestStringKey(
        call: ApplicationCall,
        params: T,
        manifest: Map<String, E>,
        getId: (E) -> Int,
        searchConfig: SearchConfig<E>? = null,
        transformResponse: ((Map<String, E>) -> Any)? = null,
        cacheKey: String? = null,
        endpoint: String? = null
    ) {
        val path = call.request.path()
        val queryParamsString = call.request.queryString()
        val filtered = filterManifest(manifest, params, getId, searchConfig, cacheKey, endpoint, path, queryParamsString)
        val response = transformResponse?.invoke(filtered) ?: filtered
        call.respond(response)
    }
}

abstract class QueryHandlerRaw<T : BaseQueryParams> : QueryHandler<T>()

enum class SearchMode {
    ID, GAMEVAL, REGEX, NAME
}

interface SearchConfig<E> {
    fun getNameField(entry: E): String?
    fun getRegexField(entry: E): String?
    fun getGameValField(entry: E): String?
    fun getValidSearchModes(): Set<SearchMode>? = null
}

abstract class BaseQueryParams {
    @ParamDescription("Archive ID of the resource")
    abstract val id: Int?
    
    @ParamDescription("Lookup resource by name (gameVal field)")
    abstract val gameVal: String?
    
    @ParamDescription("Filter by ID range. Formats: \"45..49\", \"45-49\", or \"45,49\". Ignored when using searchMode.")
    abstract val idRange: IntRange?
    
    @ParamDescription("Limit the number of results returned")
    abstract val limit: Int?
    
    @ParamDescription("Search mode: 'id' (ID ranges like \"45+90\" or single IDs), 'gameval' (comma-separated list, quoted for exact match), 'regex' (regex pattern), or 'name' (case-insensitive substring). When used, idRange is ignored.")
    abstract val searchMode: SearchMode?
    
    @ParamDescription("Search query string. With searchMode='id': supports ranges (\"45+90\") or comma-separated IDs. With searchMode='gameval': comma-separated list (quoted strings for exact match). With searchMode='regex': regex pattern. With searchMode='name': substring to match.")
    abstract val q: String?
    
    fun validateIdOrGameVal(): String? {
        if (id != null && gameVal != null) {
            throw IllegalArgumentException("Cannot specify both 'id' and 'gameVal'. Please use only one.")
        }
        return gameVal
    }
    
    companion object {
        fun parseSearchMode(searchModeStr: String?): SearchMode? {
            return try {
                searchModeStr?.uppercase()?.let { SearchMode.valueOf(it) }
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        
        fun parseIdRange(rangeStr: String?): IntRange? {
            if (rangeStr == null) return null
            
            return try {
                when {
                    rangeStr.contains("..") -> {
                        val parts = rangeStr.split("..")
                        if (parts.size == 2) {
                            parts[0].trim().toInt()..parts[1].trim().toInt()
                        } else null
                    }
                    rangeStr.contains("-") -> {
                        val parts = rangeStr.split("-")
                        if (parts.size == 2) {
                            parts[0].trim().toInt()..parts[1].trim().toInt()
                        } else null
                    }
                    rangeStr.contains(",") -> {
                        val parts = rangeStr.split(",")
                        if (parts.size == 2) {
                            parts[0].trim().toInt()..parts[1].trim().toInt()
                        } else null
                    }
                    else -> null
                }
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}

data class ErrorResponse(val error: String, val message: String? = null)
