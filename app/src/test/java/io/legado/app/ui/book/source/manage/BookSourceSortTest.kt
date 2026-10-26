package io.legado.app.ui.book.source.manage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 书源页排序持久化解析的回归网：存 name 而非 ordinal，
 * 坏偏好（历史遗留/未来枚举改名）必须静默回退手动排序，不能炸掉书源页。
 */
class BookSourceSortTest {

    @Test
    fun parsesEveryStoredName() {
        BookSourceSort.entries.forEach { sort ->
            assertEquals(sort, parseBookSourceSort(sort.name))
        }
    }

    @Test
    fun fallsBackToDefaultOnUnknownName() {
        // 未来某版本枚举成员改名/删除后，旧偏好值应回退而不是抛异常
        assertEquals(BookSourceSort.Default, parseBookSourceSort("Legacy"))
    }

    @Test
    fun fallsBackToDefaultOnEmptyOrMissing() {
        assertEquals(BookSourceSort.Default, parseBookSourceSort(""))
        assertEquals(BookSourceSort.Default, parseBookSourceSort(null))
    }
}
