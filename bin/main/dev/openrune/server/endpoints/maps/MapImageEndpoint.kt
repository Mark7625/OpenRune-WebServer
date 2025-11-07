package dev.openrune.server.endpoints.maps

import dev.openrune.ServerConfig
import dev.openrune.cache.CachePathHelper
import dev.openrune.server.endpoints.ImageResizeHelper
import dev.openrune.util.json
import com.google.gson.reflect.TypeToken
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.header
import io.ktor.server.response.*
import mu.KotlinLogging
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

private val logger = KotlinLogging.logger {}

class MapImageEndpoint(private val config: ServerConfig) {
    
    private val mapsDir: File by lazy {
        CachePathHelper.getCacheDirectory(
            config.gameType,
            config.environment,
            config.revision
        ).resolve("extracted").resolve("maps")
    }
    
    private val regionCrcFile: File by lazy {
        mapsDir.resolve("region_crcs.json")
    }
    
    private val regionCrcs: Map<Int, Long> by lazy {
        loadRegionCrcs()
    }
    
    private fun loadRegionCrcs(): Map<Int, Long> {
        if (!regionCrcFile.exists()) {
            return emptyMap()
        }
        
        return try {
            val content = regionCrcFile.readText()
            val type = object : TypeToken<Map<String, Long>>() {}.type
            val loaded: Map<String, Long> = json.fromJson(content, type)
            loaded.mapNotNull { (key, crc) ->
                key.toIntOrNull()?.let { regionId -> regionId to crc }
            }.toMap()
        } catch (e: Exception) {
            logger.warn("Failed to load region CRCs: ${e.message}")
            emptyMap()
        }
    }
    
    /**
     * Extract region ID from filename: {plane}_{regionX}_{regionY}.png
     */
    private fun getRegionIdFromFilename(filename: String): Int? {
        val nameWithoutExt = filename.removeSuffix(".png")
        val parts = nameWithoutExt.split("_")
        if (parts.size != 3) {
            return null
        }
        
        val regionX = parts[1].toIntOrNull() ?: return null
        val regionY = parts[2].toIntOrNull() ?: return null
        
        // Region ID is (regionX shl 8) or regionY
        return (regionX shl 8) or regionY
    }
    
    suspend fun handle(call: ApplicationCall, zoom: String, filename: String) {
        try {
            logger.info("Map request: zoom=$zoom, filename=$filename")
            
            // Validate zoom (should be 0-11), default to 2 if invalid
            val zoomInt = zoom.toIntOrNull()?.takeIf { it in 0..11 } ?: 2
            if (zoomInt != zoom.toIntOrNull()) {
                logger.debug("Invalid or missing zoom level: $zoom, defaulting to 2")
            }
            val zoomString = zoomInt.toString()
            
            // Validate filename format: {plane}_{regionX}_{regionY}.png
            if (!filename.endsWith(".png")) {
                logger.warn("Invalid filename format: $filename")
                call.respond(HttpStatusCode.BadRequest, "Invalid filename. Must end with .png")
                return
            }
            
            // Get the map file
            val mapFile = mapsDir.resolve(zoomString).resolve(filename)
            logger.debug("Looking for map file: ${mapFile.absolutePath}")

            // Get region ID from filename and look up CRC
            val regionId = getRegionIdFromFilename(filename)
            logger.debug("Extracted regionId=$regionId from filename=$filename")


            // Read and serve the image
            val image = ImageIO.read(mapFile) ?: run {
                call.respond(HttpStatusCode.InternalServerError, "Failed to read map image")
                return
            }

            // Convert to bytes
            val bytes = imageToBytes(image)

            call.respondBytes(bytes = bytes, contentType = ContentType.Image.PNG)
            logger.debug("Successfully served map image: regionId=$regionId, zoom=$zoomString")
            
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid request: ${e.message}", e)
            call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid request")
        } catch (e: Exception) {
            logger.error("Error serving map image: ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, "Internal server error: ${e.message}")
        }
    }
    
    /**
     * Handle request by regionId, plane, and zoom
     */
    suspend fun handleByRegionId(call: ApplicationCall, zoom: String, regionId: String, plane: String) {
        try {
            logger.info("Map request by regionId: zoom=$zoom, regionId=$regionId, plane=$plane")
            
            // Validate zoom (should be 0-11), default to 2 if invalid
            val zoomInt = zoom.toIntOrNull()?.takeIf { it in 0..11 } ?: 2
            if (zoomInt != zoom.toIntOrNull()) {
                logger.debug("Invalid or missing zoom level: $zoom, defaulting to 2")
            }
            
            val zoomString = zoomInt.toString()
            
            // Validate regionId
            val regionIdInt = regionId.toIntOrNull()
            if (regionIdInt == null) {
                logger.warn("Invalid regionId: $regionId")
                call.respond(HttpStatusCode.BadRequest, "Invalid regionId. Must be a number")
                return
            }
            
            // Validate plane (should be 0-3), default to 0 if invalid
            val planeInt = plane.toIntOrNull()?.takeIf { it in 0..3 } ?: 0
            if (planeInt != plane.toIntOrNull()) {
                logger.debug("Invalid or missing plane: $plane, defaulting to 0")
            }
            
            // Calculate regionX and regionY from regionId
            val regionX = regionIdInt shr 8
            val regionY = regionIdInt and 0xFF
            
            // Build filename: {plane}_{regionX}_{regionY}.png
            val filename = "${planeInt}_${regionX}_${regionY}.png"
            logger.debug("Generated filename: $filename from regionId=$regionIdInt, plane=$planeInt")
            
            // Get the map file
            val mapFile = mapsDir.resolve(zoomString).resolve(filename)
            logger.debug("Looking for map file: ${mapFile.absolutePath}")
            
            if (!mapFile.exists()) {
                logger.warn("Map file not found: ${mapFile.absolutePath}")
                call.respond(HttpStatusCode.NotFound, "Map image not found: zoom=$zoomString, regionId=$regionId, plane=$planeInt")
                return
            }
            
            // Check if file is within the maps directory (security check)
            if (!mapFile.canonicalPath.startsWith(mapsDir.canonicalPath)) {
                logger.warn("Invalid file path (security check failed): ${mapFile.canonicalPath}")
                call.respond(HttpStatusCode.BadRequest, "Invalid file path")
                return
            }
            
            // Look up CRC
            val regionCrc = regionCrcs[regionIdInt]
            logger.debug("Region CRC: $regionCrc for regionId=$regionIdInt")
            
            // Check if client has matching CRC (cache validation)
            if (regionCrc != null) {
                val etag = "\"$regionCrc\""
                val ifNoneMatch = call.request.header(HttpHeaders.IfNoneMatch)
                logger.debug("ETag check: client=$ifNoneMatch, server=$etag")
                
                // If client has matching CRC, return 304 Not Modified
                if (ifNoneMatch == etag || ifNoneMatch == "*") {
                    logger.info("Returning 304 Not Modified for regionId=$regionIdInt, zoom=$zoomString, plane=$planeInt")
                    call.response.status(HttpStatusCode.NotModified)
                    call.response.header(HttpHeaders.ETag, etag)
                    call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 86400 * 7))
                    call.respond(HttpStatusCode.NotModified)
                    return
                }
            }
            
            logger.info("Serving map image: regionId=$regionIdInt, zoom=$zoomString, plane=$planeInt")
            
            // Read and serve the image
            val image = ImageIO.read(mapFile) ?: run {
                logger.error("Failed to read map image: ${mapFile.absolutePath}")
                call.respond(HttpStatusCode.InternalServerError, "Failed to read map image")
                return
            }
            
            // Convert to bytes
            val bytes = imageToBytes(image)
            
            // Set headers and respond
            call.response.header(HttpHeaders.ContentType, ContentType.Image.PNG.toString())
            call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 86400 * 7)) // 7 days cache
            
            // Set ETag header with CRC if available
            if (regionCrc != null) {
                val etag = "\"$regionCrc\""
                call.response.header(HttpHeaders.ETag, etag)
            }
            
            call.respondBytes(bytes = bytes, contentType = ContentType.Image.PNG)
            logger.debug("Successfully served map image: regionId=$regionIdInt, zoom=$zoomString, plane=$planeInt")
            
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid request: ${e.message}", e)
            call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid request")
        } catch (e: Exception) {
            logger.error("Error serving map image by regionId: ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, "Internal server error: ${e.message}")
        }
    }
    
    /**
     * Convert BufferedImage to PNG bytes
     */
    private fun imageToBytes(image: java.awt.image.BufferedImage): ByteArray {
        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, "png", outputStream)
        return outputStream.toByteArray()
    }
}

