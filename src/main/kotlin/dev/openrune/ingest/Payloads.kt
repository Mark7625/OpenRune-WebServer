package dev.openrune.ingest

import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.cache.diff.DefinitionSnapshot
import dev.openrune.cache.diff.GamevalExtra
import dev.openrune.cache.diff.IndexedSpriteMeta
import dev.openrune.cache.diff.LocationCustom
import dev.openrune.cache.diff.ModelMeta
import dev.openrune.cache.diff.RegionData
import dev.openrune.model.EntitySnapshot
import dev.openrune.model.Hashing
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO

/**
 * Payload shapes shared by every [RevisionSource]. Two sources describing the same entity must
 * produce byte-identical canonical JSON, otherwise history imported from `.bin` files and new
 * revisions decoded from caches would disagree about what changed.
 */
object Payloads {

    fun config(type: ConfigDiffType<*>, id: Int, snapshot: DefinitionSnapshot): EntitySnapshot {
        val nameField = type.searchFields()["name"] ?: "name"
        val name = (snapshot[nameField]?.value as? String) ?: (snapshot["name"]?.value as? String)
        return EntitySnapshot.of(id, name, snapshot)
    }

    fun model(id: Int, meta: ModelMeta): EntitySnapshot = EntitySnapshot.of(
        id,
        null,
        linkedMapOf(
            "v" to meta.vertexCount,
            "f" to meta.faceCount,
            "tf" to meta.texturedFaceCount,
            "af" to meta.transparentFaceCount,
            "ver" to meta.version,
            "pri" to meta.renderPriority,
            "tex" to meta.textures,
            "col" to meta.colors,
            "items" to meta.itemIds,
            "npcs" to meta.npcIds,
            "objs" to meta.objectIds,
        ),
    )

    fun modelFromPayload(body: com.google.gson.JsonObject): ModelMeta = ModelMeta(
        vertexCount = body["v"]?.asInt ?: 0,
        faceCount = body["f"]?.asInt ?: 0,
        texturedFaceCount = body["tf"]?.asInt ?: 0,
        transparentFaceCount = body["af"]?.asInt ?: 0,
        version = body["ver"]?.asInt ?: 0,
        renderPriority = body["pri"]?.asInt ?: 0,
        textures = body["tex"]?.asJsonArray?.map { it.asInt } ?: emptyList(),
        colors = body["col"]?.asJsonArray?.map { it.asInt } ?: emptyList(),
        itemIds = body["items"]?.asJsonArray?.map { it.asInt } ?: emptyList(),
        npcIds = body["npcs"]?.asJsonArray?.map { it.asInt } ?: emptyList(),
        objectIds = body["objs"]?.asJsonArray?.map { it.asInt } ?: emptyList(),
    )

    fun clientScript(id: Int, bytes: ByteArray): EntitySnapshot =
        EntitySnapshot.of(id, null, mapOf("length" to bytes.size, "sha" to Hashing.hex(Hashing.hash16(bytes))), blob = bytes)

    fun gameval(id: Int, extra: GamevalExtra): EntitySnapshot =
        EntitySnapshot.of(id, extra.searchable, linkedMapOf("text" to extra.text, "sub" to extra.sub.mapKeys { it.key.toString() }))

    /**
     * Sprite payload: per-frame metadata plus a hash of the rendered pixels. Two sprites whose PNG
     * bytes differ but render identically hash the same, which matches the legacy pixel comparison.
     */
    fun sprite(id: Int, metas: List<IndexedSpriteMeta>, png: ByteArray?, pixelHash: ByteArray?): EntitySnapshot {
        val frames = metas.map { m ->
            linkedMapOf(
                "offsetX" to m.offsetX,
                "offsetY" to m.offsetY,
                "width" to m.width,
                "height" to m.height,
                "averageColor" to m.averageColor,
                "subHeight" to m.subHeight,
                "subWidth" to m.subWidth,
                "hasAlpha" to m.hasAlpha,
            )
        }
        return EntitySnapshot.of(
            id,
            null,
            linkedMapOf("frames" to frames, "pixels" to pixelHash?.let { Hashing.hex(it) }),
            blob = png,
        )
    }

    fun pixelHash(image: BufferedImage): ByteArray {
        val argb: IntArray = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val buffer = ByteBuffer.allocate(8 + argb.size * 4)
        buffer.putInt(image.width).putInt(image.height)
        argb.forEach { buffer.putInt(it) }
        return Hashing.hash16(buffer.array())
    }

    fun pixelHashOfPng(png: ByteArray): ByteArray? =
        runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull()?.let { pixelHash(it) }

    fun region(region: RegionData): EntitySnapshot {
        val positions = region.positions.sortedBy { it.position }.map { loc ->
            listOf(loc.id, loc.type, loc.orientation, loc.position, if (loc.isDynamic) 1 else 0)
        }
        val payload = linkedMapOf(
            "totalObjects" to region.totalObjects,
            "positions" to positions,
            "overlayIds" to region.overlayIds.toSortedMap().mapKeys { it.key.toString() }.mapValues { it.value.sorted() },
            "underlayIds" to region.underlayIds.toSortedMap().mapKeys { it.key.toString() }.mapValues { it.value.sorted() },
        )
        val refs = region.positions.map { it.id }.distinct().toIntArray()
        return EntitySnapshot.of(region.id, null, payload, refs = refs)
    }

    fun regionFromPayload(id: Int, body: com.google.gson.JsonObject): RegionData {
        val positions = body["positions"]?.asJsonArray?.map { el ->
            val a = el.asJsonArray
            LocationCustom(a[0].asInt, a[1].asInt, a[2].asInt, a[3].asInt, a[4].asInt == 1)
        } ?: emptyList()
        fun tiles(key: String): Map<Int, List<Int>> =
            body[key]?.asJsonObject?.entrySet()?.associate { (k, v) -> k.toInt() to v.asJsonArray.map { it.asInt } } ?: emptyMap()
        return RegionData(id, positions, body["totalObjects"]?.asInt ?: positions.size, tiles("overlayIds"), tiles("underlayIds"))
    }
}
