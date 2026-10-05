package dev.openrune.query

import dev.openrune.TestDatabase
import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import dev.openrune.db.Database
import dev.openrune.model.ChangeKind
import dev.openrune.model.EntityKind
import dev.openrune.model.EntitySnapshot
import dev.openrune.model.EntityTypeDef
import dev.openrune.store.GameRegistry
import dev.openrune.store.VersionWriter
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiffQueriesTest {
    private lateinit var db: Database
    private lateinit var game: GameRegistry.RegisteredGame
    private lateinit var diffs: DiffQueries
    private lateinit var entities: EntityQueries
    private val type by lazy { game.type("items") }

    @BeforeAll
    fun setUp() {
        db = TestDatabase.fresh()
        game = GameRegistry(db.ingest).register(
            GameType.OLDSCHOOL, CacheEnvironment.LIVE,
            listOf(
                EntityTypeDef("items", EntityKind.CONFIG, "Items", gamevalGroup = "items"),
                EntityTypeDef("gameval.items", EntityKind.GAMEVAL, "Item names"),
            ),
        )
        diffs = DiffQueries(db.api, game)
        entities = EntityQueries(db.api, game)
        val writer = VersionWriter(db.ingest)

        // rev 1: three items. rev 2: #1 changes a field, #2 loses a field and gains one,
        // #3 is removed, #4 is added, #5 is added then its value matches nothing earlier.
        writer.writeType(
            game.game.id, type.id, 1, null,
            listOf(
                EntitySnapshot.of(1, "Sword", mapOf("name" to "Sword", "cost" to 10, "ops" to listOf("Wield"))),
                EntitySnapshot.of(2, "Shield", mapOf("name" to "Shield", "cost" to 20, "legacy" to true)),
                EntitySnapshot.of(3, "Gone", mapOf("name" to "Gone", "cost" to 30)),
            ).iterator(),
        )
        writer.writeType(
            game.game.id, type.id, 2, null,
            listOf(
                EntitySnapshot.of(1, "Sword", mapOf("name" to "Sword", "cost" to 15, "ops" to listOf("Wield"))),
                EntitySnapshot.of(2, "Shield", mapOf("name" to "Shield", "cost" to 20, "members" to true)),
                EntitySnapshot.of(4, "New", mapOf("name" to "New", "cost" to 40)),
            ).iterator(),
        )
        // #3's gameval disappears alongside the entity itself, so it only exists at rev 1.
        val gamevals = game.type("gameval.items").id
        writer.writeType(
            game.game.id, gamevals, 1, null,
            listOf(
                EntitySnapshot.of(1, "sword", mapOf("text" to "sword", "sub" to emptyMap<String, String>())),
                EntitySnapshot.of(3, "gone", mapOf("text" to "gone", "sub" to emptyMap<String, String>())),
            ).iterator(),
        )
        writer.writeType(
            game.game.id, gamevals, 2, null,
            listOf(EntitySnapshot.of(1, "sword", mapOf("text" to "sword", "sub" to emptyMap<String, String>()))).iterator(),
        )
    }

    @AfterAll
    fun tearDown() = db.close()

    @Test
    fun `the paged diff and the whole-set diff agree field for field`() {
        val paged = diffs.changesPage(type, 1, 2, limit = 100).rows.associateBy { it.id }

        // `/content` streams both payloads and reduces in one pass; `/changes` reduces per page.
        // Both must produce the same `{field: {from, to}}` for every changed entity.
        val wholeSet = HashMap<Int, String>()
        diffs.forEachEntry(type, 1, 2) { e ->
            if (e.kind == ChangeKind.CHANGED) {
                wholeSet[e.id] = changedFields(e.oldPayload!!.asJsonObject, e.newPayload!!.asJsonObject).toString()
            }
        }

        assertEquals(setOf(1, 2), wholeSet.keys)
        wholeSet.forEach { (id, expected) ->
            assertEquals(expected, paged.getValue(id).body!!.toString(), "changed fields for id $id")
        }
    }

    @Test
    fun `absent fields are omitted rather than sent as null`() {
        val row = diffs.changesPage(type, 1, 2, limit = 100).rows.first { it.id == 2 }
        val body = row.body!!.asJsonObject
        assertEquals(setOf("legacy", "members"), body.keySet())
        // `legacy` existed only before, `members` only after: each side keeps exactly one member.
        assertEquals(setOf("from"), body["legacy"].asJsonObject.keySet())
        assertEquals(setOf("to"), body["members"].asJsonObject.keySet())
        assertTrue(body["legacy"].asJsonObject["from"].asBoolean)
        assertTrue(body["members"].asJsonObject["to"].asBoolean)
    }

    @Test
    fun `added rows carry the new payload, removed rows carry nothing`() {
        val rows = diffs.changesPage(type, 1, 2, limit = 100).rows.associateBy { it.id }
        val added = rows.getValue(4)
        assertEquals(ChangeKind.ADDED, added.kind)
        assertEquals("New", added.body!!.asJsonObject["name"].asString)
        assertEquals(2, added.changedInRev)

        val removed = rows.getValue(3)
        assertEquals(ChangeKind.REMOVED, removed.kind)
        assertNull(removed.body)
    }

    @Test
    fun `paging and kind filtering agree with the unpaged id list`() {
        val all = diffs.ids(type, 1, 2).map { it.id }
        assertEquals(listOf(1, 2, 3, 4), all)

        val firstPage = diffs.changesPage(type, 1, 2, offset = 0, limit = 2)
        assertEquals(listOf(1, 2), firstPage.rows.map { it.id })
        assertEquals(2, firstPage.nextCursor)

        // Exactly fills the page but nothing follows, so the cursor is null without a probe request.
        val byCursor = diffs.changesPage(type, 1, 2, limit = 2, afterId = firstPage.nextCursor)
        assertEquals(listOf(3, 4), byCursor.rows.map { it.id })
        assertNull(byCursor.nextCursor)

        val byOffset = diffs.changesPage(type, 1, 2, offset = 2, limit = 2)
        assertEquals(byCursor.rows.map { it.id }, byOffset.rows.map { it.id })

        assertEquals(listOf(4), diffs.changesPage(type, 1, 2, kinds = setOf(ChangeKind.ADDED), limit = 10).rows.map { it.id })
        assertEquals(listOf(3), diffs.changesPage(type, 1, 2, kinds = setOf(ChangeKind.REMOVED), limit = 10).rows.map { it.id })
        assertEquals(listOf(1, 2), diffs.changesPage(type, 1, 2, kinds = setOf(ChangeKind.CHANGED), limit = 10).rows.map { it.id })

        val counts = diffs.counts(type, 1, 2)
        assertEquals(DiffCounts(added = 1, changed = 2, removed = 1), counts)
    }

    @Test
    fun `gameval name is attached when the type has one`() {
        val rows = diffs.changesPage(type, 1, 2, limit = 100).rows.associateBy { it.id }
        assertEquals("sword", rows.getValue(1).gameval)
        assertNull(rows.getValue(4).gameval)
        // #3 was removed, so its name only exists at the base revision — the page must still carry
        // it, or the website would label the entry with a generated fallback instead.
        assertEquals("gone", rows.getValue(3).gameval)

        // Pages that cannot contain removals skip the base-revision lookup entirely.
        val changedOnly = diffs.changesPage(type, 1, 2, kinds = setOf(ChangeKind.CHANGED), limit = 100)
        assertEquals("sword", changedOnly.rows.first { it.id == 1 }.gameval)
    }
}
