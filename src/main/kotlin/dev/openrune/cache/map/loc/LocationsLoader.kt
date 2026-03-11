package dev.openrune.cache.map.loc

import dev.openrune.cache.map.net.InputStream
import dev.openrune.cache.map.region.Location
import dev.openrune.cache.map.region.Position

class LocationsLoader {

    fun load(regionX: Int, regionY: Int, data: ByteArray, rev: Int): LocationsDefinition {
        val locs = LocationsDefinition().apply {
            this.regionX = regionX
            this.regionY = regionY
        }

        try {
            if (rev >= 209) {
                loadLocationsNew(locs, data)
            } else {
                loadLocationsOld(locs, data)
            }
        }catch (e : Exception) {

        }

        return locs
    }

    private fun loadLocationsOld(locs: LocationsDefinition, data: ByteArray) {
        val buf = InputStream(data)

        var id = -1
        while (true) {
            val idOffset = buf.readUnsignedShortSmart()
            if (idOffset == 0) break
            id += idOffset

            var position = 0
            while (true) {
                val positionOffset = buf.readUnsignedShortSmart()
                if (positionOffset == 0) break
                position += positionOffset - 1

                val localY = position and 0x3F
                val localX = (position shr 6) and 0x3F
                val height = (position shr 12) and 0x3

                val attributes = buf.readUnsignedByte()
                val type = attributes shr 2
                val orientation = attributes and 0x3

                locs.locations.add(
                    Location(id, type.toInt(), orientation.toInt(), Position(localX, localY, height))
                )
            }
        }
    }

    private fun loadLocationsNew(locs: LocationsDefinition, data: ByteArray) {
        val buf = InputStream(data)

        var id = -1
        while (true) {
            val idOffset = buf.readUnsignedIntSmartShortCompat()
            if (idOffset == 0) break
            id += idOffset

            var position = 0
            while (true) {
                val positionOffset = buf.readUnsignedShortSmart()
                if (positionOffset == 0) break
                position += positionOffset - 1

                val localY = position and 0x3F
                val localX = (position shr 6) and 0x3F
                val height = (position shr 12) and 0x3

                val attributes = buf.readUnsignedByte()
                val type = attributes shr 2
                val orientation = attributes and 0x3

                locs.locations.add(
                    Location(id, type, orientation, Position(localX, localY, height))
                )
            }
        }
    }
}