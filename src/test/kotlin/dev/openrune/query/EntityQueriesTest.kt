package dev.openrune.query

import dev.openrune.TestDatabase
import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import dev.openrune.db.Database
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntityQueriesTest {
    private lateinit var db: Database
    private lateinit var game: GameRegistry.RegisteredGame
    private lateinit var entities: EntityQueries

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
        entities = EntityQueries(db.api, game)
        val writer = VersionWriter(db.ingest)
        val items = (1..100).map { id -> EntitySnapshot.of(id, "Item $id", mapOf("name" to "Item $id", "stackable" to (id % 2 == 0))) }
        writer.writeType(game.game.id, game.type("items").id, 1, null, items.iterator())
        val names = (1..100).filter { it % 3 == 0 }.map { id -> EntitySnapshot.of(id, "gv_$id", mapOf("text" to "gv_$id", "sub" to emptyMap<String, String>())) }
        writer.writeType(game.game.id, game.type("gameval.items").id, 1, null, names.iterator())
    }

    @AfterAll
    fun tearDown() = db.close()

    private val type by lazy { game.type("items") }

    @Test
    fun `offset and keyset paging agree and expose gameval names`() {
        val first = entities.page(type, 1, 0, 10)
        assertEquals(100, first.total)
        assertEquals((1..10).toList(), first.rows.map { it.id })
        assertEquals(10, first.nextCursor)
        assertEquals("gv_3", first.rows.first { it.id == 3 }.gameval)
        assertNull(first.rows.first { it.id == 1 }.gameval)

        val second = entities.page(type, 1, 0, 10, afterId = first.nextCursor)
        assertEquals((11..20).toList(), second.rows.map { it.id })
        val offsetSecond = entities.page(type, 1, 10, 10)
        assertEquals(second.rows.map { it.id }, offsetSecond.rows.map { it.id })
    }

    @Test
    fun `search modes`() {
        // "item 1" matches Item 1, Item 10..19 and Item 100 (case-insensitive substring).
        assertEquals(12, entities.page(type, 1, 0, 50, Search(SearchMode.NAME, "item 1")).total)
        assertEquals(listOf(5, 6, 7), entities.page(type, 1, 0, 50, Search(SearchMode.ID, "5+7")).rows.map { it.id })
        assertEquals(listOf(2, 40), entities.page(type, 1, 0, 50, Search(SearchMode.ID, "2,40")).rows.map { it.id })
        assertEquals(listOf(7) + (70..79).toList(), entities.page(type, 1, 0, 50, Search(SearchMode.REGEX, "^item 7")).rows.map { it.id })
        assertEquals(listOf(30, 33, 36, 39), entities.page(type, 1, 0, 50, Search(SearchMode.GAMEVAL, "gv_3")).rows.map { it.id }.filter { it in 30..39 })
        assertEquals(listOf(3), entities.page(type, 1, 0, 50, Search(SearchMode.GAMEVAL, "\"gv_3\"")).rows.map { it.id })
        assertEquals(0, entities.page(type, 1, 0, 50, Search(SearchMode.ID, "abc")).total)
    }

    @Test
    fun `lookup and counts`() {
        assertEquals("Item 42", entities.get(type, 1, 42)!!.payload!!.asJsonObject["name"].asString)
        assertEquals(100, entities.countAt(type, 1))
        assertEquals(33, entities.names(game.type("gameval.items"), 1).size)
        assertEquals(mapOf(type.id to 100, game.type("gameval.items").id to 33), entities.countsAt(game.types, 1))
    }
}
