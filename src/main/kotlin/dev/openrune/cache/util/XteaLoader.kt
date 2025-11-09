package dev.openrune.cache.util

import com.google.gson.Gson
import mu.KotlinLogging
import java.io.File

data class Xtea(
    val archive: Int,
    val name_hash: Long,
    val name: String,
    val mapsquare: Int,
    var key: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Xtea

        if (archive != other.archive) return false
        if (name_hash != other.name_hash) return false
        if (name != other.name) return false
        if (mapsquare != other.mapsquare) return false
        if (!key.contentEquals(other.key)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = archive
        result = 31 * result + name_hash.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + mapsquare
        result = 31 * result + key.contentHashCode()
        return result
    }
}

object XteaLoader {
    private val logger = KotlinLogging.logger {}
    
    public val xteas: MutableMap<Int, Xtea> = mutableMapOf()
    private val xteasList: MutableMap<Int, IntArray> = mutableMapOf()

    fun load(xteaLocation: File) {
        xteas.clear()
        xteasList.clear()
        
        if (!xteaLocation.exists()) {
            logger.warn("XTEAs file not found: ${xteaLocation.path}")
            return
        }
        
        try {
            val data: Array<Xtea> = Gson().fromJson(xteaLocation.readText(), Array<Xtea>::class.java)
            data.forEach {
                xteas[it.mapsquare] = it
                xteasList[it.mapsquare] = it.key
            }
            logger.info { "Keys Loaded: ${xteasList.size}" }
        } catch (e: Exception) {
            logger.error("Failed to load XTEAs from ${xteaLocation.path}: ${e.message}", e)
        }
    }

    fun getKeys(region: Int): IntArray? = xteasList[region]
    
    /**
     * Get a copy of all current XTEAs
     */
    fun getXteasCopy(): Map<Int, Xtea> {
        return xteas.mapValues { (_, xtea) ->
            Xtea(
                archive = xtea.archive,
                name_hash = xtea.name_hash,
                name = xtea.name,
                mapsquare = xtea.mapsquare,
                key = xtea.key.copyOf()
            )
        }
    }
    
    /**
     * Compare two XTEA maps and return region IDs that have changed keys
     */
    fun findChangedRegions(oldXteas: Map<Int, Xtea>, newXteas: Map<Int, Xtea>): Set<Int> {
        val changedRegions = mutableSetOf<Int>()
        
        // Check for changed keys in existing regions
        oldXteas.forEach { (regionId, oldXtea) ->
            val newXtea = newXteas[regionId]
            if (newXtea != null && !oldXtea.key.contentEquals(newXtea.key)) {
                changedRegions.add(regionId)
            }
        }
        
        // Check for new regions (regions that exist in new but not in old)
        newXteas.forEach { (regionId, _) ->
            if (!oldXteas.containsKey(regionId)) {
                changedRegions.add(regionId)
            }
        }
        
        return changedRegions
    }
}








