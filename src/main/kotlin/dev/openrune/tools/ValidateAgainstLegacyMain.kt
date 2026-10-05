package dev.openrune.tools

import com.google.gson.JsonParser
import dev.openrune.cache.CachePathHelper
import dev.openrune.cache.diff.ConfigDiffType
import dev.openrune.ingest.LegacyBinRevisionSource
import dev.openrune.ingest.Payloads
import dev.openrune.model.ChangeKind
import dev.openrune.model.EntitySnapshot
import dev.openrune.model.Hashing
import dev.openrune.model.OsrsEntityTypes
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Compares what PostgreSQL holds for published revisions against the legacy `.bin` system:
 *
 * 1. state: every entity type at every revision, id by id and hash by hash;
 * 2. diffs: for each consecutive pair and the first/last pair, the added / removed / changed
 *    sets the legacy code derives from its snapshots versus `DiffQueries`, including the set of
 *    changed fields for changed entities.
 *
 * Flags: `revs=1,240,241 game=OLDSCHOOL env=LIVE report=build/validation-report.md`
 */
fun main(args: Array<String>) {
    val flags = ToolArgs(args)
    val revs = flags.ints("revs").sorted()
    require(revs.size >= 1) { "revs=... is required" }
    val report = StringBuilder()
    fun line(s: String = "") { report.appendLine(s); println(s) }

    openPlatform(flags).use { platform ->
        val dir = CachePathHelper.getDiffBinaryDirectory(platform.config.gameType, platform.config.environment)
        val base = LegacyBinRevisionSource.load(File(dir, "1.bin"))
        val sources = revs.associateWith { rev ->
            LegacyBinRevisionSource(rev, base, if (rev == 1) base else LegacyBinRevisionSource.load(File(dir, "$rev.bin")))
        }
        var problems = 0

        line("# Validation: PostgreSQL vs legacy .bin")
        line()
        line("Revisions: ${revs.joinToString()}")
        line()
        line("## State per revision")
        line()
        line("| rev | type | legacy | postgres | missing | extra | hash mismatch |")
        line("|-----|------|--------|----------|---------|-------|---------------|")
        for (rev in revs) {
            if (!platform.catalog.isPublished(rev)) { line("| $rev | - | not published in PostgreSQL | | | | |"); problems++; continue }
            val source = sources.getValue(rev)
            for (key in source.typeKeys) {
                val type = platform.game.typeOrNull(key) ?: continue
                val legacy = LinkedHashMap<Int, EntitySnapshot>()
                source.snapshots(key).forEach { legacy[it.entityId] = it }
                val stored = platform.entities.hashes(type, rev)
                val missing = legacy.keys.filter { it !in stored }
                val extra = stored.keys.filter { it !in legacy }
                val mismatch = legacy.filter { (id, s) -> stored[id]?.let { !it.contentEquals(s.payloadHash) } == true }.keys
                if (missing.isNotEmpty() || extra.isNotEmpty() || mismatch.isNotEmpty()) problems++
                line("| $rev | $key | ${legacy.size} | ${stored.size} | ${missing.size} | ${extra.size} | ${mismatch.size} |")
                mismatch.take(3).forEach { id ->
                    val pg = platform.entities.get(type, rev, id)?.payload
                    val lg = JsonParser.parseString(legacy.getValue(id).payloadJson)
                    val diff = if (pg != null && pg.isJsonObject && lg.isJsonObject) dev.openrune.query.changedFieldNames(lg.asJsonObject, pg.asJsonObject).toList() else listOf("<non-object>")
                    line("|   |   | mismatch id $id fields: ${diff.joinToString()} |  |  |  |  |")
                }
            }
        }

        line()
        line("## Diffs between revisions")
        line()
        line("| pair | type | legacy +/~/- | postgres +/~/- | set differences |")
        line("|------|------|--------------|----------------|-----------------|")
        val pairs = revs.zipWithNext().toMutableList()
        if (revs.size > 2) pairs.add(revs.first() to revs.last())
        for ((a, b) in pairs) {
            if (!platform.catalog.isPublished(a) || !platform.catalog.isPublished(b)) continue
            val sa = sources.getValue(a)
            val sb = sources.getValue(b)
            for (type in ConfigDiffType.all) {
                val stateA = sa.configState(type)
                val stateB = sb.configState(type)
                val legacyAdded = (stateB.keys - stateA.keys).sorted()
                val legacyRemoved = (stateA.keys - stateB.keys).sorted()
                val legacyChanged = (stateA.keys intersect stateB.keys).filter { stateA[it] != stateB[it] }.sorted()
                val registered = platform.game.type(type.fileName)
                val pg = platform.diffs.ids(registered, a, b)
                val pgAdded = pg.filter { it.kind == ChangeKind.ADDED }.map { it.id }
                val pgRemoved = pg.filter { it.kind == ChangeKind.REMOVED }.map { it.id }
                val pgChanged = pg.filter { it.kind == ChangeKind.CHANGED }.map { it.id }
                val notes = ArrayList<String>()
                fun compare(label: String, l: List<Int>, p: List<Int>) {
                    if (l != p) notes.add("$label legacy-only=${(l - p.toSet()).take(5)} pg-only=${(p - l.toSet()).take(5)}")
                }
                compare("added", legacyAdded, pgAdded)
                compare("removed", legacyRemoved, pgRemoved)
                compare("changed", legacyChanged, pgChanged)
                // Field-level check on a sample of changed entities.
                var fieldMismatches = 0
                val pgByIdFields = HashMap<Int, Set<String>>()
                if (legacyChanged.isNotEmpty() && legacyChanged == pgChanged) {
                    platform.diffs.forEachEntry(registered, a, b) { e ->
                        if (e.kind == ChangeKind.CHANGED) pgByIdFields[e.id] = dev.openrune.query.changedFieldNames(e.oldPayload!!.asJsonObject, e.newPayload!!.asJsonObject)
                    }
                    legacyChanged.forEach { id ->
                        val legacyFields = (stateA.getValue(id).keys + stateB.getValue(id).keys).filter { stateA.getValue(id)[it] != stateB.getValue(id)[it] }.toSet()
                        if (legacyFields != pgByIdFields[id]) {
                            fieldMismatches++
                            if (fieldMismatches <= 3) notes.add("fields differ for $id: legacy=$legacyFields pg=${pgByIdFields[id]}")
                        }
                    }
                    if (fieldMismatches > 0) notes.add("$fieldMismatches entities with different changed-field sets")
                }
                if (notes.isNotEmpty()) problems++
                line("| $a->$b | ${type.fileName} | ${legacyAdded.size}/${legacyChanged.size}/${legacyRemoved.size} | ${pgAdded.size}/${pgChanged.size}/${pgRemoved.size} | ${if (notes.isEmpty()) "ok" else notes.joinToString("; ")} |")
            }
            // Sprites: legacy pixel comparison versus stored pixel hashes.
            run {
                val spritesA = sa.spriteSources()
                val spritesB = sb.spriteSources()
                val legacyAdded = (spritesB.keys - spritesA.keys).sorted()
                val legacyRemoved = (spritesA.keys - spritesB.keys).sorted()
                val legacyChanged = (spritesA.keys intersect spritesB.keys).filter { id ->
                    val pa = spritesA.getValue(id).sprites[id]
                    val pb = spritesB.getValue(id).sprites[id]
                    pa != null && pb != null && !Payloads.pixelHashOfPng(pa).contentEquals(Payloads.pixelHashOfPng(pb))
                }.sorted()
                val pg = platform.diffs.ids(platform.game.type(OsrsEntityTypes.SPRITES), a, b)
                val pgAdded = pg.filter { it.kind == ChangeKind.ADDED }.map { it.id }
                val pgRemoved = pg.filter { it.kind == ChangeKind.REMOVED }.map { it.id }
                val pgChanged = pg.filter { it.kind == ChangeKind.CHANGED }.map { it.id }
                val notes = ArrayList<String>()
                if (legacyAdded != pgAdded) notes.add("added differ")
                if (legacyRemoved != pgRemoved) notes.add("removed differ")
                if (legacyChanged != pgChanged) notes.add("changed legacy-only=${(legacyChanged - pgChanged.toSet()).take(5)} pg-only=${(pgChanged - legacyChanged.toSet()).take(5)}")
                if (notes.isNotEmpty()) problems++
                line("| $a->$b | sprites | ${legacyAdded.size}/${legacyChanged.size}/${legacyRemoved.size} | ${pgAdded.size}/${pgChanged.size}/${pgRemoved.size} | ${if (notes.isEmpty()) "ok" else notes.joinToString("; ")} |")
            }
            run {
                val ma = sa.modelState(); val mb = sb.modelState()
                val legacyAdded = (mb.keys - ma.keys).sorted()
                val legacyRemoved = (ma.keys - mb.keys).sorted()
                val legacyChanged = (ma.keys intersect mb.keys).filter { ma[it] != mb[it] }.sorted()
                val pg = platform.diffs.ids(platform.game.type(OsrsEntityTypes.MODELS), a, b)
                val ok = legacyAdded == pg.filter { it.kind == ChangeKind.ADDED }.map { it.id } &&
                    legacyRemoved == pg.filter { it.kind == ChangeKind.REMOVED }.map { it.id } &&
                    legacyChanged == pg.filter { it.kind == ChangeKind.CHANGED }.map { it.id }
                if (!ok) problems++
                line("| $a->$b | models | ${legacyAdded.size}/${legacyChanged.size}/${legacyRemoved.size} | ${pg.count { it.kind == ChangeKind.ADDED }}/${pg.count { it.kind == ChangeKind.CHANGED }}/${pg.count { it.kind == ChangeKind.REMOVED }} | ${if (ok) "ok" else "differ"} |")
            }
        }
        line()
        line(if (problems == 0) "Result: no differences." else "Result: $problems table rows with differences (see above).")
        val out = File(flags["report"] ?: "build/validation-report.md")
        out.parentFile?.mkdirs()
        out.writeText(report.toString())
        logger.info { "Report written to ${out.absolutePath}" }
        if (problems > 0) System.exit(2)
    }
}

@Suppress("unused")
private fun hex(bytes: ByteArray) = Hashing.hex(bytes)
