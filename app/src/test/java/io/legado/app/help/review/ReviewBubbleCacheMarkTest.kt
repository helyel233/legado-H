package io.legado.app.help.review

import io.legado.app.model.ParagraphBubbleRenderer
import io.legado.app.ui.book.read.page.entities.ReviewBubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 段评气泡「已缓存」标记的两条判定：
 * 书源原生气泡由绘制层补点，派生气泡把角标画进 SVG。
 */
class ReviewBubbleCacheMarkTest {

    // --- 书源原生气泡判定 ---

    @Test
    fun textStyleWithCommentClickIsParagraphBubble() {
        assertTrue(ReviewBubble.isParagraphBubble("TEXT", """showcmt("b","c","12","")"""))
        assertTrue(ReviewBubble.isParagraphBubble("text", "startBrowser('/comment?id=1')"))
        assertTrue(ReviewBubble.isParagraphBubble(null, "openParagraph(12)"))
    }

    @Test
    fun imageWithoutParagraphClickIsNotBubble() {
        assertFalse(ReviewBubble.isParagraphBubble("TEXT", null))
        assertFalse(ReviewBubble.isParagraphBubble("TEXT", "   "))
        // 普通插图点击：不能因为图片是内联小图就给段评角标
        assertFalse(ReviewBubble.isParagraphBubble("TEXT", "showPhoto('a.jpg')"))
    }

    @Test
    fun nonTextStyleIsNeverTreatedAsBubble() {
        // 大图/宫格样式即便 click 命中段评语义，也不是内联气泡
        assertFalse(ReviewBubble.isParagraphBubble("FULL", """showcmt("b","c","12","")"""))
    }

    // --- 派生气泡的 SVG 角标 ---

    @Test
    fun markIsInjectedBeforeClosingTag() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">""" +
            """<text x="32" y="32">23</text></svg>"""
        val marked = ParagraphBubbleRenderer.injectCachedMark(svg, "#FF0000")

        assertTrue(marked.contains("<circle"))
        assertTrue(marked.endsWith("</svg>"))
        assertTrue(marked.contains("#FF0000"))
        // 角标必须落在原有图形之后、闭合标签之前，否则会被模板内容覆盖
        assertTrue(marked.indexOf("<circle") > marked.indexOf("<text"))
        assertTrue(marked.indexOf("</svg>") > marked.indexOf("<circle"))
    }

    @Test
    fun templateWithoutViewBoxIsLeftUntouched() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><text>23</text></svg>"""
        assertEquals(svg, ParagraphBubbleRenderer.injectCachedMark(svg, "#FF0000"))
    }

    @Test
    fun malformedViewBoxIsLeftUntouched() {
        val svg = """<svg viewBox="0 0 64"><text>23</text></svg>"""
        assertEquals(svg, ParagraphBubbleRenderer.injectCachedMark(svg, "#FF0000"))
    }

    @Test
    fun markFollowsTemplateViewBox() {
        // 自定义气泡包常用非 64 基准：角标按各自 viewBox 的右上角落位
        val svg = """<svg viewBox="10 20 32 32"><text>9</text></svg>"""
        val marked = ParagraphBubbleRenderer.injectCachedMark(svg, "#00FF00")

        // 半径 = min(32,32)*0.19 = 6.08，圆心 x = 10+32-6.08*0.94 ≈ 36.28
        assertTrue(marked.contains("cx=\"36.28\""))
        assertTrue(marked.contains("cy=\"25.72\""))
        assertTrue(marked.contains("r=\"6.08\""))
    }

    @Test
    fun numbersUseDotDecimalSeparator() {
        // 小数点若被区域设置成逗号，SVG 属性会解析失败（cx="3,14"）
        assertEquals("3.14", ParagraphBubbleRenderer.fixed(3.14159f))
    }
}
