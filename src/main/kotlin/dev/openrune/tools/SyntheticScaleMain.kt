package dev.openrune.tools

import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import dev.openrune.ingest.RevisionSource
import dev.openrune.model.EntityKind
import dev.openrune.model.EntitySnapshot
import dev.openrune.query.Search
import dev.openrune.query.SearchMode
import mu.KotlinLogging
import java.io.File
import java.util.Random

private val logger = KotlinLogging.logger {}

/**
 * Scale test with generated data standing in for an RS3-sized stream: N entity types with M
 * entities each, R revisions, a fixed churn per revision. Reports ingestion time per revision,
 * table sizes and query latency once the dataset is large.
 *
 * Flags: `revs=50 types=20 entities=100000 churn=0.03 report=build/synthetic.md`
 * (defaults: 20 types x 100k = 2M entities, 50 revisions, 3% churn)
 */
fun main(args: Array<String>) {
    val flags = ToolArgs(args)
    val revCount = flags.int("revs") ?: 50
    val typeCount = flags.int("types") ?: 20
    val perType = flags.int("entities") ?: 100_000
    val churn = flags["churn"]?.toDoubleOrNull() ?: 0.03
    val lines = ArrayList<String>()
    fun out(s: String) { lines.add(s); println(s) }

    // A dedicated stream so the synthetic data never mixes with real games.
    val types = (1..typeCount).map { "synthetic$it" }
    val typeDefs = types.map { dev.openrune.model.EntityTypeDef(it, EntityKind.CONFIG, it) }
    openPlatform(flags, GameType.DARKSCAPE, CacheEnvironment.LIVE, typeDefs).use { synthetic ->
        val game = synthetic.game
        out("# Synthetic scale run")
        out("")
        out("$typeCount types x $perType entities = ${typeCount * perType} entities per revision, $revCount revisions, churn ${(churn * 100).toInt()}%")
        out("")
        out("| rev | entities | added | changed | removed | import ms | peak heap MB |")
        out("|-----|----------|-------|---------|---------|-----------|--------------|")

        val random = Random(42)
        // Per-entity current version number; a change bumps it so the payload changes.
        val versions = HashMap<String, IntArray>()
        types.forEach { versions[it] = IntArray(perType + revCount * 1000) }
        val alive = HashMap<String, BooleanArray>()
        types.forEach { t -> alive[t] = BooleanArray(perType + revCount * 1000) { it < perType } }

        for (rev in 1..revCount) {
            if (rev > 1) {
                types.forEach { t ->
                    val v = versions.getValue(t); val a = alive.getValue(t)
                    val changes = (perType * churn).toInt()
                    repeat(changes) { val id = random.nextInt(perType); if (a[id]) v[id]++ }
                    repeat(changes / 10) { val id = random.nextInt(perType); a[id] = false }
                    repeat(changes / 10) { val id = perType + random.nextInt(revCount * 1000); a[id] = true }
                }
            }
            val source = object : RevisionSource {
                override val rev: Int = rev
                override val typeKeys: List<String> = types
                override fun snapshots(typeKey: String): Iterator<EntitySnapshot> {
                    val v = versions.getValue(typeKey); val a = alive.getValue(typeKey)
                    return a.indices.asSequence().filter { a[it] }.map { id ->
                        EntitySnapshot.of(id, "$typeKey entity $id", mapOf("version" to v[id], "name" to "$typeKey entity $id", "flags" to listOf(id % 7, id % 11), "params" to mapOf("1" to id, "2" to v[id])))
                    }.iterator()
                }
                override fun close() {}
            }
            resetPeakHeap()
            val start = System.nanoTime()
            val result = synthetic.ingestFrom(rev, { source })
            out("| $rev | ${result.entities} | ${result.added} | ${result.changed} | ${result.removed} | ${(System.nanoTime() - start) / 1_000_000} | ${peakHeapMb()} |")
        }

        out("")
        out("## Queries at scale")
        out("")
        out("| operation | p50 ms | p95 ms |")
        out("|-----------|--------|--------|")
        val entities = synthetic.entities
        val diffs = synthetic.diffs
        val t = game.type(types.first())
        fun bench(name: String, op: () -> Any?) {
            repeat(3) { op() }
            val samples = (1..10).map { val s = System.nanoTime(); op(); (System.nanoTime() - s) / 1_000_000.0 }.sorted()
            out("| $name | ${"%.1f".format(samples[4])} | ${"%.1f".format(samples[9])} |")
        }
        bench("page offset 0 limit 50 @$revCount") { entities.page(t, revCount, 0, 50) }
        bench("page offset 50000 limit 50 @$revCount") { entities.page(t, revCount, 50_000, 50) }
        bench("page keyset after 50000 limit 50 @$revCount") { entities.page(t, revCount, 0, 50, afterId = 50_000) }
        bench("name search 'entity 12' @$revCount") { entities.page(t, revCount, 0, 50, Search(SearchMode.NAME, "entity 12")) }
        bench("lookup @$revCount") { entities.get(t, revCount, 777) }
        bench("diff counts adjacent ${revCount - 1}->$revCount") { diffs.counts(t, revCount - 1, revCount) }
        bench("diff counts distant 1->$revCount") { diffs.counts(t, 1, revCount) }
        bench("diff counts all types adjacent") { diffs.countsForTypes(game.types, revCount - 1, revCount) }

        val sizes = synthetic.db.api.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT pg_size_pretty(pg_total_relation_size('entity_version')), pg_size_pretty(pg_total_relation_size('entity_payload')), (SELECT count(*) FROM entity_version), (SELECT count(*) FROM entity_payload)").use { rs ->
                    rs.next(); listOf(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4))
                }
            }
        }
        out("")
        out("entity_version: ${sizes[2]} rows, ${sizes[0]}; entity_payload: ${sizes[3]} rows, ${sizes[1]}")
        val report = File(flags["report"] ?: "build/synthetic.md")
        report.parentFile?.mkdirs()
        report.writeText(lines.joinToString("\n") + "\n")
        logger.info { "Report written to ${report.absolutePath}" }
    }
}
