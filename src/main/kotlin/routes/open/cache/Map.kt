
import io.ktor.http.*
import javax.imageio.ImageIO
import io.ktor.server.application.*
import io.ktor.server.response.*
import java.io.ByteArrayOutputStream
import java.io.File


object MapHandler {

    suspend fun handle(call: ApplicationCall) {
        // Get the ID from the parameters
        val scale = call.parameters["scale"]?.toIntOrNull() ?: 4
        val plane = call.parameters["plane"]?.toIntOrNull() ?: 0
        val id = call.parameters["id"]?.toIntOrNull()

        // Check if ID is valid
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid or missing ID")
            return
        }

        // Construct the file path based on the ID
        val filePath = "E:\\RSPS\\OpenRune\\OpenRune-WebServer\\data\\maps\\$scale\\$plane\\$id.png"
        val file = File(filePath)

        // Check if the file exists
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound, "Image not found")
            return
        }

        // Set the content type to PNG
        call.response.header(HttpHeaders.ContentType, ContentType.Image.PNG.toString())

        // Respond with the file as bytes
        call.respondBytes(file.readBytes(), ContentType.Image.PNG)
    }

}