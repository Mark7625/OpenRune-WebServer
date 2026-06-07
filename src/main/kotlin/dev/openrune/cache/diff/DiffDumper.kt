package dev.openrune.cache.diff

import dev.openrune.OsrsCacheProvider
import dev.openrune.ServerConfig
import dev.openrune.cache.CLIENTSCRIPT
import dev.openrune.cache.CacheDownloader
import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.ChecksumManifestManager
import dev.openrune.cache.CacheManager
import dev.openrune.cache.filestore.definition.ComponentDecoder
import dev.openrune.cache.filestore.definition.InterfaceType
import dev.openrune.cache.gameval.GameValElement
import dev.openrune.cache.filestore.definition.SpriteDecoder
import dev.openrune.cache.gameval.GameValHandler
import dev.openrune.cache.gameval.impl.Interface
import dev.openrune.cache.gameval.impl.Sprite
import dev.openrune.cache.gameval.impl.Table
import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.DownloadListener
import dev.openrune.cache.tools.GameType
import dev.openrune.cache.tools.OpenRS2
import dev.openrune.cache.util.XteaLoader
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.definition.type.EnumType
import dev.openrune.definition.type.HealthBarType
import dev.openrune.definition.type.InventoryType
import dev.openrune.definition.type.ItemType
import dev.openrune.definition.type.MapElementType
import dev.openrune.definition.type.NpcType
import dev.openrune.definition.type.ObjectType
import dev.openrune.definition.type.OverlayType
import dev.openrune.definition.type.ParamType
import dev.openrune.definition.type.SequenceType
import dev.openrune.definition.type.SpotAnimType
import dev.openrune.definition.type.SpriteType
import dev.openrune.definition.type.StructType
import dev.openrune.definition.type.TextureType
import dev.openrune.definition.type.UnderlayType
import dev.openrune.definition.type.VarBitType
import dev.openrune.definition.type.VarClanType
import dev.openrune.definition.type.VarClientType
import dev.openrune.definition.type.VarpType
import dev.openrune.definition.type.WorldEntityType
import dev.openrune.definition.type.WorldMapAreaType
import dev.openrune.filesystem.Cache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarBuilder
import me.tongfei.progressbar.ProgressBarStyle
import mu.KotlinLogging
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.net.URL
import java.util.Base64
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val logger = KotlinLogging.logger {}

const val REV_CACHE_ONE = 241

class DiffDumper(
    private val gameType: GameType,
    private val environment: CacheEnvironment,
    private val downloader: CacheDownloader = CacheDownloader(),
    private val onProgress: (message: String) -> Unit = { logger.info(it) },
) {
    private val legacyXteasByRevision = ConcurrentHashMap<Int, Map<Int, IntArray>>()

    private fun toGamevalExtra(element: GameValElement): CacheBinaryFormat.GamevalExtra {
        val text = when (element) {
            is Sprite -> "${element.name},${element.id}"
            else      -> element.name
        }
        val sub = when (element) {
            is Interface -> element.components.associate { it.id to it.name }
            is Table     -> element.columns.associate { it.id to it.name }
            else         -> emptyMap()
        }
        return CacheBinaryFormat.GamevalExtra(searchable = element.name, text = text, sub = sub)
    }

    private fun clearRevCacheDownloadArtifacts(cacheDir: File, cachePath: File) {
        runCatching { if (cachePath.exists()) cachePath.deleteRecursively() }
        runCatching { File(cacheDir, "disk.zip").delete() }
        runCatching { File(cacheDir, ".openrs2_cache_id").delete() }
        runCatching { File(cacheDir, "keys.json").delete() }
    }

    /** Optional bar updater for progress bars: (phase description, combined percent 0..100). */
    private suspend fun ensureCacheForRevision(
        rev: Int,
        barUpdater: ((phase: String, percent: Int) -> Unit)? = null,
        openRs2CacheId: Int? = null,
    ) = withContext(Dispatchers.IO) {
        val cacheDir = CachePathHelper.getCacheDirectory(gameType, environment, rev)
        val cachePath = File(cacheDir, "data/cache")
        val preferredId = openRs2CacheId?.takeIf { it > 0 }

        if (cachePath.exists()) {
            val marker = File(cacheDir, ".openrs2_cache_id")
            val stored = marker.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
            val knownId = stored

            if (preferredId != null) {
                if (knownId == null || knownId != preferredId) {
                    if (barUpdater == null) {
                        val reason = when {
                            knownId == null -> "OpenRS2 id unknown"
                            else -> "OpenRS2 id $knownId"
                        }
                        onProgress("Rev $rev: replacing on-disk cache ($reason -> $preferredId)")
                    }
                    clearRevCacheDownloadArtifacts(cacheDir, cachePath)
                } else {
                    ensureLegacyXteas(rev, barUpdater, preferredId)
                    if (barUpdater == null) onProgress("Rev $rev: cache already present, skipping download")
                    barUpdater?.invoke("cached", 50)
                    return@withContext
                }
            } else {
                val latestId = runCatching {
                    OpenRS2.findRevision(rev, -1, gameType, environment).id
                }.getOrNull()
                if (latestId != null && (knownId == null || knownId != latestId)) {
                    if (barUpdater == null) {
                        val reason = when {
                            knownId == null -> "OpenRS2 id unknown"
                            else -> "OpenRS2 id $knownId"
                        }
                        onProgress("Rev $rev: replacing on-disk cache ($reason -> $latestId)")
                    }
                    clearRevCacheDownloadArtifacts(cacheDir, cachePath)
                } else {
                    ensureLegacyXteas(rev, barUpdater, knownId ?: latestId)
                    if (barUpdater == null) onProgress("Rev $rev: cache already present, skipping download")
                    barUpdater?.invoke("cached", 50)
                    return@withContext
                }
            }
        }

        val resolvedId = preferredId ?: runCatching {
            OpenRS2.findRevision(rev, -1, gameType, environment).id
        }.getOrNull()

        if (resolvedId != null) {
            val info = OpenRS2.allCaches.firstOrNull { it.id == resolvedId }
                ?: error("Unknown OpenRS2 cache id $resolvedId")
            val major = info.builds.firstOrNull()?.major
                ?: error("OpenRS2 cache id $resolvedId has no builds")
            require(major == rev) {
                "OpenRS2 cache id $resolvedId is major revision $major, but cache directory is for rev=$rev"
            }
        }

        cacheDir.mkdirs()
        if (barUpdater == null) onProgress("Rev $rev: downloading cache from OpenRS2...")
        barUpdater?.invoke("downloading 0%", 0)
        var lastPct = -1
        suspendCancellableCoroutine<Unit> { cont ->
            val listener = object : DownloadListener {
                override fun onProgress(progress: Int, max: Long, current: Long) {
                    if (max <= 0) return
                    val pct = (current * 100 / max).toInt().coerceIn(0, 100)
                    if (pct >= lastPct + 5 || pct == 100) {
                        lastPct = pct
                        barUpdater?.invoke("downloading $pct%", (pct * 50 / 100))
                        if (barUpdater == null) {
                            val curMb = current / (1024 * 1024)
                            val maxMb = max / (1024 * 1024)
                            onProgress("Rev $rev: downloading $pct% (${curMb}MB / ${maxMb}MB)")
                        }
                    }
                }

                override fun onError(exception: Exception) {
                    cont.resumeWithException(exception)
                }

                override fun onFinished() {
                    cont.resume(Unit)
                }
            }
            try {
                if (resolvedId != null) {
                    OpenRS2.downloadByInternalID(resolvedId, cacheDir, listener, "disk.zip")
                } else {
                    OpenRS2.downloadCacheByRevision(rev, cacheDir, gameType, environment, -1, listener)
                }
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
        ensureLegacyXteas(rev, barUpdater, resolvedId)
        barUpdater?.invoke("extracting 0%", 50)
        if (barUpdater == null) onProgress("Rev $rev: download complete, extracting...")
        var lastExtractPct = -1
        downloader.unzipCache(cacheDir) { _, progress, _ ->
            progress?.let { p ->
                val pctInt = ((p - 50) / 49 * 100).toInt().coerceIn(0, 100)
                if (pctInt >= lastExtractPct + 10 || pctInt == 100) {
                    lastExtractPct = pctInt
                    barUpdater?.invoke("extracting $pctInt%", 50 + (pctInt * 50 / 100))
                    if (barUpdater == null) onProgress("Rev $rev: extracting $pctInt%")
                }
            }
        }
        if (resolvedId != null) {
            runCatching { File(cacheDir, ".openrs2_cache_id").writeText(resolvedId.toString()) }
        }
        barUpdater?.invoke("ready", 100)
        if (barUpdater == null) onProgress("Rev $rev: extracted and ready")
    }

    private fun ensureLegacyXteas(
        rev: Int,
        barUpdater: ((phase: String, percent: Int) -> Unit)? = null,
        preferredOpenRs2CacheId: Int? = null,
    ) {
        loadLegacyXteas(rev, barUpdater, preferredOpenRs2CacheId)
    }

    /**
     * Run dump for the given revision.
     * - rev == -2: run delta for every rev that has cache (after ensuring base exists) — builds accurate per-rev manifests (e.g. added in 50, removed in 100).
     * - rev < 0 (e.g. -1): base only — run fullDump() for rev 1 and exit.
     * - rev >= 0: ensure rev 1 base exists, then run deltaDump(rev) against base.
     * @param openRs2CacheId when non-null, download that OpenRS2 archive (must match [rev] major); otherwise latest build for [rev].
     */
    suspend fun run(rev: Int, openRs2CacheId: Int) = withContext(Dispatchers.IO) {
        when {
            rev == -2 -> {
                ensureCacheForRevision(1)
                runAllRevs()
            }

            rev < 0 -> {
                ensureCacheForRevision(1)
                fullDump()
            }

            else -> {
                if (rev == 1) {
                    ensureCacheForRevision(1, openRs2CacheId = openRs2CacheId)
                    fullDump(openRs2CacheId)
                    return@withContext
                }
                val baseBin = CachePathHelper.getDiffBinaryFile(gameType, environment, 1)
                if (!baseBin.exists()) {
                    logger.info("Base (rev 1) .bin not found; running full dump first.")
                    ensureCacheForRevision(1)
                    fullDump()
                }
                ensureCacheForRevision(rev, null, openRs2CacheId)
                deltaDump(rev, preferredOpenRs2CacheId = openRs2CacheId)
            }
        }
    }

    /** Max revision to run for "all" mode (limit to 100 for now). */
    private val maxDiffRev = 100

    /** How many revisions to run in parallel in "all" mode (10 at a time). */
    private val runAllBatchSize = 10

    /**
     * Run delta dump for every revision that exists in OpenRS2 (capped at [maxDiffRev]).
     * Uses OpenRS2.allCaches so only revs that actually exist are used (they don't always go 1..100).
     */
    private suspend fun runAllRevs() = withContext(Dispatchers.IO) {
        OpenRS2.loadCaches()
        val game = gameType.name
        val env = environment.name
        val available = OpenRS2.allCaches
            .filter { (it.game ?: "").equals(game, true) && (it.environment ?: "").equals(env, true) }
            .flatMap { c -> (c.builds ?: emptyList()).map { b -> b.major } }
            .distinct()
            .filter { it in 1..maxDiffRev }
            .sorted()
        if (available.isEmpty()) {
            logger.warn("No revisions found in OpenRS2 for $game / $env (max rev $maxDiffRev)")
            return@withContext
        }
        val baseBin = CachePathHelper.getDiffBinaryFile(gameType, environment, 1)
        if (!baseBin.exists()) {
            logger.info("Base (rev 1) .bin not found; running full dump first.")
            fullDump()
        }
        val toRun = available.filter { it > 1 }
        onProgress("Running ${toRun.size} revisions ($runAllBatchSize at a time, max rev $maxDiffRev): $toRun")
        val progressBars = ConcurrentHashMap<Int, ProgressBar>()
        toRun.forEach { r ->
            progressBars[r] = ProgressBarBuilder()
                .setTaskName("Rev $r")
                .setInitialMax(100)
                .setStyle(ProgressBarStyle.UNICODE_BLOCK)
                .setUpdateIntervalMillis(100)
                .build()
        }
        // Serialize all bar updates through one channel so one thread updates display (1 bar per line, no overwrite)
        data class BarUpdate(val rev: Int, val phase: String, val percent: Int)

        val updateChannel = Channel<BarUpdate>(Channel.UNLIMITED)
        try {
            coroutineScope {
                val consumerJob = launch(Dispatchers.IO) {
                    for (u in updateChannel) {
                        progressBars[u.rev]?.setExtraMessage(u.phase)
                        progressBars[u.rev]?.stepTo(u.percent.toLong().coerceIn(0, 100))
                    }
                }
                toRun.chunked(runAllBatchSize).forEach { chunk ->
                    chunk.map { r ->
                        async {
                            val updater: (String, Int) -> Unit = { phase, percent ->
                                updateChannel.trySend(BarUpdate(r, phase, percent))
                            }
                            try {
                                ensureCacheForRevision(r, updater)
                                deltaDump(r, updater)
                                updater("done", 100)
                            } finally {
                                progressBars[r]?.close()
                            }
                        }
                    }.awaitAll()
                }
                updateChannel.close()
                consumerJob.join()
            }
        } finally {
            progressBars.values.forEach { it.close() }
        }
        onProgress("All ${toRun.size} revisions finished")
    }



    companion object {
        var paramType: MutableMap<Int, ParamType> = mutableMapOf()
        var currentRev: Int = -1
        val gamevals: MutableMap<GameValGroupTypes, List<GameValElement>> = mutableMapOf()
    }

    private data class DecodedConfigs(
        val invTypes: Map<Int, InventoryType>,
        val overlayTypes: Map<Int, OverlayType>,
        val underlayTypes: Map<Int, UnderlayType>,
        val textureTypes: Map<Int, TextureType>,
        val npcTypes: Map<Int, NpcType>,
        val itemTypes: Map<Int, ItemType>,
        val objectTypes: Map<Int, ObjectType>,
        val paramTypes: Map<Int, ParamType>,
        val seqTypes: Map<Int, SequenceType>,
        val spotTypes: Map<Int, SpotAnimType>,
        val enumTypes: Map<Int, EnumType>,
        val healthBarTypes: Map<Int, HealthBarType>,
        val mapElementTypes: Map<Int, MapElementType>,
        val varpTypes: Map<Int, VarpType>,
        val varBitTypes: Map<Int, VarBitType>,
        val worldEntityTypes: Map<Int, WorldEntityType>,
        val worldMapAreaTypes: Map<Int, WorldMapAreaType>,
        val structTypes: Map<Int, StructType>,
        val varClanTypes: Map<Int, VarClanType>,
        val varClientTypes: Map<Int, VarClientType>,
        val interfaceTypes: Map<Int, InterfaceType>,
    )

    private fun readGamevals(cache: Cache, rev: Int): Map<GameValGroupTypes, List<GameValElement>> {
        return GameValGroupTypes.entries.associateWith { groupType ->
            runCatching {
               GameValHandler.readGameVal(groupType, cache, rev)
            }.getOrElse { error ->
                logger.warn(error) { "Skipping missing gameval group ${groupType.groupName} for rev $rev" }
                emptyList()
            }
        }
    }

    private fun applyDefinitionContext(
        cache: Cache,
        rev: Int,
        gamevalData: Map<GameValGroupTypes, List<GameValElement>>,
    ) {
        paramType = mutableMapOf()
        currentRev = rev
        gamevals.clear()
        gamevals.putAll(gamevalData)
        OsrsCacheProvider.ParamDecoder(rev).load(cache, paramType)
    }

    private fun buildGamevalExtras(
        gamevalData: Map<GameValGroupTypes, List<GameValElement>>,
    ): Map<String, Map<Int, CacheBinaryFormat.GamevalExtra>> =
        gamevalData.entries.associate { (group, list) ->
            group.groupName to list.associate { it.id to toGamevalExtra(it) }
        }

    private fun decodeConfigs(cache: Cache, rev: Int): DecodedConfigs {
        fun <T> load(loader: (MutableMap<Int, T>) -> Unit): Map<Int, T> =
            mutableMapOf<Int, T>().also(loader)
        val ifaceMap = mutableMapOf<Int, InterfaceType>()
        ComponentDecoder(cache, rev).load(ifaceMap)
        return DecodedConfigs(
            invTypes      = load { OsrsCacheProvider.InventoryDecoder().load(cache, it) },
            overlayTypes  = load { OsrsCacheProvider.OverlayDecoder().load(cache, it) },
            underlayTypes = load { OsrsCacheProvider.UnderlayDecoder().load(cache, it) },
            textureTypes  = load { OsrsCacheProvider.TextureDecoder(rev).load(cache, it) },
            npcTypes      = load { OsrsCacheProvider.NPCDecoder(rev).load(cache, it) },
            itemTypes     = load { OsrsCacheProvider.ItemDecoder(rev).load(cache, it) },
            objectTypes   = load { OsrsCacheProvider.ObjectDecoder(rev).load(cache, it) },
            paramTypes    = paramType.toMap(),
            seqTypes      = load { OsrsCacheProvider.SequenceDecoder(rev).load(cache, it) },
            spotTypes     = load { OsrsCacheProvider.SpotAnimDecoder(rev).load(cache, it) },
            enumTypes     = load { OsrsCacheProvider.EnumDecoder().load(cache, it) },
            healthBarTypes = load { OsrsCacheProvider.HealthBarDecoder().load(cache, it) },
            mapElementTypes = load { OsrsCacheProvider.AreaDecoder().load(cache, it) },
            varpTypes     = load { OsrsCacheProvider.VarDecoder().load(cache, it) },
            varBitTypes   = load { OsrsCacheProvider.VarBitDecoder().load(cache, it) },
            worldEntityTypes = load { OsrsCacheProvider.WorldEntityDecoder().load(cache, it) },
            worldMapAreaTypes = load { OsrsCacheProvider.WorldMapAreasDecoder(rev).load(cache, it) },
            structTypes   = load { OsrsCacheProvider.StructDecoder().load(cache, it) },
            varClanTypes  = load { OsrsCacheProvider.VarClanDecoder().load(cache, it) },
            varClientTypes = load { OsrsCacheProvider.VarClientDecoder().load(cache, it) },
            interfaceTypes = ifaceMap,
        )
    }

    private fun buildTypedConfigs(
        decoded: DecodedConfigs,
        gamevalData: Map<GameValGroupTypes, List<GameValElement>>,
    ): Map<String, Map<Int, DefinitionSnapshot>> = mapOf(
        ConfigDiffType.INV.fileName       to ConfigSerializer.serializeAll(ConfigDiffType.INV,      decoded.invTypes,      gamevalData, paramType),
        ConfigDiffType.OVERLAY.fileName   to ConfigSerializer.serializeAll(ConfigDiffType.OVERLAY,  decoded.overlayTypes,  gamevalData, paramType),
        ConfigDiffType.UNDERLAY.fileName  to ConfigSerializer.serializeAll(ConfigDiffType.UNDERLAY, decoded.underlayTypes, gamevalData, paramType),
        ConfigDiffType.TEXTURES.fileName  to ConfigSerializer.serializeAll(ConfigDiffType.TEXTURES, decoded.textureTypes,  gamevalData, paramType),
        ConfigDiffType.NPCS.fileName      to ConfigSerializer.serializeAll(ConfigDiffType.NPCS,     decoded.npcTypes,      gamevalData, paramType),
        ConfigDiffType.ITEMS.fileName     to ConfigSerializer.serializeAll(ConfigDiffType.ITEMS,    decoded.itemTypes,     gamevalData, paramType),
        ConfigDiffType.OBJECTS.fileName   to ConfigSerializer.serializeAll(ConfigDiffType.OBJECTS,  decoded.objectTypes,   gamevalData, paramType),
        ConfigDiffType.PARAMS.fileName    to ConfigSerializer.serializeAll(ConfigDiffType.PARAMS,   decoded.paramTypes,    gamevalData, paramType),
        ConfigDiffType.SEQUENCE.fileName  to ConfigSerializer.serializeAll(ConfigDiffType.SEQUENCE, decoded.seqTypes,      gamevalData, paramType),
        ConfigDiffType.SPOTANIMS.fileName to ConfigSerializer.serializeAll(ConfigDiffType.SPOTANIMS,decoded.spotTypes,     gamevalData, paramType),
        ConfigDiffType.ENUMS.fileName     to ConfigSerializer.serializeAll(ConfigDiffType.ENUMS,    decoded.enumTypes,     gamevalData, paramType),
        ConfigDiffType.HEALTHBARS.fileName to ConfigSerializer.serializeAll(ConfigDiffType.HEALTHBARS, decoded.healthBarTypes, gamevalData, paramType),
        ConfigDiffType.MAPELEMENTS.fileName to ConfigSerializer.serializeAll(ConfigDiffType.MAPELEMENTS, decoded.mapElementTypes, gamevalData, paramType),
        ConfigDiffType.VARP.fileName      to ConfigSerializer.serializeAll(ConfigDiffType.VARP,     decoded.varpTypes,     gamevalData, paramType),
        ConfigDiffType.VARBIT.fileName    to ConfigSerializer.serializeAll(ConfigDiffType.VARBIT,   decoded.varBitTypes,   gamevalData, paramType),
        ConfigDiffType.WORLDENTITY.fileName to ConfigSerializer.serializeAll(ConfigDiffType.WORLDENTITY, decoded.worldEntityTypes, gamevalData, paramType),
        ConfigDiffType.WORLDMAPAREA.fileName to ConfigSerializer.serializeAll(ConfigDiffType.WORLDMAPAREA, decoded.worldMapAreaTypes, gamevalData, paramType),
        ConfigDiffType.STRUCTS.fileName   to ConfigSerializer.serializeAll(ConfigDiffType.STRUCTS,  decoded.structTypes,   gamevalData, paramType),
        ConfigDiffType.VARCLAN.fileName   to ConfigSerializer.serializeAll(ConfigDiffType.VARCLAN,  decoded.varClanTypes,  gamevalData, paramType),
        ConfigDiffType.VARCLIENT.fileName to ConfigSerializer.serializeAll(ConfigDiffType.VARCLIENT,decoded.varClientTypes, gamevalData, paramType),
        ConfigDiffType.INTERFACES.fileName to ConfigSerializer.serializeAll(ConfigDiffType.INTERFACES, buildInterfaceEntries(decoded.interfaceTypes), gamevalData, paramType),
    )

    private fun buildInterfaceEntries(interfaces: Map<Int, InterfaceType>): Map<Int, InterfaceEntry> =
        interfaces.mapValues { (_, iface) ->
            InterfaceEntry(
                name = runCatching { iface.internalName }.getOrNull(),
                componentCount = iface.components.size,
                hash = iface.computeIdentityHash(),
                components = iface.components,
            )
        }

    private fun buildInterfaceManifest(
        interfaces: Map<Int, InterfaceType>,
        gamevalData: Map<GameValGroupTypes, List<GameValElement>>,
    ): List<InterfaceManifestEntry> {
        val interfaceNames = gamevalData[GameValGroupTypes.IFTYPES]
            .orEmpty()
            .associate { it.id to it.name }
        return interfaces.entries
            .sortedBy { it.key }
            .map { (interfaceId, iface) ->
                InterfaceManifestEntry(
                    interfaceId = interfaceId,
                    gameval = interfaceNames[interfaceId],
                    iflegacy = computeIfLegacy(iface),
                )
            }
    }

    private fun computeIfLegacy(interfaceType: InterfaceType): Boolean? {
        val comps = interfaceType.components.values
        if (comps.isEmpty()) return null
        val rootLayer = if (comps.any { it.layer == -1 }) -1 else interfaceType.id
        val root = comps
            .asSequence()
            .filter { it.layer == rootLayer }
            .sortedBy { it.id }
            .firstOrNull() ?: return null
        return !root.v3
    }

    private fun readClientScripts(cache: Cache): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        cache.archives(CLIENTSCRIPT).forEach { scriptId ->
            cache.data(CLIENTSCRIPT, scriptId)?.let { raw ->
                out[scriptId] = raw
            }
        }
        return out
    }

    private fun serverConfigForRevision(rev: Int, cacheId: Int = 0): ServerConfig {
        return ServerConfig(gameType, cacheId, environment, 0).also { it.revision = rev }
    }

    private inline fun withLoadedCache(
        rev: Int,
        onMissing: (File) -> Unit,
        block: (cacheDir: File, cache: Cache) -> Unit,
    ) {
        val cacheDir = CachePathHelper.getCacheDirectory(gameType, environment, rev)
        val cachePath = File(cacheDir, "data/cache")
        if (!cachePath.exists()) {
            onMissing(cachePath)
            return
        }

        val cache = Cache.load(cachePath.toPath())
        try {
            block(cacheDir, cache)
        } finally {
            try {
                (cache as? AutoCloseable)?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun extractMapDataForRevision(
        rev: Int,
        cache: Cache,
        preferredOpenRs2CacheId: Int?,
        barUpdater: ((phase: String, percent: Int) -> Unit)?,
        progress: (percent: Int, phase: String) -> Unit,
        basePercent: Int,
        spanPercent: Int,
    ): Pair<Map<Int, IntArray>, ExtractedMapData> {
        val xteasByRegion = loadLegacyXteas(rev, barUpdater, preferredOpenRs2CacheId)
        if (xteasByRegion.isNotEmpty()) {
            XteaLoader.loadFromRegionKeys(xteasByRegion)
        }

        val mapExtractor = MapExtractor(serverConfigForRevision(rev), cache)
        val mapData = mapExtractor.extract { _, pct, message ->
            val mapPct = pct?.coerceIn(0.0, 100.0) ?: 0.0
            val overall = basePercent + ((mapPct / 100.0) * spanPercent).toInt()
            progress(overall, message ?: "Extracting full map")
        }

        return xteasByRegion to mapData
    }

    private fun writeDiffBinaryForRevision(
        rev: Int,
        openRs2CacheId: Int?,
        manifest: DiffManifest,
        configs: Map<String, Map<Int, DefinitionSnapshot>>,
        gameval: Map<String, Map<Int, CacheBinaryFormat.GamevalExtra>>,
        sprites: Map<Int, ByteArray>,
        spriteMetadata: Map<Int, List<CacheBinaryFormat.IndexedSpriteMeta>>,
        mapData: ExtractedMapData,
        xteasByRegion: Map<Int, IntArray>,
        interfaceManifest: List<InterfaceManifestEntry>,
        clientScripts: Map<Int, ByteArray>,
    ): File {
        val binFile = CachePathHelper.getDiffBinaryFile(gameType, environment, rev)
        CacheBinaryFormat.writeToFile(
            file = binFile, revision = rev, openRs2CacheId = openRs2CacheId?.toLong(), manifest = manifest, configs = configs,
            gameval = gameval, sprites = sprites, spriteMetadata = spriteMetadata,
            mapObjects = mapData.objectPositions, mapRegions = mapData.regions,
            xteasByRegion = xteasByRegion,
            interfaceManifest = interfaceManifest,
            clientScripts = clientScripts,
        )
        return binFile
    }

    private fun spriteTypeMetadata(st: SpriteType): List<CacheBinaryFormat.IndexedSpriteMeta> {
        return st.sprites.map { sprite ->
            CacheBinaryFormat.IndexedSpriteMeta(
                offsetX = sprite.offsetX,
                offsetY = sprite.offsetY,
                width = sprite.width,
                height = sprite.height,
                averageColor = sprite.averageColor,
                subHeight = sprite.subHeight,
                subWidth = sprite.subWidth,
                alphaBase64 = sprite.alpha?.let { alpha -> Base64.getEncoder().encodeToString(alpha) },
                rasterBase64 = Base64.getEncoder().encodeToString(sprite.raster),
                palette = sprite.palette.toList(),
            )
        }
    }


    /**
     * Full dump: serialize all typed snapshots for rev 1 + write .bin.
     */
    fun fullDump(preferredOpenRs2CacheId: Int? = null) {
        val t0 = System.nanoTime()
        fun progress(pct: Int, phase: String) { onProgress("Rev 1: ${pct.coerceIn(0, 100)}% - $phase") }
        progress(0, "Full dump start (sprites + configs -> .bin)")
        withLoadedCache(1, { cachePath -> logger.warn("Cache not found at $cachePath") }) { cacheDir, cache ->
            CacheManager.init(OsrsCacheProvider(cache, 1))
            val manifestManager = ChecksumManifestManager(serverConfigForRevision(1, REV_CACHE_ONE))
            val checksumManifest = manifestManager.createManifest(cache)
            progress(4, "Manifest created")

            progress(8, "Decoding sprites")
            val spriteBytes = mutableMapOf<Int, ByteArray>()
            val spriteMetadata = mutableMapOf<Int, List<CacheBinaryFormat.IndexedSpriteMeta>>()
            val spriteTypesMap = mutableMapOf<Int, SpriteType>()
            SpriteDecoder().load(cache, spriteTypesMap)
            spriteTypesMap.values.forEach { st ->
                ByteArrayOutputStream().use { out -> ImageIO.write(st.getSprite(true), "png", out); spriteBytes[st.id] = out.toByteArray() }
                spriteMetadata[st.id] = spriteTypeMetadata(st)
            }
            val allSpriteIds = spriteBytes.keys.sorted()
            progress(30, "Sprites decoded (${allSpriteIds.size})")

            val gamevalData = readGamevals(cache, 1)
            applyDefinitionContext(cache, 1, gamevalData)
            progress(32, "Context applied")

            progress(34, "Decoding configs")
            val decodedConfigs = decodeConfigs(cache, 1)
            val configs = buildTypedConfigs(decodedConfigs, gamevalData)
            val interfaceManifest = buildInterfaceManifest(decodedConfigs.interfaceTypes, gamevalData)
            progress(60, "Configs decoded")

            val gameval = buildGamevalExtras(gamevalData)
            val clientScripts = readClientScripts(cache)
            val configSummaries = ConfigDiffType.diffTypeNames.associateWith { type ->
                val ids = configs[type]?.keys?.sorted() ?: emptyList()
                ConfigDiffSummary(added = ids, removed = emptyList(), changed = emptyList())
            }
            progress(74, "Gamevals ready")

            progress(75, "Extracting map data")
            val (xteasByRegion, mapData) = extractMapDataForRevision(1, cache, preferredOpenRs2CacheId, null, ::progress, 75, 15)
            progress(92, "Map done (${mapData.regions.size} regions)")

            val diffManifest = DiffManifest(
                revision = 1,
                sprites  = SpriteDiffSummary(added = allSpriteIds, removed = emptyList(), changed = emptyList()),
                configs  = configSummaries,
                gamevals = emptyMap(),
            )
            manifestManager.saveManifest(checksumManifest, File(cacheDir, "cache-master-checksums.json"))
            progress(96, "Writing binary")
            val binFile = writeDiffBinaryForRevision(
                1,
                preferredOpenRs2CacheId,
                diffManifest,
                configs,
                gameval,
                spriteBytes,
                spriteMetadata,
                mapData,
                xteasByRegion,
                interfaceManifest,
                clientScripts,
            )
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            progress(100, "Done -> ${binFile.name} (${ms.toLong()}ms)")
        }
    }

    /**
     * Delta dump: load base from 1.bin; build configs/gameval extras/sprites in memory; write only rev.bin (no loose files).
     */
    /**
     * Delta dump: compare typed snapshots against base rev 1 .bin, write only changed entries.
     */
    fun deltaDump(
        rev: Int,
        barUpdater: ((phase: String, percent: Int) -> Unit)? = null,
        preferredOpenRs2CacheId: Int? = null,
    ) {
        if (rev <= 1) {
            barUpdater?.invoke("skipped (use fullDump for rev 1)", 100) ?: onProgress("Rev $rev: delta skipped")
            return
        }
        val t0 = System.nanoTime()
        fun progress(pct: Int, phase: String) {
            val p = pct.coerceIn(0, 100)
            barUpdater?.invoke(phase, p) ?: onProgress("Rev $rev: $p% - $phase")
        }
        progress(0, "Delta dump start")

        val baseBin = CachePathHelper.getDiffBinaryFile(gameType, environment, 1)
        val baseDecoded = CacheBinaryFormat.readFromFile(baseBin)
        if (baseDecoded == null) { logger.warn("Base .bin missing or unreadable; run fullDump first"); return }
        progress(4, "Base binary loaded")

        withLoadedCache(rev, { logger.warn("Cache not found for rev $rev") }) { _, cache ->
            CacheManager.init(OsrsCacheProvider(cache, rev))
            progress(12, "Cache initialized")

            // Sprites
            progress(28, "Decoding sprites")
            val currentSprites = mutableMapOf<Int, ByteArray>()
            val currentSpriteMetadata = mutableMapOf<Int, List<CacheBinaryFormat.IndexedSpriteMeta>>()
            val spriteTypesMap = mutableMapOf<Int, SpriteType>()
            SpriteDecoder().load(cache, spriteTypesMap)
            spriteTypesMap.values.forEach { st ->
                ByteArrayOutputStream().use { out -> ImageIO.write(st.getSprite(true), "png", out); currentSprites[st.id] = out.toByteArray() }
                currentSpriteMetadata[st.id] = spriteTypeMetadata(st)
            }
            val baseSprites   = baseDecoded.sprites
            val baseSha       = baseDecoded.spriteSha256
            val baseIds       = baseSprites.keys.toSet()
            val currentIds    = currentSprites.keys.toSet()
            val spriteAdded   = (currentIds - baseIds).sorted()
            val spriteRemoved = (baseIds - currentIds).sorted()
            val spriteChanged = (baseIds intersect currentIds)
                .filter { id -> spriteDiffers(baseSprites[id]!!, currentSprites[id]!!, baseSha[id]) }
                .sorted()
            val deltaSprites  = currentSprites.filterKeys { it in spriteAdded || it in spriteChanged }
            val deltaSpriteMetadata = currentSpriteMetadata.filterKeys { it in spriteAdded || it in spriteChanged }
            progress(42, "+${spriteAdded.size}/-${spriteRemoved.size}/~${spriteChanged.size} sprites")

            // Gamevals
            val gamevalData = readGamevals(cache, rev)
            applyDefinitionContext(cache, rev, gamevalData)
            val gameval = buildGamevalExtras(gamevalData)

            // Configs (includes interfaces via decodeConfigs)
            progress(55, "Decoding configs")
            val decoded = decodeConfigs(cache, rev)
            val currentConfigs = buildTypedConfigs(decoded, gamevalData)
            val interfaceManifest = buildInterfaceManifest(decoded.interfaceTypes, gamevalData)
            val clientScripts = readClientScripts(cache)
            progress(72, "Building delta")

            val baseConfigs = baseDecoded.configs
            val deltaConfigs = mutableMapOf<String, Map<Int, DefinitionSnapshot>>()
            val configSummaries = mutableMapOf<String, ConfigDiffSummary>()
            ConfigDiffType.diffTypeNames.forEach { type ->
                val base    = baseConfigs[type] ?: emptyMap()
                val current = currentConfigs[type] ?: emptyMap()
                val bIds    = base.keys.toSet()
                val cIds    = current.keys.toSet()
                val added   = (cIds - bIds).sorted()
                val removed = (bIds - cIds).sorted()
                val changed = (bIds intersect cIds).filter { id -> current[id] != base[id] }.sorted()
                configSummaries[type] = ConfigDiffSummary(added, removed, changed)
                val deltaIds = (added + changed).toSet()
                if (deltaIds.isNotEmpty()) deltaConfigs[type] = current.filterKeys { it in deltaIds }
            }
            progress(92, "Delta built")

            val diffManifest = DiffManifest(
                revision = rev,
                sprites  = SpriteDiffSummary(spriteAdded, spriteRemoved, spriteChanged),
                configs  = configSummaries,
                gamevals = emptyMap(),
            )

            progress(95, "Extracting map data")
            val (xteasByRegion, mapData) = extractMapDataForRevision(rev, cache, preferredOpenRs2CacheId, barUpdater, ::progress, 95, 4)
            progress(99, "Writing binary")
            val binFile = writeDiffBinaryForRevision(
                rev,
                preferredOpenRs2CacheId,
                diffManifest,
                deltaConfigs,
                gameval,
                deltaSprites,
                deltaSpriteMetadata,
                mapData,
                xteasByRegion,
                interfaceManifest,
                clientScripts,
            )
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            if (barUpdater == null) onProgress(
                "Rev $rev: done -> ${binFile.name} " +
                "+${spriteAdded.size}/-${spriteRemoved.size}/~${spriteChanged.size} sprites (${ms.toLong()}ms)"
            )
            progress(100, "Done")
        }
    }

    private fun spriteDiffers(before: ByteArray, after: ByteArray, baseSha: ByteArray?): Boolean {
        if (before.contentEquals(after)) return false
        if (baseSha != null) {
            val afterSha = MessageDigest.getInstance("SHA-256").digest(after)
            if (baseSha.contentEquals(afterSha)) return false
        }
        return !areSpritesVisuallyEqual(before, after)
    }

    private fun areSpritesVisuallyEqual(a: ByteArray, b: ByteArray): Boolean {
        if (a === b || a.contentEquals(b)) return true
        val imageA = runCatching { ImageIO.read(ByteArrayInputStream(a)) }.getOrNull()
        val imageB = runCatching { ImageIO.read(ByteArrayInputStream(b)) }.getOrNull()
        if (imageA == null || imageB == null) return a.contentEquals(b)
        if (imageA.width != imageB.width || imageA.height != imageB.height) return false
        for (y in 0 until imageA.height) for (x in 0 until imageA.width) if (imageA.getRGB(x, y) != imageB.getRGB(x, y)) return false
        return true
    }

    private fun resolveOpenRs2Stamp(rev: Int, preferredOpenRs2CacheId: Int? = null): Any? = null

    private fun loadLegacyXteas(
        rev: Int,
        barUpdater: ((phase: String, percent: Int) -> Unit)? = null,
        preferredOpenRs2CacheId: Int? = null,
    ): Map<Int, IntArray> {
        if (rev >= 237) return emptyMap()
        legacyXteasByRevision[rev]?.let { return it }
        barUpdater?.invoke("downloading xteas", 48) ?: onProgress("Rev $rev: downloading legacy keys")
        val cacheId = preferredOpenRs2CacheId?.toLong()
            ?: throw IllegalStateException("Missing OpenRS2 cache id for rev $rev (required for keys.json)")
        val url = "https://archive.openrs2.org/caches/runescape/$cacheId/keys.json"
        val parsed = runCatching { XteaLoader.parseXteas(URL(url).readText()) }.getOrElse {
            logger.warn("Failed downloading legacy xteas from $url: ${it.message}"); emptyMap()
        }
        legacyXteasByRevision.putIfAbsent(rev, parsed)
        return legacyXteasByRevision[rev] ?: parsed
    }
}
