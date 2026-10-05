package dev.openrune.api

import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.cache.diff.SpriteCdn
import dev.openrune.model.OsrsEntityTypes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val logger = KotlinLogging.logger {}

enum class ZipKind { SPRITES, TEXTURES }

class ZipProgress(val progress: Double, val message: String, val downloadUrl: String?)

/**
 * Builds sprite / texture zips for a revision in the background. Bytes come from the blob table
 * one sprite at a time, so a zip of several thousand PNGs never holds them all in memory.
 */
class SpriteZipService(
    private val ctx: ApiContext,
    private val zipsDir: File,
    private val broadcast: (SseEventType, Map<String, Any?>) -> Unit,
) : AutoCloseable {
    private class ZipJob(val id: String, val kind: ZipKind, @Volatile var progress: ZipProgress, var job: Job)

    private val active = ConcurrentHashMap<String, ZipJob>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ttlMs = TimeUnit.DAYS.toMillis(3)

    private fun jobId(kind: ZipKind, base: Int, rev: Int) = "${kind.name.lowercase()}-$base-$rev"
    private fun file(jobId: String) = File(zipsDir, "$jobId.zip")

    fun existing(kind: ZipKind, base: Int, rev: Int): String? {
        cleanup()
        return jobId(kind, base, rev).takeIf { file(it).exists() }
    }

    fun create(kind: ZipKind, base: Int, rev: Int): String {
        cleanup()
        val id = jobId(kind, base, rev)
        active[id]?.let { return id }
        val job = ZipJob(id, kind, ZipProgress(0.0, "Initializing...", null), Job())
        active[id] = job
        job.job = scope.launch {
            try {
                delay(1_000)
                build(job, rev)
            } catch (e: Exception) {
                logger.error(e) { "zip $id failed" }
                update(job, 0.0, "Error: ${e.message}", null)
            } finally {
                active.remove(id)
            }
        }
        return id
    }

    fun progress(jobId: String): ZipProgress? {
        active[jobId]?.let { return it.progress }
        return if (file(jobId).exists()) ZipProgress(100.0, "Complete", "/zip/download/$jobId") else null
    }

    fun zipFile(jobId: String): File? = file(jobId).takeIf { it.exists() }

    fun cancel(jobId: String): Boolean = active.remove(jobId)?.also { it.job.cancel() } != null

    /**
     * A zip holds the full set at [rev]; `base` only distinguishes job ids, because the sprite
     * state at a revision no longer depends on which earlier revision you merged from.
     */
    private fun build(job: ZipJob, rev: Int) {
        val sprites = ctx.type(OsrsEntityTypes.SPRITES)
        val entries: List<Pair<Int, Int>> = when (job.kind) {
            ZipKind.SPRITES -> ctx.entities.ids(sprites, rev).map { it to it }
            ZipKind.TEXTURES -> {
                val out = ArrayList<Pair<Int, Int>>()
                ctx.entities.forEachPayload(ctx.type(ConfigDiffType.TEXTURES.fileName), rev) { id, _, body ->
                    body.asJsonObject["fileId"]?.let { unwrap(it) }?.takeIf { it.isJsonPrimitive }?.asInt?.let { out.add(id to it) }
                }
                out
            }
        }
        val target = file(job.id)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        var done = 0
        var skipped = 0
        var lastPct = -1.0
        ZipOutputStream(FileOutputStream(temp)).use { zos ->
            entries.forEach { (entryId, spriteId) ->
                val bytes = ctx.entities.blobForEntity(sprites, rev, spriteId)
                    ?: (if (ctx.cdn.canServe) SpriteCdn.fetchSpritePng(ctx.cdn, ctx.game.game.gameType, rev, spriteId) else null)
                if (bytes != null) {
                    zos.putNextEntry(ZipEntry("$entryId.png")); zos.write(bytes); zos.closeEntry()
                } else skipped++
                done++
                val pct = done * 100.0 / entries.size.coerceAtLeast(1)
                if (pct - lastPct >= 1.0 || done == entries.size) {
                    lastPct = pct
                    update(job, pct, "Adding ${job.kind.name.lowercase()}... ($done/${entries.size}, skipped $skipped)", null)
                }
            }
        }
        temp.renameTo(target)
        update(job, 100.0, "Complete", "/zip/download/${job.id}")
    }

    private fun update(job: ZipJob, progress: Double, message: String, downloadUrl: String?) {
        job.progress = ZipProgress(progress, message, downloadUrl)
        broadcast(SseEventType.ZIP_PROGRESS, mapOf("jobId" to job.id, "type" to job.kind.name, "progress" to progress, "message" to message, "downloadUrl" to downloadUrl))
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        zipsDir.listFiles()?.forEach { f -> if (f.isFile && f.name.endsWith(".zip") && now - f.lastModified() > ttlMs) f.delete() }
    }

    override fun close() = scope.cancel()
}

fun Route.zipRoutes(ctx: ApiContext, zips: SpriteZipService) {
    EndpointRegistry.registerEndpoint("POST", "/zip/create", "Create a sprites / textures zip job for a revision. Query: type, base, rev.", "Zip Archives", null, "application/json", listOf("/zip/create?type=sprites&base=1&rev=240"))
    EndpointRegistry.registerEndpoint("GET", "/zip/progress/{jobId}", "Progress of a zip job.", "Zip Archives", null, "application/json", listOf("/zip/progress/sprites-1-240"))
    EndpointRegistry.registerEndpoint("GET", "/zip/download/{jobId}", "Download a completed zip.", "Zip Archives", null, "application/zip", listOf("/zip/download/sprites-1-240"))
    EndpointRegistry.registerEndpoint("DELETE", "/zip/{jobId}", "Cancel a zip job.", "Zip Archives", null, "application/json", listOf("/zip/sprites-1-240"))

    post("/zip/create") {
        val typeParam = call.request.queryParameters["type"] ?: badRequest("Missing 'type' parameter")
        val kind = ZipKind.entries.firstOrNull { it.name.equals(typeParam, ignoreCase = true) }
            ?: badRequest("Invalid type: $typeParam. Valid types: ${ZipKind.entries.joinToString { it.name }}")
        val baseParam = call.intParam("base")
        val revParam = call.intParam("rev")
        if ((baseParam == null) != (revParam == null)) badRequest("Both base and rev must be provided together")
        val (base, rev) = if (baseParam != null) call.revisionPair(ctx) else (ctx.catalog.published().firstOrNull() ?: 1) to (ctx.catalog.latest() ?: missingRevision(-1))
        zips.existing(kind, base, rev)?.let { id ->
            call.respond(mapOf("jobId" to id, "type" to kind.name, "status" to "ready", "downloadUrl" to "/zip/download/$id", "progress" to 100.0, "message" to "Complete - Ready for download"))
            return@post
        }
        val id = zips.create(kind, base, rev)
        call.respond(mapOf("jobId" to id, "type" to kind.name, "status" to "created", "progressUrl" to "/zip/progress/$id", "sseUrl" to "/sse?type=ZIP_PROGRESS"))
    }

    get("/zip/progress/{jobId}") {
        val id = call.parameters["jobId"] ?: badRequest("Missing jobId parameter")
        val p = zips.progress(id) ?: notFound("Job not found or expired")
        call.respond(mapOf("jobId" to id, "progress" to p.progress, "message" to p.message, "downloadUrl" to p.downloadUrl))
    }

    get("/zip/download/{jobId}") {
        val id = call.parameters["jobId"] ?: badRequest("Missing jobId parameter")
        val file = zips.zipFile(id) ?: notFound("Zip file not found")
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${file.name}\"")
        call.respondFile(file)
    }

    delete("/zip/{jobId}") {
        val id = call.parameters["jobId"] ?: badRequest("Missing jobId parameter")
        if (zips.cancel(id)) call.respond(mapOf("jobId" to id, "status" to "cancelled"))
        else call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job not found"))
    }
}
