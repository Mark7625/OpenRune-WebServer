package dev.openrune.cache.diff

import com.github.luben.zstd.Zstd
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Binary format for one revision's typed cache snapshot.
 *
 * File: cache/{gameType}/{env}/diffs/{rev}.bin
 * Magic: "ORCA" (OpenRune Cache Archive)
 * Header (uncompressed, 12 bytes): magic(4) + revision(4 LE) + bodyLen(4 LE)
 * Body: ZSTD-compressed blob containing:
 *   - Schema  : ordered config type names + gameval group names
 *   - Manifest: revision + sprite/config/gameval summaries
 *   - Configs : per type → id(Int) → field snapshots
 *   - Gamevals: per group → id → searchable/text/sub
 *   - Sprites : id → PNG bytes + SHA-256
 *   - Map data: objects / regions / xteas
 *
 * FieldEntry binary codec:
 *   [1-byte type tag] [value payload] [1-byte has_ref] [if has_ref: group(str) refId(svarint) refName(str)]
 *
 * Type tags:
 *   0=NULL  1=INT(svarint)  2=BOOL  3=STRING  4=LONG(svarint)  5=DOUBLE(8b LE)
 *   6=INT_LIST  7=STR_LIST  8=PARAMS_MAP(Map<Int,FieldEntry>)  9=JSON_BLOB(string)
 */
object CacheBinaryFormat {

    private const val MAGIC = "ORCA"
    private const val SPRITE_SHA_LEN = 32

    private val gson = Gson()

    data class GamevalExtra(
        val searchable: String,
        val text: String,
        val sub: Map<Int, String> = emptyMap(),
    )

    data class DecodedRev(
        val revision: Int,
        val openRs2CacheId: Long? = null,
        val manifest: DiffManifest,
        val configs: Map<String, Map<Int, DefinitionSnapshot>>,
        val gameval: Map<String, Map<Int, GamevalExtra>> = emptyMap(),
        val sprites: Map<Int, ByteArray> = emptyMap(),
        val spriteSha256: Map<Int, ByteArray> = emptyMap(),
        val mapObjects: Map<Int, List<LocationCustom>> = emptyMap(),
        val mapRegions: Map<Int, RegionData> = emptyMap(),
        val xteasByRegion: Map<Int, IntArray> = emptyMap(),
    )

    fun encode(
        revision: Int,
        openRs2CacheId: Long? = null,
        manifest: DiffManifest,
        configs: Map<String, Map<Int, DefinitionSnapshot>>,
        gameval: Map<String, Map<Int, GamevalExtra>> = emptyMap(),
        sprites: Map<Int, ByteArray> = emptyMap(),
        mapObjects: Map<Int, List<LocationCustom>> = emptyMap(),
        mapRegions: Map<Int, RegionData> = emptyMap(),
        xteasByRegion: Map<Int, IntArray> = emptyMap(),
    ): ByteArray {
        val body = ByteArrayOutputStream()
        val configTypes = ConfigDiffType.diffTypeNames
        val gamevalGroups = gameval.keys.sorted()

        // Schema
        writeVarint(body, configTypes.size)
        configTypes.forEach { writeString(body, it) }
        writeVarint(body, gamevalGroups.size)
        gamevalGroups.forEach { writeString(body, it) }

        // Manifest
        writeVarint(body, manifest.revision)
        writeIntList(body, manifest.sprites.added)
        writeIntList(body, manifest.sprites.removed)
        writeIntList(body, manifest.sprites.changed)
        configTypes.forEach { type ->
            val s = manifest.configs[type] ?: ConfigDiffSummary(emptyList(), emptyList(), emptyList())
            writeIntList(body, s.added); writeIntList(body, s.removed); writeIntList(body, s.changed)
        }
        gamevalGroups.forEach { group ->
            val s = manifest.gamevals[group] ?: ConfigDiffSummary(emptyList(), emptyList(), emptyList())
            writeIntList(body, s.added); writeIntList(body, s.removed); writeIntList(body, s.changed)
        }

        // Configs
        configTypes.forEach { type ->
            val idMap = configs[type] ?: emptyMap()
            writeVarint(body, idMap.size)
            idMap.entries.sortedBy { it.key }.forEach { (id, snapshot) ->
                writeSVarint(body, id)
                writeVarint(body, snapshot.size)
                snapshot.forEach { (fieldName, entry) ->
                    writeString(body, fieldName)
                    writeFieldEntry(body, entry)
                }
            }
        }

        // Gamevals
        gamevalGroups.forEach { group ->
            val extras = gameval[group] ?: emptyMap()
            writeVarint(body, extras.size)
            extras.entries.sortedBy { it.key }.forEach { (id, extra) ->
                writeVarint(body, id)
                writeString(body, extra.searchable)
                writeString(body, extra.text)
                writeVarint(body, extra.sub.size)
                extra.sub.entries.sortedBy { it.key }.forEach { (subId, name) ->
                    writeVarint(body, subId)
                    writeString(body, name)
                }
            }
        }

        // Sprites
        val spriteIds = sprites.keys.sorted()
        writeVarint(body, spriteIds.size)
        spriteIds.forEach { id ->
            val png = sprites[id] ?: ByteArray(0)
            writeVarint(body, id)
            writeVarint(body, png.size)
            body.write(png)
            body.write(sha256(png))
        }

        // Map objects
        val objectIds = mapObjects.keys.sorted()
        writeVarint(body, objectIds.size)
        objectIds.forEach { oid ->
            writeVarint(body, oid)
            val locs = mapObjects[oid].orEmpty()
            writeVarint(body, locs.size)
            locs.forEach { writeLocation(body, it) }
        }

        // Map regions
        val regionIds = mapRegions.keys.sorted()
        writeVarint(body, regionIds.size)
        regionIds.forEach { rid ->
            writeVarint(body, rid)
            val region = mapRegions[rid] ?: RegionData(rid)
            writeVarint(body, region.totalObjects)
            writeVarint(body, region.positions.size)
            region.positions.forEach { writeLocation(body, it) }
            val overlays = region.overlayIds.toSortedMap()
            writeVarint(body, overlays.size)
            overlays.forEach { (oid, positions) ->
                writeVarint(body, oid); writeVarint(body, positions.size)
                positions.forEach { writeVarint(body, it) }
            }
            val underlays = region.underlayIds.toSortedMap()
            writeVarint(body, underlays.size)
            underlays.forEach { (uid, positions) ->
                writeVarint(body, uid); writeVarint(body, positions.size)
                positions.forEach { writeVarint(body, it) }
            }
        }

        // Xteas
        val squares = xteasByRegion.keys.sorted()
        writeVarint(body, squares.size)
        squares.forEach { sq ->
            writeVarint(body, sq)
            val key = xteasByRegion[sq] ?: IntArray(4)
            val norm = if (key.size == 4) key else key.copyOf(4)
            repeat(4) { writeInt32LE(body, norm[it]) }
        }

        // Optional source OpenRS2 cache id trailer for boot-time freshness checks.
        if (openRs2CacheId != null) {
            writeInt64LE(body, openRs2CacheId)
        }

        val bodyBytes = body.toByteArray()
        val compressed = Zstd.compress(bodyBytes)
        val header = ByteBuffer.allocate(4 + 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        header.put(MAGIC.toByteArray(Charsets.UTF_8))
        header.putInt(revision)
        header.putInt(bodyBytes.size)
        return header.array() + compressed
    }


    fun decode(bytes: ByteArray): DecodedRev {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4); bb.get(magic)
        if (String(magic, Charsets.UTF_8) != MAGIC) throw IllegalArgumentException("Invalid ORCA magic")
        val revision = bb.int
        val uncompressedSize = bb.int
        val compressed = ByteArray(bb.remaining()); bb.get(compressed)
        val bodyBytes = Zstd.decompress(compressed, uncompressedSize)
        val input = ByteArrayInputStream(bodyBytes)

        // Schema
        val configTypes = List(readVarint(input)) { readString(input) }
        val gamevalGroups = List(readVarint(input)) { readString(input) }

        // Manifest
        val manifestRev = readVarint(input)
        val spriteAdded = readIntList(input)
        val spriteRemoved = readIntList(input)
        val spriteChanged = readIntList(input)
        val configSummaries = HashMap<String, ConfigDiffSummary>()
        configTypes.forEach { type ->
            configSummaries[type] = ConfigDiffSummary(readIntList(input), readIntList(input), readIntList(input))
        }
        val gamevalSummaries = HashMap<String, ConfigDiffSummary>()
        gamevalGroups.forEach { group ->
            gamevalSummaries[group] = ConfigDiffSummary(readIntList(input), readIntList(input), readIntList(input))
        }
        val manifest = DiffManifest(
            revision = manifestRev,
            sprites = SpriteDiffSummary(spriteAdded, spriteRemoved, spriteChanged),
            configs = configSummaries,
            gamevals = gamevalSummaries,
        )

        // Configs
        val configs = HashMap<String, Map<Int, DefinitionSnapshot>>()
        configTypes.forEach { type ->
            val idCount = readVarint(input)
            val idMap = HashMap<Int, DefinitionSnapshot>(idCount)
            repeat(idCount) {
                val id = readSVarint(input)
                val fieldCount = readVarint(input)
                val snapshot = LinkedHashMap<String, FieldEntry>(fieldCount)
                repeat(fieldCount) {
                    val fieldName = readString(input)
                    snapshot[fieldName] = readFieldEntry(input)
                }
                idMap[id] = snapshot
            }
            configs[type] = idMap
        }

        // Gamevals
        val gameval = HashMap<String, Map<Int, GamevalExtra>>()
        gamevalGroups.forEach { group ->
            val count = readVarint(input)
            val entries = HashMap<Int, GamevalExtra>(count)
            repeat(count) {
                val id = readVarint(input)
                val searchable = readString(input)
                val text = readString(input)
                val subCount = readVarint(input)
                val sub = HashMap<Int, String>(subCount)
                repeat(subCount) { sub[readVarint(input)] = readString(input) }
                entries[id] = GamevalExtra(searchable, text, sub)
            }
            gameval[group] = entries
        }

        // Sprites
        val spriteCount = readVarint(input)
        val sprites = HashMap<Int, ByteArray>(spriteCount)
        val spriteSha256 = HashMap<Int, ByteArray>(spriteCount)
        repeat(spriteCount) {
            val id = readVarint(input)
            val len = readVarint(input)
            sprites[id] = readBytes(input, len)
            spriteSha256[id] = readBytes(input, SPRITE_SHA_LEN)
        }

        // Map objects
        val objectCount = readVarint(input)
        val mapObjects = HashMap<Int, List<LocationCustom>>(objectCount)
        repeat(objectCount) {
            val oid = readVarint(input)
            val locCount = readVarint(input)
            mapObjects[oid] = List(locCount) { readLocation(input) }
        }

        // Map regions
        val regionCount = readVarint(input)
        val mapRegions = HashMap<Int, RegionData>(regionCount)
        repeat(regionCount) {
            val rid = readVarint(input)
            val totalObjects = readVarint(input)
            val positions = List(readVarint(input)) { readLocation(input) }
            val overlayCount = readVarint(input)
            val overlays = HashMap<Int, List<Int>>(overlayCount)
            repeat(overlayCount) {
                val oid = readVarint(input)
                overlays[oid] = List(readVarint(input)) { readVarint(input) }
            }
            val underlayCount = readVarint(input)
            val underlays = HashMap<Int, List<Int>>(underlayCount)
            repeat(underlayCount) {
                val uid = readVarint(input)
                underlays[uid] = List(readVarint(input)) { readVarint(input) }
            }
            mapRegions[rid] = RegionData(rid, positions, totalObjects, overlays, underlays)
        }

        // Xteas
        val xteasCount = readVarint(input)
        val xteasByRegion = HashMap<Int, IntArray>(xteasCount)
        repeat(xteasCount) {
            val sq = readVarint(input)
            xteasByRegion[sq] = IntArray(4) { readInt32LE(input) }
        }

        val openRs2CacheId = if (input.available() >= 8) readInt64LE(input) else null

        return DecodedRev(
            revision = revision,
            openRs2CacheId = openRs2CacheId,
            manifest = manifest,
            configs = configs,
            gameval = gameval,
            sprites = sprites,
            spriteSha256 = spriteSha256,
            mapObjects = mapObjects,
            mapRegions = mapRegions,
            xteasByRegion = xteasByRegion,
        )
    }

    fun writeToFile(
        file: File,
        revision: Int,
        openRs2CacheId: Long? = null,
        manifest: DiffManifest,
        configs: Map<String, Map<Int, DefinitionSnapshot>>,
        gameval: Map<String, Map<Int, GamevalExtra>> = emptyMap(),
        sprites: Map<Int, ByteArray> = emptyMap(),
        mapObjects: Map<Int, List<LocationCustom>> = emptyMap(),
        mapRegions: Map<Int, RegionData> = emptyMap(),
        xteasByRegion: Map<Int, IntArray> = emptyMap(),
    ) {
        file.parentFile?.mkdirs()
        file.writeBytes(
            encode(revision, openRs2CacheId, manifest, configs, gameval, sprites, mapObjects, mapRegions, xteasByRegion)
        )
    }

    fun readFromFile(file: File): DecodedRev? {
        if (!file.exists()) return null
        return try { decode(file.readBytes()) } catch (_: Exception) { null }
    }


    private const val TAG_NULL       = 0
    private const val TAG_INT        = 1
    private const val TAG_BOOL       = 2
    private const val TAG_STRING     = 3
    private const val TAG_LONG       = 4
    private const val TAG_DOUBLE     = 5
    private const val TAG_INT_LIST   = 6
    private const val TAG_STR_LIST   = 7
    private const val TAG_PARAMS_MAP = 8
    private const val TAG_JSON_BLOB  = 9

    @Suppress("UNCHECKED_CAST")
    private fun writeFieldEntry(out: ByteArrayOutputStream, entry: FieldEntry) {
        when (val v = entry.value) {
            null -> out.write(TAG_NULL)
            is Int -> { out.write(TAG_INT); writeSVarint(out, v) }
            is Boolean -> { out.write(TAG_BOOL); out.write(if (v) 1 else 0) }
            is String -> { out.write(TAG_STRING); writeString(out, v) }
            is Long -> { out.write(TAG_LONG); writeSVarint64(out, v) }
            is Double -> { out.write(TAG_DOUBLE); writeDouble(out, v) }
            is List<*> -> {
                if (v.isEmpty() || v.all { it is Int }) {
                    out.write(TAG_INT_LIST)
                    writeVarint(out, v.size)
                    (v as List<Int>).forEach { writeSVarint(out, it) }
                } else if (v.all { it is String }) {
                    out.write(TAG_STR_LIST)
                    writeVarint(out, v.size)
                    v.forEach { writeString(out, it?.toString() ?: "") }
                } else {
                    out.write(TAG_JSON_BLOB)
                    writeString(out, gson.toJson(v))
                }
            }
            is Map<*, *> -> {
                val first = v.entries.firstOrNull()
                if (first?.key is Int && (first.value == null || first.value is FieldEntry)) {
                    // params map: Map<Int, FieldEntry>
                    out.write(TAG_PARAMS_MAP)
                    writeVarint(out, v.size)
                    (v as Map<Int, FieldEntry>).entries.sortedBy { it.key }.forEach { (k, fe) ->
                        writeSVarint(out, k)
                        writeFieldEntry(out, fe)
                    }
                } else {
                    out.write(TAG_JSON_BLOB)
                    writeString(out, gson.toJson(v))
                }
            }
            else -> { out.write(TAG_JSON_BLOB); writeString(out, gson.toJson(v)) }
        }
        // gameval ref
        if (entry.ref != null) {
            out.write(1)
            writeString(out, entry.ref.group)
            writeSVarint(out, entry.ref.id)
            writeString(out, entry.ref.name)
        } else {
            out.write(0)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readFieldEntry(input: ByteArrayInputStream): FieldEntry {
        val tag = input.read()
        val value: Any? = when (tag) {
            TAG_NULL      -> null
            TAG_INT       -> readSVarint(input)
            TAG_BOOL      -> input.read() != 0
            TAG_STRING    -> readString(input)
            TAG_LONG      -> readSVarint64(input)
            TAG_DOUBLE    -> readDouble(input)
            TAG_INT_LIST  -> List(readVarint(input)) { readSVarint(input) }
            TAG_STR_LIST  -> List(readVarint(input)) { readString(input) }
            TAG_PARAMS_MAP -> {
                val count = readVarint(input)
                val m = LinkedHashMap<Int, FieldEntry>(count)
                repeat(count) { m[readSVarint(input)] = readFieldEntry(input) }
                m
            }
            TAG_JSON_BLOB -> gson.fromJson(readString(input), Any::class.java)
            else          -> null
        }
        val hasRef = input.read() == 1
        val ref = if (hasRef) GamevalRef(readString(input), readSVarint(input), readString(input)) else null
        return FieldEntry(value, ref)
    }

    private fun writeLocation(out: ByteArrayOutputStream, loc: LocationCustom) {
        writeVarint(out, loc.id)
        writeVarint(out, loc.type)
        writeVarint(out, loc.orientation)
        writeVarint(out, loc.position)
        out.write(if (loc.isDynamic) 1 else 0)
    }

    private fun readLocation(input: ByteArrayInputStream) = LocationCustom(
        id = readVarint(input),
        type = readVarint(input),
        orientation = readVarint(input),
        position = readVarint(input),
        isDynamic = input.read() == 1,
    )

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) { out.write((v and 0x7F) or 0x80); v = v ushr 7 }
        out.write(v and 0x7F)
    }

    private fun readVarint(input: ByteArrayInputStream): Int {
        var result = 0; var shift = 0; var b: Int
        do { b = input.read(); result = result or ((b and 0x7F) shl shift); shift += 7 } while (b and 0x80 != 0)
        return result
    }

    /** Signed ZigZag LEB128 (for field values that can be negative). */
    private fun writeSVarint(out: ByteArrayOutputStream, value: Int) {
        writeVarint(out, (value shl 1) xor (value shr 31))
    }

    private fun readSVarint(input: ByteArrayInputStream): Int {
        val n = readVarint(input)
        return (n ushr 1) xor -(n and 1)
    }

    private fun writeSVarint64(out: ByteArrayOutputStream, value: Long) {
        var v = (value shl 1) xor (value shr 63)
        while (v and 0x7FL.inv() != 0L) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        out.write(v.toInt() and 0x7F)
    }

    private fun readSVarint64(input: ByteArrayInputStream): Long {
        var result = 0L; var shift = 0; var b: Int
        do { b = input.read(); result = result or ((b.toLong() and 0x7F) shl shift); shift += 7 } while (b and 0x80 != 0)
        return (result ushr 1) xor -(result and 1)
    }

    private fun writeString(out: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeVarint(out, bytes.size)
        out.write(bytes)
    }

    private fun readString(input: ByteArrayInputStream): String {
        val len = readVarint(input)
        return String(readBytes(input, len), Charsets.UTF_8)
    }

    private fun writeIntList(out: ByteArrayOutputStream, list: List<Int>) {
        writeVarint(out, list.size); list.forEach { writeVarint(out, it) }
    }

    private fun readIntList(input: ByteArrayInputStream): List<Int> =
        List(readVarint(input)) { readVarint(input) }

    private fun writeInt32LE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
    }

    private fun readInt32LE(input: ByteArrayInputStream): Int {
        val b = IntArray(4) { input.read() }
        return b[0] or (b[1] shl 8) or (b[2] shl 16) or (b[3] shl 24)
    }

    private fun writeInt64LE(out: ByteArrayOutputStream, value: Long) {
        repeat(8) { index -> out.write(((value ushr (index * 8)) and 0xFF).toInt()) }
    }

    private fun readInt64LE(input: ByteArrayInputStream): Long {
        var value = 0L
        repeat(8) { index -> value = value or ((input.read().toLong() and 0xFF) shl (index * 8)) }
        return value
    }

    private fun writeDouble(out: ByteArrayOutputStream, v: Double) {
        val bits = java.lang.Double.doubleToRawLongBits(v)
        repeat(8) { i -> out.write((bits ushr (i * 8)).toInt() and 0xFF) }
    }

    private fun readDouble(input: ByteArrayInputStream): Double {
        var bits = 0L
        repeat(8) { i -> bits = bits or (input.read().toLong() and 0xFF shl (i * 8)) }
        return java.lang.Double.longBitsToDouble(bits)
    }

    private fun readBytes(input: ByteArrayInputStream, len: Int): ByteArray {
        val buf = ByteArray(len); var read = 0
        while (read < len) { val n = input.read(buf, read, len - read); if (n <= 0) break; read += n }
        return buf
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
