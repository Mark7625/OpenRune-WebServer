package dev.openrune.ingest

/**
 * A cache link pasted by an operator: any URL serving the `disk.zip` of a raw cache, used instead
 * of waiting for [RevisionDiscovery] to find the revision on OpenRS2.
 *
 * OpenRS2 links are recognised rather than treated as opaque, because their cache id is what the
 * rest of the pipeline normally gets from the catalogue: it resolves the revision number so the
 * operator does not have to type one, and it names the matching `keys.json` for the XTEA keys that
 * revisions before 237 need. A link anywhere else is downloaded as-is and needs an explicit
 * revision number.
 */
object ManualCacheLink {

    /** `revision.source` for a revision added from a link. */
    const val SOURCE = "manual"

    /** `https://archive.openrs2.org/caches/<scope>/<id>` with or without a trailing file. */
    private val OPENRS2 = Regex("""^https?://archive\.openrs2\.org/caches/([^/?#]+)/(\d+)(?:/([^?#]*))?$""", RegexOption.IGNORE_CASE)

    /**
     * The URL to download, from whatever the operator pasted. An OpenRS2 cache page
     * (`.../caches/runescape/2746`) is completed to its archive, so either form of the link works.
     */
    fun normalise(raw: String): String {
        val url = raw.trim()
        require(url.startsWith("http://", true) || url.startsWith("https://", true)) {
            "A cache link must be an http(s) URL, got: $raw"
        }
        val match = OPENRS2.matchEntire(url) ?: return url
        val (scope, id, file) = match.destructured
        return if (file.isBlank()) "https://archive.openrs2.org/caches/$scope/$id/disk.zip" else url
    }

    /** The OpenRS2 catalogue id a link points at, or null for a link hosted anywhere else. */
    fun openRs2CacheId(url: String): Int? = OPENRS2.matchEntire(url.trim())?.groupValues?.get(2)?.toIntOrNull()
}
