package io.legado.app.model.localBook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `EpubCss` 是整套 Epub 渲染的 CSS 解析基础，被 `TextChapterLayout`（阅读分页）直接调用，
 * 例如 `splitValueList` 参与 margin/padding 简写取边。这里锁住切分与声明解析的边界，
 * 避免"引号/括号内的分隔符被误切"这类会让 Epub 样式整体错乱的回归。
 */
class EpubCssTest {

    // --- splitDeclarations ---------------------------------------------------

    @Test
    fun splitsOnSemicolons() {
        assertEquals(
            listOf("color:red", "font-size:12px"),
            EpubCss.splitDeclarations("color:red;font-size:12px")
        )
    }

    @Test
    fun keepsSemicolonInsideParens() {
        // url(a;b.png) 里的分号属于值，不能切分
        assertEquals(
            listOf("background:url(a;b.png)", "color:red"),
            EpubCss.splitDeclarations("background:url(a;b.png);color:red")
        )
    }

    @Test
    fun keepsSemicolonInsideQuotes() {
        assertEquals(
            listOf("content:\"a;b\"", "color:red"),
            EpubCss.splitDeclarations("content:\"a;b\";color:red")
        )
    }

    @Test
    fun handlesMissingTrailingSemicolon() {
        assertEquals(listOf("color:red"), EpubCss.splitDeclarations("color:red"))
    }

    // --- splitValueList ------------------------------------------------------

    @Test
    fun splitsValuesOnWhitespace() {
        assertEquals(listOf("1px", "2px"), EpubCss.splitValueList("1px 2px"))
    }

    @Test
    fun keepsSpacesInsideFunction() {
        assertEquals(listOf("rgb(1, 2, 3)", "4px"), EpubCss.splitValueList("rgb(1, 2, 3) 4px"))
    }

    @Test
    fun keepsSpacesInsideQuotes() {
        assertEquals(listOf("'a b'", "2px"), EpubCss.splitValueList("'a b' 2px"))
    }

    @Test
    fun collapsesExtraWhitespace() {
        assertEquals(listOf("1px", "2px"), EpubCss.splitValueList("  1px \t 2px \n"))
    }

    @Test
    fun blankValueYieldsNoValues() {
        assertEquals(emptyList<String>(), EpubCss.splitValueList("   "))
    }

    // --- declarations --------------------------------------------------------

    @Test
    fun lowercasesPropertyNames() {
        assertEquals("red", EpubCss.declarations("COLOR:red")["color"])
    }

    @Test
    fun stripsImportant() {
        assertEquals("red", EpubCss.declarations("color:red !important")["color"])
    }

    @Test
    fun normalizesDoubleQuotesToSingle() {
        assertEquals("'Arial'", EpubCss.declarations("font-family:\"Arial\"")["font-family"])
    }

    @Test
    fun mapsDuokanTextIndentToItsLastValue() {
        assertEquals("2em", EpubCss.declarations("duokan-text-indent:1em 2em")["text-indent"])
    }

    @Test
    fun skipsEntriesWithoutAColon() {
        assertEquals(0, EpubCss.declarations("no-colon-here").size)
    }
}
