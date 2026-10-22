package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDisplayStylePolicyTest {

    private val full = Book.imgStyleFull
    private val single = Book.imgStyleSingle

    @Test
    fun aLocalImageBookDefaultsToFullPageStyle() {
        val decision = BookDisplayStylePolicy.decide(
            isLocal = true, isImageOrPdf = true, currentStyle = null, sourceStyle = null
        )
        assertEquals(BookDisplayStyleDecision.Set(full, false), decision)
    }

    @Test
    fun aLocalTextBookKeepsItsStyle() {
        val decision = BookDisplayStylePolicy.decide(
            isLocal = true, isImageOrPdf = false, currentStyle = null, sourceStyle = null
        )
        assertEquals(BookDisplayStyleDecision.Keep, decision)
    }

    @Test
    fun aLocalBookWithAStyleIsLeftAlone() {
        val decision = BookDisplayStylePolicy.decide(
            isLocal = true, isImageOrPdf = true, currentStyle = full, sourceStyle = null
        )
        assertEquals(BookDisplayStyleDecision.Keep, decision)
    }

    @Test
    fun aWebBookWithAStyleIsLeftAlone() {
        val decision = BookDisplayStylePolicy.decide(
            isLocal = false, isImageOrPdf = false, currentStyle = full, sourceStyle = single
        )
        assertEquals(BookDisplayStyleDecision.Keep, decision)
    }

    @Test
    fun aWebBookTakesTheStyleItsSourceDefines() {
        val decision = BookDisplayStylePolicy.decide(
            isLocal = false, isImageOrPdf = false, currentStyle = null, sourceStyle = single
        )
        assertEquals(BookDisplayStyleDecision.Set(single, true), decision)
    }

    @Test
    fun theSingleStyleCheckIgnoresCase() {
        val decision = BookDisplayStylePolicy.decide(
            isLocal = false, isImageOrPdf = false, currentStyle = null, sourceStyle = "SiNgLe"
        )
        assertTrue(
            "大小写不同的 single 也要重置翻页动画",
            (decision as BookDisplayStyleDecision.Set).resetPageAnim
        )
    }

    @Test
    fun aWebImageBookWithoutASourceStyleDefaultsToFullPage() {
        val decision = BookDisplayStylePolicy.decide(
            isLocal = false, isImageOrPdf = true, currentStyle = null, sourceStyle = null
        )
        assertEquals(BookDisplayStyleDecision.Set(full, false), decision)
    }

    @Test
    fun aWebTextBookWithNoStyleAnywhereClearsTheStyle() {
        // 清空是真实结果：换书后不能沿用上一本书的图片样式。
        val decision = BookDisplayStylePolicy.decide(
            isLocal = false, isImageOrPdf = false, currentStyle = null, sourceStyle = null
        )
        assertEquals(BookDisplayStyleDecision.Set(null, false), decision)
    }

    @Test
    fun aBookSourceStyleAlwaysWinsOverTheFullPageFallback() {
        val decision = BookDisplayStylePolicy.decide(
            isLocal = false, isImageOrPdf = true, currentStyle = null, sourceStyle = "other"
        )
        assertEquals(BookDisplayStyleDecision.Set("other", false), decision)
    }
}
