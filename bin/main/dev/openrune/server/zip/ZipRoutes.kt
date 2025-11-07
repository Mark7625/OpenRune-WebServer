package dev.openrune.server.zip

import dev.openrune.ServerConfig
import dev.openrune.server.EndpointRegistry
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

data class CreateZipRequest(
    val type: String
)

fun Route.registerZipEndpoints(config: ServerConfig, zipService: ZipService) {
    
    // Register endpoints for documentation
    EndpointRegistry.registerEndpoint(
        method = "POST",
        path = "/zip/create",
        description = "Create a zip file archive job for sprites, models, or textures. Returns job ID and status. If zip already exists, returns download link immediately. Progress updates available via SSE at /sse?type=ZIP_PROGRESS or polling at /zip/progress/{jobId}.",
        category = "Zip Archives",
        queryParamsClass = null,
        responseType = "application/json",
        examples = listOf(
            "/zip/create?type=sprites",
            "/zip/create?type=models",
            "/zip/create?type=textures"
        )
    )
    
    EndpointRegistry.registerEndpoint(
        method = "GET",
        path = "/zip/progress/{jobId}",
        description = "Get the current progress of a zip file creation job. Returns job status, progress percentage, message, and download URL when complete.",
        category = "Zip Archives",
        queryParamsClass = null,
        responseType = "application/json",
        examples = listOf(
            "/zip/progress/sprites-235",
            "/zip/progress/models-235"
        )
    )
    
    EndpointRegistry.registerEndpoint(
        method = "GET",
        path = "/zip/download/{jobId}",
        description = "Download a completed zip file archive. Returns the zip file as a binary download with appropriate Content-Disposition header.",
        category = "Zip Archives",
        queryParamsClass = null,
        responseType = "application/zip",
        examples = listOf(
            "/zip/download/sprites-235",
            "/zip/download/models-235"
        )
    )
    
    EndpointRegistry.registerEndpoint(
        method = "DELETE",
        path = "/zip/{jobId}",
        description = "Cancel an in-progress zip file creation job. Cancels the job and removes it from active jobs. Returns success status.",
        category = "Zip Archives",
        queryParamsClass = null,
        responseType = "application/json",
        examples = listOf(
            "/zip/sprites-235",
            "/zip/models-235"
        )
    )
    
    post("/zip/create") {
        try {
            val typeParam = call.request.queryParameters["type"] 
                ?: try { call.receive<CreateZipRequest>().type } catch (e: Exception) { null }
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'type' parameter"))
                    return@post
                }
            
            val zipType = try {
                ZipType.valueOf(typeParam.uppercase())
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid type: $typeParam. Valid types: ${ZipType.values().joinToString { it.name }}")
                )
                return@post
            }

            zipService.zipExists(zipType)?.let { existingJobId ->
                call.respond(mapOf(
                    "jobId" to existingJobId,
                    "type" to zipType.name,
                    "status" to "ready",
                    "downloadUrl" to "/zip/download/$existingJobId",
                    "progress" to 100.0,
                    "message" to "Complete - Ready for download"
                ))
                return@post
            }
            
            val jobId = zipService.createZip(zipType)
            
            call.respond(mapOf(
                "jobId" to jobId,
                "type" to zipType.name,
                "status" to "created",
                "progressUrl" to "/zip/progress/$jobId",
                "sseUrl" to "/sse?type=ZIP_PROGRESS"
            ))
        } catch (e: Exception) {
            logger.error("Error creating zip: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to create zip", "message" to (e.message ?: "Unknown error"))
            )
        }
    }
    
    get("/zip/progress/{jobId}") {
        try {
            val jobId = call.parameters["jobId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing jobId parameter"))
                return@get
            }
            
            val progress = zipService.getProgress(jobId) ?: run {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found or expired"))
                return@get
            }
            
            call.respond(mapOf(
                "jobId" to jobId,
                "progress" to progress.progress,
                "message" to progress.message,
                "downloadUrl" to progress.downloadUrl
            ))
        } catch (e: Exception) {
            logger.error("Error getting zip progress: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to get progress", "message" to (e.message ?: "Unknown error"))
            )
        }
    }
    
    get("/zip/download/{jobId}") {
        try {
            val jobId = call.parameters["jobId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing jobId parameter"))
                return@get
            }
            
            val zipFile = zipService.getZipFile(jobId) ?: run {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Zip file not found"))
                return@get
            }
            
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${zipFile.name}\"")
            call.respondFile(zipFile)
        } catch (e: Exception) {
            logger.error("Error downloading zip: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to download zip", "message" to (e.message ?: "Unknown error"))
            )
        }
    }
    
    delete("/zip/{jobId}") {
        try {
            val jobId = call.parameters["jobId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing jobId parameter"))
                return@delete
            }
            
            if (zipService.cancelJob(jobId)) {
                call.respond(mapOf("jobId" to jobId, "status" to "cancelled"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
            }
        } catch (e: Exception) {
            logger.error("Error cancelling zip job: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to cancel job", "message" to (e.message ?: "Unknown error"))
            )
        }
    }
}

