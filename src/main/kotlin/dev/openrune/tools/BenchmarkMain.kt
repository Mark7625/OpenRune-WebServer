package dev.openrune.tools

import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import dev.openrune.model.ChangeKind
import dev.openrune.model.EntityKind
import dev.openrune.model.OsrsEntityTypes
import dev.openrune.query.Search
import dev.openrune.query.SearchMode
import dev.openrune.query.changedFields
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Measures the query paths the website drives. Legacy `.bin` numbers are in
 * docs/BENCHMARKS.md, captured with the dumper before it was removed.
 *
 * Flags: `revs=1,240,241 iterations=10 threads=16 report=build/benchmark-postgres.md`
 */
fun main(args: Array<String>) {
    val flags = ToolArgs(args)
    val revs = flags.ints("revs").sorted().ifEmpty { listOf(1, 240, 241) }
    val iterations = flags.int("iterations") ?: 10
    val threads = flags.int("threads") ?: 16
    val lines = ArrayList<String>()
    fun out(s: String) { lines.add(s); println(s) }

    out("# Benchmark (PostgreSQL)")
    out("")
    out("Revisions ${revs.joinToString()}; $iterations timed iterations after 3 warm-ups; times in ms.")
    out("")
    out("| operation | p50 | p95 | max | notes |")
    out("|-----------|-----|-----|-----|-------|")

    resetPeakHeap()
    val coldStart = System.nanoTime()
    openPlatform(flags, GameType.OLDSCHOOL, CacheEnvironment.LIVE).use { platform ->
        out("| cold start (connect + registry) | ${(System.nanoTime() - coldStart) / 1_000_000} | | | peak heap ${peakHeapMb()}MB |")
        val latest = revs.last()
        val previous = revs[revs.size - 2]
        val first = revs.first()
        val items = platform.game.type("items")
        val npcs = platform.game.type("npcs")
        val sprites = platform.game.type(OsrsEntityTypes.SPRITES)
        val configTypes = platform.game.types.filter { it.def.kind == EntityKind.CONFIG }

        fun bench(name: String, notes: () -> String = { "" }, op: () -> Any?) =
            out(measure(name, iterations, notes, op))

        bench("revision list") { platform.catalog.published() }
        bench("entity lookup items#4151 @$latest") { platform.entities.get(items, latest, 4151) }
        bench("entity search items name~dragon @$latest (page 50)") { platform.entities.page(items, latest, 0, 50, Search(SearchMode.NAME, "dragon")) }
        bench("items page offset 5000 limit 50 @$latest") { platform.entities.page(items, latest, 5000, 50) }
        bench("items page keyset after 5000 limit 50 @$latest") { platform.entities.page(items, latest, 0, 50, afterId = 5000) }
        bench("diff summary adjacent $previous->$latest (all config types)") { platform.diffs.countsForTypes(configTypes, previous, latest) }
        bench("diff summary distant $first->$latest") { platform.diffs.countsForTypes(configTypes, first, latest) }
        // Old path: ship both payload bodies per changed entity to the JVM and diff them here.
        bench("diff content items $first->$latest (JVM diff, whole set)", { "${platform.entities.countAt(items, latest)} items at $latest" }) {
            var n = 0
            platform.diffs.forEachEntry(items, first, latest) { e ->
                if (e.kind == ChangeKind.CHANGED) changedFields(e.oldPayload!!.asJsonObject, e.newPayload!!.asJsonObject)
                n++
            }
            n
        }
        // Same reduction, walked a page at a time instead of in one pass.
        bench("diff content items $first->$latest (paged, whole set)") {
            var n = 0
            var after: Int? = null
            while (true) {
                val page = platform.diffs.changesPage(items, first, latest, limit = 500, afterId = after)
                n += page.rows.size
                after = page.nextCursor ?: break
            }
            n
        }
        // What a user actually waits for: the first screen of a base-to-tip diff.
        bench("diff first page items $first->$latest (100 rows)") { platform.diffs.changesPage(items, first, latest, limit = 100) }
        bench("diff first page items $previous->$latest (100 rows)") { platform.diffs.changesPage(items, previous, latest, limit = 100) }
        bench("sprite delta $previous->$latest") { platform.diffs.ids(sprites, previous, latest) }
        bench("entity history items#4151 (all revisions)") { platform.entities.history(items, 4151) }

        out(
            concurrent("mixed requests (lookup, search, summary)", threads, 30) { i ->
                when (i % 3) {
                    0 -> platform.entities.get(items, latest, i * 7 % 30000)
                    1 -> platform.entities.page(npcs, latest, 0, 50, Search(SearchMode.NAME, "man"))
                    else -> platform.diffs.countsForTypes(configTypes, previous, latest)
                }
            },
        )
        out("| peak heap after run | | | | ${peakHeapMb()}MB (used now ${usedHeapMb()}MB) |")
    }

    val report = File(flags["report"] ?: "build/benchmark-postgres.md")
    report.parentFile?.mkdirs()
    report.writeText(lines.joinToString("\n") + "\n")
    println("Report written to ${report.absolutePath}")
}

private fun row(name: String, samples: LongArray, notes: String): String {
    val sorted = samples.sorted()
    fun pct(p: Double) = sorted[((sorted.size - 1) * p).roundToInt()] / 1_000_000.0
    return "| $name | ${"%.2f".format(pct(0.5))} | ${"%.2f".format(pct(0.95))} | ${"%.2f".format(sorted.last() / 1_000_000.0)} | $notes |"
}

private fun measure(name: String, iterations: Int, notes: () -> String, op: () -> Any?): String {
    repeat(3) { op() }
    val samples = LongArray(iterations) {
        val start = System.nanoTime()
        op()
        System.nanoTime() - start
    }
    return row(name, samples, notes())
}

private fun concurrent(name: String, threads: Int, perThread: Int, op: (Int) -> Unit): String {
    val pool = Executors.newFixedThreadPool(threads)
    val samples = LongArray(threads * perThread)
    val start = System.nanoTime()
    try {
        (0 until threads).map { t ->
            pool.submit {
                repeat(perThread) { i ->
                    val index = t * perThread + i
                    val s = System.nanoTime()
                    op(index)
                    samples[index] = System.nanoTime() - s
                }
            }
        }.forEach { it.get() }
    } finally {
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.MINUTES)
    }
    val wall = (System.nanoTime() - start) / 1_000_000
    return row(name, samples, "$threads threads x $perThread, wall ${wall}ms, ${(samples.size * 1000.0 / wall).roundToInt()} req/s")
}
