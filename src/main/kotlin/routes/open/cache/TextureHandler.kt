package routes.open.cache

import cache.texture.TextureManager
import com.google.gson.GsonBuilder
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

object TextureHandler {

    val json = GsonBuilder().setPrettyPrinting().create()

    suspend fun handleTextureById(call: ApplicationCall) {
        val id = call.parameters["id"]?.toIntOrNull()
        val imageFormat = call.request.queryParameters["image"]

        if (id == null) {
            call.respondText(json.toJson(TextureManager.cache.values))
            return
        }

        val texture = TextureManager.cache[id]
        if (texture == null) {
            call.respond(HttpStatusCode.NotFound, "Texture not found")
            return
        }

        if (imageFormat == "png") {
            call.respondBytes(ContentType.Image.PNG) {
                ByteArrayOutputStream().use { outputStream ->
                    ImageIO.write(texture.image, "png", outputStream)
                    outputStream.toByteArray()
                }
            }
        } else {
            call.respond(HttpStatusCode.OK, json.toJson(texture))
        }
    }
}
