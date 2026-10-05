package dev.openrune.store

import dev.openrune.TestDatabase
import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import dev.openrune.db.Database
import dev.openrune.model.ChangeKind
import dev.openrune.model.EntityKind
import dev.openrune.model.EntitySnapshot
import dev.openrune.model.EntityTypeDef
import dev.openrune.query.DiffQueries
import dev.openrune.query.EntityQueries
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VersionWriterTest {
    private lateinit var db: Database
    private lateinit var game: GameRegistry.RegisteredGame
    private lateinit var writer: VersionWriter
    private lateinit var entities: EntityQueries
    private lateinit var diffs: DiffQueries
    private val type by lazy { game.type("things") }

    @BeforeAll
    fun setUp() {
        db = TestDatabase.fresh()
        game = GameRegistry(db.ingest).register(
            GameType.OLDSCHOOL, CacheEnvironment.LIVE,
            listOf(EntityTypeDef("things", EntityKind.CONFIG, "Things"), EntityTypeDef("gameval.things", EntityKind.GAMEVAL, "Names")),
        )
        writer = VersionWriter(db.ingest)
        entities = EntityQueries(db.api, game)
        diffs = DiffQueries(db.api, game)
    }

    @AfterAll
    fun tearDown() = db.close()

    private fun snap(id: Int, x: Int, name: String? = "e$id") = EntitySnapshot.of(id, name, mapOf("x" to x, "id" to id))

    private fun write(rev: Int, next: Int?, vararg snaps: EntitySnapshot) =
        writer.writeType(game.game.id, type.id, rev, next, snaps.iterator())

    private fun stateAt(rev: Int): Map<Int, Int> =
        entities.page(type, rev, 0, 100, withPayload = true).rows.associate { it.id to it.payload!!.asJsonObject["x"].asInt }

    @Test
    fun `append detects added changed removed and reverted entities`() {
        val r1 = write(1, null, snap(1, 1), snap(2, 1), snap(3, 1))
        assertEquals(3, r1.added); assertEquals(0, r1.changed); assertEquals(0, r1.removed)

        val r2 = write(2, null, snap(1, 2), snap(2, 1), snap(4, 1))
        assertEquals(1, r2.added); assertEquals(1, r2.changed); assertEquals(1, r2.removed)
        assertEquals(2, r2.payloadsWritten, "payloads for the changed entity 1 and the new entity 4")

        val r3 = write(3, null, snap(1, 1), snap(2, 1), snap(4, 1))
        assertEquals(0, r3.added); assertEquals(1, r3.changed); assertEquals(0, r3.removed)

        assertEquals(mapOf(1 to 1, 2 to 1, 3 to 1), stateAt(1))
        assertEquals(mapOf(1 to 2, 2 to 1, 4 to 1), stateAt(2))
        assertEquals(mapOf(1 to 1, 2 to 1, 4 to 1), stateAt(3))

        val d12 = diffs.ids(type, 1, 2).associate { it.id to it.kind }
        assertEquals(mapOf(1 to ChangeKind.CHANGED, 3 to ChangeKind.REMOVED, 4 to ChangeKind.ADDED), d12)

        // 1 -> 3: entity 1 reverted to its rev 1 content, so it is not a change.
        val d13 = diffs.ids(type, 1, 3).associate { it.id to it.kind }
        assertEquals(mapOf(3 to ChangeKind.REMOVED, 4 to ChangeKind.ADDED), d13)
        assertEquals(1, diffs.counts(type, 1, 3).added)

        val history = entities.history(type, 1)
        assertEquals(listOf(1 to 2, 2 to 3, 3 to null), history.map { it.validFrom to it.validTo })
    }

    @Test
    fun `rollback of an appended revision restores the previous state`() {
        val t = game.type("gameval.things")
        fun w(rev: Int, next: Int?, vararg s: EntitySnapshot) = writer.writeType(game.game.id, t.id, rev, next, s.iterator())
        w(10, null, snap(1, 1), snap(2, 1))
        w(11, null, snap(1, 5), snap(3, 1))
        writer.rollback(game.game.id, 11, null)
        assertFalse(writer.hasRowsFor(game.game.id, 11))
        val at10 = entities.page(t, 10, 0, 10, withPayload = true).rows.associate { it.id to it.payload!!.asJsonObject["x"].asInt }
        assertEquals(mapOf(1 to 1, 2 to 1), at10)
        assertEquals(listOf(10 to null), entities.history(t, 1).map { it.validFrom to it.validTo })
    }

    @Test
    fun `out of order insert splits covering versions and rolls back cleanly`() {
        val g = GameRegistry(db.ingest).register(GameType.OLDSCHOOL, CacheEnvironment.BETA, listOf(EntityTypeDef("things", EntityKind.CONFIG, "Things")))
        val t = g.type("things")
        val q = EntityQueries(db.api, g)
        val d = DiffQueries(db.api, g)
        fun w(rev: Int, next: Int?, vararg s: EntitySnapshot) = writer.writeType(g.game.id, t.id, rev, next, s.iterator())
        fun state(rev: Int) = q.page(t, rev, 0, 10, withPayload = true).rows.associate { it.id to it.payload!!.asJsonObject["x"].asInt }

        w(1, null, snap(1, 1), snap(2, 1), snap(5, 1))
        w(3, null, snap(1, 1), snap(2, 3), snap(6, 1))
        // Insert rev 2 between: entity 1 differs at 2 only, entity 2 equals rev 1, entity 5 absent at 2, entity 7 only at 2.
        val r = w(2, 3, snap(1, 9), snap(2, 1), snap(7, 1))
        assertEquals(1, r.added); assertEquals(1, r.changed); assertEquals(1, r.removed)

        assertEquals(mapOf(1 to 1, 2 to 1, 5 to 1), state(1))
        assertEquals(mapOf(1 to 9, 2 to 1, 7 to 1), state(2))
        assertEquals(mapOf(1 to 1, 2 to 3, 6 to 1), state(3))
        assertEquals(mapOf(2 to ChangeKind.CHANGED, 5 to ChangeKind.REMOVED, 6 to ChangeKind.ADDED), d.ids(t, 1, 3).associate { it.id to it.kind })
        assertEquals(mapOf(1 to ChangeKind.CHANGED, 2 to ChangeKind.CHANGED, 6 to ChangeKind.ADDED, 7 to ChangeKind.REMOVED), d.ids(t, 2, 3).associate { it.id to it.kind })

        writer.rollback(g.game.id, 2, 3)
        assertFalse(writer.hasRowsFor(g.game.id, 2))
        assertEquals(mapOf(1 to 1, 2 to 1, 5 to 1), state(1))
        assertEquals(mapOf(1 to 1, 2 to 3, 6 to 1), state(3))
        assertEquals(listOf(1 to null), q.history(t, 1).map { it.validFrom to it.validTo })
        assertEquals(listOf(1 to 3), q.history(t, 5).map { it.validFrom to it.validTo })
    }

    @Test
    fun `identical content across entities shares one payload row`() {
        val g = GameRegistry(db.ingest).register(GameType.RUNESCAPE, CacheEnvironment.LIVE, listOf(EntityTypeDef("things", EntityKind.CONFIG, "Things")))
        val t = g.type("things")
        val same = mapOf("x" to 1)
        val r = writer.writeType(g.game.id, t.id, 1, null, listOf(EntitySnapshot.of(1, null, same), EntitySnapshot.of(2, null, same)).iterator())
        assertEquals(2, r.added)
        assertEquals(1, r.payloadsWritten)
        assertTrue(EntityQueries(db.api, g).get(t, 1, 1)!!.payloadHash.contentEquals(EntityQueries(db.api, g).get(t, 1, 2)!!.payloadHash))
        assertNull(EntityQueries(db.api, g).get(t, 1, 3))
    }
}
