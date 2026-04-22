package dev.openrune.server.endpoints.diff

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import dev.openrune.ServerConfig
import dev.openrune.cache.CachePathHelper
import dev.openrune.util.json
import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.cache.diff.DiffBinaryCache
import dev.openrune.cache.diff.CacheBinaryFormat
import dev.openrune.cache.tools.OpenRS2
import dev.openrune.cache.filestore.definition.SpriteDecoder
import dev.openrune.cache.diff.DefinitionSnapshot
import dev.openrune.cache.diff.FieldEntry
import dev.openrune.cache.diff.InterfaceManifestEntry
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.definition.type.SpriteType
import dev.openrune.filesystem.Cache
import dev.openrune.server.EndpointRegistry
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

private val logger = KotlinLogging.logger {}
private val jsonCompact = GsonBuilder().create()
private val quotedTokenRegex = Regex("\"([^\"]+)\"")

/** Enable detailed per-request table query logs via -Dopenrune.table.logs=true */
private val TABLE_QUERY_LOGS_ENABLED: Boolean =
    System.getProperty("openrune.table.logs")?.equals("true", ignoreCase = true) == true
private const val CONFIG_TABLE_SEARCH_CACHE_VERSION = "v2"

private val DIFF_CONFIG_TYPES = ConfigDiffType.diffTypeNames
/** Maps frontend sectionId → backend fileName for types where they differ (e.g. "spotanim" → "spotanims"). */
private val SECTION_ID_TO_FILE_NAME: Map<String, String> = ConfigDiffType.all
    .filter { it.sectionId != it.fileName }
    .associate { it.sectionId to it.fileName }
private val FILE_NAME_TO_DIFF_TYPE: Map<String, ConfigDiffType<*>> = ConfigDiffType.all
    .associateBy { it.fileName }
private const val GAMEVAL_TYPE_PREFIX = "gamevals_"
private const val MAX_DIFF_REV = 1000

/** Optional response header; mirrors log line for clients (see [openRuneCacheDebug]). */
private const val OPENRUNE_CACHE_DEBUG_HEADER = "X-OpenRune-Cache-Debug"

/** Suppress duplicate [openRuneCacheDebug] lines for the same method+path+HTTP outcome within this window (ms). */
private const val OPENRUNE_CACHE_LOG_DEDUPE_MS = 300L

private val openRuneCacheLogDedupeLock = Any()
private val openRuneCacheLogLastNs = HashMap<String, Long>(128)
private val liveSpriteTypeCache = ConcurrentHashMap<String, Map<Int, SpriteType>>()

private enum class CachePayloadOutcome {
    /** HTTP 304 — If-None-Match matched; response has no body (client keeps its copy). */
    NOT_MODIFIED_NO_BODY,
    /** HTTP 200 — full representation bytes are sent in this response. */
    FULL_BODY,
}

private fun elapsedMsSince(startNs: Long): Double =
    (System.nanoTime() - startNs) / 1_000_000.0

/**
 * Conditional GET logging: bracket tag + elapsed ms + method + path + route.
 * Response header (compact, for clients): `304;NO_BODY;routeTag` or `200;FULL_BODY;routeTag`.
 */
private fun ApplicationCall.openRuneCacheDebug(
    outcome: CachePayloadOutcome,
    routeTag: String,
    startNs: Long,
    detail: String = "",
) {
    val (code, token, bracketTag) = when (outcome) {
        CachePayloadOutcome.NOT_MODIFIED_NO_BODY ->
            Triple("304", "NO_BODY", "[304 · unchanged — no body]")
        CachePayloadOutcome.FULL_BODY ->
            Triple("200", "FULL_BODY", "[200 · full body]")
    }
    val header = "$code;$token;$routeTag".take(200)
    response.header(OPENRUNE_CACHE_DEBUG_HEADER, header)

    val dedupeKey = "${request.httpMethod.value} ${request.path()} $code"
    val nowNs = System.nanoTime()
    val windowNs = OPENRUNE_CACHE_LOG_DEDUPE_MS * 1_000_000L
    val shouldPrint = synchronized(openRuneCacheLogDedupeLock) {
        val prev = openRuneCacheLogLastNs[dedupeKey]
        if (prev != null && (nowNs - prev) < windowNs) {
            false
        } else {
            openRuneCacheLogLastNs[dedupeKey] = nowNs
            if (openRuneCacheLogLastNs.size > 512) {
                openRuneCacheLogLastNs.clear()
            }
            true
        }
    }
    if (!shouldPrint) return

    // Header stays for clients, but suppress server log noise.
}

private fun md5HexUtf8(s: String): String {
    val md = MessageDigest.getInstance("MD5")
    return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private fun parseIfNoneMatch(raw: String?): String? =
    raw?.trim()?.removePrefix("W/")?.removeSurrounding("\"")

private fun liveSpriteCacheKey(config: ServerConfig, rev: Int): String =
    "${config.gameType.name}:${config.environment.name}:$rev"

private fun loadSpriteTypesForRevision(config: ServerConfig, rev: Int): Map<Int, SpriteType>? {
    val key = liveSpriteCacheKey(config, rev)
    liveSpriteTypeCache[key]?.let { return it }

    val cacheDir = CachePathHelper.getCacheDirectory(config.gameType, config.environment, rev)
    val cachePath = File(cacheDir, "data/cache")
    if (!cachePath.exists()) return null

    val loaded = runCatching {
        val cache = Cache.load(cachePath.toPath())
        val out = mutableMapOf<Int, SpriteType>()
        SpriteDecoder().load(cache, out)
        out.toMap()
    }.getOrNull() ?: return null

    liveSpriteTypeCache[key] = loaded
    return loaded
}

private fun gameEnvBaseRevKey(config: ServerConfig, base: Int, rev: Int): GameEnvBaseRevKey =
    GameEnvBaseRevKey(
        game = config.gameType.name,
        environment = config.environment.name,
        base = base,
        rev = rev,
    )

private enum class TableSearchMode(val value: String) {
    GAMEVAL("gameval"),
    ID("id"),
    NAME("name"),
    REGEX("regex");

    companion object {
        fun from(value: String?): TableSearchMode? = entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
    }
}

private fun formatQueryDesc(mode: TableSearchMode?, q: String?): String {
    if (q == null) return ""
    return when (mode ?: TableSearchMode.NAME) {
        TableSearchMode.NAME   -> "name=\"$q\""
        TableSearchMode.REGEX  -> "regex=\"$q\""
        TableSearchMode.ID     -> {
            val isRange = q.contains("+") || q.contains("..") || q.contains(",") ||
                (q.contains("-") && q.indexOf("-") > 0)
            if (isRange) "range=$q" else "id=$q"
        }
        TableSearchMode.GAMEVAL -> {
            val tokens = parseGamevalTokens(q)
            if (tokens.isEmpty()) "gameval=\"$q\""
            else "gameval=[${tokens.joinToString(", ") { if (it.exact) "\"${it.raw}\"" else it.raw }}]"
        }
    }
}

private fun parseIdSearch(query: String): Set<Int> {
    val ids = mutableSetOf<Int>()
    query.split(",").forEach { part ->
        val trimmed = part.trim()
        when {
            trimmed.contains("+") -> {
                val (a, b) = trimmed.split("+").map { it.trim().toIntOrNull() ?: return@forEach }
                ids.addAll(minOf(a, b)..maxOf(a, b))
            }
            trimmed.contains("..") -> {
                val split = trimmed.split("..", limit = 2)
                val a = split[0].trim().toIntOrNull() ?: return@forEach
                val b = split[1].trim().toIntOrNull() ?: return@forEach
                ids.addAll(minOf(a, b)..maxOf(a, b))
            }
            trimmed.contains("-") && trimmed.indexOf("-") > 0 -> {
                val split = trimmed.split("-", limit = 2)
                val a = split[0].trim().toIntOrNull() ?: return@forEach
                val b = split[1].trim().toIntOrNull() ?: return@forEach
                ids.addAll(minOf(a, b)..maxOf(a, b))
            }
            else -> trimmed.toIntOrNull()?.let { ids.add(it) }
        }
    }
    return ids
}

private data class GamevalToken(val raw: String, val exact: Boolean)

private fun parseGamevalTokens(input: String): List<GamevalToken> {
    val tokens = mutableListOf<GamevalToken>()
    val s = input.trim()
    if (s.isEmpty()) return tokens

    quotedTokenRegex.findAll(s).forEach { m ->
        val value = m.groupValues.getOrNull(1)?.trim().orEmpty()
        if (value.isNotEmpty()) tokens.add(GamevalToken(raw = value, exact = true))
    }

    val remainder = s.replace(quotedTokenRegex, " ")
    remainder.split(",", "\n", "\r", "\t", " ").forEach { part ->
        val v = part.trim()
        if (v.isNotEmpty()) tokens.add(GamevalToken(raw = v, exact = false))
    }

    return tokens.distinctBy { it.raw.lowercase() to it.exact }
}

private fun gamevalGroupNameForConfigType(type: String): String? {
    return when (type.lowercase()) {
        "items" -> "items"
        "npcs" -> "npcs"
        "objects" -> "objects"
        "inv" -> "inv"
        "sequences" -> "sequences"
        "spotanims" -> "spotanims"
        "sprites" -> "sprites"
        "components" -> "components"
        "interfaces" -> "components"
        "varp" -> "varp"
        "varbit" -> "varbits"
        "varclient" -> "varcs"
        "varcs" -> "varcs"
        else -> null
    }
}

private fun gamevalDumpKeyFromTypeParam(typeParam: String): String? {
    return when (typeParam.trim().lowercase()) {
        "items", "itemtypes" -> "items"
        "npcs", "npctypes" -> "npcs"
        "inv", "invtypes" -> "inv"
        "varp", "varptypes" -> "varp"
        "varbits", "varbittypes" -> "varbits"
        "varcs", "varclient", "varclients", "varctypes", "varclienttypes" -> "varcs"
        "objects", "objtypes" -> "objects"
        "sequences", "seqtypes" -> "sequences"
        "spotanims", "spottypes" -> "spotanims"
        "dbrows", "rowtypes" -> "dbrows"
        "dbtables", "tabletypes" -> "dbtables"
        "jingles", "soundtypes" -> "jingles"
        "sprites", "spritetypes" -> "sprites"
        "components", "iftypes", "interfaces" -> "components"
        else -> null
    }
}

private fun loadIdToNameGamevals(config: ServerConfig, rev: Int, groupName: String): Map<Int, String> {
    val revClamped = rev.coerceIn(1, MAX_DIFF_REV)
    return DiffBinaryCache.getDecodedRev(config, revClamped)
        ?.gameval
        ?.get(groupName)
        .orEmpty()
        .mapValues { (_, extra) -> extra.searchable }
}

private fun buildGamevalNameToId(idToName: Map<Int, String>): Map<String, Int> {
    val nameToId = linkedMapOf<String, Int>()
    idToName.entries.sortedBy { it.key }.forEach { (id, name) ->
        nameToId[name] = id
    }
    return nameToId
}

private fun buildGamevalExtrasById(config: ServerConfig, rev: Int, dumpKey: String): Map<Int, Map<String, Any>> {
    val clampedRev = rev.coerceIn(1, MAX_DIFF_REV)
    return DiffBinaryCache.getDecodedRev(config, clampedRev)
        ?.gameval
        ?.get(dumpKey)
        .orEmpty()
        .mapValues { (_, extra) ->
            mapOf(
                "searchable" to extra.searchable,
                "text" to extra.text,
                "sub" to extra.sub
            )
        }
}

private fun loadIdToLowerNameGamevals(config: ServerConfig, rev: Int, groupName: String): Map<Int, String> {
    val revClamped = rev.coerceIn(1, MAX_DIFF_REV)
    return loadIdToNameGamevals(config, revClamped, groupName)
        .mapValues { (_, name) -> name.lowercase() }
}

private fun minSupportedRevisionForGamevalGroup(groupName: String): Int? {
    val entries = GameValGroupTypes.entries.filter { it.groupName.equals(groupName, ignoreCase = true) }
    if (entries.isEmpty()) return null
    if (entries.any { it.revision < 0 }) return 1
    return entries.map { it.revision }.filter { it >= 0 }.minOrNull()?.coerceAtLeast(1) ?: 1
}

private suspend fun ApplicationCall.resolveGamevalRequest(
    config: ServerConfig,
    typeOverride: String? = null
): Pair<String, Int>? {
    appendNoStoreJsonHeaders()
    val typeParam = typeOverride ?: request.queryParameters["type"] ?: run {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing type"))
        return null
    }
    val dumpKey = gamevalDumpKeyFromTypeParam(typeParam) ?: run {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid type"))
        return null
    }
    val rev = run {
        val revParam = request.queryParameters["rev"]
        if (revParam.isNullOrBlank() || revParam.equals("latest", ignoreCase = true)) {
            config.revision
        } else {
            revParam.toIntOrNull()?.coerceIn(1, MAX_DIFF_REV) ?: run {
                respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid rev"))
                return null
            }
        }
    }
    val minSupportedRev = minSupportedRevisionForGamevalGroup(dumpKey) ?: 1
    if (rev < minSupportedRev) {
        respond(
            HttpStatusCode.BadRequest,
            mapOf(
                "error" to "Gameval type '$dumpKey' is not available for rev $rev",
                "type" to dumpKey,
                "rev" to rev,
                "availableFromRev" to minSupportedRev
            )
        )
        return null
    }
    return dumpKey to rev
}

private fun ApplicationCall.appendNoStoreJsonHeaders() {
    response.header(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate")
    response.header(HttpHeaders.Pragma, "no-cache")
    response.header(HttpHeaders.Expires, "0")
}

private suspend fun ApplicationCall.respondGamevals(config: ServerConfig, typeOverride: String? = null) {
    val (dumpKey, rev) = resolveGamevalRequest(config, typeOverride) ?: return
    val cacheLogStartNs = System.nanoTime()
    val includeExtras = request.queryParameters["includeExtras"]?.toBooleanStrictOrNull() ?: true

    val idToName = loadIdToNameGamevals(config, rev, dumpKey)
    val nameToId = buildGamevalNameToId(idToName)
    val payload: Any = if (!includeExtras) {
        nameToId
    } else {
        mapOf(
            "type" to dumpKey,
            "rev" to rev,
            "values" to nameToId,
            "gameval" to buildGamevalExtrasById(config, rev, dumpKey)
        )
    }
    val jsonText = json.toJson(payload)
    val fp = md5HexUtf8(jsonText)
    response.header(HttpHeaders.ETag, "\"$fp\"")
    val inm = parseIfNoneMatch(request.header(HttpHeaders.IfNoneMatch))
    if (inm != null && inm == fp) {
        openRuneCacheDebug(CachePayloadOutcome.NOT_MODIFIED_NO_BODY, "gameval", cacheLogStartNs, "INM=ETag")
        respond(HttpStatusCode.NotModified)
        return
    }
    openRuneCacheDebug(CachePayloadOutcome.FULL_BODY, "gameval", cacheLogStartNs, "ETag miss or no INM")
    respondText(jsonText, ContentType.Application.Json, HttpStatusCode.OK)
}

private fun manifestHasChanges(config: ServerConfig, rev: Int): Boolean {
    val key = ManifestHasChangesCacheKey(
        game = config.gameType.name,
        environment = config.environment.name,
        rev = rev
    )
    DiffRouteCaches.manifestHasChanges.get(key)?.let { return it }

    val manifest = DiffBinaryCache.getDecodedRev(config, rev)?.manifest ?: return false
    val sprites = manifest.sprites
    val hasChanges = when {
        sprites.added.isNotEmpty() || sprites.removed.isNotEmpty() || sprites.changed.isNotEmpty() -> true
        manifest.configs.values.any { it.isEmpty.not() } -> true
        manifest.gamevals.values.any { it.isEmpty.not() } -> true
        else -> false
    }
    DiffRouteCaches.manifestHasChanges.put(key, hasChanges)
    return hasChanges
}

private fun getGamevalsSupportManifest(rev: Int): Map<String, Any> {
    val byGroupName = GameValGroupTypes.entries.groupBy { it.groupName }
    val minRevByGroup: Map<String, Int> = byGroupName.mapValues { (_, entries) ->
        val hasAlways = entries.any { it.revision < 0 }
        if (hasAlways) 1 else entries.map { it.revision }.filter { it >= 0 }.minOrNull()?.coerceAtLeast(1) ?: 1
    }
    val supported: Map<String, Boolean> = minRevByGroup.mapValues { (_, introRev) -> rev >= introRev }
    val available = supported.filterValues { it }.keys.sorted()
    val unsupported = supported.filterValues { it.not() }.keys.sorted()
    return mapOf(
        "rev" to rev,
        "supported" to supported,
        "minRev" to minRevByGroup,
        "available" to available,
        "unsupported" to unsupported
    )
}
private fun getSectionSupportManifest(config: ServerConfig, rev: Int): Map<String, Any> {
    val clamped = rev.coerceIn(1, MAX_DIFF_REV)
    val decoded = DiffBinaryCache.getDecodedRev(config, clamped)

    val configSupport: Map<String, Boolean> = ConfigDiffType.all
        .associate { diffType ->
            val hasRows = getTypedCombinedConfig(config, diffType.fileName, clamped).isNotEmpty()
            diffType.sectionId to hasRows
        }

    val archiveSupport = linkedMapOf<String, Boolean>(
        "sprites" to getCombinedSpriteData(config, 1, clamped).isNotEmpty(),
        "textures" to (configSupport["textures"] == true),
        "gamevals" to (decoded?.gameval?.values?.any { it.isNotEmpty() } == true),
    )

    val supportedConfigs = configSupport.filterValues { it }.keys.sorted()
    val unsupportedConfigs = configSupport.filterValues { !it }.keys.sorted()

    return mapOf(
        "rev" to clamped,
        "archives" to archiveSupport,
        "configs" to configSupport,
        "available" to mapOf(
            "archives" to archiveSupport.filterValues { it }.keys.sorted(),
            "configs" to supportedConfigs,
        ),
        "unsupported" to mapOf(
            "archives" to archiveSupport.filterValues { !it }.keys.sorted(),
            "configs" to unsupportedConfigs,
        ),
    )
}

private fun getInterfaceManifest(config: ServerConfig, rev: Int): List<InterfaceManifestEntry> {
    val clamped = rev.coerceIn(1, MAX_DIFF_REV)
    val decoded = DiffBinaryCache.getDecodedRev(config, clamped)
    if (decoded?.interfaceManifest?.isNotEmpty() == true) {
        return decoded.interfaceManifest.sortedBy { it.interfaceId }
    }
    val interfaceIds = getTypedCombinedConfig(config, ConfigDiffType.INTERFACES.fileName, clamped).keys.sorted()
    val gamevals = loadIdToNameGamevals(config, clamped, "components")
    return interfaceIds.map { id ->
        InterfaceManifestEntry(
            interfaceId = id,
            gameval = gamevals[id],
            iflegacy = null,
        )
    }
}

private fun availableRevisionsFromOpenRS2(config: ServerConfig): List<Int> {
    val key = AvailableRevisionsCacheKey(
        game = config.gameType.name,
        environment = config.environment.name
    )
    val now = System.currentTimeMillis()
    DiffRouteCaches.availableRevisions.get(key)?.let { cached ->
        if (cached.expiresAtMs > now) return cached.revisions
    }
    val computeLock = DiffRouteCaches.availableRevisionsComputeLocks.computeIfAbsent(key) { Any() }
    synchronized(computeLock) {
        val insideNow = System.currentTimeMillis()
        DiffRouteCaches.availableRevisions.get(key)?.let { cached ->
            if (cached.expiresAtMs > insideNow) return cached.revisions
        }

        OpenRS2.loadCaches()
        val game = config.gameType.name
        val env = config.environment.name
        val revisions = OpenRS2.allCaches
            .filter { it.game.equals(game, true) && it.environment.equals(env, true) }
            .flatMap { c -> c.builds.map { b -> b.major } }
            .distinct()
            .filter { it in 1..MAX_DIFF_REV }
            .sorted()
        DiffRouteCaches.availableRevisions.put(
            key,
            CachedAvailableRevisions(
                expiresAtMs = insideNow + AVAILABLE_REVISIONS_CACHE_TTL_MS,
                revisions = revisions,
            ),
        )
        return revisions
    }
}

internal fun getRevisionsWithData(config: ServerConfig): Map<String, Any> {
    val available = availableRevisionsFromOpenRS2(config)
    val withManifest = DiffBinaryCache.listRevisionsWithBinary(config)
    val serverRev = config.revision.coerceIn(1, MAX_DIFF_REV)
    val cacheKey = RevisionsWithDataCacheKey(
        game = config.gameType.name,
        environment = config.environment.name,
        availableHash = available.hashCode(),
        manifestHash = withManifest.hashCode(),
        serverRev = serverRev
    )
    DiffRouteCaches.revisionsWithData.get(cacheKey)?.let { return it }
    val computeLock = DiffRouteCaches.revisionsWithDataComputeLocks.computeIfAbsent(cacheKey) { Any() }
    synchronized(computeLock) {
        DiffRouteCaches.revisionsWithData.get(cacheKey)?.let { return it }
        val withChanges = withManifest.filter { r -> manifestHasChanges(config, r) }
        val hasUsableBase = DiffBinaryCache.getDecodedRev(config, 1) != null
        val revs = (withChanges + if (hasUsableBase) listOf(1) else emptyList())
            .distinct()
            .filter { it in available }
            .sorted()
        val revisionOptions = listOf("latest") + revs.map { it.toString() }
        val result = mapOf(
            "revisions" to revs,
            "serverRevision" to serverRev,
            "revisionOptions" to revisionOptions
        )
        DiffRouteCaches.revisionsWithData.put(cacheKey, result)
        return result
    }
}

private fun diffBinaryRevisionsSet(config: ServerConfig): Set<Int> =
    DiffBinaryCache.listRevisionsWithBinary(config).toSet()

/** Merge all delta revisions from 1 through [upToRev] to produce a complete typed snapshot map. */
private fun getTypedCombinedConfig(config: ServerConfig, type: String, upToRev: Int): Map<Int, DefinitionSnapshot> {
    val base = DiffBinaryCache.getDecodedRev(config, 1)?.configs?.get(type) ?: emptyMap()
    if (upToRev <= 1) return base
    val merged = base.toMutableMap()
    for (r in 2..upToRev) {
        val decoded = DiffBinaryCache.getDecodedRev(config, r) ?: continue
        val summary = decoded.manifest.configs[type] ?: continue
        summary.removed.forEach { id -> merged.remove(id) }
        val delta = decoded.configs[type] ?: continue
        summary.added.forEach   { id -> delta[id]?.let { merged[id] = it } }
        summary.changed.forEach { id -> delta[id]?.let { merged[id] = it } }
    }
    return merged
}

private fun rawValueToJson(value: Any?): Any? = when (value) {
    is FieldEntry -> fieldEntryToJson(value)
    is Map<*, *> -> value.entries.associate { (key, nested) -> key.toString() to rawValueToJson(nested) }
    is List<*> -> value.map { rawValueToJson(it) }
    else -> value
}

private fun fieldEntryToJson(entry: FieldEntry): Any? {
    val v: Any? = when (val raw = entry.value) {
        is Map<*, *> -> if (raw.entries.all { it.key is Int && (it.value == null || it.value is FieldEntry) }) {
            @Suppress("UNCHECKED_CAST")
            (raw as Map<Int, FieldEntry>).entries.associate { (k, e) -> k.toString() to fieldEntryToJson(e) }
        } else rawValueToJson(raw)
        is List<*> -> raw.map { rawValueToJson(it) }
        else -> raw
    }
    return if (entry.ref == null) v
    else mapOf("value" to v, "ref" to mapOf("group" to entry.ref.group, "id" to entry.ref.id, "name" to entry.ref.name))
}

private fun typedSnapshotToJson(snap: DefinitionSnapshot): Map<String, Any?> =
    snap.entries.associate { (k, e) -> k to fieldEntryToJson(e) }

/**
 * Revisions in [fromRev, toRev] that actually have a diff `.bin` on disk. Missing revs are skipped by
 * [getCombinedSpriteData] the same way — we must not block on them.
 */
private fun revisionsWithExistingDiffBinariesInRange(existing: Set<Int>, fromRev: Int, toRev: Int): List<Int> {
    val lo = minOf(fromRev, toRev).coerceIn(1, MAX_DIFF_REV)
    val hi = maxOf(fromRev, toRev).coerceIn(1, MAX_DIFF_REV)
    if (hi < lo) return emptyList()
    return (lo..hi).filter { it in existing }
}

private fun revisionsForSpriteMergeChain(existing: Set<Int>, fromRev: Int, toRev: Int): List<Int> =
    revisionsWithExistingDiffBinariesInRange(existing, fromRev, toRev)

/** [getDeltaSprites] uses combined chains from 1 through max(base, rev); only existing binaries matter. */
private fun revisionsForSpriteDeltaCompare(existing: Set<Int>, base: Int, rev: Int): List<Int> {
    val b = base.coerceIn(1, MAX_DIFF_REV)
    val r = rev.coerceIn(1, MAX_DIFF_REV)
    val hi = maxOf(b, r)
    return revisionsWithExistingDiffBinariesInRange(existing, 1, hi)
}

/** [getDeltaConfig] manifest walk only touches revs that have binaries; anchor at rev 1 when present. */
private fun revisionsForAnchoredConfigDelta(existing: Set<Int>, base: Int, rev: Int): List<Int> {
    val b = base.coerceIn(1, MAX_DIFF_REV)
    val r = rev.coerceIn(1, MAX_DIFF_REV)
    if (r <= b) return listOf(1, b, r).distinct().sorted().filter { it in existing }
    return buildList {
        if (1 in existing) add(1)
        addAll((b..r).filter { it in existing })
    }.distinct().sorted()
}

private suspend fun ApplicationCall.respondMissingDiffBinary(rev: Int) {
    respond(
        HttpStatusCode.NotFound,
        mapOf(
            "error" to "No diff binary file for rev $rev",
            "revision" to rev,
            "status" to "missing"
        )
    )
}

private suspend fun ApplicationCall.ensureDecodedRevisionsReady(
    config: ServerConfig,
    revisions: Collection<Int>
): Boolean {
    val uniqueRevs = revisions.distinct().filter { it > 0 }
    if (uniqueRevs.isEmpty()) return true
    val statuses = uniqueRevs.map { rev ->
        val ready = DiffBinaryCache.getDecodedRevIfReady(config, rev)
        if (ready != null) {
            DiffBinaryCache.DecodeStatus(revision = rev, status = "ready", progress = 100, message = "Ready")
        } else {
            DiffBinaryCache.getDecodeStatus(config, rev)
        }
    }
    val pending = statuses.filter { it.status != "ready" && it.status != "missing" }
    if (pending.isEmpty()) return true
    val progress = pending.map { it.progress }.average().toInt().coerceIn(0, 99)
    val pendingSorted = pending.sortedBy { it.revision }
    val details = pendingSorted.take(24).joinToString(", ") {
        "rev ${it.revision}: ${it.message} (${it.progress}%)"
    } + if (pendingSorted.size > 24) " … (+${pendingSorted.size - 24} more)" else ""
    logger.info {
        "diff.http 202 decoding (blocked until binaries ready): avgProgress=$progress% pendingCount=${pendingSorted.size} pendingRevs=${pendingSorted.map { it.revision }} — $details"
    }
    respond(
        HttpStatusCode.Accepted,
        mapOf(
            "status" to "decoding",
            "progress" to progress,
            "message" to "Decoding diff binaries. Please wait...",
            "details" to details,
            "revisions" to pending.map {
                mapOf(
                    "revision" to it.revision,
                    "status" to it.status,
                    "progress" to it.progress,
                    "message" to it.message,
                    "error" to it.error
                )
            }
        )
    )
    return false
}

fun Route.registerDiffEndpoints(config: ServerConfig) {
    EndpointRegistry.registerEndpoint(
        "GET",
        "/gamevals",
        "Get gameval map for type and rev. Query: type, rev, includeExtras.",
        "Gamevals",
        null,
        "application/json",
        listOf("/gamevals?type=items&rev=236")
    )
    EndpointRegistry.registerEndpoint(
        "GET",
        "/gameval/{type}",
        "Get gameval map for a type path segment. Query: rev, includeExtras.",
        "Gamevals",
        null,
        "application/json",
        listOf("/gameval/items?rev=236")
    )
    EndpointRegistry.registerEndpoint(
        "GET",
        "/gamevals/manifest",
        "Gameval group availability by revision. Query: rev.",
        "Gamevals",
        null,
        "application/json",
        listOf("/gamevals/manifest?rev=236")
    )
    EndpointRegistry.registerEndpoint(
        "GET",
        "/sprites",
        "Sprite compatibility endpoint. Query: id, base, rev, source.",
        "Diff",
        null,
        "image/png",
        listOf("/sprites?id=447&base=1&rev=236")
    )
    EndpointRegistry.registerEndpoint(
        "GET",
        "/sprites/raw",
        "Sprite raw metadata endpoint (IndexedSprite fields, excluding raster/palette). Query: id, base, rev, source.",
        "Diff",
        null,
        "application/json",
        listOf("/sprites/raw?id=447&base=1&rev=236")
    )

    listOf(
        Triple("/diff/revisions", "List revisions with non-empty binary diff data.", listOf("/diff/revisions")),
        Triple("/diff/decode/status", "Decode status for revision(s). Query: rev or revs=1,100,236.", listOf("/diff/decode/status?revs=1,236")),
        Triple(
            "/diff/manifest/{rev}",
            "Binary diff manifest (added/removed/changed ids).",
            listOf("/diff/manifest/100"),
        ),
        Triple("/diff/interface/manifest", "Interface manifest (interfaceId, gameval, iflegacy). Query: rev.", listOf("/diff/interface/manifest?rev=236")),
        Triple("/diff/clientscripts/manifest", "Client script ids and byte lengths from binary diff. Query: rev.", listOf("/diff/clientscripts/manifest?rev=236")),
        Triple("/diff/clientscript/{id}", "Client script raw bytes (base64) from binary diff. Query: rev.", listOf("/diff/clientscript/1234?rev=236")),
        Triple("/diff/gamevals/manifest", "Gameval group availability by revision. Query: rev.", listOf("/diff/gamevals/manifest?rev=236")),
            Triple("/diff/support/manifest", "Archive/config support by revision from decoded binary content. Query: rev.", listOf("/diff/support/manifest?rev=236")),
        Triple("/diff/config-types", "List available config diff types.", listOf("/diff/config-types")),
        Triple("/diff/combined/sprites", "Merged sprite ids and source revision map. Query: base, rev.", listOf("/diff/combined/sprites?base=1&rev=236")),
        Triple("/diff/delta/sprites", "Delta sprite ids between base and rev.", listOf("/diff/delta/sprites?base=1&rev=100")),
        Triple("/diff/delta/summary", "Config delta summary counts by type between base and rev.", listOf("/diff/delta/summary?base=1&rev=100")),
        Triple("/diff/delta/sprites/summary", "Delta sprite counts between base and rev.", listOf("/diff/delta/sprites/summary?base=1&rev=100")),
        Triple("/diff/sprite/{id}", "Serve sprite PNG. Query: base, rev, source.", listOf("/diff/sprite/0?rev=100")),
        Triple("/diff/sprite/{id}/raw", "Serve sprite raw metadata JSON. Query: base, rev, source.", listOf("/diff/sprite/0/raw?rev=100")),
        Triple("/diff/config/{type}/content", "Merged binary config content for a type. Query: base, rev.", listOf("/diff/config/items/content?rev=236")),
        Triple("/diff/config/{type}/table", "Paginated binary config table rows. Query: base, rev, offset, limit, q.", listOf("/diff/config/items/table?rev=236&offset=0&limit=50")),
        Triple("/interface/{id}", "Get full InterfaceType (all components) by interface id. Query: rev.", listOf("/interface/0?rev=236")),
    ).forEach { (path, desc, examples) ->
        EndpointRegistry.registerEndpoint("GET", path, desc, "Diff", null, "application/json", examples)
    }

    get("/gamevals") {
        call.respondGamevals(config)
    }

    // Backward-compatible alias for callers using plural path segment with type in URL.
    get("/gamevals/{type}") {
        val type = call.parameters["type"] ?: run {
            call.appendNoStoreJsonHeaders()
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing type"))
            return@get
        }
        call.respondGamevals(config, type)
    }

    get("/gameval/{type}") {
        val type = call.parameters["type"] ?: run {
            call.appendNoStoreJsonHeaders()
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing type"))
            return@get
        }
        call.respondGamevals(config, type)
    }

    get("/gamevals/manifest") {
        val rev = call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision
        val clamped = rev.coerceIn(1, MAX_DIFF_REV)
        call.appendNoStoreJsonHeaders()
        call.respond(getGamevalsSupportManifest(clamped))
    }

    get("/sprites") {
        val id = call.request.queryParameters["id"]?.toIntOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid sprite id"))
            return@get
        }
        val source = call.request.queryParameters["source"]?.toIntOrNull()?.coerceIn(1, MAX_DIFF_REV)
        val base = (call.request.queryParameters["base"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_DIFF_REV)
        val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision).coerceIn(base, MAX_DIFF_REV)
        val binRevs = diffBinaryRevisionsSet(config)
        if (source != null) {
            if (source !in binRevs) {
                call.respondMissingDiffBinary(source)
                return@get
            }
        } else {
            if (base !in binRevs) {
                call.respondMissingDiffBinary(base)
                return@get
            }
            if (rev !in binRevs) {
                call.respondMissingDiffBinary(rev)
                return@get
            }
        }
        val needed =
            if (source != null) listOf(source) else revisionsForSpriteMergeChain(binRevs, base, rev)
        if (!call.ensureDecodedRevisionsReady(config, needed)) return@get
        call.respondSprite(config, id)
    }

    get("/sprites/raw") {
        val id = call.request.queryParameters["id"]?.toIntOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid sprite id"))
            return@get
        }
        val source = call.request.queryParameters["source"]?.toIntOrNull()?.coerceIn(1, MAX_DIFF_REV)
        val base = (call.request.queryParameters["base"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_DIFF_REV)
        val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision).coerceIn(base, MAX_DIFF_REV)
        val binRevs = diffBinaryRevisionsSet(config)
        if (source != null) {
            if (source !in binRevs) {
                call.respondMissingDiffBinary(source)
                return@get
            }
        } else {
            if (base !in binRevs) {
                call.respondMissingDiffBinary(base)
                return@get
            }
            if (rev !in binRevs) {
                call.respondMissingDiffBinary(rev)
                return@get
            }
        }
        val needed =
            if (source != null) listOf(source) else revisionsForSpriteMergeChain(binRevs, base, rev)
        if (!call.ensureDecodedRevisionsReady(config, needed)) return@get
        call.respondSpriteRaw(config, id)
    }

    route("/diff") {
        get("/decode/status") {
            val revsParam = call.request.queryParameters["revs"]
            val revParam = call.request.queryParameters["rev"]
            val revisions = when {
                !revsParam.isNullOrBlank() -> revsParam.split(",").mapNotNull { it.trim().toIntOrNull()?.coerceIn(1, MAX_DIFF_REV) }
                !revParam.isNullOrBlank() -> listOfNotNull(revParam.toIntOrNull()?.coerceIn(1, MAX_DIFF_REV))
                else -> emptyList()
            }.distinct()
            if (revisions.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing rev or revs query"))
                return@get
            }
            val statuses = revisions.map { rev -> DiffBinaryCache.getDecodeStatus(config, rev) }
            call.respond(
                mapOf(
                    "status" to if (statuses.all { it.status == "ready" }) "ready" else "decoding",
                    "revisions" to statuses.map {
                        mapOf(
                            "revision" to it.revision,
                            "status" to it.status,
                            "progress" to it.progress,
                            "message" to it.message,
                            "error" to it.error
                        )
                    }
                )
            )
        }

        get("/revisions") {
            val payload = withContext(Dispatchers.Default) { getRevisionsWithData(config) }
            call.respond(payload)
        }

        get("/manifest/{rev}") {
            val rev = call.parameters["rev"]?.toIntOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid rev"))
                return@get
            }
            if (!call.ensureDecodedRevisionsReady(config, listOf(rev))) return@get
            val manifest = DiffBinaryCache.getDecodedRev(config, rev)?.manifest ?: run {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No diff for rev $rev"))
                return@get
            }
            call.respond(manifest)
        }

        get("/interface/manifest") {
            val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision).coerceIn(1, MAX_DIFF_REV)
            val needed = if (rev <= 1) listOf(1) else listOf(1, rev)
            if (!call.ensureDecodedRevisionsReady(config, needed)) return@get
            call.appendNoStoreJsonHeaders()
            call.respond(
                mapOf(
                    "rev" to rev,
                    "rows" to getInterfaceManifest(config, rev),
                )
            )
        }

        get("/clientscripts/manifest") {
            val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision).coerceIn(1, MAX_DIFF_REV)
            if (!call.ensureDecodedRevisionsReady(config, listOf(rev))) return@get
            val decoded = DiffBinaryCache.getDecodedRev(config, rev) ?: run {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No diff for rev $rev"))
                return@get
            }
            call.appendNoStoreJsonHeaders()
            val rows = decoded.clientScripts.entries
                .sortedBy { it.key }
                .map { (id, raw) -> mapOf("id" to id, "length" to raw.size) }
            call.respond(
                mapOf(
                    "rev" to rev,
                    "count" to rows.size,
                    "rows" to rows,
                )
            )
        }

        get("/clientscript/{id}") {
            val scriptId = call.parameters["id"]?.toIntOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid clientscript id"))
                return@get
            }
            val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision).coerceIn(1, MAX_DIFF_REV)
            if (!call.ensureDecodedRevisionsReady(config, listOf(rev))) return@get
            val decoded = DiffBinaryCache.getDecodedRev(config, rev) ?: run {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No diff for rev $rev"))
                return@get
            }
            val raw = decoded.clientScripts[scriptId] ?: run {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Clientscript $scriptId not found for rev $rev"))
                return@get
            }
            call.appendNoStoreJsonHeaders()
            call.respond(
                mapOf(
                    "id" to scriptId,
                    "rev" to rev,
                    "length" to raw.size,
                    "rawBytesBase64" to Base64.getEncoder().encodeToString(raw),
                )
            )
        }

        get("/gamevals/manifest") {
            val rev = call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision
            val clamped = rev.coerceIn(1, MAX_DIFF_REV)
            call.appendNoStoreJsonHeaders()
            call.respond(getGamevalsSupportManifest(clamped))
        }
        get("/support/manifest") {
            val rev = call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision
            val clamped = rev.coerceIn(1, MAX_DIFF_REV)
            val needed = if (clamped <= 1) listOf(1) else listOf(1, clamped)
            if (!call.ensureDecodedRevisionsReady(config, needed)) return@get
            call.appendNoStoreJsonHeaders()
            call.respond(getSectionSupportManifest(config, clamped))
        }

        get("/config-types") {
            call.respond(mapOf("types" to DIFF_CONFIG_TYPES))
        }

        get("/combined/sprites") {
            val cacheLogStartNs = System.nanoTime()
            val base = (call.request.queryParameters["base"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_DIFF_REV)
            val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision).coerceIn(base, MAX_DIFF_REV)
            val binRevs = diffBinaryRevisionsSet(config)
            if (base !in binRevs) {
                call.respondMissingDiffBinary(base)
                return@get
            }
            if (rev !in binRevs) {
                call.respondMissingDiffBinary(rev)
                return@get
            }
            val spriteChain = revisionsForSpriteMergeChain(binRevs, base, rev)
            if (!call.ensureDecodedRevisionsReady(config, spriteChain)) return@get
            val (spriteIds, sourceRevById) = withContext(Dispatchers.Default) { getCombinedSprites(config, base, rev) }
            val body = mutableMapOf<String, Any>(
                "base" to base,
                "rev" to rev,
                "spriteIds" to spriteIds,
                "sourceRevById" to sourceRevById
            )
            val jsonText = json.toJson(body)
            val fp = md5HexUtf8(jsonText)
            call.response.header(HttpHeaders.ETag, "\"$fp\"")
            call.appendNoStoreJsonHeaders()
            val inm = parseIfNoneMatch(call.request.header(HttpHeaders.IfNoneMatch))
            if (inm != null && inm == fp) {
                call.openRuneCacheDebug(CachePayloadOutcome.NOT_MODIFIED_NO_BODY, "diff/combined/sprites", cacheLogStartNs, "INM=ETag")
                call.respond(HttpStatusCode.NotModified)
                return@get
            }
            call.openRuneCacheDebug(CachePayloadOutcome.FULL_BODY, "diff/combined/sprites", cacheLogStartNs, "full JSON")
            call.respondText(jsonText, ContentType.Application.Json, HttpStatusCode.OK)
        }

        get("/delta/sprites") {
            val cacheLogStartNs = System.nanoTime()
            val base = (call.request.queryParameters["base"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_DIFF_REV)
            val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision).coerceIn(base, MAX_DIFF_REV)
            val binRevs = diffBinaryRevisionsSet(config)
            if (base !in binRevs) {
                call.respondMissingDiffBinary(base)
                return@get
            }
            if (rev !in binRevs) {
                call.respondMissingDiffBinary(rev)
                return@get
            }
            val spriteDeltaRevs = revisionsForSpriteDeltaCompare(binRevs, base, rev)
            if (!call.ensureDecodedRevisionsReady(config, spriteDeltaRevs)) return@get
            val delta = withContext(Dispatchers.Default) {
                logger.info { "diff.compute /diff/delta/sprites base=$base rev=$rev (chainSize=${spriteDeltaRevs.size})" }
                getDeltaSprites(config, base, rev)
            }
            val body = mutableMapOf<String, Any>(
                "base" to base,
                "rev" to rev,
                "added" to delta.added,
                "changed" to delta.changed,
                "removed" to delta.removed,
                "addedInRev" to delta.addedInRev,
                "changedInRev" to delta.changedInRev,
                "removedInRev" to delta.removedInRev
            )
            val jsonText = json.toJson(body)
            val fp = md5HexUtf8(jsonText)
            call.response.header(HttpHeaders.ETag, "\"$fp\"")
            call.appendNoStoreJsonHeaders()
            val inm = parseIfNoneMatch(call.request.header(HttpHeaders.IfNoneMatch))
            if (inm != null && inm == fp) {
                call.openRuneCacheDebug(CachePayloadOutcome.NOT_MODIFIED_NO_BODY, "diff/delta/sprites", cacheLogStartNs, "INM=ETag")
                call.respond(HttpStatusCode.NotModified)
                return@get
            }
            call.openRuneCacheDebug(CachePayloadOutcome.FULL_BODY, "diff/delta/sprites", cacheLogStartNs, "full JSON")
            call.respondText(jsonText, ContentType.Application.Json, HttpStatusCode.OK)
        }

        get("/delta/sprites/summary") {
            val base = (call.request.queryParameters["base"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_DIFF_REV)
            val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision).coerceIn(base, MAX_DIFF_REV)
            val binRevsSummary = diffBinaryRevisionsSet(config)
            if (base !in binRevsSummary) {
                call.respondMissingDiffBinary(base)
                return@get
            }
            if (rev !in binRevsSummary) {
                call.respondMissingDiffBinary(rev)
                return@get
            }
            val spriteDeltaRevsSummary = revisionsForSpriteDeltaCompare(binRevsSummary, base, rev)
            if (!call.ensureDecodedRevisionsReady(config, spriteDeltaRevsSummary)) return@get
            val delta = withContext(Dispatchers.Default) {
                logger.info { "diff.compute /diff/delta/sprites/summary base=$base rev=$rev (chainSize=${spriteDeltaRevsSummary.size})" }
                getDeltaSprites(config, base, rev)
            }
            call.respond(
                mapOf(
                    "base" to base,
                    "rev" to rev,
                    "added" to delta.added.size,
                    "changed" to delta.changed.size,
                    "removed" to delta.removed.size
                )
            )
        }

        get("/delta/summary") {
            val base = (call.request.queryParameters["base"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_DIFF_REV)
            val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision).coerceIn(base, MAX_DIFF_REV)
            val binRevsCfg = diffBinaryRevisionsSet(config)
            if (base !in binRevsCfg) {
                call.respondMissingDiffBinary(base)
                return@get
            }
            if (rev !in binRevsCfg) {
                call.respondMissingDiffBinary(rev)
                return@get
            }
            val configDeltaRevs = revisionsForAnchoredConfigDelta(binRevsCfg, base, rev)
            if (!call.ensureDecodedRevisionsReady(config, configDeltaRevs)) return@get
            val configSummaries = withContext(Dispatchers.Default) {
                logger.info { "diff.compute /diff/delta/summary base=$base rev=$rev types=${DIFF_CONFIG_TYPES.size} (revWindow=${configDeltaRevs.size})" }
                ConfigDiffType.all.associate { diffType ->
                    diffType.sectionId to run {
                        val type = diffType.fileName
                        val atBase = getTypedCombinedConfig(config, type, base)
                        val atRev  = getTypedCombinedConfig(config, type, rev)
                        val bIds = atBase.keys.toSet()
                        val rIds = atRev.keys.toSet()
                        mapOf(
                            "added"   to (rIds - bIds).size,
                            "removed" to (bIds - rIds).size,
                            "changed" to (bIds intersect rIds).count { atBase[it] != atRev[it] }
                        )
                    }
                }
            }
            call.respond(
                mapOf(
                    "base" to base,
                    "rev" to rev,
                    "configs" to configSummaries
                )
            )
        }

        get("/sprite/{id}") {
            val id = call.parameters["id"]?.toIntOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid sprite id"))
                return@get
            }
            val source = call.request.queryParameters["source"]?.toIntOrNull()?.coerceIn(1, MAX_DIFF_REV)
            val base = (call.request.queryParameters["base"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_DIFF_REV)
            val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision).coerceIn(base, MAX_DIFF_REV)
            val binRevsSprite = diffBinaryRevisionsSet(config)
            if (source != null) {
                if (source !in binRevsSprite) {
                    call.respondMissingDiffBinary(source)
                    return@get
                }
            } else {
                if (base !in binRevsSprite) {
                    call.respondMissingDiffBinary(base)
                    return@get
                }
                if (rev !in binRevsSprite) {
                    call.respondMissingDiffBinary(rev)
                    return@get
                }
            }
            val needed =
                if (source != null) listOf(source) else revisionsForSpriteMergeChain(binRevsSprite, base, rev)
            if (!call.ensureDecodedRevisionsReady(config, needed)) return@get
            call.respondSprite(config, id)
        }

        get("/sprite/{id}/raw") {
            val id = call.parameters["id"]?.toIntOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid sprite id"))
                return@get
            }
            val source = call.request.queryParameters["source"]?.toIntOrNull()?.coerceIn(1, MAX_DIFF_REV)
            val base = (call.request.queryParameters["base"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_DIFF_REV)
            val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision).coerceIn(base, MAX_DIFF_REV)
            val binRevsSprite = diffBinaryRevisionsSet(config)
            if (source != null) {
                if (source !in binRevsSprite) {
                    call.respondMissingDiffBinary(source)
                    return@get
                }
            } else {
                if (base !in binRevsSprite) {
                    call.respondMissingDiffBinary(base)
                    return@get
                }
                if (rev !in binRevsSprite) {
                    call.respondMissingDiffBinary(rev)
                    return@get
                }
            }
            val needed =
                if (source != null) listOf(source) else revisionsForSpriteMergeChain(binRevsSprite, base, rev)
            if (!call.ensureDecodedRevisionsReady(config, needed)) return@get
            call.respondSpriteRaw(config, id)
        }

        get("/config/{type}/content") {
            try {
                val type = (call.parameters["type"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing type"))
                    return@get
                }).let { raw -> SECTION_ID_TO_FILE_NAME[raw.lowercase()] ?: raw }
                val gameValDumpKey = if (type.startsWith(GAMEVAL_TYPE_PREFIX)) {
                    val groupKey = type.removePrefix(GAMEVAL_TYPE_PREFIX)
                    GameValGroupTypes.values().firstOrNull { it.groupName.equals(groupKey, ignoreCase = true) }?.groupName ?: groupKey
                } else null
                if (type !in DIFF_CONFIG_TYPES && gameValDumpKey == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid type"))
                    return@get
                }
                val base = (call.request.queryParameters["base"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_DIFF_REV)
                val rev  = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision).coerceIn(1, MAX_DIFF_REV)
                val binRevs = diffBinaryRevisionsSet(config)
                if (base !in binRevs) { call.respondMissingDiffBinary(base); return@get }
                if (rev  !in binRevs) { call.respondMissingDiffBinary(rev);  return@get }
                val revWindow = revisionsForAnchoredConfigDelta(binRevs, base, rev)
                if (!call.ensureDecodedRevisionsReady(config, revWindow)) return@get
                val cacheLogStartNs = System.nanoTime()
                call.appendNoStoreJsonHeaders()
                val clientHash = parseIfNoneMatch(call.request.header(HttpHeaders.IfNoneMatch))

                val payload: Map<String, Any?> = withContext(Dispatchers.Default) {
                    if (gameValDumpKey != null) {
                        val baseNames = loadIdToNameGamevals(config, base, gameValDumpKey)
                        val revNames  = loadIdToNameGamevals(config, rev,  gameValDumpKey)
                        val added   = (revNames.keys - baseNames.keys).sorted().associateWith { revNames[it] }
                        val removed = (baseNames.keys - revNames.keys).sorted()
                        val changed = (baseNames.keys intersect revNames.keys)
                            .filter { baseNames[it] != revNames[it] }
                            .associate { id -> id to mapOf("from" to baseNames[id], "to" to revNames[id]) }
                        mapOf("base" to base, "rev" to rev, "type" to type, "added" to added, "removed" to removed, "changed" to changed)
                    } else {
                        val atBase = getTypedCombinedConfig(config, type, base)
                        val atRev  = getTypedCombinedConfig(config, type, rev)
                        val bIds = atBase.keys.toSet()
                        val rIds = atRev.keys.toSet()
                        val added   = (rIds - bIds).sorted().associateWith { id -> typedSnapshotToJson(atRev[id]!!) }
                        val removed = (bIds - rIds).sorted()
                        val changed = (bIds intersect rIds).filter { atBase[it] != atRev[it] }.sorted()
                            .associate { id ->
                                val baseSnap = atBase[id]!!
                                val revSnap  = atRev[id]!!
                                val allKeys  = baseSnap.keys + revSnap.keys
                                val fieldDiffs = allKeys.filter { k -> baseSnap[k] != revSnap[k] }.associate { k ->
                                    k to mapOf("from" to fieldEntryToJson(baseSnap[k] ?: FieldEntry(null)), "to" to fieldEntryToJson(revSnap[k] ?: FieldEntry(null)))
                                }
                                id.toString() to fieldDiffs
                            }
                        mapOf("base" to base, "rev" to rev, "type" to type, "added" to added, "removed" to removed, "changed" to changed)
                    }
                }
                val jsonText = json.toJson(payload)
                val etag = md5HexUtf8(jsonText)
                call.response.header(HttpHeaders.ETag, "\"$etag\"")
                if (clientHash != null && clientHash == etag) {
                    call.openRuneCacheDebug(CachePayloadOutcome.NOT_MODIFIED_NO_BODY, "diff/config/content", cacheLogStartNs, "INM=ETag type=$type")
                    call.respond(HttpStatusCode.NotModified)
                    return@get
                }
                call.openRuneCacheDebug(CachePayloadOutcome.FULL_BODY, "diff/config/content", cacheLogStartNs, "type=$type")
                call.respondText(jsonText, ContentType.Application.Json)
            } catch (e: Exception) {
                logger.error(e) { "diff config content failed: ${e.message}" }
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Config content failed")))
            }
        }

        get("/config/{type}/props") {
            try {
                val type = (call.parameters["type"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing type"))
                    return@get
                }).let { raw -> SECTION_ID_TO_FILE_NAME[raw.lowercase()] ?: raw }
                val diffType = FILE_NAME_TO_DIFF_TYPE[type]
                if (diffType == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid type"))
                    return@get
                }
                call.appendNoStoreJsonHeaders()
                val tableColumns = diffType.tableColumns().map { candidates ->
                    if (candidates.size == 1) candidates.first() else candidates
                }.toMutableList<Any>()
                if (diffType.navGamevalType != null && tableColumns.none { it == "gameval" }) {
                    tableColumns.add(0, "gameval")
                }
                call.respond(
                    mapOf(
                        "fields" to diffType.fieldProps(),
                        "tableColumns" to tableColumns,
                        "searchModes" to diffType.searchFields(),
                        "hasGameval" to (diffType.navGamevalType != null),
                    )
                )
            } catch (e: Exception) {
                logger.error(e) { "diff config schema failed: ${e.message}" }
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Config schema failed")))
            }
        }

        get("/config/{type}/table") {
            try {
                val type = (call.parameters["type"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing type"))
                    return@get
                }).let { raw -> SECTION_ID_TO_FILE_NAME[raw.lowercase()] ?: raw }
                val gameValDumpKey = if (type.startsWith(GAMEVAL_TYPE_PREFIX)) {
                    val groupKey = type.removePrefix(GAMEVAL_TYPE_PREFIX)
                    gamevalDumpKeyFromTypeParam(groupKey) ?: groupKey
                } else null
                if (type !in DIFF_CONFIG_TYPES && gameValDumpKey == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid type"))
                    return@get
                }
                val base = (call.request.queryParameters["base"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_DIFF_REV)
                val rev  = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision).coerceIn(1, MAX_DIFF_REV)
                if (!call.ensureDecodedRevisionsReady(config, listOf(1, base, rev))) return@get
                val cacheLogStartNs = System.nanoTime()
                call.appendNoStoreJsonHeaders()
                val clientHash = parseIfNoneMatch(call.request.header(HttpHeaders.IfNoneMatch))
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                val limit  = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
                val qRaw   = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
                val mode   = TableSearchMode.from(
                    call.request.queryParameters["mode"]
                        ?: call.request.queryParameters["searchMode"]
                        ?: call.request.queryParameters["queryType"]
                        ?: call.request.queryParameters["querytype"]
                )
                val modeKey = (mode ?: TableSearchMode.NAME).value

                var rowsCached = false
                var searchCached: Boolean? = null  // null = no query

                val rowsKey = GameEnvTypeBaseRevKey(
                    game = config.gameType.name,
                    environment = config.environment.name,
                    type = type,
                    base = base,
                    rev = rev,
                )
                val existingRows = DiffRouteCaches.configRows.get(rowsKey)
                val cachedRows = if (existingRows != null) {
                    rowsCached = true
                    existingRows
                } else {
                    val built = withContext(Dispatchers.Default) {
                        val rows = if (gameValDumpKey != null) {
                            loadIdToNameGamevals(config, rev, gameValDumpKey).entries.sortedBy { it.key }
                                .map { (id, name) -> mapOf("id" to id, "fields" to mapOf("name" to name)) }
                        } else {
                            getTypedCombinedConfig(config, type, rev).entries.sortedBy { it.key }
                                .map { (id, snap) -> mapOf("id" to id, "fields" to typedSnapshotToJson(snap)) }
                        }
                        val byId = rows.mapNotNull { row -> (row["id"] as? Int)?.let { it to row } }.toMap()
                        CachedConfigRows(allRows = rows, rowById = byId)
                    }
                    DiffRouteCaches.configRows.put(rowsKey, built)
                    built
                }
                val allRows = cachedRows.allRows
                val rowById = cachedRows.rowById
                val sourceHash = md5HexUtf8(
                    "$type|$base|$rev|${allRows.size}|${allRows.firstOrNull()?.get("id") ?: -1}|${allRows.lastOrNull()?.get("id") ?: -1}"
                )

                val searchCacheKey = if (qRaw == null) null else ConfigTableSearchCacheKey(
                    game = config.gameType.name,
                    environment = config.environment.name,
                    type = type,
                    base = base,
                    rev = rev,
                    mode = "$modeKey:$CONFIG_TABLE_SEARCH_CACHE_VERSION",
                    q = qRaw,
                )
                val nowMs = System.currentTimeMillis()
                val cachedSearch = searchCacheKey
                    ?.let { DiffRouteCaches.configTableSearch.get(it) }
                    ?.takeIf { it.expiresAtMs > nowMs }

                val filteredIds: List<Int>? = when {
                    qRaw == null -> null
                    cachedSearch != null -> {
                        searchCached = true
                        cachedSearch.matchingIds
                    }
                    else -> {
                        val q = qRaw
                        val computed = when (mode ?: TableSearchMode.NAME) {
                            TableSearchMode.NAME -> {
                                val qLower = q.lowercase()
                                allRows.mapNotNull { row ->
                                    val id = row["id"] as? Int ?: return@mapNotNull null
                                    if (id.toString().contains(qLower)) return@mapNotNull id
                                    @Suppress("UNCHECKED_CAST")
                                    val fields = row["fields"] as? Map<String, Any?> ?: return@mapNotNull null
                                    val nameValue = fields["name"]?.toString()?.trim().orEmpty()
                                    if (nameValue.isNotEmpty() && nameValue.lowercase().contains(qLower)) id else null
                                }
                            }
                            TableSearchMode.ID -> {
                                val ids = parseIdSearch(q)
                                if (ids.isEmpty()) emptyList() else allRows.mapNotNull { row ->
                                    val id = row["id"] as? Int ?: return@mapNotNull null
                                    if (id in ids) id else null
                                }
                            }
                            TableSearchMode.REGEX -> {
                                val regex = try {
                                    Regex(q, setOf(RegexOption.IGNORE_CASE))
                                } catch (_: Exception) {
                                    null
                                }
                                if (regex == null) {
                                    emptyList()
                                } else {
                                    allRows.mapNotNull { row ->
                                        val id = row["id"] as? Int ?: return@mapNotNull null
                                        @Suppress("UNCHECKED_CAST")
                                        val fields = row["fields"] as? Map<String, Any?> ?: return@mapNotNull null
                                        val match = fields.values.any { v ->
                                            val text = v?.toString()?.trim().orEmpty()
                                            text.isNotEmpty() && regex.containsMatchIn(text)
                                        }
                                        if (match) id else null
                                    }
                                }
                            }
                            TableSearchMode.GAMEVAL -> {
                                val groupName = gameValDumpKey ?: gamevalGroupNameForConfigType(type)
                                if (groupName == null) {
                                    val qLower = q.lowercase()
                                    allRows.mapNotNull { row ->
                                        val id = row["id"] as? Int ?: return@mapNotNull null
                                        @Suppress("UNCHECKED_CAST")
                                        val fields = row["fields"] as? Map<String, Any?> ?: return@mapNotNull null
                                        if (fields["name"]?.toString()?.lowercase()?.contains(qLower) == true) id else null
                                    }
                                } else {
                                    val tokens = parseGamevalTokens(q)
                                    if (tokens.isEmpty()) emptyList()
                                    else {
                                        val idToLower = loadIdToLowerNameGamevals(config, rev, groupName)
                                        val exactTokens = tokens.filter { it.exact }.map { it.raw.trim().lowercase() }.toHashSet()
                                        val fuzzyTokens = tokens.filter { !it.exact }.map { it.raw.trim().lowercase() }
                                        val matchingIds = idToLower.filter { (_, n) ->
                                            exactTokens.contains(n) || fuzzyTokens.any { t -> n.contains(t) }
                                        }.keys.toHashSet()
                                        if (matchingIds.isEmpty()) emptyList()
                                        else allRows.mapNotNull { row ->
                                            val id = row["id"] as? Int ?: return@mapNotNull null
                                            if (id in matchingIds) id else null
                                        }
                                    }
                                }
                            }
                        }

                        if (searchCacheKey != null) {
                            searchCached = false
                            DiffRouteCaches.configTableSearch.put(
                                searchCacheKey,
                                CachedConfigTableSearch(
                                    expiresAtMs = nowMs + CONFIG_TABLE_SEARCH_CACHE_TTL_MS,
                                    matchingIds = computed,
                                )
                            )
                        }
                        computed
                    }
                }

                val filtered = if (filteredIds == null) allRows else filteredIds.mapNotNull { rowById[it] }
                val total = filtered.size
                val page = if (offset >= total) emptyList() else filtered.drop(offset).take(limit)
                val elapsedMs = (System.nanoTime() - cacheLogStartNs) / 1_000_000
                val queryHash = md5HexUtf8("$type|$base|$rev|$modeKey|${qRaw ?: ""}")
                val hash = md5HexUtf8(
                    jsonCompact.toJson(
                        mapOf(
                            "sourceHash" to sourceHash,
                            "queryHash" to queryHash,
                            "offset" to offset,
                            "limit" to limit,
                            "total" to total,
                            "rowIds" to page.mapNotNull { it["id"] as? Int },
                        )
                    )
                )
                val clientCacheHit = clientHash != null && clientHash == hash
                val cacheSource = when {
                    clientCacheHit -> "CLIENT"
                    rowsCached || searchCached == true -> "SERVER"
                    else -> "NONE"
                }
                if (TABLE_QUERY_LOGS_ENABLED) {
                    logger.info {
                        buildString {
                            append("[diff/config/table] $type")
                            append(" | base=$base  rev=$rev")
                            val queryDesc = formatQueryDesc(mode, qRaw)
                            if (queryDesc.isNotEmpty()) append(" | $queryDesc")
                            append(" | cache=$cacheSource")
                            append(" | rows=${if (rowsCached) "cached" else "loaded"}(${allRows.size})")
                            when (searchCached) {
                                true  -> append("  search=cached(${filteredIds?.size ?: 0} ids)")
                                false -> append("  search=computed(${filteredIds?.size ?: 0} ids)")
                                null  -> { /* no query */ }
                            }
                            append(" | total=$total  page=${page.size}  offset=$offset  limit=$limit")
                            append(" | ${elapsedMs}ms")
                        }
                    }
                }
                call.response.header(HttpHeaders.ETag, "\"$hash\"")
                if (clientCacheHit) {
                    call.openRuneCacheDebug(CachePayloadOutcome.NOT_MODIFIED_NO_BODY, "diff/config/table", cacheLogStartNs, "INM=ETag type=$type")
                    call.respond(HttpStatusCode.NotModified)
                    return@get
                }
                call.openRuneCacheDebug(CachePayloadOutcome.FULL_BODY, "diff/config/table", cacheLogStartNs, "type=$type")
                call.respond(buildMap {
                    put("base", base); put("rev", rev); put("type", type)
                    put("mode", modeKey); put("q", qRaw ?: "")
                    put("offset", offset); put("limit", limit); put("total", total); put("rows", page)
                    put("hash", hash); put("queryHash", queryHash); put("sourceHash", sourceHash)
                    put("cache", if (cachedSearch != null) "hit" else if (qRaw == null) "none" else "miss")
                })
            } catch (e: Exception) {
                logger.error(e) { "diff config table failed: ${e.message}" }
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Config table failed")))
            }
        }
    }

    get("/interface/{id}") {
        val id = call.parameters["id"]?.toIntOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid interface id"))
            return@get
        }
        val rev = (call.request.queryParameters["rev"]?.toIntOrNull() ?: config.revision).coerceIn(1, MAX_DIFF_REV)
        call.appendNoStoreJsonHeaders()
        val binRevs = diffBinaryRevisionsSet(config)
        if (1 !in binRevs) { call.respondMissingDiffBinary(1); return@get }
        if (rev !in binRevs) { call.respondMissingDiffBinary(rev); return@get }
        val needed = revisionsForAnchoredConfigDelta(binRevs, 1, rev)
        if (!call.ensureDecodedRevisionsReady(config, needed)) return@get
        val snapshot = withContext(Dispatchers.Default) {
            getTypedCombinedConfig(config, ConfigDiffType.INTERFACES.fileName, rev)[id]
        } ?: run {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Interface $id not found for rev $rev"))
            return@get
        }
        // Expand any JSON-blob fields (e.g. components) inline so callers get a real object, not a double-encoded string.
        val response = linkedMapOf<String, Any?>()
        snapshot.forEach { (key, entry) ->
            response[key] = when (val raw = entry.value) {
                is String -> runCatching { JsonParser.parseString(raw) }.getOrElse { raw }
                else -> fieldEntryToJson(entry)
            }
        }
        call.respondText(json.toJson(response), ContentType.Application.Json)
    }
}

private fun getDeltaSprites(config: ServerConfig, base: Int, rev: Int): SpriteDelta {
    val key = gameEnvBaseRevKey(config, base, rev)
    DiffRouteCaches.deltaSprites.get(key)?.let { return it }
    val computeLock = DiffRouteCaches.deltaSpritesComputeLocks.computeIfAbsent(key) { Any() }
    synchronized(computeLock) {
        DiffRouteCaches.deltaSprites.get(key)?.let { return it }

        val atBase = getCombinedSpriteData(config, 1, base)
        val atRev = getCombinedSpriteData(config, 1, rev)
        val baseIds = atBase.keys
        val revIds = atRev.keys

        val added = (revIds - baseIds).sorted()
        val removed = (baseIds - revIds).sorted()
        val common = baseIds intersect revIds
        val changed = common.filter { id ->
            val baseSpr = atBase[id]
            val revSpr = atRev[id]
            baseSpr != null && revSpr != null && !spritesContentEquivalent(baseSpr, revSpr)
        }.sorted()

        val delta = SpriteDelta(
            added = added,
            changed = changed,
            removed = removed,
            addedInRev = added.associateWith { rev },
            changedInRev = changed.associateWith { rev },
            removedInRev = removed.associateWith { rev }
        )
        DiffRouteCaches.deltaSprites.put(key, delta)
        return delta
    }
}

private fun spriteContentHash(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

private suspend fun ApplicationCall.respondSprite(config: ServerConfig, id: Int) {
    val cacheLogStartNs = System.nanoTime()
    val base = (request.queryParameters["base"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_DIFF_REV)
    val rev = (request.queryParameters["rev"]?.toIntOrNull() ?: config.revision).coerceIn(base, MAX_DIFF_REV)
    val sourceRev = request.queryParameters["source"]?.toIntOrNull()?.coerceIn(1, MAX_DIFF_REV)
    val pngBytes = if (sourceRev != null) {
        DiffBinaryCache.getDecodedRev(config, sourceRev)?.sprites?.get(id)
    } else {
        getCombinedSpriteData(config, base, rev)[id]?.bytes
    } ?: run {
        respond(HttpStatusCode.NotFound, mapOf("error" to "Sprite $id not found"))
        return
    }

    val etagKey = SpriteEtagCacheKey(
        game = config.gameType.name,
        environment = config.environment.name,
        sourceRev = sourceRev ?: rev,
        id = id
    )
    val etag = DiffRouteCaches.spriteEtag.getOrPut(etagKey) { spriteContentHash(pngBytes) }
    val clientEtag = request.header(HttpHeaders.IfNoneMatch)
        ?.trim()
        ?.removePrefix("W/")
        ?.removeSurrounding("\"")

    response.header(HttpHeaders.ETag, "\"$etag\"")
    response.header(HttpHeaders.CacheControl, "public, max-age=86400, stale-while-revalidate=604800")
    response.header(HttpHeaders.ContentType, ContentType.Image.PNG.toString())
    if (clientEtag != null && clientEtag == etag) {
        openRuneCacheDebug(CachePayloadOutcome.NOT_MODIFIED_NO_BODY, "diff/sprite/png", cacheLogStartNs, "INM=ETag")
        respond(HttpStatusCode.NotModified)
        return
    }

    openRuneCacheDebug(CachePayloadOutcome.FULL_BODY, "diff/sprite/png", cacheLogStartNs, "full PNG")
    respondBytes(pngBytes, ContentType.Image.PNG)
}

private suspend fun ApplicationCall.respondSpriteRaw(config: ServerConfig, id: Int) {
    val base = (request.queryParameters["base"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_DIFF_REV)
    val rev = (request.queryParameters["rev"]?.toIntOrNull() ?: config.revision).coerceIn(base, MAX_DIFF_REV)
    val sourceRev = request.queryParameters["source"]?.toIntOrNull()?.coerceIn(1, MAX_DIFF_REV)

    val source = sourceRev ?: resolveSpriteSourceRevById(config, base, rev, setOf(id))[id] ?: rev

    // Prefer binary metadata (SMRP trailer) — avoids live cache filesystem access.
    val binaryMetas = DiffBinaryCache.getDecodedRev(config, source)?.spriteMetadata?.get(id)
    val sprites: List<Map<String, Any?>>
    if (binaryMetas != null && binaryMetas.isNotEmpty() && binaryMetas.all { it.rasterBase64.isNotEmpty() }) {
        sprites = binaryMetas.map { meta ->
            mapOf(
                "offsetX" to meta.offsetX,
                "offsetY" to meta.offsetY,
                "width" to meta.width,
                "height" to meta.height,
                "averageColor" to meta.averageColor,
                "subHeight" to meta.subHeight,
                "subWidth" to meta.subWidth,
                "alphaBase64" to meta.alphaBase64,
                "rasterBase64" to meta.rasterBase64,
                "palette" to meta.palette,
            )
        }
    } else {
        // Fall back to loading directly from live cache filesystem.
        val type = loadSpriteTypesForRevision(config, source)?.get(id) ?: run {
            respond(HttpStatusCode.NotFound, mapOf("error" to "Sprite $id not found for source rev $source"))
            return
        }
        sprites = type.sprites.map { sprite ->
            mapOf(
                "offsetX" to sprite.offsetX,
                "offsetY" to sprite.offsetY,
                "width" to sprite.width,
                "height" to sprite.height,
                "averageColor" to sprite.averageColor,
                "subHeight" to sprite.subHeight,
                "subWidth" to sprite.subWidth,
                "alphaBase64" to sprite.alpha?.let { Base64.getEncoder().encodeToString(it) },
                "rasterBase64" to Base64.getEncoder().encodeToString(sprite.raster),
                "palette" to sprite.palette.toList(),
            )
        }
    }

    appendNoStoreJsonHeaders()
    respond(
        mapOf(
            "id" to id,
            "base" to base,
            "rev" to rev,
            "source" to source,
            "sprites" to sprites,
        )
    )
}

internal fun getCombinedSprites(config: ServerConfig, base: Int, rev: Int): Pair<List<Int>, Map<Int, Int>> {
    val key = gameEnvBaseRevKey(config, base, rev)
    DiffRouteCaches.combinedSprites.get(key)?.let { return it }
    val computeLock = DiffRouteCaches.combinedSpritesComputeLocks.computeIfAbsent(key) { Any() }
    synchronized(computeLock) {
        DiffRouteCaches.combinedSprites.get(key)?.let { return it }

        val revData = getCombinedSpriteData(config, base, rev)
        val sourceRevById = resolveSpriteSourceRevById(config, base, rev, revData.keys)
        if (rev == base) {
            val result = revData.keys.sorted() to sourceRevById
            DiffRouteCaches.combinedSprites.put(key, result)
            return result
        }
        val result = revData.keys.sorted() to sourceRevById
        DiffRouteCaches.combinedSprites.put(key, result)
        return result
    }
}

private data class CombinedSpriteEntry(
    val bytes: ByteArray,
    val contentSha256: ByteArray?,
    val metadata: List<CacheBinaryFormat.IndexedSpriteMeta>,
)

/** PNG bytes plus optional SHA-256 from v7 `.bin` (used to skip ImageIO for unchanged-looking sprites). */
private fun getCombinedSpriteData(config: ServerConfig, base: Int, rev: Int): Map<Int, CombinedSpriteEntry> {
    val clampedBase = base.coerceAtLeast(1)
    val clampedRev = rev.coerceAtLeast(clampedBase)
    val baseDecoded = DiffBinaryCache.getDecodedRev(config, clampedBase)
    val merged = LinkedHashMap<Int, CombinedSpriteEntry>(baseDecoded?.sprites?.size ?: 0)
    baseDecoded?.sprites?.forEach { (id, bytes) ->
        merged[id] = CombinedSpriteEntry(
            bytes = bytes,
            contentSha256 = baseDecoded.spriteSha256[id],
            metadata = baseDecoded.spriteMetadata[id] ?: emptyList(),
        )
    }
    if (clampedRev <= clampedBase) return merged

    for (r in (clampedBase + 1)..clampedRev) {
        val decoded = DiffBinaryCache.getDecodedRev(config, r) ?: continue
        val summary = decoded.manifest.sprites
        summary.removed.forEach { id -> merged.remove(id) }
        summary.added.forEach { id ->
            decoded.sprites[id]?.let { bytes ->
                merged[id] = CombinedSpriteEntry(
                    bytes = bytes,
                    contentSha256 = decoded.spriteSha256[id],
                    metadata = decoded.spriteMetadata[id] ?: emptyList(),
                )
            }
        }
        summary.changed.forEach { id ->
            decoded.sprites[id]?.let { bytes ->
                merged[id] = CombinedSpriteEntry(
                    bytes = bytes,
                    contentSha256 = decoded.spriteSha256[id],
                    metadata = decoded.spriteMetadata[id] ?: emptyList(),
                )
            }
        }
    }
    return merged
}

private fun spritesContentEquivalent(a: CombinedSpriteEntry, b: CombinedSpriteEntry): Boolean {
    if (a.bytes.contentEquals(b.bytes)) return true
    val ha = a.contentSha256
    val hb = b.contentSha256
    if (ha != null && hb != null && ha.contentEquals(hb)) return true
    return areSpritesVisuallyEqual(a.bytes, b.bytes)
}

private fun resolveSpriteSourceRevById(
    config: ServerConfig,
    base: Int,
    rev: Int,
    presentIds: Set<Int>
): Map<Int, Int> {
    val clampedBase = base.coerceAtLeast(1)
    val clampedRev = rev.coerceAtLeast(clampedBase)
    val source = presentIds.associateWith { clampedBase }.toMutableMap()
    if (clampedRev <= clampedBase) return source
    for (r in (clampedBase + 1)..clampedRev) {
        val decoded = DiffBinaryCache.getDecodedRev(config, r) ?: continue
        val summary = decoded.manifest.sprites
        summary.removed.forEach { id -> source.remove(id) }
        summary.added.forEach { id ->
            if (id in presentIds && decoded.sprites.containsKey(id)) source[id] = r
        }
        summary.changed.forEach { id ->
            if (id in presentIds && decoded.sprites.containsKey(id)) source[id] = r
        }
    }
    return source
}

private fun areSpritesVisuallyEqual(a: ByteArray, b: ByteArray): Boolean {
    if (a === b || a.contentEquals(b)) return true
    val imageA = runCatching { ImageIO.read(ByteArrayInputStream(a)) }.getOrNull()
    val imageB = runCatching { ImageIO.read(ByteArrayInputStream(b)) }.getOrNull()
    if (imageA == null || imageB == null) return a.contentEquals(b)
    if (imageA.width != imageB.width || imageA.height != imageB.height) return false
    for (y in 0 until imageA.height) {
        for (x in 0 until imageA.width) {
            if (imageA.getRGB(x, y) != imageB.getRGB(x, y)) return false
        }
    }
    return true
}
