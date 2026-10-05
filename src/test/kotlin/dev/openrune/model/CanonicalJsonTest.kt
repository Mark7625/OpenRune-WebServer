package dev.openrune.model

import com.google.gson.Gson
import dev.openrune.cache.diff.FieldEntry
import dev.openrune.cache.diff.GamevalRef
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CanonicalJsonTest {

    @Test
    fun `keys are sorted and nulls dropped`() {
        val json = CanonicalJson.encode(linkedMapOf("z" to 1, "a" to null, "m" to listOf(3, 2)))
        assertEquals("""{"m":[3,2],"z":1}""", json)
    }

    @Test
    fun `field entries render as bare values or value plus ref`() {
        val json = CanonicalJson.encode(
            linkedMapOf(
                "plain" to FieldEntry(5),
                "ref" to FieldEntry(7, GamevalRef("items", 7, "bronze_sword")),
                "none" to FieldEntry(null),
            ),
        )
        assertEquals("""{"plain":5,"ref":{"ref":{"group":"items","id":7,"name":"bronze_sword"},"value":7}}""", json)
    }

    @Test
    fun `gson round trip through doubles hashes the same as the direct value`() {
        val direct = CanonicalJson.encode(mapOf("ops" to mapOf("0" to "Take", "n" to 3), "list" to listOf(1, 2)))
        val roundTripped = Gson().fromJson(Gson().toJson(mapOf("ops" to mapOf("0" to "Take", "n" to 3), "list" to listOf(1, 2))), Any::class.java)
        @Suppress("UNCHECKED_CAST")
        assertEquals(direct, CanonicalJson.encode(roundTripped as Map<String, Any?>))
    }

    @Test
    fun `serialised field entry without ref collapses to its value`() {
        val fromBin = Gson().fromJson("""{"values":{"a":{"value":1.0},"b":{"value":2.0,"ref":{"group":"g","id":2,"name":"n"}}}}""", Any::class.java)
        @Suppress("UNCHECKED_CAST")
        val json = CanonicalJson.encode(fromBin as Map<String, Any?>)
        assertEquals("""{"values":{"a":1,"b":{"ref":{"group":"g","id":2,"name":"n"},"value":2}}}""", json)
    }
}
