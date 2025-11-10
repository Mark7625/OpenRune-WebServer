package dev.openrune.server.endpoints.maps

import dev.openrune.ServerConfig
import dev.openrune.cache.CachePathHelper
import dev.openrune.util.json
import com.google.gson.reflect.TypeToken
import dev.openrune.cache.extractor.osrs.LocationCustom
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

class MapObjectsEndpoint(private val config: ServerConfig) {

    private val objectsDir: File by lazy {
        CachePathHelper.getCacheDirectory(
            config.gameType,
            config.environment,
            config.revision
        ).resolve("extracted").resolve("maps").resolve("objects")
    }

    /**
     * Find all locations of a given object ID.
     */
    suspend fun findObjectsById(call: ApplicationCall, objectId: String) {
        try {
            val objectIdInt = objectId.toIntOrNull()
            if (objectIdInt == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid object ID. Must be a number")
                return
            }

            if (!objectsDir.exists()) {
                call.respond(HttpStatusCode.NotFound, mapOf(
                    "error" to "Objects directory not found",
                    "message" to "The extracted maps/objects folder does not exist."
                ))
                return
            }

            val file = objectsDir.resolve("$objectIdInt.json")
            if (!file.exists()) {
                call.respond(HttpStatusCode.NotFound, mapOf(
                    "error" to "Object not found",
                    "message" to "Object ID $objectIdInt not found in extracted objects.",
                    "objectId" to objectIdInt
                ))
                return
            }

            try {
                val content = file.readText()
                val type = object : TypeToken<List<LocationCustom>>() {}.type
                val objects: List<LocationCustom> = json.fromJson(content, type)

                call.respond(
                    mapOf(
                        "objectId" to objectIdInt,
                        "count" to objects.size,
                        "locations" to objects
                    )
                )
            } catch (e: Exception) {
                logger.error("Failed to parse $objectIdInt.json: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf(
                    "error" to "Failed to parse JSON",
                    "message" to "Error parsing $objectIdInt.json: ${e.message}"
                ))
            }

        } catch (e: Exception) {
            logger.error("Error retrieving object data: ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, "Internal server error: ${e.message}")
        }
    }
}