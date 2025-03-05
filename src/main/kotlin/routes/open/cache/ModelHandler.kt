package routes.open.cache

import LoadModels
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import routes.open.cache.TextureHandler.json

object ModelHandler {

    suspend fun handleModelRequest(call: ApplicationCall) {
        val id = call.parameters["id"]?.toIntOrNull()

        if (id == null) {
            call.respondText(json.toJson(LoadModels.models))
            return
        }

        val modelExists = LoadModels.models.contains(id)
        if (!modelExists) {
            call.respond(HttpStatusCode.NotFound, "Model Not Found")
            return
        }

        call.respondText(json.toJson(LoadModels.models[id]))

    }
}
