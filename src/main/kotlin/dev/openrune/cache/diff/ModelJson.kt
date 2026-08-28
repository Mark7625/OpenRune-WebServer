package dev.openrune.cache.diff

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * JSON form of [ModelMeta], used both for the on-disk per-revision sidecar and for the payload
 * embedded in the revision `.bin`. Meshes themselves are never stored — only these summaries.
 *
 * Keys are short because a revision holds ~60k entries:
 * `v`=vertices, `f`=faces, `tf`=textured faces, `af`=faces with alpha, `ver`=mesh version,
 * `pri`=render priority, `tex`=texture ids, `col`=HSL colours, `items`/`npcs`/`objs`=attachments.
 */
private data class ModelMetaJson(
    val v: Int = 0,
    val f: Int = 0,
    val tf: Int = 0,
    val af: Int = 0,
    val ver: Int = 0,
    val pri: Int = 0,
    val tex: List<Int> = emptyList(),
    val col: List<Int> = emptyList(),
    val items: List<Int> = emptyList(),
    val npcs: List<Int> = emptyList(),
    val objs: List<Int> = emptyList(),
)

object ModelJson {

    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, ModelMetaJson>>() {}.type

    fun encode(models: Map<Int, ModelMeta>): String {
        val out = LinkedHashMap<String, ModelMetaJson>(models.size)
        models.entries.sortedBy { it.key }.forEach { (id, meta) ->
            out[id.toString()] = ModelMetaJson(
                v = meta.vertexCount,
                f = meta.faceCount,
                tf = meta.texturedFaceCount,
                af = meta.transparentFaceCount,
                ver = meta.version,
                pri = meta.renderPriority,
                tex = meta.textures,
                col = meta.colors,
                items = meta.itemIds,
                npcs = meta.npcIds,
                objs = meta.objectIds,
            )
        }
        return gson.toJson(out)
    }

    fun decode(json: String): Map<Int, ModelMeta> {
        if (json.isBlank()) return emptyMap()
        val parsed: Map<String, ModelMetaJson> = runCatching {
            gson.fromJson<Map<String, ModelMetaJson>>(json, mapType)
        }.getOrElse { e ->
            logger.warn(e) { "Failed parsing model metadata JSON" }
            return emptyMap()
        } ?: return emptyMap()

        val out = LinkedHashMap<Int, ModelMeta>(parsed.size)
        parsed.forEach { (rawId, entry) ->
            val id = rawId.toIntOrNull() ?: return@forEach
            out[id] = ModelMeta(
                vertexCount = entry.v,
                faceCount = entry.f,
                texturedFaceCount = entry.tf,
                transparentFaceCount = entry.af,
                version = entry.ver,
                renderPriority = entry.pri,
                textures = entry.tex,
                colors = entry.col,
                itemIds = entry.items,
                npcIds = entry.npcs,
                objectIds = entry.objs,
            )
        }
        return out
    }

    fun readFile(file: File): Map<Int, ModelMeta>? {
        if (!file.isFile) return null
        return runCatching { decode(file.readText(Charsets.UTF_8)) }
            .getOrElse { e ->
                logger.warn(e) { "Failed reading ${file.name}" }
                null
            }
            ?.takeIf { it.isNotEmpty() }
    }

    fun writeFile(file: File, models: Map<Int, ModelMeta>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(encode(models), Charsets.UTF_8)
        }.onFailure { e -> logger.warn(e) { "Failed writing ${file.name}" } }
    }
}
