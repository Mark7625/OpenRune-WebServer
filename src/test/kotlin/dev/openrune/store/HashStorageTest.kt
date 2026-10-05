package dev.openrune.store

import dev.openrune.TestDatabase
import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import dev.openrune.model.EntityKind
import dev.openrune.model.EntitySnapshot
import dev.openrune.model.EntityTypeDef
import dev.openrune.query.EntityQueries
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HashStorageTest {
    @Test
    fun `hashes and blobs round-trip through COPY as raw bytes`() {
        val db = TestDatabase.fresh()
        db.use {
            val game = GameRegistry(db.ingest).register(GameType.OLDSCHOOL, CacheEnvironment.LIVE, listOf(EntityTypeDef("t", EntityKind.ARCHIVE, "T")))
            val type = game.type("t")
            val blob = byteArrayOf(0, 1, 2, 9, 92, 120, 10, 13, -1) // includes backslash, 'x', newline, CR
            val snap = EntitySnapshot.of(7, "x\ty\\z", mapOf("k" to "v\\w\n"), blob = blob)
            VersionWriter(db.ingest).writeType(game.game.id, type.id, 1, null, listOf(snap).iterator())
            val q = EntityQueries(db.api, game)
            val row = q.get(type, 1, 7)!!
            assertEquals(16, row.payloadHash.size)
            assertContentEquals(snap.payloadHash, row.payloadHash)
            assertEquals("x\ty\\z", row.name)
            assertEquals("v\\w\n", row.payload!!.asJsonObject["k"].asString)
            assertContentEquals(blob, q.blobForEntity(type, 1, 7))
        }
    }
}
