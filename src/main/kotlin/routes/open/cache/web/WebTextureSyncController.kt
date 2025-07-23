package routes.open.cache.web

import cache.texture.TextureManager
import cache.texture.TextureManager.textures
import com.google.gson.Gson
import dev.openrune.Index
import gameCache
import io.ktor.server.application.*
import io.ktor.server.response.*

object WebTextureSyncController {

    suspend fun handleTextureById(call: ApplicationCall) {
        val crc = TextureManager.crc
        val clientCrc = call.request.queryParameters["crc"]?.toIntOrNull()

        if (clientCrc != null && clientCrc == crc) {
            call.respond(Gson().toJson(mapOf("updated" to false)))
        } else {
            call.respond(
                Gson().toJson(mapOf(
                    "updated" to true,
                    "crc" to crc,
                    "results" to textures,
                    "total" to textures.size,
                ))
            )
        }
    }
}