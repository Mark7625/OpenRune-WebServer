package dev.openrune.ingest

import com.google.gson.Gson
import dev.openrune.cache.diff.CacheBinaryFormat
import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.cache.diff.DefinitionSnapshot
import dev.openrune.cache.diff.ModelMeta
import dev.openrune.model.EntitySnapshot
import dev.openrune.model.OsrsEntityTypes
import dev.openrune.model.gamevalTypeKey
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Reads a revision from the legacy `{rev}.bin` format. Legacy deltas are relative to rev 1, so
 * the full state of revision N is `(base minus removed_N) union delta_N` and needs only bins 1
 * and N: history can be imported without re-downloading any cache.
 */
class LegacyBinRevisionSource(
    override val rev: Int,
    private val base: CacheBinaryFormat.DecodedRev,
    private val current: CacheBinaryFormat.DecodedRev,
) : RevisionSource {

    private val gson = Gson()

    override val typeKeys: List<String> =
        ConfigDiffType.all.map { it.fileName } +
            listOf(OsrsEntityTypes.SPRITES, OsrsEntityTypes.MODELS, OsrsEntityTypes.CLIENTSCRIPTS, OsrsEntityTypes.MAP_REGIONS) +
            current.gameval.keys.sorted().map { gamevalTypeKey(it) }

    override fun snapshots(typeKey: String): Iterator<EntitySnapshot> = when {
        typeKey == OsrsEntityTypes.SPRITES -> sprites()
        typeKey == OsrsEntityTypes.MODELS -> models()
        typeKey == OsrsEntityTypes.CLIENTSCRIPTS ->
            current.clientScripts.entries.sortedBy { it.key }.asSequence().map { (id, bytes) -> Payloads.clientScript(id, bytes) }.iterator()
        typeKey == OsrsEntityTypes.MAP_REGIONS ->
            current.mapRegions.entries.sortedBy { it.key }.asSequence().map { (_, region) -> Payloads.region(region) }.iterator()
        typeKey.startsWith("gameval.") -> {
            val group = typeKey.removePrefix("gameval.")
            current.gameval[group].orEmpty().entries.sortedBy { it.key }.asSequence()
                .map { (id, extra) -> Payloads.gameval(id, extra) }.iterator()
        }
        else -> config(ConfigDiffType.all.first { it.fileName == typeKey })
    }

    override fun artifacts(): Map<String, String> = buildMap {
        put(ArtifactKind.INTERFACE_MANIFEST, gson.toJson(current.interfaceManifest))
        if (current.xteasByRegion.isNotEmpty()) {
            put(ArtifactKind.XTEAS, gson.toJson(current.xteasByRegion.mapKeys { it.key.toString() }))
        }
    }

    override fun close() {}

    /** Full state of a config type at this revision. */
    fun configState(type: ConfigDiffType<*>): Map<Int, DefinitionSnapshot> {
        val key = type.fileName
        if (rev == 1) return base.configs[key].orEmpty()
        val summary = current.manifest.configs[key]
        val state = LinkedHashMap(base.configs[key].orEmpty())
        summary?.removed?.forEach { state.remove(it) }
        current.configs[key]?.forEach { (id, snapshot) -> state[id] = snapshot }
        return state
    }

    private fun config(type: ConfigDiffType<*>): Iterator<EntitySnapshot> =
        configState(type).entries.sortedBy { it.key }.asSequence().map { (id, snapshot) -> Payloads.config(type, id, snapshot) }.iterator()

    fun modelState(): Map<Int, ModelMeta> {
        if (rev == 1) return base.models
        val state = LinkedHashMap(base.models)
        current.modelSummary.removed.forEach { state.remove(it) }
        state.putAll(current.models)
        return state
    }

    private fun models(): Iterator<EntitySnapshot> =
        modelState().entries.sortedBy { it.key }.asSequence().map { (id, meta) -> Payloads.model(id, meta) }.iterator()

    /** Sprite ids present at this revision with the binary that holds their bytes (base or current). */
    fun spriteSources(): Map<Int, CacheBinaryFormat.DecodedRev> {
        val ids = LinkedHashMap<Int, CacheBinaryFormat.DecodedRev>()
        val baseIds = if (base.sprites.isNotEmpty()) base.sprites.keys else base.spriteMetadata.keys.ifEmpty { base.manifest.sprites.added.toSet() }
        baseIds.forEach { ids[it] = base }
        if (rev != 1) {
            val summary = current.manifest.sprites
            summary.removed.forEach { ids.remove(it) }
            (summary.added + summary.changed).forEach { ids[it] = current }
        }
        return ids
    }

    private var warnedMissingPng = false

    private fun sprites(): Iterator<EntitySnapshot> =
        spriteSources().entries.sortedBy { it.key }.asSequence().map { (id, source) ->
            val png = source.sprites[id]
            val metas = source.spriteMetadata[id].orEmpty()
            val pixels = png?.let { Payloads.pixelHashOfPng(it) }
            if (png == null && !warnedMissingPng) {
                warnedMissingPng = true
                logger.warn { "rev $rev: sprite PNGs absent from .bin; sprite change detection uses metadata only" }
            }
            Payloads.sprite(id, metas, png, pixels)
        }.iterator()

    companion object {
        fun load(binFile: File): CacheBinaryFormat.DecodedRev =
            CacheBinaryFormat.readFromFile(binFile) ?: error("Unreadable legacy binary ${binFile.absolutePath}")
    }
}
