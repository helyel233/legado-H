package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节索引策略的回归网
 */
class ReadBookChapterIndexPolicyTest {

    @Test
    fun rejectsAdjacentIndexesOutsideKnownChapterList() {
        assertFalse(ReadBookChapterIndexPolicy.canLoad(-1, 1485))
        assertFalse(ReadBookChapterIndexPolicy.canLoad(1485, 1485))
        assertTrue(ReadBookChapterIndexPolicy.canLoad(0, 1485))
        assertTrue(ReadBookChapterIndexPolicy.canLoad(1484, 1485))
    }

    @Test
    fun keepsMissingListOnNormalErrorPath() {
        assertFalse(ReadBookChapterIndexPolicy.canLoad(-1, 0))
        assertTrue(ReadBookChapterIndexPolicy.canLoad(0, 0))
    }

    @Test
    fun `can select only indexes inside the chapter count`() {
        assertTrue(ReadBookChapterIndexPolicy.canSelect(0, 3))
        assertFalse(ReadBookChapterIndexPolicy.canSelect(3, 3))
    }

    @Test
    fun `clamp keeps index inside the chapter list`() {
        assertEquals(0, ReadBookChapterIndexPolicy.clamp(0, 10))
        assertEquals(9, ReadBookChapterIndexPolicy.clamp(9, 10))
        assertEquals(9, ReadBookChapterIndexPolicy.clamp(999, 10))
        assertEquals(0, ReadBookChapterIndexPolicy.clamp(-5, 10))
    }

    @Test
    fun `clamp keeps reading position when chapter list is unknown`() {
        // 目录还没拉到（size<=0）：不能把进度重置成 0，否则会丢阅读位置
        assertEquals(42, ReadBookChapterIndexPolicy.clamp(42, 0))
        assertEquals(0, ReadBookChapterIndexPolicy.clamp(-3, 0))
        assertEquals(7, ReadBookChapterIndexPolicy.clamp(7, -1))
    }

    @Test
    fun `clamp with a single chapter list always resolves to zero`() {
        assertEquals(0, ReadBookChapterIndexPolicy.clamp(5, 1))
        assertEquals(0, ReadBookChapterIndexPolicy.clamp(0, 1))
    }
}
