package dev.openrune.cache.util

import com.google.gson.Gson
import dev.openrune.ServerConfig
import dev.openrune.cache.diff.DiffBinaryCache
import mu.KotlinLogging
import java.io.File

data class Xtea(
    val mapsquare: Int,
    var key: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Xtea

        if (mapsquare != other.mapsquare) return false
        if (!key.contentEquals(other.key)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mapsquare
        result = 31 * result + mapsquare
        result = 31 * result + key.contentHashCode()
        return result
    }
}

object XteaLoader {
    private val logger = KotlinLogging.logger {}

    val xteas: MutableMap<Int, Xtea> = mutableMapOf()
    private val xteasList: MutableMap<Int, IntArray> = mutableMapOf()

    fun load(xteaLocation: File) {
        xteas.clear()
        xteasList.clear()
        
        if (!xteaLocation.exists()) {
            logger.warn("XTEAs file not found: ${xteaLocation.path}")
            return
        }
        
        try {
            val parsed = parseXteas(xteaLocation.readText())
            loadFromRegionKeys(parsed)
            logger.info { "Keys loaded from file: ${xteaLocation.name} (${xteasList.size})" }
        } catch (e: Exception) {
            logger.error("Failed to load XTEAs from ${xteaLocation.path}: ${e.message}", e)
        }
    }

    fun loadFromDiffBinary(config: ServerConfig, rev: Int): Boolean {
        val decoded = DiffBinaryCache.getDecodedRev(config, rev) ?: return false
        val keys = decoded.xteasByRegion
        if (keys.isEmpty()) return false
        loadFromRegionKeys(keys)
        logger.info { "Keys loaded from diff binary rev $rev (${xteasList.size})" }
        return true
    }

    fun parseXteas(jsonText: String): Map<Int, IntArray> {
        val data: Array<Xtea> = Gson().fromJson(jsonText, Array<Xtea>::class.java)
        return data.associate { it.mapsquare to it.key.copyOf() }
    }

    fun loadFromRegionKeys(regionKeys: Map<Int, IntArray>) {
        xteas.clear()
        xteasList.clear()
        regionKeys.forEach { (mapsquare, key) ->
            val normalizedKey = if (key.size == 4) key.copyOf() else key.copyOf(4)
            val xtea = Xtea(mapsquare = mapsquare, key = normalizedKey)
            xteas[mapsquare] = xtea
            xteasList[mapsquare] = normalizedKey.copyOf()
        }
    }

    fun getKeys(region: Int): IntArray? = xteasList[region]

    fun hasKeys(): Boolean = xteasList.isNotEmpty()

}








