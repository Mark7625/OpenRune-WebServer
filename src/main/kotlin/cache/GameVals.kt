package cache

import dev.openrune.cache.GAMEVALS
import dev.openrune.definition.Js5GameValGroup
import dev.openrune.definition.Js5GameValGroup.IFTYPES
import dev.openrune.definition.Js5GameValGroup.TABLETYPES
import dev.openrune.definition.util.readString
import gameCache
import io.netty.buffer.Unpooled

object GameVals {

    val gameValData: MutableMap<Js5GameValGroup, MutableMap<Int, String>> = mutableMapOf()

    fun loadGameVals() {
        for (group in gameCache.archives(GAMEVALS)) {
            val values = mapOf<Int, String>().toMutableMap()
            gameCache.files(GAMEVALS,group).forEach { file ->
                val value = unpackGameVal(Js5GameValGroup.fromId(group), file, gameCache.data(GAMEVALS,group,file))
                values[file] = value
            }
            gameValData.put(Js5GameValGroup.fromId(group),values)
        }
    }

    private fun unpackGameVal(group: Js5GameValGroup, id: Int, bytes: ByteArray?): String {
        if (bytes == null) return ""

        val data = Unpooled.wrappedBuffer(bytes)

        return when (group) {
            TABLETYPES -> {
                data.readUnsignedByte()
                val tableName = data.readString()
                if (tableName.isEmpty()) return ""

                while (data.readUnsignedByte().toInt() != 0) {
                    data.readString() // Skip over field names
                }

               tableName
            }

            IFTYPES -> {
                val interfaceName = data.readString().ifEmpty { "_" }
                if (interfaceName.isEmpty()) return ""

                while (data.readUnsignedByte().toInt() != 0xFF) {
                    // Skipping unknown structure
                }

                interfaceName
            }

            else -> {
                val arr = ByteArray(data.readableBytes())
                data.readBytes(arr)
                String(arr)
            }
        }
    }
}
