package dev.openrune.ingest

import dev.openrune.TestDatabase
import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import dev.openrune.db.Database
import dev.openrune.metrics.Metrics
import dev.openrune.model.EntityKind
import dev.openrune.model.EntitySnapshot
import dev.openrune.model.EntityTypeDef
import dev.openrune.model.RevisionStatus
import dev.openrune.query.EntityQueries
import dev.openrune.query.RevisionCatalog
import dev.openrune.store.GameRegistry
import dev.openrune.store.RevisionRepository
import dev.openrune.store.VersionWriter
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionPipelineTest {
    private lateinit var db: Database
    private lateinit var game: GameRegistry.RegisteredGame
    private lateinit var revisions: RevisionRepository
    private lateinit var pipeline: IngestionPipeline
    private lateinit var catalog: RevisionCatalog
    private lateinit var entities: EntityQueries

    private class FakeSource(override val rev: Int, private val data: Map<String, List<EntitySnapshot>>, private val failOn: String? = null) : RevisionSource {
        override val typeKeys: List<String> = data.keys.toList()
        override fun snapshots(typeKey: String): Iterator<EntitySnapshot> {
            if (typeKey == failOn) throw IllegalStateException("decoder exploded on $typeKey")
            return data.getValue(typeKey).iterator()
        }
        override fun close() {}
    }

    @BeforeAll
    fun setUp() {
        db = TestDatabase.fresh()
        game = GameRegistry(db.ingest).register(
            GameType.OLDSCHOOL, CacheEnvironment.LIVE,
            listOf(EntityTypeDef("items", EntityKind.CONFIG, "Items"), EntityTypeDef("npcs", EntityKind.CONFIG, "Npcs")),
        )
        revisions = RevisionRepository(db.ingest)
        catalog = RevisionCatalog(revisions, game.game.id, refreshIntervalMs = 0)
        pipeline = IngestionPipeline(db.ingest, game, revisions, VersionWriter(db.ingest), Metrics(), catalog)
        entities = EntityQueries(db.api, game)
    }

    @AfterAll
    fun tearDown() = db.close()

    private fun snaps(prefix: String, n: Int, version: Int) = (1..n).map { EntitySnapshot.of(it, "$prefix$it", mapOf("v" to version)) }

    private fun ingest(rev: Int, source: RevisionSource) {
        revisions.discover(game.game.id, rev, null, null)
        val run = revisions.startRun(game.game.id, rev)
        pipeline.ingest(rev, run, { source })
    }

    @Test
    fun `publish makes a revision visible and a failed later revision leaves it intact`() {
        ingest(100, FakeSource(100, mapOf("items" to snaps("i", 50, 1), "npcs" to snaps("n", 20, 1))))
        assertEquals(listOf(100), catalog.published())
        assertEquals(RevisionStatus.READY, revisions.get(game.game.id, 100)!!.status)

        // Revision 101 fails while importing its second type: everything it wrote is rolled back.
        assertThrows<IngestionException> {
            ingest(101, FakeSource(101, mapOf("items" to snaps("i", 60, 2), "npcs" to emptyList()), failOn = "npcs"))
        }
        val failed = revisions.get(game.game.id, 101)!!
        assertEquals(RevisionStatus.FAILED, failed.status)
        assertFalse(failed.published)
        assertFalse(failed.hasData)
        assertEquals(listOf(100), catalog.published())
        assertEquals(50, entities.countAt(game.type("items"), 100))
        assertEquals(1, entities.get(game.type("items"), 100, 1)!!.payload!!.asJsonObject["v"].asInt)
        assertEquals(listOf(100 to null), entities.history(game.type("items"), 1).map { it.validFrom to it.validTo })

        // Retrying succeeds and publishes.
        ingest(101, FakeSource(101, mapOf("items" to snaps("i", 60, 2), "npcs" to snaps("n", 20, 1))))
        assertEquals(listOf(100, 101), catalog.published())
        assertEquals(60, entities.countAt(game.type("items"), 101))
        assertEquals(2, entities.get(game.type("items"), 101, 1)!!.payload!!.asJsonObject["v"].asInt)
        assertEquals(20, entities.countAt(game.type("npcs"), 101))
        assertTrue(revisions.get(game.game.id, 101)!!.publishStamp > revisions.get(game.game.id, 100)!!.publishStamp)

        pipeline.unpublish(101)
        assertEquals(listOf(100), catalog.published())
        assertEquals(listOf(100 to null), entities.history(game.type("items"), 1).map { it.validFrom to it.validTo })
    }
}
