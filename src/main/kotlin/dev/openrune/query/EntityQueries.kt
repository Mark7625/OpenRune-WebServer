package dev.openrune.query

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import dev.openrune.db.getIntOrNull
import dev.openrune.db.query
import dev.openrune.db.queryOne
import dev.openrune.db.withConnection
import dev.openrune.model.Hashing
import dev.openrune.store.GameRegistry.RegisteredGame
import dev.openrune.store.GameRegistry.RegisteredType
import javax.sql.DataSource

enum class SearchMode { NAME, ID, REGEX, GAMEVAL }

data class Search(val mode: SearchMode, val query: String)

class EntityRow(
    val id: Int,
    val name: String?,
    val gameval: String?,
    val payloadHash: ByteArray,
    val payload: JsonElement?,
) {
    val hashHex: String get() = Hashing.hex(payloadHash)
}

class EntityPage(val rows: List<EntityRow>, val total: Int, val offset: Int, val limit: Int) {
    val hasMore: Boolean get() = offset + rows.size < total
    val nextCursor: Int? get() = if (hasMore) rows.lastOrNull()?.id else null
}

class VersionRow(val validFrom: Int, val validTo: Int?, val payloadHash: ByteArray, val name: String?)

/** Reads of entities at a revision. Every statement is bounded by the page limit or by one id. */
class EntityQueries(private val dataSource: DataSource, private val game: RegisteredGame) {

    private val gameId = game.game.id

    private fun gamevalTypeId(type: RegisteredType): Int? =
        type.def.gamevalGroup?.let { game.typeOrNull("gameval.$it")?.id }

    /**
     * One page of entities at [rev], ordered by id. [afterId] enables keyset paging; [offset] is
     * kept for the legacy offset API. [withPayload] joins the payload body for the page only.
     */
    fun page(
        type: RegisteredType,
        rev: Int,
        offset: Int,
        limit: Int,
        search: Search? = null,
        afterId: Int? = null,
        withPayload: Boolean = false,
    ): EntityPage {
        val gvType = gamevalTypeId(type)
        val gamevalJoin = if (gvType == null) "" else
            " LEFT JOIN entity_version g ON g.game_id = v.game_id AND g.type_id = ? AND g.entity_id = v.entity_id" +
                " AND g.valid_from <= ? AND (g.valid_to IS NULL OR g.valid_to > ?)"
        val joinParams: List<Any?> = if (gvType == null) emptyList() else listOf(gvType, rev, rev)

        val where = StringBuilder("v.game_id = ? AND v.type_id = ? AND v.valid_from <= ? AND (v.valid_to IS NULL OR v.valid_to > ?)")
        val whereParams = mutableListOf<Any?>(gameId, type.id, rev, rev)
        if (afterId != null) {
            where.append(" AND v.entity_id > ?")
            whereParams.add(afterId)
        }
        if (search != null && !appendSearch(search, gvType != null, where, whereParams)) {
            return EntityPage(emptyList(), 0, offset, limit)
        }

        // The gameval join is 1:0..1 (one version of an entity covers a revision), so it cannot
        // change count(*) — only a gameval *filter* needs it. Leaving it out of the count is the
        // difference between one index range scan and one joined lookup per matching row.
        val countNeedsJoin = gvType != null && search?.mode == SearchMode.GAMEVAL
        val countFrom = if (countNeedsJoin) "entity_version v$gamevalJoin" else "entity_version v"
        val countParams = if (countNeedsJoin) joinParams + whereParams else whereParams

        val gvSelect = if (gvType != null) "g.name" else "NULL::text"
        return dataSource.withConnection { c ->
            val total = c.queryOne("SELECT count(*) FROM $countFrom WHERE $where", *countParams.toTypedArray()) { it.getInt(1) } ?: 0
            val inner = "SELECT v.entity_id, v.name, $gvSelect AS gameval, v.payload_hash FROM entity_version v$gamevalJoin" +
                " WHERE $where ORDER BY v.entity_id " + (if (afterId == null) "OFFSET ? " else "") + "LIMIT ?"
            val pageSql = if (withPayload) {
                "SELECT v.entity_id, v.name, v.gameval, v.payload_hash, p.body::text FROM ($inner) v JOIN entity_payload p ON p.hash = v.payload_hash ORDER BY v.entity_id"
            } else inner
            val pageParams = joinParams + whereParams + listOfNotNull(if (afterId == null) offset else null, limit)
            val rows = c.query(pageSql, *pageParams.toTypedArray()) { rs ->
                EntityRow(
                    id = rs.getInt(1),
                    name = rs.getString(2),
                    gameval = rs.getString(3),
                    payloadHash = rs.getBytes(4),
                    payload = if (withPayload) JsonParser.parseString(rs.getString(5)) else null,
                )
            }
            EntityPage(rows, total, offset, limit)
        }
    }

    /** Appends the search predicate; returns false when the search can match nothing. */
    private fun appendSearch(search: Search, hasGameval: Boolean, where: StringBuilder, params: MutableList<Any?>): Boolean {
        val q = search.query.trim()
        if (q.isEmpty()) return true
        when (search.mode) {
            // A name query is a literal substring and ids are plain digits, so a query with no
            // digit cannot match one. Dropping the id predicate in that case keeps the planner on
            // an index instead of forcing a scan to evaluate the OR.
            SearchMode.NAME -> {
                val pattern = "%" + escapeLike(q) + "%"
                if (q.any { it.isDigit() }) {
                    where.append(" AND (v.name ILIKE ? OR v.entity_id::text LIKE ?)")
                    params.add(pattern); params.add(pattern)
                } else {
                    where.append(" AND v.name ILIKE ?")
                    params.add(pattern)
                }
            }
            // Regexes keep both sides: `\d` or `.` contain no literal digit yet still match ids,
            // so "could this match a number" is not decidable from the pattern text.
            SearchMode.REGEX -> {
                where.append(" AND (v.name ~* ? OR v.entity_id::text ~ ?)")
                params.add(q); params.add(q)
            }
            SearchMode.ID -> {
                val ids = IdSearch.parse(q) ?: return false
                where.append(" AND v.entity_id = ANY(?)")
                params.add(ids)
            }
            SearchMode.GAMEVAL -> {
                val tokens = GamevalTokens.parse(q)
                if (tokens.isEmpty()) return true
                val column = if (hasGameval) "g.name" else "v.name"
                where.append(" AND (")
                tokens.forEachIndexed { i, token ->
                    if (i > 0) where.append(" OR ")
                    if (token.exact) {
                        where.append("lower($column) = ?"); params.add(token.raw.lowercase())
                    } else {
                        where.append("$column ILIKE ?"); params.add("%" + escapeLike(token.raw) + "%")
                    }
                }
                where.append(")")
            }
        }
        return true
    }

    fun get(type: RegisteredType, rev: Int, id: Int): EntityRow? {
        val gvType = gamevalTypeId(type)
        return dataSource.withConnection { c ->
            c.queryOne(
                """SELECT v.entity_id, v.name, v.payload_hash, p.body::text,
                          ${if (gvType != null) "(SELECT g.name FROM entity_version g WHERE g.game_id = v.game_id AND g.type_id = ? AND g.entity_id = v.entity_id AND g.valid_from <= ? AND (g.valid_to IS NULL OR g.valid_to > ?))" else "NULL"}
                   FROM entity_version v JOIN entity_payload p ON p.hash = v.payload_hash
                   WHERE v.game_id = ? AND v.type_id = ? AND v.entity_id = ? AND v.valid_from <= ? AND (v.valid_to IS NULL OR v.valid_to > ?)""",
                *(listOfNotNull(gvType, gvType?.let { rev }, gvType?.let { rev }) + listOf(gameId, type.id, id, rev, rev)).toTypedArray(),
            ) { rs ->
                EntityRow(rs.getInt(1), rs.getString(2), rs.getString(5), rs.getBytes(3), JsonParser.parseString(rs.getString(4)))
            }
        }
    }

    fun payload(hash: ByteArray): JsonElement? = dataSource.withConnection { c ->
        c.queryOne("SELECT body::text FROM entity_payload WHERE hash = ?", hash) { JsonParser.parseString(it.getString(1)) }
    }

    fun blob(hash: ByteArray): ByteArray? = dataSource.withConnection { c ->
        c.queryOne("SELECT bytes FROM entity_blob WHERE hash = ?", hash) { it.getBytes(1) }
    }

    fun blobForEntity(type: RegisteredType, rev: Int, id: Int): ByteArray? = dataSource.withConnection { c ->
        c.queryOne(
            """SELECT b.bytes FROM entity_version v JOIN entity_blob b ON b.hash = v.blob_hash
               WHERE v.game_id = ? AND v.type_id = ? AND v.entity_id = ? AND v.valid_from <= ? AND (v.valid_to IS NULL OR v.valid_to > ?)""",
            gameId, type.id, id, rev, rev,
        ) { it.getBytes(1) }
    }

    fun history(type: RegisteredType, id: Int): List<VersionRow> = dataSource.withConnection { c ->
        c.query(
            "SELECT valid_from, valid_to, payload_hash, name FROM entity_version WHERE game_id = ? AND type_id = ? AND entity_id = ? ORDER BY valid_from",
            gameId, type.id, id,
        ) { rs -> VersionRow(rs.getInt(1), rs.getIntOrNull("valid_to"), rs.getBytes(3), rs.getString(4)) }
    }

    fun countAt(type: RegisteredType, rev: Int): Int = dataSource.withConnection { c ->
        c.queryOne(
            "SELECT count(*) FROM entity_version WHERE game_id = ? AND type_id = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)",
            gameId, type.id, rev, rev,
        ) { it.getInt(1) } ?: 0
    }

    /** Counts for several types in one statement (the support manifest). */
    fun countsAt(types: Collection<RegisteredType>, rev: Int): Map<Int, Int> = dataSource.withConnection { c ->
        c.query(
            """SELECT type_id, count(*) FROM entity_version
               WHERE game_id = ? AND type_id = ANY(?) AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?) GROUP BY type_id""",
            gameId, types.map { it.id }.toTypedArray(), rev, rev,
        ) { rs -> rs.getInt(1) to rs.getInt(2) }.toMap()
    }

    /** All ids of a type at [rev], ascending. Bounded by the type's size; used for sprite id lists. */
    fun ids(type: RegisteredType, rev: Int): IntArray = dataSource.withConnection { c ->
        c.query(
            "SELECT entity_id FROM entity_version WHERE game_id = ? AND type_id = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?) ORDER BY entity_id",
            gameId, type.id, rev, rev,
        ) { it.getInt(1) }.toIntArray()
    }

    /** `id -> valid_from` of the version current at [rev]: the revision each entity last changed in. */
    fun sourceRevisions(type: RegisteredType, rev: Int): Map<Int, Int> = dataSource.withConnection { c ->
        c.query(
            "SELECT entity_id, valid_from FROM entity_version WHERE game_id = ? AND type_id = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?) ORDER BY entity_id",
            gameId, type.id, rev, rev,
        ) { rs -> rs.getInt(1) to rs.getInt(2) }.toMap()
    }

    /**
     * The revision one entity's content last changed in, at or before [rev] — the `valid_from` of
     * the version covering [rev]. A new version row only exists where content changed, so this is
     * exactly the revision whose CDN path holds the asset.
     */
    fun sourceRevision(type: RegisteredType, rev: Int, id: Int): Int? = dataSource.withConnection { c ->
        c.queryOne(
            """SELECT valid_from FROM entity_version
               WHERE game_id = ? AND type_id = ? AND entity_id = ? AND valid_from <= ?
                 AND (valid_to IS NULL OR valid_to > ?)""",
            gameId, type.id, id, rev, rev,
        ) { it.getInt(1) }
    }

    /**
     * Whether the set of entities for a type differs at [rev] from the revision before it — any
     * addition, change or removal. A zip of the whole set only needs rebuilding when this is true;
     * otherwise it is byte for byte the previous revision's.
     *
     * Removals leave no row with `valid_from = rev`, only a `valid_to = rev`, so both are checked.
     */
    fun setChangedAt(type: RegisteredType, rev: Int): Boolean = dataSource.withConnection { c ->
        c.queryOne(
            """SELECT EXISTS (
                   SELECT 1 FROM entity_version
                   WHERE game_id = ? AND type_id = ? AND (valid_from = ? OR valid_to = ?)
               )""",
            gameId, type.id, rev, rev,
        ) { it.getBoolean(1) } ?: false
    }

    /** Entity ids whose content changed exactly at [rev] — what a CDN upload for [rev] must carry. */
    fun changedAt(type: RegisteredType, rev: Int): List<Int> = dataSource.withConnection { c ->
        c.query(
            "SELECT entity_id FROM entity_version WHERE game_id = ? AND type_id = ? AND valid_from = ? ORDER BY entity_id",
            gameId, type.id, rev,
        ) { it.getInt(1) }
    }

    /** `id -> blob hash` for a whole type at [rev]; lets a re-ingest reuse a blob it already has. */
    fun blobHashes(type: RegisteredType, rev: Int): Map<Int, ByteArray> = dataSource.withConnection { c ->
        val out = HashMap<Int, ByteArray>(8192)
        c.query(
            """SELECT entity_id, blob_hash FROM entity_version
               WHERE game_id = ? AND type_id = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)
                 AND blob_hash IS NOT NULL""",
            gameId, type.id, rev, rev,
        ) { rs -> out[rs.getInt(1)] = rs.getBytes(2) }
        out
    }

    /** `id -> payload hash` for a whole type at [rev]; used by validation tooling. */
    fun hashes(type: RegisteredType, rev: Int): Map<Int, ByteArray> = dataSource.withConnection { c ->
        val out = LinkedHashMap<Int, ByteArray>()
        c.query(
            "SELECT entity_id, payload_hash FROM entity_version WHERE game_id = ? AND type_id = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?) ORDER BY entity_id",
            gameId, type.id, rev, rev,
        ) { rs -> out[rs.getInt(1)] = rs.getBytes(2) }
        out
    }

    /** `id -> name` for a whole type at [rev]; gameval groups and other name-only listings. */
    fun names(type: RegisteredType, rev: Int): Map<Int, String> = dataSource.withConnection { c ->
        val out = LinkedHashMap<Int, String>()
        c.query(
            "SELECT entity_id, name FROM entity_version WHERE game_id = ? AND type_id = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?) AND name IS NOT NULL ORDER BY entity_id",
            gameId, type.id, rev, rev,
        ) { rs -> out[rs.getInt(1)] = rs.getString(2) }
        out
    }

    /** Blob bytes for a whole type at [rev], streamed to [consumer] in id order (sprite PNGs, scripts). */
    fun forEachBlob(type: RegisteredType, rev: Int, consumer: (id: Int, bytes: ByteArray) -> Unit) {
        dataSource.withConnection { c ->
            c.autoCommit = false
            try {
                c.prepareStatement(
                    """SELECT v.entity_id, b.bytes FROM entity_version v JOIN entity_blob b ON b.hash = v.blob_hash
                       WHERE v.game_id = ? AND v.type_id = ? AND v.valid_from <= ? AND (v.valid_to IS NULL OR v.valid_to > ?)
                       ORDER BY v.entity_id""",
                ).use { st ->
                    st.fetchSize = 200
                    st.setInt(1, gameId); st.setInt(2, type.id); st.setInt(3, rev); st.setInt(4, rev)
                    st.executeQuery().use { rs ->
                        while (rs.next()) consumer(rs.getInt(1), rs.getBytes(2))
                    }
                }
            } finally {
                c.autoCommit = true
            }
        }
    }

    /** Payload bodies for a whole type at [rev], streamed to [consumer] in id order. */
    fun forEachPayload(type: RegisteredType, rev: Int, consumer: (id: Int, name: String?, body: JsonElement) -> Unit) {
        dataSource.withConnection { c ->
            c.autoCommit = false
            try {
                c.prepareStatement(
                    """SELECT v.entity_id, v.name, p.body::text FROM entity_version v JOIN entity_payload p ON p.hash = v.payload_hash
                       WHERE v.game_id = ? AND v.type_id = ? AND v.valid_from <= ? AND (v.valid_to IS NULL OR v.valid_to > ?) ORDER BY v.entity_id""",
                ).use { st ->
                    st.fetchSize = 500
                    st.setInt(1, gameId); st.setInt(2, type.id); st.setInt(3, rev); st.setInt(4, rev)
                    st.executeQuery().use { rs ->
                        while (rs.next()) consumer(rs.getInt(1), rs.getString(2), JsonParser.parseString(rs.getString(3)))
                    }
                }
            } finally {
                c.autoCommit = true
            }
        }
    }

    /** Entities whose `entity_ref` rows include [refId] at [rev] (map regions placing an object). */
    fun referencing(type: RegisteredType, rev: Int, refId: Int): List<EntityRow> = dataSource.withConnection { c ->
        c.query(
            """SELECT v.entity_id, v.name, v.payload_hash, p.body::text
               FROM entity_ref r
               JOIN entity_version v ON v.game_id = r.game_id AND v.type_id = r.type_id AND v.entity_id = r.entity_id AND v.valid_from = r.valid_from
               JOIN entity_payload p ON p.hash = v.payload_hash
               WHERE r.game_id = ? AND r.type_id = ? AND r.ref_id = ? AND v.valid_from <= ? AND (v.valid_to IS NULL OR v.valid_to > ?)
               ORDER BY v.entity_id""",
            gameId, type.id, refId, rev, rev,
        ) { rs -> EntityRow(rs.getInt(1), rs.getString(2), null, rs.getBytes(3), JsonParser.parseString(rs.getString(4))) }
    }

    private fun escapeLike(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

/** Id search syntax shared with the website: `12`, `1,2,3`, `10+20` (range), `10..20`, `10-20`. */
object IdSearch {
    fun parse(query: String): Array<Int>? {
        val ids = LinkedHashSet<Int>()
        for (part in query.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val range = when {
                part.contains('+') -> part.split('+', limit = 2)
                part.contains("..") -> part.split("..", limit = 2)
                part.indexOf('-') > 0 -> part.split('-', limit = 2)
                else -> null
            }
            if (range == null) {
                part.toIntOrNull()?.let { ids.add(it) }
                continue
            }
            val a = range[0].trim().toIntOrNull() ?: continue
            val b = range[1].trim().toIntOrNull() ?: continue
            val lo = minOf(a, b)
            val hi = maxOf(a, b)
            if (hi - lo > 200_000) return null
            for (i in lo..hi) ids.add(i)
        }
        return if (ids.isEmpty()) null else ids.toTypedArray()
    }
}

class GamevalToken(val raw: String, val exact: Boolean)

object GamevalTokens {
    private val quoted = Regex("\"([^\"]+)\"")

    fun parse(input: String): List<GamevalToken> {
        val s = input.trim()
        if (s.isEmpty()) return emptyList()
        val tokens = ArrayList<GamevalToken>()
        quoted.findAll(s).forEach { m ->
            m.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { tokens.add(GamevalToken(it, true)) }
        }
        s.replace(quoted, " ").split(',', '\n', '\r', '\t', ' ').forEach { part ->
            part.trim().takeIf { it.isNotEmpty() }?.let { tokens.add(GamevalToken(it, false)) }
        }
        return tokens.distinctBy { it.raw.lowercase() to it.exact }
    }
}
