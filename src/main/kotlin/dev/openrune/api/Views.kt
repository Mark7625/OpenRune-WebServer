package dev.openrune.api

import com.google.gson.Gson
import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.model.ChangeKind
import dev.openrune.model.EntityKind
import dev.openrune.model.OsrsEntityTypes
import dev.openrune.query.DiffCounts

private val gson = Gson()

/**
 * Derived views of published revisions that are worth caching: each costs a few statements or a
 * payload scan and is requested on every page load. Routes read them through [ApiContext.cached];
 * [HotRevisions] precomputes them for the revisions users hit most.
 */
object Views {

    fun deltaSummary(ctx: ApiContext, base: Int, rev: Int): Map<String, Map<String, Int>> =
        ctx.cached("delta-summary|$base|$rev") {
            ctx.metrics.time("api.delta.summary", { "$base->$rev" }) {
                val configTypes = ctx.game.types.filter { it.def.kind == EntityKind.CONFIG }
                val counts = ctx.diffs.countsForTypes(configTypes, base, rev)
                configTypes.associate { t ->
                    val c = counts[t.id] ?: DiffCounts(0, 0, 0)
                    t.def.sectionId to mapOf("added" to c.added, "removed" to c.removed, "changed" to c.changed)
                }
            }
        }

    fun supportManifest(ctx: ApiContext, rev: Int): Map<String, Any> = ctx.cached("support|$rev") {
        val counts = ctx.entities.countsAt(ctx.game.types, rev)
        val configSupport = ctx.game.types.filter { it.def.kind == EntityKind.CONFIG }
            .associate { it.def.sectionId to ((counts[it.id] ?: 0) > 0) }
        val spriteCount = counts[ctx.type(OsrsEntityTypes.SPRITES).id] ?: 0
        val textureCount = counts[ctx.type(ConfigDiffType.TEXTURES.fileName).id] ?: 0
        val gamevalsPresent = ctx.game.types.any { it.def.kind == EntityKind.GAMEVAL && (counts[it.id] ?: 0) > 0 }
        val archives = linkedMapOf("sprites" to (spriteCount > 0), "textures" to (textureCount > 0), "gamevals" to gamevalsPresent)
        mapOf(
            "rev" to rev,
            "archives" to archives,
            "configs" to configSupport,
            "counts" to mapOf("archives" to mapOf("sprites" to spriteCount, "textures" to textureCount)),
            "available" to mapOf("archives" to archives.filterValues { it }.keys.sorted(), "configs" to configSupport.filterValues { it }.keys.sorted()),
            "unsupported" to mapOf("archives" to archives.filterValues { !it }.keys.sorted(), "configs" to configSupport.filterValues { !it }.keys.sorted()),
        )
    }

    fun combinedSpritesJson(ctx: ApiContext, base: Int, rev: Int): String = ctx.cached("combined-sprites|$base|$rev") {
        val sources = ctx.entities.sourceRevisions(ctx.type(OsrsEntityTypes.SPRITES), rev)
        val ids = sources.keys.sorted()
        gson.toJson(
            mapOf(
                "base" to base,
                "rev" to rev,
                "spriteIds" to ids,
                "sourceRevById" to ids.associate { it.toString() to maxOf(sources.getValue(it), base) },
            ),
        )
    }

    fun deltaSpritesJson(ctx: ApiContext, base: Int, rev: Int): String = ctx.cached("delta-sprites|$base|$rev") {
        val entries = ctx.metrics.time("api.delta.sprites", { "$base->$rev" }) { ctx.diffs.ids(ctx.type(OsrsEntityTypes.SPRITES), base, rev) }
        val added = entries.filter { it.kind == ChangeKind.ADDED }.map { it.id }
        val changed = entries.filter { it.kind == ChangeKind.CHANGED }.map { it.id }
        val removed = entries.filter { it.kind == ChangeKind.REMOVED }.map { it.id }
        gson.toJson(
            mapOf(
                "base" to base, "rev" to rev,
                "added" to added, "changed" to changed, "removed" to removed,
                "addedInRev" to added.associate { it.toString() to rev },
                "changedInRev" to changed.associate { it.toString() to rev },
                "removedInRev" to removed.associate { it.toString() to rev },
            ),
        )
    }

    fun deltaSpritesCounts(ctx: ApiContext, base: Int, rev: Int): DiffCounts =
        ctx.cached("delta-sprites-summary|$base|$rev") { ctx.diffs.counts(ctx.type(OsrsEntityTypes.SPRITES), base, rev) }
}
