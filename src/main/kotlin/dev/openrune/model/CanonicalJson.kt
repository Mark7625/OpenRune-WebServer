package dev.openrune.model

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import dev.openrune.cache.diff.FieldEntry
import java.util.TreeMap

/**
 * Canonical JSON for payload hashing and storage: object keys sorted, null members dropped,
 * integral doubles rendered as integers (the legacy binary format round-trips JSON blobs through
 * Gson, which turns every number into a Double; without this normalisation the same entity would
 * hash differently depending on which import path produced it).
 */
object CanonicalJson {
    private val gson = Gson()

    fun encode(value: Map<String, Any?>): String = toElement(value).toString()

    fun parse(json: String): JsonElement = JsonParser.parseString(json)

    fun toElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull.INSTANCE
        is JsonElement -> canonicalize(value)
        is FieldEntry -> fieldEntry(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Short -> JsonPrimitive(value.toInt())
        is Byte -> JsonPrimitive(value.toInt())
        is Float -> number(value.toDouble())
        is Double -> number(value)
        is Number -> number(value.toDouble())
        is String -> JsonPrimitive(value)
        is Char -> JsonPrimitive(value.toString())
        is Enum<*> -> JsonPrimitive(value.name)
        is IntArray -> JsonArray().also { arr -> value.forEach { arr.add(it) } }
        is LongArray -> JsonArray().also { arr -> value.forEach { arr.add(it) } }
        is ByteArray -> JsonArray().also { arr -> value.forEach { arr.add(it.toInt()) } }
        is ShortArray -> JsonArray().also { arr -> value.forEach { arr.add(it.toInt()) } }
        is BooleanArray -> JsonArray().also { arr -> value.forEach { arr.add(it) } }
        is Array<*> -> JsonArray().also { arr -> value.forEach { arr.add(toElement(it)) } }
        is Iterable<*> -> JsonArray().also { arr -> value.forEach { arr.add(toElement(it)) } }
        is Map<*, *> -> {
            val sorted = TreeMap<String, JsonElement>()
            value.forEach { (k, v) ->
                if (v != null) {
                    val e = toElement(v)
                    if (!e.isJsonNull) sorted[k.toString()] = e
                }
            }
            objectOf(sorted)
        }
        else -> canonicalize(gson.toJsonTree(value))
    }

    /**
     * A serialised FieldEntry without a ref (`{"value": x}`) collapses to its bare value, the form
     * the direct serialiser produces, so both import paths agree.
     */
    private fun objectOf(sorted: TreeMap<String, JsonElement>): JsonElement =
        if (sorted.size == 1 && sorted.containsKey("value")) sorted.getValue("value")
        else JsonObject().also { obj -> sorted.forEach { (k, v) -> obj.add(k, v) } }

    /** Legacy API shape: a bare value, or `{value, ref:{group,id,name}}` when a gameval ref exists. */
    private fun fieldEntry(entry: FieldEntry): JsonElement {
        val ref = entry.ref ?: return toElement(entry.value)
        val obj = JsonObject()
        val refObj = JsonObject()
        refObj.addProperty("group", ref.group)
        refObj.addProperty("id", ref.id)
        refObj.addProperty("name", ref.name)
        obj.add("ref", refObj)
        val v = toElement(entry.value)
        if (!v.isJsonNull) obj.add("value", v)
        return obj
    }

    private fun number(d: Double): JsonElement =
        if (d.isFinite() && d == Math.rint(d) && Math.abs(d) < 9.0e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

    private fun canonicalize(element: JsonElement): JsonElement = when {
        element.isJsonObject -> {
            val sorted = TreeMap<String, JsonElement>()
            element.asJsonObject.entrySet().forEach { (k, v) ->
                val c = canonicalize(v)
                if (!c.isJsonNull) sorted[k] = c
            }
            objectOf(sorted)
        }
        element.isJsonArray -> JsonArray().also { arr -> element.asJsonArray.forEach { arr.add(canonicalize(it)) } }
        element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> {
            val raw = element.asJsonPrimitive.asNumber
            if (raw is Double || raw is Float) number(raw.toDouble()) else JsonPrimitive(raw)
        }
        else -> element
    }
}
