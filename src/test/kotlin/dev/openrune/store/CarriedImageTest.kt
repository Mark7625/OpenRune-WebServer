package dev.openrune.store

import dev.openrune.TestDatabase
import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import dev.openrune.db.Database
import dev.openrune.model.CanonicalJson
import dev.openrune.model.EntityKind
import dev.openrune.model.EntitySnapshot
import dev.openrune.model.EntityTypeDef
import dev.openrune.model.Hashing
import dev.openrune.query.EntityQueries
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Rendered item and object images are carried forward by hash when nothing they depend on changed:
 * the snapshot repeats the previous image hash and supplies no bytes. That has to leave the entity
 * looking unchanged rather than removed, and still serve the original image.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CarriedImageTest {
    private lateinit var db: Database
    private lateinit var game: GameRegistry.RegisteredGame
    private lateinit var entities: EntityQueries
    private lateinit var writer: VersionWriter
    private val type by lazy { game.type("item.sprites") }

    private val imageA = "PNG-A".toByteArray()
    private val imageB = "PNG-B".toByteArray()

    /** Mirrors the renderer: the payload carries the image hash, so it moves only when the image does. */
    private fun snapshot(id: Int, image: ByteArray, bytes: ByteArray?): EntitySnapshot {
        val hash = Hashing.hash16(image)
        val json = CanonicalJson.encode(mapOf("image" to Hashing.hex(hash)))
        return EntitySnapshot(
            entityId = id,
            name = "item_$id",
            payloadJson = json,
            payloadHash = Hashing.hash16(json.toByteArray(Charsets.UTF_8)),
            blob = bytes,
            blobHash = hash,
        )
    }

    @BeforeAll
    fun setUp() {
        db = TestDatabase.fresh()
        game = GameRegistry(db.ingest).register(
            GameType.OLDSCHOOL, CacheEnvironment.LIVE,
            listOf(EntityTypeDef("item.sprites", EntityKind.ARCHIVE, "Item images")),
        )
        entities = EntityQueries(db.api, game)
        writer = VersionWriter(db.ingest)

        // rev 1 renders both. rev 2 carries #1 forward with no bytes, re-renders #2, and drops #3.
        writer.writeType(
            game.game.id, type.id, 1, null,
            listOf(snapshot(1, imageA, imageA), snapshot(2, imageA, imageA), snapshot(3, imageA, imageA)).iterator(),
        )
        writer.writeType(
            game.game.id, type.id, 2, null,
            listOf(snapshot(1, imageA, null), snapshot(2, imageB, imageB)).iterator(),
        )
    }

    @AfterAll
    fun tearDown() = db.close()

    @Test
    fun `an image carried forward with no bytes still serves and is not treated as removed`() {
        assertContentEquals(imageA, entities.blobForEntity(type, 2, 1), "carried image should still serve at rev 2")
        // The version row was never closed, so it still points at the revision that rendered it —
        // which is what the CDN resolution relies on.
        assertEquals(1, entities.sourceRevision(type, 2, 1))
        assertEquals(1, entities.sourceRevision(type, 1, 1))
    }

    @Test
    fun `a re-rendered image opens a new version at this revision`() {
        assertContentEquals(imageB, entities.blobForEntity(type, 2, 2))
        assertContentEquals(imageA, entities.blobForEntity(type, 1, 2))
        assertEquals(2, entities.sourceRevision(type, 2, 2))
    }

    @Test
    fun `only the re-rendered image counts as changed, so only it is uploaded`() {
        assertEquals(listOf(2), entities.changedAt(type, 2))
        assertEquals(listOf(1, 2, 3), entities.changedAt(type, 1))
    }

    @Test
    fun `an image left out of the revision is removed`() {
        assertNull(entities.blobForEntity(type, 2, 3))
        assertContentEquals(imageA, entities.blobForEntity(type, 1, 3))
    }
}
