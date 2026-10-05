package dev.openrune.query

import com.google.gson.JsonObject

/**
 * Comparing two payloads field by field.
 *
 * This lives in one place because two call paths need it: a whole-set diff streams both payloads
 * and reduces them in one pass, while a paged diff reduces only the page it returns. Doing the
 * reduction in SQL with `jsonb_each` was measured at twice the cost (2,184 ms against 1,014 ms
 * over 34,607 entities), so both paths compare here.
 */

/** Names of the fields that differ. */
fun changedFieldNames(old: JsonObject, new: JsonObject): Set<String> {
    val out = LinkedHashSet<String>()
    old.keySet().forEach { key -> if (old[key] != new[key]) out.add(key) }
    new.keySet().forEach { key -> if (key !in out && old[key] != new[key]) out.add(key) }
    return out
}

/**
 * `{field: {from, to}}` for the fields that differ. A member is omitted when that side had no such
 * field, which is how the website distinguishes "field did not exist" from "field became empty";
 * stored payloads never contain JSON nulls, so an omitted member is unambiguous.
 */
fun changedFields(old: JsonObject, new: JsonObject): JsonObject {
    val out = JsonObject()
    changedFieldNames(old, new).forEach { key ->
        val entry = JsonObject()
        old[key]?.let { entry.add("from", it) }
        new[key]?.let { entry.add("to", it) }
        out.add(key, entry)
    }
    return out
}
