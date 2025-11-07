package dev.openrune.server.endpoints.models

import dev.openrune.ServerConfig
import dev.openrune.cache.CachePathHelper
import dev.openrune.server.QueryHandlerRaw
import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.response.*
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

class ModelRawEndpoint(private val config: ServerConfig) : QueryHandlerRaw<ModelQueryParams>() {
    
    public override fun getQueryParams(call: ApplicationCall): ModelQueryParams {
        val id = call.parameters["id"]?.toIntOrNull()
        return ModelQueryParams(id = id)
    }

    override suspend fun handle(call: ApplicationCall, params: ModelQueryParams) {
        try {
            val modelId = params.id ?: run {
                respondError(call, "Model ID is required", 400)
                return
            }
            
            val modelFile = getModelFile(modelId)
            if (!modelFile.exists()) {
                respondError(call, "Model file not found", 404)
                return
            }

            val bytes = modelFile.readBytes()
            call.response.header(io.ktor.http.HttpHeaders.ContentDisposition, "attachment; filename=\"$modelId.dat\"")
            call.respondBytes(bytes = bytes, contentType = ContentType.Application.OctetStream)
        } catch (e: Exception) {
            logger.error("Error handling model request: ${e.message}", e)
            respondError(call, "Internal server error: ${e.message}", 500)
        }
    }

    private fun getModelFile(modelId: Int): File {
        val cacheDir = CachePathHelper.getCacheDirectory(
            config.gameType,
            config.environment,
            config.revision
        ).resolve("extracted").resolve("models")
        
        return cacheDir.resolve("$modelId.dat")
    }
}

