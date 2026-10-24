package io.legado.app.help.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评论泡识别回归（含段落规则 pclick）。
 *
 * 背景：识别逻辑此前只认 src 选项里的 click/js，段落规则段评（键为 pclick）
 * 永远进不了快照管线，预热带因此抓不到任何段落规则段评。
 */
class ReviewButtonExtractionTest {

    @Test
    fun extractsPclickButton() {
        val content = """<p>text<img src="https://example.com/bubble.svg,{"style":"TEXT","pclick":"rule:12:java.showBrowser('u')"}"></p>"""

        val extraction = ReviewSnapshotManager.extractReviewButtonsWithStats(content)

        assertEquals(1, extraction.buttons.size)
        val button = extraction.buttons.first()
        assertEquals("https://example.com/bubble.svg,{\"style\":\"TEXT\",\"pclick\":\"rule:12:java.showBrowser('u')\"}", button.src)
        assertEquals("rule:12:java.showBrowser('u')", button.pclick)
        assertTrue(button.hasAction)
        assertNull(button.click)
        assertNull(button.js)
    }

    @Test
    fun extractsLegacyClickAndJsButtons() {
        val clickContent = """<img src="https://a.com/x.svg,{"style":"TEXT","click":"java.showBrowser('u')"}">"""
        val jsContent = """<img src="https://a.com/y.svg,{"style":"text","js":"java.ajax('u')"}">"""

        val clickButton = ReviewSnapshotManager.extractReviewButtons(clickContent).first()
        assertEquals("java.showBrowser('u')", clickButton.click)
        assertNull(clickButton.pclick)
        assertTrue(clickButton.hasAction)

        val jsButton = ReviewSnapshotManager.extractReviewButtons(jsContent).first()
        assertEquals("java.ajax('u')", jsButton.js)
        assertTrue(jsButton.hasAction)
    }

    @Test
    fun keepsBothPclickAndClickWhenSourceProvidesBoth() {
        val content = """<img src="https://a.com/z.svg,{"style":"TEXT","pclick":"rule:1:js","click":"legacy"}">"""

        val button = ReviewSnapshotManager.extractReviewButtons(content).first()

        assertEquals("rule:1:js", button.pclick)
        assertEquals("legacy", button.click)
        assertTrue(button.hasAction)
    }

    @Test
    fun nonTextStyleAndActionlessButtonsAreNotCaptured() {
        val nonText = """<img src="https://a.com/a.svg,{"style":"IMG","pclick":"rule:1:js"}">"""
        val actionless = """<img src="https://a.com/b.svg,{"style":"TEXT"}">"""

        val nonTextExtraction = ReviewSnapshotManager.extractReviewButtonsWithStats(nonText)
        assertTrue(nonTextExtraction.buttons.isEmpty())
        assertEquals(1, nonTextExtraction.nonTextStyle)

        // 无动作的泡仍会被收集（hasAction=false），由预热带过滤；
        // 计数进 noAction 供缓存诊断展示
        val actionlessExtraction = ReviewSnapshotManager.extractReviewButtonsWithStats(actionless)
        assertEquals(1, actionlessExtraction.buttons.size)
        assertFalse(actionlessExtraction.buttons.first().hasAction)
        assertEquals(1, actionlessExtraction.noAction)
    }

    @Test
    fun brokenOptionsJsonIsCountedNotTreatedAsButton() {
        val content = """<img src="https://a.com/c.svg,{not-json">"""

        val extraction = ReviewSnapshotManager.extractReviewButtonsWithStats(content)

        assertTrue(extraction.buttons.isEmpty())
        assertEquals(1, extraction.optionParseFailed)
        assertEquals(1, extraction.imgCount)
    }

    @Test
    fun sameSrcIsDeduped() {
        val content = """<img src="https://a.com/d.svg,{"style":"TEXT","pclick":"rule:1:js"}"><img src="https://a.com/d.svg,{"style":"TEXT","pclick":"rule:1:js"}">"""

        val extraction = ReviewSnapshotManager.extractReviewButtonsWithStats(content)

        assertEquals(1, extraction.buttons.size)
        assertEquals(2, extraction.imgCount)
    }

    @Test
    fun contentWithoutImagesYieldsEmptyExtraction() {
        val extraction = ReviewSnapshotManager.extractReviewButtonsWithStats("plain text only")

        assertTrue(extraction.buttons.isEmpty())
        assertEquals(0, extraction.imgCount)
        assertFalse(extraction.buttons.any { it.hasAction })
    }
}
