package dev.openrune.query

import dev.openrune.store.RevisionRepository

/**
 * Published revisions of one game as seen by the API. The published set only changes when a
 * revision is published or unpublished, which bumps `publish_stamp`; the stamp is re-read at most
 * once per second and everything else is derived from it, so a request costs no revision queries.
 */
class RevisionCatalog(
    private val repository: RevisionRepository,
    private val gameId: Int,
    private val refreshIntervalMs: Long = 1_000,
) {
    private class State(val stamp: Long, val published: List<Int>, val set: Set<Int>, val checkedAt: Long)

    @Volatile
    private var state: State? = null

    private fun current(): State {
        val now = System.currentTimeMillis()
        val s = state
        if (s != null && now - s.checkedAt < refreshIntervalMs) return s
        synchronized(this) {
            val again = state
            if (again != null && now - again.checkedAt < refreshIntervalMs) return again
            val stamp = repository.publishStamp(gameId)
            val next = if (again != null && again.stamp == stamp) {
                State(stamp, again.published, again.set, now)
            } else {
                val list = repository.published(gameId)
                State(stamp, list, list.toHashSet(), now)
            }
            state = next
            return next
        }
    }

    /** Force the next call to re-read the published set (used after publish in the same process). */
    fun invalidate() {
        state = null
    }

    fun published(): List<Int> = current().published

    fun latest(): Int? = current().published.lastOrNull()

    fun stamp(): Long = current().stamp

    fun isPublished(rev: Int): Boolean = rev in current().set

    /** `latest`, blank or a number; null when the value is unparseable. */
    fun resolve(param: String?): Int? {
        if (param.isNullOrBlank() || param.equals("latest", ignoreCase = true)) return latest()
        return param.toIntOrNull()
    }
}
