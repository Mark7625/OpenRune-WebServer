package dev.openrune.ingest

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ManualCacheLinkTest {

    @Test
    fun `an openrs2 disk zip link is kept as it is`() {
        val url = "https://archive.openrs2.org/caches/runescape/2746/disk.zip"
        assertEquals(url, ManualCacheLink.normalise(url))
        assertEquals(2746, ManualCacheLink.openRs2CacheId(url))
    }

    @Test
    fun `an openrs2 cache page is completed to its archive`() {
        val expected = "https://archive.openrs2.org/caches/runescape/2746/disk.zip"
        assertEquals(expected, ManualCacheLink.normalise("https://archive.openrs2.org/caches/runescape/2746"))
        assertEquals(expected, ManualCacheLink.normalise("https://archive.openrs2.org/caches/runescape/2746/"))
        assertEquals(expected, ManualCacheLink.normalise("  https://archive.openrs2.org/caches/runescape/2746  "))
    }

    @Test
    fun `a link hosted elsewhere is left alone and has no cache id`() {
        val url = "https://example.com/caches/my-cache.zip"
        assertEquals(url, ManualCacheLink.normalise(url))
        assertNull(ManualCacheLink.openRs2CacheId(url))
    }

    @Test
    fun `only http urls are accepted`() {
        assertFailsWith<IllegalArgumentException> { ManualCacheLink.normalise("file:///tmp/disk.zip") }
        assertFailsWith<IllegalArgumentException> { ManualCacheLink.normalise("archive.openrs2.org/caches/runescape/2746") }
    }
}
