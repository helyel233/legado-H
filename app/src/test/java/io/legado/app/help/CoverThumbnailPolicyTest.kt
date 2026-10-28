package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面缩略图目录回收策略的回归网
 */
class CoverThumbnailPolicyTest {

    private fun entry(name: String, ageMs: Long, length: Long) =
        CoverThumbnailPolicy.Entry(
            name = name,
            lastModified = NOW - ageMs,
            length = length
        )

    @Test
    fun `removes files older than the retention period`() {
        val removal = CoverThumbnailPolicy.selectForRemoval(
            entries = listOf(
                entry("old.png", ageMs = 10_000L, length = 100L),
                entry("fresh.png", ageMs = 1_000L, length = 100L)
            ),
            now = NOW,
            maxAgeMs = 5_000L,
            maxBytes = 1_000L
        )
        assertEquals(listOf("old.png"), removal)
    }

    @Test
    fun `keeps everything when under the size limit`() {
        val removal = CoverThumbnailPolicy.selectForRemoval(
            entries = listOf(
                entry("a.png", ageMs = 100L, length = 100L),
                entry("b.png", ageMs = 200L, length = 200L)
            ),
            now = NOW,
            maxAgeMs = 5_000L,
            maxBytes = 1_000L
        )
        assertTrue(removal.isEmpty())
    }

    @Test
    fun `removes oldest first when over the size limit`() {
        val removal = CoverThumbnailPolicy.selectForRemoval(
            entries = listOf(
                entry("newest.png", ageMs = 100L, length = 400L),
                entry("oldest.png", ageMs = 900L, length = 400L),
                entry("middle.png", ageMs = 500L, length = 400L)
            ),
            now = NOW,
            maxAgeMs = 60_000L,
            maxBytes = 500L
        )
        // 需要删到 500 以内：先删最旧的 400（剩 800），再删中间的 400（剩 400）
        assertEquals(listOf("oldest.png", "middle.png"), removal)
    }

    @Test
    fun `expired files are removed before the size based eviction`() {
        val removal = CoverThumbnailPolicy.selectForRemoval(
            entries = listOf(
                entry("expired.png", ageMs = 10_000L, length = 100L),
                entry("alive.png", ageMs = 100L, length = 900L)
            ),
            now = NOW,
            maxAgeMs = 5_000L,
            maxBytes = 100L
        )
        assertEquals("expired.png", removal.first())
        assertTrue(removal.contains("alive.png"))
    }

    @Test
    fun `empty directory needs no removal`() {
        val removal = CoverThumbnailPolicy.selectForRemoval(
            entries = emptyList(),
            now = NOW,
            maxAgeMs = 5_000L,
            maxBytes = 100L
        )
        assertTrue(removal.isEmpty())
    }

    companion object {
        private const val NOW = 1_000_000L
    }
}
