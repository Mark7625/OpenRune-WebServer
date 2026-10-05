package dev.openrune.cache.diff

import com.github.luben.zstd.Zstd
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reader for the legacy per-revision binary (`cache/{game}/{env}/diffs/{rev}.bin`).
 *
 * Nothing writes this format any more; it exists so historical revisions can be imported into
 * PostgreSQL and so the new output can be validated against it. See docs/ARCHITECTURE.md §6.
 *
 * Layout: magic `ORCA` + revision + uncompressed body length, then a ZSTD body holding the type
 * schema, the added/removed/changed manifest, config snapshots, gamevals, sprites, map data and
 * XTEA keys, followed by marker-prefixed optional trailers.
 */
object CacheBinaryFormat {

    private const val MAGIC = "ORCA"
    private const val SPRITE_SHA_LEN = 32
    private const val TRAILER_SPRITE_META = "SMET"
    private const val TRAILER_SPRITE_RASTER = "SMRP"
    private const val TRAILER_OPENRS2_ID = "OCID"
    private const val TRAILER_INTERFACE_MANIFEST = "IFMF"
    private const val TRAILER_CLIENT_SCRIPTS = "CSRB"
    private const val TRAILER_MODEL_META = "MDLM"

    private val gson = Gson()

    data class DecodedRev(
        val revision: Int,
        val openRs2CacheId: Long? = null,
        val manifest: DiffManifest,
        val configs: Map<String, Map<Int, DefinitionSnapshot>>,
        val gameval: Map<String, Map<Int, GamevalExtra>> = emptyMap(),
        val sprites: Map<Int, ByteArray> = emptyMap(),
        val spriteMetadata: Map<Int, List<IndexedSpriteMeta>> = emptyMap(),
        val mapRegions: Map<Int, RegionData> = emptyMap(),
        val xteasByRegion: Map<Int, IntArray> = emptyMap(),
        val interfaceManifest: List<InterfaceManifestEntry> = emptyList(),
        val clientScripts: Map<Int, ByteArray> = emptyMap(),
        val models: Map<Int, ModelMeta> = emptyMap(),
        val modelSummary: ConfigDiffSummary = ConfigDiffSummary(emptyList(), emptyList(), emptyList()),
    )

    fun readFromFile(file: File): DecodedRev? {
        if (!file.exists()) return null
        return try {
            decode(file.readBytes())
        } catch (_: Exception) {
            null
        }
    }

    fun decode(bytes: ByteArray): DecodedRev {
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { header.get(it) }
        require(String(magic, Charsets.UTF_8) == MAGIC) { "Invalid ORCA magic" }
        val revision = header.int
        val uncompressedSize = header.int
        val compressed = ByteArray(header.remaining()).also { header.get(it) }
        val input = ByteArrayInputStream(Zstd.decompress(compressed, uncompressedSize))

        val configTypes = List(readVarint(input)) { readString(input) }
        val gamevalGroups = List(readVarint(input)) { readString(input) }
        val manifest = readManifest(input, configTypes, gamevalGroups)

        val configs = configTypes.associateWith { readConfigType(input) }
        val gameval = gamevalGroups.associateWith { readGamevalGroup(input) }
        val sprites = HashMap<Int, ByteArray>()
        repeat(readVarint(input)) {
            val id = readVarint(input)
            sprites[id] = readBytes(input, readVarint(input))
            readBytes(input, SPRITE_SHA_LEN) // stored SHA-256; recomputed from pixels on import
        }
        // Object placements were stored both per object id and per region; the region map is enough.
        repeat(readVarint(input)) {
            readVarint(input)
            repeat(readVarint(input)) { readLocation(input) }
        }
        val mapRegions = HashMap<Int, RegionData>()
        repeat(readVarint(input)) {
            val id = readVarint(input)
            mapRegions[id] = readRegion(input, id)
        }
        val xteasByRegion = HashMap<Int, IntArray>()
        repeat(readVarint(input)) {
            val square = readVarint(input)
            xteasByRegion[square] = IntArray(4) { readInt32LE(input) }
        }

        val trailers = Trailers()
        if (input.available() > 0) readTrailers(readBytes(input, input.available()), trailers)

        return DecodedRev(
            revision = revision,
            openRs2CacheId = trailers.openRs2CacheId,
            manifest = manifest,
            configs = configs,
            gameval = gameval,
            sprites = sprites,
            spriteMetadata = trailers.spriteMetadata,
            mapRegions = mapRegions,
            xteasByRegion = xteasByRegion,
            interfaceManifest = trailers.interfaceManifest,
            clientScripts = trailers.clientScripts,
            models = trailers.models,
            modelSummary = trailers.modelSummary,
        )
    }

    private fun readManifest(input: ByteArrayInputStream, configTypes: List<String>, gamevalGroups: List<String>): DiffManifest {
        val revision = readVarint(input)
        val sprites = SpriteDiffSummary(readIntList(input), readIntList(input), readIntList(input))
        val configs = configTypes.associateWith { ConfigDiffSummary(readIntList(input), readIntList(input), readIntList(input)) }
        val gamevals = gamevalGroups.associateWith { ConfigDiffSummary(readIntList(input), readIntList(input), readIntList(input)) }
        return DiffManifest(revision, sprites, configs, gamevals)
    }

    private fun readConfigType(input: ByteArrayInputStream): Map<Int, DefinitionSnapshot> {
        val count = readVarint(input)
        val out = HashMap<Int, DefinitionSnapshot>(count)
        repeat(count) {
            val id = readSVarint(input)
            val fieldCount = readVarint(input)
            val snapshot = LinkedHashMap<String, FieldEntry>(fieldCount)
            repeat(fieldCount) { snapshot[readString(input)] = readFieldEntry(input) }
            out[id] = snapshot
        }
        return out
    }

    private fun readGamevalGroup(input: ByteArrayInputStream): Map<Int, GamevalExtra> {
        val count = readVarint(input)
        val out = HashMap<Int, GamevalExtra>(count)
        repeat(count) {
            val id = readVarint(input)
            val searchable = readString(input)
            val text = readString(input)
            val sub = HashMap<Int, String>()
            repeat(readVarint(input)) { sub[readVarint(input)] = readString(input) }
            out[id] = GamevalExtra(searchable, text, sub)
        }
        return out
    }

    private fun readRegion(input: ByteArrayInputStream, id: Int): RegionData {
        val totalObjects = readVarint(input)
        val positions = List(readVarint(input)) { readLocation(input) }
        fun tiles(): Map<Int, List<Int>> {
            val count = readVarint(input)
            val out = HashMap<Int, List<Int>>(count)
            repeat(count) {
                val tileId = readVarint(input)
                out[tileId] = List(readVarint(input)) { readVarint(input) }
            }
            return out
        }
        return RegionData(id, positions, totalObjects, tiles(), tiles())
    }

    private class Trailers {
        val spriteMetadata = HashMap<Int, List<IndexedSpriteMeta>>()
        val interfaceManifest = ArrayList<InterfaceManifestEntry>()
        val clientScripts = HashMap<Int, ByteArray>()
        val models = HashMap<Int, ModelMeta>()
        var modelSummary = ConfigDiffSummary(emptyList(), emptyList(), emptyList())
        var openRs2CacheId: Long? = null
    }

    private fun readTrailers(trailer: ByteArray, out: Trailers) {
        val input = ByteArrayInputStream(trailer)
        var sawMarker = false
        while (input.available() >= 4) {
            when (String(readBytes(input, 4), Charsets.UTF_8)) {
                TRAILER_SPRITE_META -> {
                    sawMarker = true
                    repeat(readVarint(input)) {
                        val id = readVarint(input)
                        out.spriteMetadata[id] = List(readVarint(input)) {
                            IndexedSpriteMeta(
                                offsetX = readSVarint(input),
                                offsetY = readSVarint(input),
                                width = readVarint(input),
                                height = readVarint(input),
                                averageColor = readSVarint(input),
                                subHeight = readVarint(input),
                                subWidth = readVarint(input),
                                hasAlpha = (input.read() == 1).also { has -> if (has) readString(input) },
                            )
                        }
                    }
                }
                TRAILER_SPRITE_RASTER -> {
                    // Raster and palette are regenerated from the PNG; skip over them.
                    sawMarker = true
                    repeat(readVarint(input)) {
                        readVarint(input)
                        repeat(readVarint(input)) {
                            readString(input)
                            repeat(readVarint(input)) { readInt32LE(input) }
                        }
                    }
                }
                TRAILER_OPENRS2_ID -> {
                    sawMarker = true
                    if (input.available() >= 8) out.openRs2CacheId = readInt64LE(input)
                }
                TRAILER_INTERFACE_MANIFEST -> {
                    sawMarker = true
                    repeat(readVarint(input)) {
                        val interfaceId = readVarint(input)
                        val gameval = if (input.read() == 1) readString(input) else null
                        val iflegacy = when (input.read()) {
                            1 -> true
                            0 -> false
                            else -> null
                        }
                        out.interfaceManifest.add(InterfaceManifestEntry(interfaceId, gameval, iflegacy))
                    }
                }
                TRAILER_CLIENT_SCRIPTS -> {
                    sawMarker = true
                    repeat(readVarint(input)) {
                        val scriptId = readVarint(input)
                        out.clientScripts[scriptId] = readBytes(input, readVarint(input))
                    }
                }
                TRAILER_MODEL_META -> {
                    sawMarker = true
                    out.modelSummary = ConfigDiffSummary(readIntList(input), readIntList(input), readIntList(input))
                    out.models.putAll(ModelJson.decode(readString(input)))
                }
                else -> {
                    // Binaries written before markers existed ended with a raw 8-byte OpenRS2 id.
                    if (!sawMarker && trailer.size >= 8) {
                        out.openRs2CacheId = readInt64LE(ByteArrayInputStream(trailer.copyOfRange(0, 8)))
                    }
                    return
                }
            }
        }
        if (!sawMarker && out.openRs2CacheId == null && trailer.size >= 8) {
            out.openRs2CacheId = readInt64LE(ByteArrayInputStream(trailer.copyOfRange(0, 8)))
        }
    }

    private const val TAG_NULL = 0
    private const val TAG_INT = 1
    private const val TAG_BOOL = 2
    private const val TAG_STRING = 3
    private const val TAG_LONG = 4
    private const val TAG_DOUBLE = 5
    private const val TAG_INT_LIST = 6
    private const val TAG_STR_LIST = 7
    private const val TAG_PARAMS_MAP = 8
    private const val TAG_JSON_BLOB = 9

    private fun readFieldEntry(input: ByteArrayInputStream): FieldEntry {
        val value: Any? = when (input.read()) {
            TAG_NULL -> null
            TAG_INT -> readSVarint(input)
            TAG_BOOL -> input.read() != 0
            TAG_STRING -> readString(input)
            TAG_LONG -> readSVarint64(input)
            TAG_DOUBLE -> readDouble(input)
            TAG_INT_LIST -> List(readVarint(input)) { readSVarint(input) }
            TAG_STR_LIST -> List(readVarint(input)) { readString(input) }
            TAG_PARAMS_MAP -> {
                val count = readVarint(input)
                val map = LinkedHashMap<Int, FieldEntry>(count)
                repeat(count) { map[readSVarint(input)] = readFieldEntry(input) }
                map
            }
            TAG_JSON_BLOB -> gson.fromJson(readString(input), Any::class.java)
            else -> null
        }
        val ref = if (input.read() == 1) GamevalRef(readString(input), readSVarint(input), readString(input)) else null
        return FieldEntry(value, ref)
    }

    private fun readLocation(input: ByteArrayInputStream) = LocationCustom(
        id = readVarint(input),
        type = readVarint(input),
        orientation = readVarint(input),
        position = readVarint(input),
        isDynamic = input.read() == 1,
    )

    private fun readVarint(input: ByteArrayInputStream): Int {
        var result = 0
        var shift = 0
        var b: Int
        do {
            b = input.read()
            result = result or ((b and 0x7F) shl shift)
            shift += 7
        } while (b and 0x80 != 0)
        return result
    }

    /** Signed ZigZag LEB128, used for field values that can be negative. */
    private fun readSVarint(input: ByteArrayInputStream): Int {
        val n = readVarint(input)
        return (n ushr 1) xor -(n and 1)
    }

    private fun readSVarint64(input: ByteArrayInputStream): Long {
        var result = 0L
        var shift = 0
        var b: Int
        do {
            b = input.read()
            result = result or ((b.toLong() and 0x7F) shl shift)
            shift += 7
        } while (b and 0x80 != 0)
        return (result ushr 1) xor -(result and 1)
    }

    private fun readString(input: ByteArrayInputStream): String =
        String(readBytes(input, readVarint(input)), Charsets.UTF_8)

    private fun readIntList(input: ByteArrayInputStream): List<Int> = List(readVarint(input)) { readVarint(input) }

    private fun readInt32LE(input: ByteArrayInputStream): Int {
        val b = IntArray(4) { input.read() }
        return b[0] or (b[1] shl 8) or (b[2] shl 16) or (b[3] shl 24)
    }

    private fun readInt64LE(input: ByteArrayInputStream): Long {
        var value = 0L
        repeat(8) { index -> value = value or ((input.read().toLong() and 0xFF) shl (index * 8)) }
        return value
    }

    private fun readDouble(input: ByteArrayInputStream): Double {
        var bits = 0L
        repeat(8) { i -> bits = bits or ((input.read().toLong() and 0xFF) shl (i * 8)) }
        return java.lang.Double.longBitsToDouble(bits)
    }

    private fun readBytes(input: ByteArrayInputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n <= 0) break
            read += n
        }
        return buf
    }
}
