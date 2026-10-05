package dev.openrune.metrics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * In-process counters and timers exposed on the admin API. Deliberately minimal: there is one
 * process per game and the numbers that matter (ingestion stage durations, query latency, cache
 * hit rate, pool usage) fit in a map that is cheap to read and never grows past the set of names.
 */
class Metrics {
    class Timer {
        val count = LongAdder()
        val totalNanos = LongAdder()
        val maxNanos = AtomicLong()
        val lastNanos = AtomicLong()

        fun record(nanos: Long) {
            count.increment()
            totalNanos.add(nanos)
            lastNanos.set(nanos)
            maxNanos.accumulateAndGet(nanos) { a, b -> maxOf(a, b) }
        }

        fun snapshot(): Map<String, Any> {
            val n = count.sum()
            return mapOf(
                "count" to n,
                "avgMs" to if (n == 0L) 0.0 else totalNanos.sum() / n / 1_000_000.0,
                "maxMs" to maxNanos.get() / 1_000_000.0,
                "lastMs" to lastNanos.get() / 1_000_000.0,
                "totalMs" to totalNanos.sum() / 1_000_000.0,
            )
        }
    }

    class SlowEntry(val name: String, val detail: String, val millis: Double, val at: Long)

    private val counters = ConcurrentHashMap<String, LongAdder>()
    private val timers = ConcurrentHashMap<String, Timer>()
    private val slow = ArrayDeque<SlowEntry>()
    private val slowLock = Any()

    @Volatile
    var slowThresholdMs: Double = 250.0

    fun counter(name: String): LongAdder = counters.computeIfAbsent(name) { LongAdder() }

    fun increment(name: String, by: Long = 1) = counter(name).add(by)

    fun timer(name: String): Timer = timers.computeIfAbsent(name) { Timer() }

    fun <T> time(name: String, detail: () -> String = { "" }, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(name, System.nanoTime() - start, detail)
        }
    }

    fun record(name: String, nanos: Long, detail: () -> String = { "" }) {
        timer(name).record(nanos)
        val ms = nanos / 1_000_000.0
        if (ms >= slowThresholdMs) recordSlow(name, detail(), ms)
    }

    private fun recordSlow(name: String, detail: String, ms: Double) {
        synchronized(slowLock) {
            slow.addLast(SlowEntry(name, detail, ms, System.currentTimeMillis()))
            while (slow.size > 100) slow.removeFirst()
        }
    }

    fun snapshot(): Map<String, Any> = mapOf(
        "counters" to counters.mapValues { it.value.sum() }.toSortedMap(),
        "timers" to timers.mapValues { it.value.snapshot() }.toSortedMap(),
        "slow" to synchronized(slowLock) {
            slow.toList().asReversed().map { mapOf("name" to it.name, "detail" to it.detail, "ms" to it.millis, "at" to it.at) }
        },
    )
}
