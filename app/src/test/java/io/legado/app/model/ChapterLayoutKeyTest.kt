package io.legado.app.model

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChapterLayoutKeyTest {

    private fun inputs(
        book: ChapterLayoutBookInputs? = defaultBook(),
        viewWidth: Int = 1080,
        viewHeight: Int = 1920,
        textSize: Float = 20f,
        textColor: Int = 0xff000000.toInt(),
        paragraphIndent: String = "2",
        useZhLayout: Boolean = true,
        replaceRuleKey: String = "",
        paragraphRuleKey: String = "",
        highlightRevision: String = "1"
    ) = ChapterLayoutKeyInputs(
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        visibleWidth = 1080,
        visibleHeight = 1800,
        textSize = textSize,
        textColor = textColor,
        textTypefaceStyle = 0,
        titleSize = 24f,
        titleColor = 0xff000000.toInt(),
        titleTypefaceStyle = 1,
        contentTextHeight = 30f,
        titleTextHeight = 36f,
        lineSpacingExtra = 4f,
        paragraphSpacing = 6,
        paragraphIndent = paragraphIndent,
        useZhLayout = useZhLayout,
        textFullJustify = true,
        adaptSpecialStyle = false,
        replaceRuleKey = replaceRuleKey,
        paragraphRuleKey = paragraphRuleKey,
        highlightRevision = highlightRevision,
        book = book
    )

    private fun defaultBook() = ChapterLayoutBookInputs(
        usesDirectReader = false,
        isEpub = false,
        useReplaceRule = true,
        delTag = 0L,
        reSegment = false,
        imageStyle = null,
        directReplacementRevision = null
    )

    @Test
    fun sameInputsBuildTheSameKey() {
        assertEquals(ChapterLayoutKey.of(inputs()), ChapterLayoutKey.of(inputs()))
    }

    @Test
    fun changingTheViewportChangesTheKey() {
        assertNotEquals(ChapterLayoutKey.of(inputs()), ChapterLayoutKey.of(inputs(viewWidth = 720)))
        assertNotEquals(
            ChapterLayoutKey.of(inputs()),
            ChapterLayoutKey.of(inputs(viewHeight = 2400))
        )
    }

    @Test
    fun changingTheTextStyleChangesTheKey() {
        assertNotEquals(ChapterLayoutKey.of(inputs()), ChapterLayoutKey.of(inputs(textSize = 22f)))
        assertNotEquals(
            ChapterLayoutKey.of(inputs()),
            ChapterLayoutKey.of(inputs(textColor = 0xffffffff.toInt()))
        )
    }

    @Test
    fun changingLayoutSettingsChangesTheKey() {
        assertNotEquals(
            ChapterLayoutKey.of(inputs()),
            ChapterLayoutKey.of(inputs(paragraphIndent = "0"))
        )
        assertNotEquals(
            ChapterLayoutKey.of(inputs()),
            ChapterLayoutKey.of(inputs(useZhLayout = false))
        )
    }

    @Test
    fun changingAnyRuleChangesTheKey() {
        assertNotEquals(
            ChapterLayoutKey.of(inputs()),
            ChapterLayoutKey.of(inputs(replaceRuleKey = "12:hash:hash:true:3000"))
        )
        assertNotEquals(
            ChapterLayoutKey.of(inputs()),
            ChapterLayoutKey.of(inputs(paragraphRuleKey = "rule-1"))
        )
        assertNotEquals(
            ChapterLayoutKey.of(inputs()),
            ChapterLayoutKey.of(inputs(highlightRevision = "2"))
        )
    }

    @Test
    fun changingBookSettingsChangesTheKey() {
        assertNotEquals(
            ChapterLayoutKey.of(inputs()),
            ChapterLayoutKey.of(
                inputs(
                    book = defaultBook().copy(useReplaceRule = false)
                )
            )
        )
        assertNotEquals(
            ChapterLayoutKey.of(inputs()),
            ChapterLayoutKey.of(inputs(book = defaultBook().copy(imageStyle = Book.imgStyleFull)))
        )
    }

    @Test
    fun aKeyBuiltWithoutABookDiffersFromOneBuiltWithIt() {
        // 没有书的键必须和带书的键不同，否则开书前布局的内容会被复用。
        assertNotEquals(
            ChapterLayoutKey.of(inputs(book = null)),
            ChapterLayoutKey.of(inputs(book = defaultBook()))
        )
    }

    @Test
    fun directReaderRevisionOnlyCountsForDirectReaders() {
        val direct = defaultBook().copy(usesDirectReader = true)
        assertNotEquals(
            ChapterLayoutKey.of(inputs(book = direct.copy(directReplacementRevision = "1"))),
            ChapterLayoutKey.of(inputs(book = direct.copy(directReplacementRevision = "2")))
        )
        // 普通书（非 direct reader）的修订号不进键：它不参与排版。
        val normal = defaultBook()
        assertEquals(
            ChapterLayoutKey.of(inputs(book = normal.copy(directReplacementRevision = "1"))),
            ChapterLayoutKey.of(inputs(book = normal.copy(directReplacementRevision = "2")))
        )
    }
}
