package dev.openrune.api

import mu.KotlinLogging
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * The revisions users open most: the base revision and the newest published ones. Their derived
 * views are computed into the response cache at startup, after every publish and on a slow timer,
 * so the common pages are answered from memory. Every other revision is served on demand from
 * PostgreSQL at the same query cost; nothing is ever loaded wholesale.
 */
class HotRevisions(private val ctx: ApiContext, private val newest: Int = 2) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hot-revisions").apply { isDaemon = true } }

    @Volatile
    var current: List<Int> = emptyList()
        private set

    fun start() {
        executor.schedule(::warm, 1, TimeUnit.SECONDS)
        executor.scheduleWithFixedDelay(::warm, 10, 10, TimeUnit.MINUTES)
    }

    /** Called after a publish; runs off the publishing thread. */
    fun refresh() {
        executor.execute(::warm)
    }

    fun select(): List<Int> {
        val published = ctx.catalog.published()
        if (published.isEmpty()) return emptyList()
        return (listOf(published.first()) + published.takeLast(newest)).distinct().sorted()
    }

    private fun warm() {
        val revs = select()
        current = revs
        ctx.hotRevisions = revs
        if (revs.isEmpty()) return
        val start = System.nanoTime()
        try {
            val base = revs.first()
            val latest = revs.last()
            val previous = ctx.catalog.published().filter { it < latest }.lastOrNull()
            revs.forEach { rev ->
                Views.supportManifest(ctx, rev)
                listOf("items", "npcs", "objects", "sprites").forEach { group -> gamevalJson(ctx, group, rev, true) }
                ctx.gamevalNames("items", rev); ctx.gamevalNames("npcs", rev); ctx.gamevalNames("objects", rev); ctx.gamevalNames("sprites", rev)
                Views.combinedSpritesJson(ctx, base, rev)
                textureUsage(ctx, rev)
            }
            if (previous != null) {
                Views.deltaSummary(ctx, previous, latest)
                Views.deltaSpritesJson(ctx, previous, latest)
                Views.deltaSpritesCounts(ctx, previous, latest)
            }
            if (base != latest) {
                Views.deltaSummary(ctx, base, latest)
                Views.deltaSpritesCounts(ctx, base, latest)
            }
            ctx.metrics.record("cache.warm", System.nanoTime() - start) { "revs $revs" }
            logger.info { "Warmed hot revisions $revs in ${(System.nanoTime() - start) / 1_000_000}ms" }
        } catch (e: Exception) {
            logger.warn(e) { "Hot revision warm-up failed" }
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
