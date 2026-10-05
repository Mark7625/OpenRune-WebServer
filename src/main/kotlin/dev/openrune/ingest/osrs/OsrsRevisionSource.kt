package dev.openrune.ingest.osrs

import com.google.gson.Gson
import dev.openrune.OsrsCacheProvider
import dev.openrune.cache.CLIENTSCRIPT
import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.cache.diff.ConfigSerializer
import dev.openrune.cache.diff.GamevalExtra
import dev.openrune.cache.diff.IndexedSpriteMeta
import dev.openrune.cache.diff.InterfaceEntry
import dev.openrune.cache.diff.InterfaceManifestEntry
import dev.openrune.cache.diff.LocationCustom
import dev.openrune.cache.diff.RegionData
import dev.openrune.cache.filestore.definition.ComponentDecoder
import dev.openrune.cache.filestore.definition.InterfaceType
import dev.openrune.cache.filestore.definition.SpriteDecoder
import dev.openrune.cache.gameval.GameValElement
import dev.openrune.cache.gameval.GameValHandler
import dev.openrune.cache.gameval.impl.Interface
import dev.openrune.cache.gameval.impl.Sprite
import dev.openrune.cache.gameval.impl.Table
import dev.openrune.cache.map.MAX_REGION
import dev.openrune.cache.map.RegionLoader
import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
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
import dev.openrune.ingest.ArtifactKind
import dev.openrune.ingest.Payloads
import dev.openrune.ingest.RevisionSource
import dev.openrune.model.EntitySnapshot
import dev.openrune.cache.tools.item.ItemSpriteFactory
import dev.openrune.cache.tools.obj.ObjectSpriteFactory
import dev.openrune.ingest.RenderReuse
import dev.openrune.model.CanonicalJson
import dev.openrune.model.Hashing
import dev.openrune.model.OsrsEntityTypes
import dev.openrune.model.gamevalTypeKey
import dev.openrune.cache.diff.ModelExtractor
import mu.KotlinLogging
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

private val logger = KotlinLogging.logger {}

/**
 * Item and object images are rendered once at this size and scaled down on request. Rendering at
 * 512 costs about 1.7x a 36px render rather than the 200x the pixel count suggests — the work is
 * dominated by mesh setup — so one large render that downscales cleanly beats storing several.
 */
private const val RENDER_SIZE = 512

/**
 * Decodes one OSRS revision from its raw cache directory, one entity type per call. The decoded
 * item / npc / object definitions are kept only until the model type (which needs them for
 * attachments) has been produced.
 */
class OsrsRevisionSource(
    override val rev: Int,
    private val cachePath: File,
    private val environment: CacheEnvironment,
    private val xteas: Map<Int, IntArray>?,
    /** Null renders every image; supplied, only what changed is rendered. See [RenderReuse]. */
    private val reuse: RenderReuse? = null,
) : RevisionSource {

    private val cache: Cache = Cache.load(cachePath.toPath())
    private val gson = Gson()

    private val gamevals: Map<GameValGroupTypes, List<GameValElement>> by lazy { readGamevals() }
    private val paramTypes: Map<Int, ParamType> by lazy {
        mutableMapOf<Int, ParamType>().also { OsrsCacheProvider.ParamDecoder(rev).load(cache, it) }
    }
    private var itemTypes: Map<Int, ItemType>? = null
    private var npcTypes: Map<Int, NpcType>? = null
    private var objectTypes: Map<Int, ObjectType>? = null
    private var interfaceManifest: List<InterfaceManifestEntry> = emptyList()

    override val typeKeys: List<String> =
        ConfigDiffType.all.map { it.fileName } +
            listOf(
                OsrsEntityTypes.SPRITES, OsrsEntityTypes.MODELS, OsrsEntityTypes.CLIENTSCRIPTS,
                OsrsEntityTypes.MAP_REGIONS, OsrsEntityTypes.ITEM_SPRITES, OsrsEntityTypes.OBJECT_SPRITES,
            ) +
            GameValGroupTypes.entries.map { it.groupName }.distinct().map { gamevalTypeKey(it) }

    override fun snapshots(typeKey: String): Iterator<EntitySnapshot> = when {
        typeKey == OsrsEntityTypes.SPRITES -> sprites()
        typeKey == OsrsEntityTypes.MODELS -> models()
        typeKey == OsrsEntityTypes.CLIENTSCRIPTS -> clientScripts()
        typeKey == OsrsEntityTypes.MAP_REGIONS -> mapRegions()
        typeKey == OsrsEntityTypes.ITEM_SPRITES -> itemSprites()
        typeKey == OsrsEntityTypes.OBJECT_SPRITES -> objectSprites()
        typeKey.startsWith("gameval.") -> gameval(typeKey.removePrefix("gameval."))
        else -> config(ConfigDiffType.all.first { it.fileName == typeKey })
    }

    override fun artifacts(): Map<String, String> = buildMap {
        put(ArtifactKind.INTERFACE_MANIFEST, gson.toJson(interfaceManifest))
        xteas?.let { keys -> put(ArtifactKind.XTEAS, gson.toJson(keys.mapKeys { it.key.toString() })) }
    }

    override fun close() {
        runCatching { cache.close() }
    }

    // ── configs ──────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun config(type: ConfigDiffType<*>): Iterator<EntitySnapshot> {
        val decoded: Map<Int, Any> = when (type) {
            ConfigDiffType.INV -> load<InventoryType> { OsrsCacheProvider.InventoryDecoder().load(cache, it) }
            ConfigDiffType.OVERLAY -> load<OverlayType> { OsrsCacheProvider.OverlayDecoder().load(cache, it) }
            ConfigDiffType.UNDERLAY -> load<UnderlayType> { OsrsCacheProvider.UnderlayDecoder().load(cache, it) }
            ConfigDiffType.TEXTURES -> load<TextureType> { OsrsCacheProvider.TextureDecoder(rev).load(cache, it) }
            ConfigDiffType.NPCS -> load<NpcType> { OsrsCacheProvider.NPCDecoder(rev).load(cache, it) }.also { npcTypes = it }
            ConfigDiffType.ITEMS -> load<ItemType> { OsrsCacheProvider.ItemDecoder(rev).load(cache, it) }.also { itemTypes = it }
            ConfigDiffType.OBJECTS -> load<ObjectType> { OsrsCacheProvider.ObjectDecoder(rev).load(cache, it) }.also { objectTypes = it }
            ConfigDiffType.PARAMS -> paramTypes
            ConfigDiffType.SEQUENCE -> load<SequenceType> { OsrsCacheProvider.SequenceDecoder(rev).load(cache, it) }
            ConfigDiffType.SPOTANIMS -> load<SpotAnimType> { OsrsCacheProvider.SpotAnimDecoder(rev).load(cache, it) }
            ConfigDiffType.ENUMS -> load<EnumType> { OsrsCacheProvider.EnumDecoder().load(cache, it) }
            ConfigDiffType.HEALTHBARS -> load<HealthBarType> { OsrsCacheProvider.HealthBarDecoder().load(cache, it) }
            ConfigDiffType.MAPELEMENTS -> load<MapElementType> { OsrsCacheProvider.AreaDecoder().load(cache, it) }
            ConfigDiffType.VARP -> load<VarpType> { OsrsCacheProvider.VarDecoder().load(cache, it) }
            ConfigDiffType.VARBIT -> load<VarBitType> { OsrsCacheProvider.VarBitDecoder().load(cache, it) }
            ConfigDiffType.WORLDENTITY -> load<WorldEntityType> { OsrsCacheProvider.WorldEntityDecoder().load(cache, it) }
            ConfigDiffType.WORLDMAPAREA -> load<WorldMapAreaType> { OsrsCacheProvider.WorldMapAreasDecoder(rev).load(cache, it) }
            ConfigDiffType.STRUCTS -> load<StructType> { OsrsCacheProvider.StructDecoder().load(cache, it) }
            ConfigDiffType.VARCLAN -> load<VarClanType> { OsrsCacheProvider.VarClanDecoder().load(cache, it) }
            ConfigDiffType.VARCLIENT -> load<VarClientType> { OsrsCacheProvider.VarClientDecoder().load(cache, it) }
            ConfigDiffType.INTERFACES -> interfaces()
        }
        val typed = type as ConfigDiffType<Any>
        return decoded.entries.sortedBy { it.key }.asSequence().map { (id, def) ->
            Payloads.config(type, id, ConfigSerializer.serialize(typed, def, gamevals, paramTypes))
        }.iterator()
    }

    private fun <T> load(loader: (MutableMap<Int, T>) -> Unit): Map<Int, T> = mutableMapOf<Int, T>().also(loader)

    private fun interfaces(): Map<Int, InterfaceEntry> {
        val types = mutableMapOf<Int, InterfaceType>()
        ComponentDecoder(cache, rev).load(types)
        val names = gamevals[GameValGroupTypes.IFTYPES].orEmpty().associate { it.id to it.name }
        interfaceManifest = types.entries.sortedBy { it.key }.map { (id, iface) ->
            InterfaceManifestEntry(id, names[id], ifLegacy(iface))
        }
        return types.mapValues { (_, iface) ->
            InterfaceEntry(
                name = runCatching { iface.internalName }.getOrNull(),
                componentCount = iface.components.size,
                hash = iface.computeIdentityHash(),
                components = iface.components,
            )
        }
    }

    private fun ifLegacy(iface: InterfaceType): Boolean? {
        val comps = iface.components.values
        if (comps.isEmpty()) return null
        val rootLayer = if (comps.any { it.layer == -1 }) -1 else iface.id
        val root = comps.asSequence().filter { it.layer == rootLayer }.sortedBy { it.id }.firstOrNull() ?: return null
        return !root.v3
    }

    // ── archives ─────────────────────────────────────────────────────────────

    private fun sprites(): Iterator<EntitySnapshot> {
        val types = mutableMapOf<Int, SpriteType>()
        SpriteDecoder().load(cache, types)
        return types.entries.sortedBy { it.key }.asSequence().map { (id, st) ->
            val image = st.getSprite(true)
            val png = ByteArrayOutputStream().use { out -> ImageIO.write(image, "png", out); out.toByteArray() }
            val metas = st.sprites.map { s ->
                IndexedSpriteMeta(
                    offsetX = s.offsetX, offsetY = s.offsetY, width = s.width, height = s.height,
                    averageColor = s.averageColor, subHeight = s.subHeight, subWidth = s.subWidth,
                    hasAlpha = s.alpha != null,
                )
            }
            Payloads.sprite(id, metas, png, Payloads.pixelHash(image))
        }.iterator()
    }

    /**
     * Inventory images, rendered here rather than linked from a third party. The factory hands back
     * a `BufferedImage`, so nothing touches disk.
     *
     * Only items whose definition or models moved at this revision are re-rendered; the rest carry
     * last revision's image forward by hash. A render that throws is skipped rather than failing the
     * revision — one bad item should not cost the whole import.
     */
    private fun itemSprites(): Iterator<EntitySnapshot> {
        val items = itemTypes ?: load<ItemType> { OsrsCacheProvider.ItemDecoder(rev).load(cache, it) }
        val names = gamevalNames("items")
        val previous = reuse?.previousImageHashes(OsrsEntityTypes.ITEM_SPRITES).orEmpty()
        val changedDefs = reuse?.changedIds(ConfigDiffType.ITEMS.fileName).orEmpty()
        val changedModels = reuse?.changedIds(OsrsEntityTypes.MODELS).orEmpty()
        val factory by lazy { ItemSpriteFactory.fromCache(cache, rev, items) }

        return items.keys.sorted().asSequence().mapNotNull { id ->
            val carried = carriedImage(id, previous, changedDefs, changedModels, itemModelIds(items[id]))
            if (carried != null) renderSnapshot(id, names[id], carried, null)
            else renderPng { factory.createSprite(factory.item(id).also { it.size = RENDER_SIZE }) }
                ?.let { png -> renderSnapshot(id, names[id], Hashing.hash16(png), png) }
        }.iterator()
    }

    /** Object images; same shape as [itemSprites]. */
    private fun objectSprites(): Iterator<EntitySnapshot> {
        val objects = objectTypes ?: load<ObjectType> { OsrsCacheProvider.ObjectDecoder(rev).load(cache, it) }
        val names = gamevalNames("objects")
        val previous = reuse?.previousImageHashes(OsrsEntityTypes.OBJECT_SPRITES).orEmpty()
        val changedDefs = reuse?.changedIds(ConfigDiffType.OBJECTS.fileName).orEmpty()
        val changedModels = reuse?.changedIds(OsrsEntityTypes.MODELS).orEmpty()
        val factory by lazy { ObjectSpriteFactory.fromCache(cache, rev, objects) }

        return objects.keys.sorted().asSequence().mapNotNull { id ->
            val carried = carriedImage(id, previous, changedDefs, changedModels, objects[id]?.objectModels?.toList())
            if (carried != null) renderSnapshot(id, names[id], carried, null)
            else renderPng { factory.createSprite(factory.obj(id).also { it.size = RENDER_SIZE }) }
                ?.let { png -> renderSnapshot(id, names[id], Hashing.hash16(png), png) }
        }.iterator()
    }

    /**
     * Last revision's image hash when nothing this render depends on changed, else null to re-render.
     * Only consulted when a [reuse] source was supplied, so a first import renders everything.
     */
    private fun carriedImage(
        id: Int,
        previous: Map<Int, ByteArray>,
        changedDefs: Set<Int>,
        changedModels: Set<Int>,
        modelIds: List<Int>?,
    ): ByteArray? {
        if (reuse == null) return null
        val hash = previous[id] ?: return null
        if (id in changedDefs) return null
        if (modelIds != null && modelIds.any { it > 0 && it in changedModels }) return null
        return hash
    }

    /**
     * The payload carries the image hash, so an entity's payload changes exactly when its image
     * does — which is what the version merge keys on. A carried-forward image passes [png] as null:
     * the bytes are already in `entity_blob` under this hash from the revision that rendered it.
     */
    private fun renderSnapshot(id: Int, name: String?, imageHash: ByteArray, png: ByteArray?): EntitySnapshot {
        val json = CanonicalJson.encode(mapOf("image" to Hashing.hex(imageHash)))
        return EntitySnapshot(
            entityId = id,
            name = name,
            payloadJson = json,
            payloadHash = Hashing.hash16(json.toByteArray(Charsets.UTF_8)),
            blob = png,
            blobHash = imageHash,
        )
    }

    private fun itemModelIds(item: ItemType?): List<Int>? {
        if (item == null) return null
        return listOf(
            item.inventoryModel, item.maleModel0, item.maleModel1, item.maleModel2,
            item.maleHeadModel0, item.maleHeadModel1,
            item.femaleModel0, item.femaleModel1, item.femaleModel2,
            item.femaleHeadModel0, item.femaleHeadModel1,
        )
    }

    private fun renderPng(render: () -> java.awt.image.BufferedImage?): ByteArray? {
        val image = runCatching(render).getOrNull() ?: return null
        if (image.width <= 0 || image.height <= 0) return null
        return ByteArrayOutputStream().use { out -> ImageIO.write(image, "png", out); out.toByteArray() }
    }

    private fun models(): Iterator<EntitySnapshot> {
        val items = itemTypes ?: load<ItemType> { OsrsCacheProvider.ItemDecoder(rev).load(cache, it) }
        val npcs = npcTypes ?: load<NpcType> { OsrsCacheProvider.NPCDecoder(rev).load(cache, it) }
        val objects = objectTypes ?: load<ObjectType> { OsrsCacheProvider.ObjectDecoder(rev).load(cache, it) }
        val ids = ModelExtractor.modelIds(cache)
        val metas = ModelExtractor.loadOrExtract(
            cache = cache,
            gameType = GameType.OLDSCHOOL,
            environment = environment,
            rev = rev,
            attachments = { ModelExtractor.attachmentsFrom(items, npcs, objects) },
            ids = ids,
            showProgressBar = false,
        ) { msg -> logger.info { "rev $rev: $msg" } }
        itemTypes = null; npcTypes = null
        return metas.entries.sortedBy { it.key }.asSequence().map { (id, meta) -> Payloads.model(id, meta) }.iterator()
    }

    private fun clientScripts(): Iterator<EntitySnapshot> =
        cache.archives(CLIENTSCRIPT).sorted().asSequence().mapNotNull { id ->
            cache.data(CLIENTSCRIPT, id)?.let { Payloads.clientScript(id, it) }
        }.iterator()

    private fun mapRegions(): Iterator<EntitySnapshot> {
        val objects = objectTypes ?: load<ObjectType> { OsrsCacheProvider.ObjectDecoder(rev).load(cache, it) }
        objectTypes = null
        val loader = RegionLoader(cache, rev, xteas.orEmpty())
        return (0 until MAX_REGION).asSequence().mapNotNull { id ->
            val region = runCatching { loader.load(id) }.getOrElse { e ->
                logger.debug(e) { "rev $rev: region $id unreadable" }
                null
            } ?: return@mapNotNull null
            // Same expression as the legacy extractor (an unknown object id counts as dynamic) so
            // imported history and freshly decoded revisions agree.
            val positions = region.locations.map { loc ->
                LocationCustom(loc.id, loc.type, loc.orientation, loc.position.pack(), objects[loc.id]?.animationId != -1)
            }
            Payloads.region(
                RegionData(
                    id = id,
                    positions = positions,
                    totalObjects = region.locations.size,
                    overlayIds = region.overlayIdPositions.mapValues { it.value.sorted() },
                    underlayIds = region.underlayIdPositions.mapValues { it.value.sorted() },
                ),
            )
        }.iterator()
    }

    // ── gamevals ─────────────────────────────────────────────────────────────

    private fun readGamevals(): Map<GameValGroupTypes, List<GameValElement>> =
        GameValGroupTypes.entries.associateWith { group ->
            runCatching { GameValHandler.readGameVal(group, cache, rev) }.getOrElse { e ->
                logger.debug { "rev $rev: gameval group ${group.name} unavailable: ${e.message}" }
                emptyList()
            }
        }

    /** `id -> gameval name` for a group, so rendered images carry the same name as their config. */
    private fun gamevalNames(group: String): Map<Int, String> {
        val candidates = GameValGroupTypes.entries.filter { it.groupName == group }
        val elements = candidates.map { gamevals[it].orEmpty() }.lastOrNull { it.isNotEmpty() } ?: emptyList()
        return elements.mapNotNull { e -> e.name?.takeIf { it.isNotBlank() }?.let { e.id to it } }.toMap()
    }

    private fun gameval(group: String): Iterator<EntitySnapshot> {
        val candidates = GameValGroupTypes.entries.filter { it.groupName == group }
        val elements = candidates.map { gamevals[it].orEmpty() }.lastOrNull { it.isNotEmpty() } ?: emptyList()
        return elements.sortedBy { it.id }.asSequence().map { element ->
            Payloads.gameval(element.id, gamevalExtra(element))
        }.iterator()
    }

    companion object {
        fun gamevalExtra(element: GameValElement): GamevalExtra {
            val text = if (element is Sprite) "${element.name},${element.id}" else element.name
            val sub = when (element) {
                is Interface -> element.components.associate { it.id to it.name }
                is Table -> element.columns.associate { it.id to it.name }
                else -> emptyMap()
            }
            return GamevalExtra(searchable = element.name, text = text, sub = sub)
        }

        /** Revisions before 237 encrypt map locations and need a `keys.json` from OpenRS2. */
        fun xteasAvailable(rev: Int): Boolean = rev < 237
    }
}
