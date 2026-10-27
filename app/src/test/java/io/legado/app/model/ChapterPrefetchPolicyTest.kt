package io.legado.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下一章预取决策的回归网
 */
class ChapterPrefetchPolicyTest {

    @Test
    fun `prefetches when everything is ready`() {
        assertTrue(ChapterPrefetchPolicy.shouldPrefetch(true, false, true, true, false, false))
    }

    @Test
    fun `does not prefetch in any blocked situation`() {
        // 开关关闭
        assertFalse(ChapterPrefetchPolicy.shouldPrefetch(false, false, true, true, false, false))
        // 本地书
        assertFalse(ChapterPrefetchPolicy.shouldPrefetch(true, true, true, true, false, false))
        // 无网络
        assertFalse(ChapterPrefetchPolicy.shouldPrefetch(true, false, false, true, false, false))
        // 没有下一章
        assertFalse(ChapterPrefetchPolicy.shouldPrefetch(true, false, true, false, false, false))
        // 下一章已有缓存
        assertFalse(ChapterPrefetchPolicy.shouldPrefetch(true, false, true, true, true, false))
        // 下一章正在下载
        assertFalse(ChapterPrefetchPolicy.shouldPrefetch(true, false, true, true, false, true))
    }

    @Test
    fun `recognizes load failure content`() {
        assertTrue(ChapterPrefetchPolicy.isLoadFailureContent("获取正文失败 timeout"))
        assertTrue(ChapterPrefetchPolicy.isLoadFailureContent("加载正文失败 没有书源"))
        assertFalse(ChapterPrefetchPolicy.isLoadFailureContent("第一章 正文内容"))
        assertFalse(ChapterPrefetchPolicy.isLoadFailureContent(""))
    }
}
