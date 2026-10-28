package io.legado.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SQLite IN 查询分片策略的回归网
 */
class SqliteInQueryPolicyTest {

    @Test
    fun `default chunk size stays below the sqlite limit`() {
        assertTrue(SqliteInQueryPolicy.DEFAULT_CHUNK_SIZE < SqliteInQueryPolicy.SQLITE_MAX_VARIABLES)
        assertEquals(500, SqliteInQueryPolicy.DEFAULT_CHUNK_SIZE)
    }

    @Test
    fun `chunk size validation rejects illegal values`() {
        assertTrue(SqliteInQueryPolicy.isSafeChunkSize(1))
        assertTrue(SqliteInQueryPolicy.isSafeChunkSize(999))
        assertFalse(SqliteInQueryPolicy.isSafeChunkSize(0))
        assertFalse(SqliteInQueryPolicy.isSafeChunkSize(-1))
        assertFalse(SqliteInQueryPolicy.isSafeChunkSize(1000))
    }

    @Test
    fun `empty list produces no chunks`() {
        assertTrue(SqliteInQueryPolicy.chunk(emptyList<Int>()).isEmpty())
    }

    @Test
    fun `list smaller than the chunk size stays in one chunk`() {
        val chunks = SqliteInQueryPolicy.chunk(listOf(1, 2, 3))
        assertEquals(1, chunks.size)
        assertEquals(listOf(1, 2, 3), chunks.first())
    }

    @Test
    fun `large list is split into several chunks without losing items`() {
        val items = (1..1200).toList()
        val chunks = SqliteInQueryPolicy.chunk(items)
        assertEquals(3, chunks.size)
        assertEquals(500, chunks[0].size)
        assertEquals(500, chunks[1].size)
        assertEquals(200, chunks[2].size)
        assertEquals(items, chunks.flatten())
    }

    @Test
    fun `custom chunk size is respected`() {
        val chunks = SqliteInQueryPolicy.chunk((1..10).toList(), chunkSize = 3)
        assertEquals(listOf(3, 3, 3, 1), chunks.map { it.size })
    }

    @Test
    fun `illegal chunk size fails fast`() {
        var failed = false
        try {
            SqliteInQueryPolicy.chunk(listOf(1, 2, 3), chunkSize = 0)
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
