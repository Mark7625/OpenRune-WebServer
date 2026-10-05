package dev.openrune.tools

import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.tools.item.ItemSpriteFactory
import dev.openrune.cache.tools.obj.ObjectSpriteFactory
import dev.openrune.filesystem.Cache
import mu.KotlinLogging
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.toPath

private val logger = KotlinLogging.logger {}

/**
 * Renders a handful of item and object images from a revision's raw cache and writes them to disk,
 * so render settings (size, angles, zoom) can be eyeballed without running a full ingest.
 *
 * Flags: `rev=241 items=4151,1127 objects=1276 out=build/render-sample game=OLDSCHOOL env=LIVE`
 */
fun main(args: Array<String>) {
    val flags = ToolArgs(args)
    val rev = flags.int("rev") ?: error("rev=... is required")
    val items = flags.ints("items").ifEmpty { listOf(4151, 1127) }
    val objects = flags.ints("objects").ifEmpty { listOf(1276) }
    val out = File(flags["out"] ?: "build/render-sample").also { it.mkdirs() }

    val cacheDir = File(CachePathHelper.getCacheDirectory(flags.gameType, flags.environment, rev), "data/cache")
    require(cacheDir.isDirectory) { "No raw cache at $cacheDir — download it first" }

    // Cache has close() but does not implement Closeable, so no `use`.
    val cache = Cache.load(cacheDir.toPath())
    try {
        val size = flags.int("size")
        val itemFactory = ItemSpriteFactory.fromCache(cache, rev)
        items.forEach { id ->
            val image = runCatching {
                itemFactory.createSprite(itemFactory.item(id).also { b -> size?.let { b.size = it } })
            }.getOrNull()
            if (image == null) {
                logger.error { "item $id produced no image" }
                return@forEach
            }
            val file = File(out, "item-$id.png")
            ImageIO.write(image, "png", file)
            logger.info { "item $id -> ${file.name} ${image.width}x${image.height}" }
        }

        val objectFactory = ObjectSpriteFactory.fromCache(cache, rev)
        objects.forEach { id ->
            val image = runCatching {
                objectFactory.createSprite(objectFactory.obj(id).also { b -> size?.let { b.size = it } })
            }.getOrNull()
            if (image == null) {
                logger.error { "object $id produced no image" }
                return@forEach
            }
            val file = File(out, "object-$id.png")
            ImageIO.write(image, "png", file)
            logger.info { "object $id -> ${file.name} ${image.width}x${image.height}" }
        }
    } finally {
        runCatching { cache.close() }
    }
    logger.info { "Wrote samples to ${out.absolutePath}" }
}
