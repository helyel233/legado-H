package io.legado.app.help.review

import android.app.Application
import io.legado.app.data.entities.BookChapter
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ReviewBubbleRawButtonSrcTest {

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
    }

    @Test
    fun `reviewButtonSrc prefers rawButtonSrc and falls back to src when blank or null`() {
        val derived = ImageColumn(
            start = 0f,
            end = 24f,
            src = "bubble://paragraph?displayText=5&num=5&status=normal",
            rawButtonSrc = "dp:5,{pclick:101}"
        )
        assertEquals("dp:5,{pclick:101}", derived.reviewButtonSrc)

        val fallbackNull = ImageColumn(
            start = 0f,
            end = 24f,
            src = "https://example.com/bubble.svg,{style:TEXT}",
            rawButtonSrc = null
        )
        assertEquals("https://example.com/bubble.svg,{style:TEXT}", fallbackNull.reviewButtonSrc)

        val fallbackBlank = ImageColumn(
            start = 0f,
            end = 24f,
            src = "bubble://paragraph?displayText=5&num=5&status=normal",
            rawButtonSrc = "   "
        )
        assertEquals("bubble://paragraph?displayText=5&num=5&status=normal", fallbackBlank.reviewButtonSrc)
    }

    @Test
    fun `markReviewSnapshotCached hits rawButtonSrc and isolates paragraphs with identical count`() {
        val sharedRenderSrc = "bubble://paragraph?displayText=5&num=5&status=normal"
        val rawPara1 = "dp:5,{pclick:101}"
        val rawPara2 = "dp:5,{pclick:202}"
        val nativeSrc = "https://example.com/native.svg,{\"style\":\"TEXT\",\"click\":\"showcmt(1)\"}"

        val colPara1 = ImageColumn(
            start = 0f,
            end = 24f,
            src = sharedRenderSrc,
            click = "paragraphRule:rule:1:p1",
            rawButtonSrc = rawPara1
        )
        val colPara2 = ImageColumn(
            start = 0f,
            end = 24f,
            src = sharedRenderSrc,
            click = "paragraphRule:rule:1:p2",
            rawButtonSrc = rawPara2
        )
        val colNative = ImageColumn(
            start = 0f,
            end = 24f,
            src = nativeSrc,
            click = "showcmt(1)",
            reviewBubble = true
        )

        val line1 = TextLine(text = "段1 ").apply { addColumn(colPara1) }
        val line2 = TextLine(text = "段2 ").apply { addColumn(colPara2) }
        val line3 = TextLine(text = "段3 ").apply { addColumn(colNative) }

        val page = TextPage(text = "p", title = "t").apply {
            addLine(line1)
            addLine(line2)
            addLine(line3)
        }
        val textChapter = TextChapter(
            chapter = BookChapter(url = "https://example.com/ch1", title = "第一章", bookUrl = "https://example.com/book"),
            position = 0,
            title = "第一章",
            chaptersSize = 1,
            sameTitleRemoved = false,
            isVip = false,
            isPay = false,
            effectiveReplaceRules = null
        )
        @Suppress("UNCHECKED_CAST")
        (textChapter.pages as MutableList<TextPage>).add(page)

        // 按原始 dp:...{pclick:101} 点亮：仅第一段置位，同评论数(5)的第二段保持未缓存
        textChapter.markReviewSnapshotCached("  $rawPara1  ")
        assertTrue(colPara1.reviewCached)
        assertFalse(colPara2.reviewCached)
        assertFalse(colNative.reviewCached)

        // 熄灭第一段角标，点亮第二段与原生气泡
        textChapter.markReviewSnapshotCached(rawPara1, cached = false)
        textChapter.markReviewSnapshotCached(rawPara2, cached = true)
        textChapter.markReviewSnapshotCached(nativeSrc, cached = true)
        assertFalse(colPara1.reviewCached)
        assertTrue(colPara2.reviewCached)
        assertTrue(colNative.reviewCached)
    }
}
