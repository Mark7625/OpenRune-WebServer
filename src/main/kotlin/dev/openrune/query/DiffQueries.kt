package dev.openrune.query

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.openrune.db.query
import dev.openrune.db.withConnection
import dev.openrune.model.ChangeKind
import dev.openrune.store.GameRegistry.RegisteredGame
import dev.openrune.store.GameRegistry.RegisteredType
import javax.sql.DataSource

data class DiffCounts(val added: Int, val changed: Int, val removed: Int) {
    val isEmpty: Boolean get() = added == 0 && changed == 0 && removed == 0
}

class DiffEntry(
    val id: Int,
    val kind: ChangeKind,
    /** Revision the new state started in (added / changed) or the old state ended in (removed). */
    val changedInRev: Int,
    val name: String?,
    val oldPayload: JsonElement?,
    val newPayload: JsonElement?,
)

/**
 * One changed entity with its difference already reduced to what actually changed:
 * the full new payload when [kind] is ADDED, `{field: {from, to}}` when CHANGED, null when REMOVED.
 */
class DiffRow(
    val id: Int,
    val kind: ChangeKind,
    val changedInRev: Int,
    val name: String?,
    val gameval: String?,
    val body: JsonElement?,
)

/**
 * [nextCursor] is authoritative: the query reads one row beyond the page, so null means there is
 * genuinely nothing after this page rather than "ask again to find out".
 */
class DiffPage(val rows: List<DiffRow>, val offset: Int, val limit: Int, val nextCursor: Int?) {
    val hasMore: Boolean get() = nextCursor != null
}

/**
 * Changes between two published revisions, computed from validity ranges: versions that ended in
 * (a, b] are the old states, versions that started in (a, b] and are still valid at b are the new
 * states. Cost is proportional to the number of changes, not to the revisions in between.
 */
class DiffQueries(private val dataSource: DataSource, private val game: RegisteredGame) {

    private val gameId = game.game.id

    private companion object {
        /** Old state at `a` that is gone by `b`, full-outer-joined with the new state at `b`. */
        const val DIFF_CORE = """
            WITH old AS (
                SELECT entity_id, payload_hash, name, valid_to FROM entity_version
                WHERE game_id = ? AND type_id = ? AND valid_from <= ? AND valid_to > ? AND valid_to <= ?
            ),
            new AS (
                SELECT entity_id, payload_hash, name, valid_from FROM entity_version
                WHERE game_id = ? AND type_id = ? AND valid_from > ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)
            ),
            joined AS (
                SELECT COALESCE(o.entity_id, n.entity_id) AS entity_id,
                       CASE WHEN o.entity_id IS NULL THEN 'ADDED' WHEN n.entity_id IS NULL THEN 'REMOVED' ELSE 'CHANGED' END AS kind,
                       o.payload_hash AS old_hash, n.payload_hash AS new_hash,
                       COALESCE(n.name, o.name) AS name,
                       COALESCE(n.valid_from, o.valid_to) AS changed_in
                FROM old o FULL OUTER JOIN new n ON n.entity_id = o.entity_id
                WHERE o.entity_id IS NULL OR n.entity_id IS NULL OR o.payload_hash <> n.payload_hash
            )
        """
    }

    private fun coreParams(typeId: Int, a: Int, b: Int): Array<Any> =
        arrayOf(gameId, typeId, a, a, b, gameId, typeId, a, b, b)

    /**
     * One page of changes, reduced to what actually differs.
     *
     * `body` holds the new payload for an added entity, and for a changed entity only the changed
     * fields as `{field: {from, to}}`. The reduction happens here rather than in SQL: computing it
     * with `jsonb_each` + `jsonb_object_agg` was measured at twice the cost of parsing both bodies
     * and comparing them (2,184 ms against 1,014 ms over 34,607 entities), so the database just
     * returns the two bodies for the page.
     *
     * What makes this fast is the `LIMIT` inside the CTE: the rows whose bodies get fetched and
     * compared are only the ones being returned, so cost tracks page size and not the number of
     * changes. A first page of 100 is ~29 ms for a base-to-tip diff and ~0.1 ms for adjacent
     * revisions, against ~1,000 ms to reduce the whole set.
     */
    fun changesPage(
        type: RegisteredType,
        a: Int,
        b: Int,
        kinds: Set<ChangeKind> = ChangeKind.entries.toSet(),
        offset: Int = 0,
        limit: Int = 50,
        afterId: Int? = null,
    ): DiffPage {
        require(kinds.isNotEmpty()) { "At least one change kind is required" }
        val gamevalType = type.def.gamevalGroup?.let { game.typeOrNull("gameval.$it")?.id }
        val params = ArrayList<Any?>()
        params.addAll(coreParams(type.id, a, b))

        // Enum names are a closed set, so an inline list is safe and avoids a driver array round trip.
        val filters = StringBuilder("kind IN (${kinds.joinToString(", ") { "'${it.name}'" }})")
        if (afterId != null) {
            filters.append(" AND entity_id > ?")
            params.add(afterId)
        }
        params.add(limit + 1) // one extra row, trimmed below, so nextCursor is exact
        if (afterId == null) params.add(offset)

        // A removed entity has no gameval at `b`, so fall back to the one it had at `a` — the same
        // precedence `/content` applies when it merges the two name maps. The second join is only
        // worth its cost on pages that can contain removals.
        val gamevalAtBase = gamevalType != null && ChangeKind.REMOVED in kinds
        fun gamevalJoin(alias: String, rev: Int): String {
            params.add(gameId); params.add(gamevalType); params.add(rev); params.add(rev)
            return " LEFT JOIN entity_version $alias ON $alias.game_id = ? AND $alias.type_id = ?" +
                " AND $alias.entity_id = j.entity_id" +
                " AND $alias.valid_from <= ? AND ($alias.valid_to IS NULL OR $alias.valid_to > ?)"
        }

        val gamevalSelect: String
        val gamevalJoins: String
        if (gamevalType == null) {
            gamevalSelect = "NULL::text"
            gamevalJoins = ""
        } else {
            gamevalSelect = if (gamevalAtBase) "COALESCE(gn.name, go.name)" else "gn.name"
            gamevalJoins = gamevalJoin("gn", b) + if (gamevalAtBase) gamevalJoin("go", a) else ""
        }

        val sql = """
            $DIFF_CORE,
            page AS (
                SELECT * FROM joined WHERE $filters ORDER BY entity_id LIMIT ?${if (afterId == null) " OFFSET ?" else ""}
            )
            SELECT j.entity_id, j.kind, j.changed_in, j.name, $gamevalSelect AS gameval,
                   po.body::text AS old_body, pn.body::text AS new_body
            FROM page j
            LEFT JOIN entity_payload po ON po.hash = j.old_hash
            LEFT JOIN entity_payload pn ON pn.hash = j.new_hash$gamevalJoins
            ORDER BY j.entity_id
        """

        val fetched = dataSource.withConnection { c ->
            c.query(sql, *params.toTypedArray()) { rs ->
                val kind = ChangeKind.valueOf(rs.getString(2))
                val oldBody = rs.getString(6)
                val newBody = rs.getString(7)
                DiffRow(
                    id = rs.getInt(1),
                    kind = kind,
                    changedInRev = rs.getInt(3),
                    name = rs.getString(4),
                    gameval = rs.getString(5),
                    body = when (kind) {
                        ChangeKind.ADDED -> newBody?.let { JsonParser.parseString(it) }
                        ChangeKind.REMOVED -> null
                        ChangeKind.CHANGED -> changedFieldsOf(oldBody, newBody)
                    },
                )
            }
        }
        val rows = if (fetched.size > limit) fetched.subList(0, limit) else fetched
        return DiffPage(rows, offset, limit, nextCursor = if (fetched.size > limit) rows.last().id else null)
    }

    private fun changedFieldsOf(oldBody: String?, newBody: String?): JsonObject? {
        if (oldBody == null || newBody == null) return null
        return changedFields(
            JsonParser.parseString(oldBody).asJsonObject,
            JsonParser.parseString(newBody).asJsonObject,
        )
    }

    fun counts(type: RegisteredType, a: Int, b: Int): DiffCounts = dataSource.withConnection { c ->
        var added = 0; var changed = 0; var removed = 0
        c.query("$DIFF_CORE SELECT kind, count(*) FROM joined GROUP BY kind", *coreParams(type.id, a, b)) { rs ->
            when (rs.getString(1)) {
                "ADDED" -> added = rs.getInt(2)
                "CHANGED" -> changed = rs.getInt(2)
                "REMOVED" -> removed = rs.getInt(2)
            }
        }
        DiffCounts(added, changed, removed)
    }

    /** Counts for many types with one statement per type on one connection. */
    fun countsForTypes(types: Collection<RegisteredType>, a: Int, b: Int): Map<Int, DiffCounts> =
        types.associate { it.id to counts(it, a, b) }

    /** Ids grouped by kind, ascending, with the revision each change happened in. */
    fun ids(type: RegisteredType, a: Int, b: Int): List<DiffEntry> = dataSource.withConnection { c ->
        c.query("$DIFF_CORE SELECT entity_id, kind, changed_in, name FROM joined ORDER BY entity_id", *coreParams(type.id, a, b)) { rs ->
            DiffEntry(rs.getInt(1), ChangeKind.valueOf(rs.getString(2)), rs.getInt(3), rs.getString(4), null, null)
        }
    }

    /**
     * Changes with payloads, streamed in id order. Old and new bodies are joined for changed
     * entities so the caller can render field-level differences without a second query.
     */
    fun forEachEntry(type: RegisteredType, a: Int, b: Int, consumer: (DiffEntry) -> Unit) {
        dataSource.withConnection { c ->
            c.autoCommit = false
            try {
                c.prepareStatement(
                    """$DIFF_CORE
                       SELECT j.entity_id, j.kind, j.changed_in, j.name, po.body::text, pn.body::text
                       FROM joined j
                       LEFT JOIN entity_payload po ON po.hash = j.old_hash
                       LEFT JOIN entity_payload pn ON pn.hash = j.new_hash
                       ORDER BY j.entity_id""",
                ).use { st ->
                    st.fetchSize = 500
                    coreParams(type.id, a, b).forEachIndexed { i, v -> st.setObject(i + 1, v) }
                    st.executeQuery().use { rs ->
                        while (rs.next()) {
                            consumer(
                                DiffEntry(
                                    id = rs.getInt(1),
                                    kind = ChangeKind.valueOf(rs.getString(2)),
                                    changedInRev = rs.getInt(3),
                                    name = rs.getString(4),
                                    oldPayload = rs.getString(5)?.let { JsonParser.parseString(it) },
                                    newPayload = rs.getString(6)?.let { JsonParser.parseString(it) },
                                ),
                            )
                        }
                    }
                }
            } finally {
                c.autoCommit = true
            }
        }
    }
}
