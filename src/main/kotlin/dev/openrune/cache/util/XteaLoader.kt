package dev.openrune.cache.util

import com.google.gson.Gson

private data class XteaEntry(val mapsquare: Int, val key: IntArray)

/** Parses OpenRS2 `keys.json` into region id -> 128-bit XTEA key, for revisions whose maps are encrypted. */
object XteaLoader {
    private val gson = Gson()

    fun parseXteas(jsonText: String): Map<Int, IntArray> =
        gson.fromJson(jsonText, Array<XteaEntry>::class.java)
            .associate { it.mapsquare to normalize(it.key) }

    fun normalize(key: IntArray): IntArray = if (key.size == 4) key.copyOf() else key.copyOf(4)
}
