package io.legado.app.help.book

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeSourceTextAnchorHelperTest {

    @After
    fun tearDown() {
        ReaderTextPositionStore.clearChangeSourceAnchors()
    }

    @Test
    fun `extracts 20-char fingerprint and resolves exact offset in new source`() {
        val pageText = "　　清晨的薄雾还未散尽，石板路上的青苔显得格外湿滑。\n　　他推开木门走了出去，迎面吹来一阵冷风。"
        val anchor = ChangeSourceTextAnchorHelper.extractAnchor(
            pageText = pageText,
            chapterContentLength = 2000,
            durChapterPos = 600
        )

        assertEquals("清晨的薄雾还未散尽，石板路上的青苔显得格", anchor.primaryFingerprint)
        assertEquals(20, anchor.primaryFingerprint.length)
        assertEquals("他推开木门走了出去，迎面吹来一阵冷风。", anchor.secondaryFingerprint)
        assertEquals(0.3f, anchor.progressRatio, 0.0001f)

        val prefix = "第一章 异界重逢\n　　夜色刚退去，山谷里静悄悄的。\n　　"
        val newChapterText = prefix + "清晨的薄雾还未散尽，石板路上的青苔显得格外湿滑。他推开木门走了出去。"
        val resolved = ChangeSourceTextAnchorHelper.resolveOffset(anchor, newChapterText)

        assertEquals(prefix.length, resolved)
        assertTrue(newChapterText.substring(resolved).startsWith("清晨的薄雾"))
    }

    @Test
    fun `matches normalized punctuation and extra whitespace across sources`() {
        val pageText = "　　“你竟敢独自闯入禁地？”黑衣人冷冷地说道，手中长剑微微颤动。\n　　林凡没有退缩，反而向前迈了一步。"
        val anchor = ChangeSourceTextAnchorHelper.extractAnchor(
            pageText = pageText,
            chapterContentLength = 1500,
            durChapterPos = 450
        )

        val prefix = "第10章\n    "
        // New source uses ASCII quotes/question mark/comma and inserts spaces and line breaks.
        val newChapterText = prefix +
            "\"你竟敢 独自闯入禁地?\" 黑衣人\n冷冷地说道,手中长剑微微颤动。林凡没有退缩。"
        val resolved = ChangeSourceTextAnchorHelper.resolveOffset(anchor, newChapterText)

        assertEquals(prefix.length + 1, resolved)
        assertTrue(newChapterText.substring(resolved).startsWith("你竟敢"))
    }

    @Test
    fun `falls back to secondary sentence fingerprint when primary sentence was altered`() {
        val pageText = "　　第一句被新书源完全删改了，或者替换成了广告语。\n　　远处的钟声沉闷地响了三声，惊起林间无数飞鸟。"
        val anchor = ChangeSourceTextAnchorHelper.extractAnchor(
            pageText = pageText,
            chapterContentLength = 1000,
            durChapterPos = 250
        )

        val prefix = "新书源替换掉的第一句。\n　　"
        val newChapterText = prefix + "远处的钟声沉闷地响了三声，惊起林间无数飞鸟。随后一切又归于平静。"
        val resolved = ChangeSourceTextAnchorHelper.resolveOffset(anchor, newChapterText)

        assertEquals(prefix.length, resolved)
        assertTrue(newChapterText.substring(resolved).startsWith("远处的钟声"))
    }

    @Test
    fun `falls back to progressRatio mapping and clamps within bounds when fingerprints miss`() {
        val anchor = ChangeSourceTextAnchorHelper.extractAnchor(
            pageText = "旧源独有的一段彻底找不到的文字内容，用来验证百分比回退。",
            chapterContentLength = 1000,
            durChapterPos = 600
        )

        val newChapterText = "A".repeat(2500)
        val resolved = ChangeSourceTextAnchorHelper.resolveOffset(anchor, newChapterText)
        assertEquals(1500, resolved)

        val fullEndAnchor = ChangeSourceTextAnchor(
            primaryFingerprint = "missing-cue-content",
            secondaryFingerprint = "",
            progressRatio = 1.5f
        )
        assertEquals(2499, ChangeSourceTextAnchorHelper.resolveOffset(fullEndAnchor, newChapterText))
    }

    @Test
    fun `handles empty text and zero chapter length boundaries safely`() {
        val emptyAnchor = ChangeSourceTextAnchorHelper.extractAnchor(
            pageText = "",
            chapterContentLength = 0,
            durChapterPos = -10
        )
        assertEquals("", emptyAnchor.primaryFingerprint)
        assertEquals("", emptyAnchor.secondaryFingerprint)
        assertEquals(0f, emptyAnchor.progressRatio, 0.0001f)
        assertEquals(0, ChangeSourceTextAnchorHelper.resolveOffset(emptyAnchor, ""))

        val nonEmptyAnchor = ChangeSourceTextAnchor("测试指纹超过四个字吧", "", 0.5f)
        assertEquals(0, ChangeSourceTextAnchorHelper.resolveOffset(nonEmptyAnchor, ""))
    }

    @Test
    fun `pending change source anchor is consumed once and cleaned up`() {
        val anchor = ChangeSourceTextAnchor("清晨的薄雾还未散尽石板路上的青苔", "备用指纹", 0.42f)
        ReaderTextPositionStore.saveChangeSourceAnchor("https://new.book/1", 12, anchor)

        assertTrue(ReaderTextPositionStore.hasChangeSourceAnchor("https://new.book/1", 12))
        val consumed = ReaderTextPositionStore.consumeChangeSourceAnchor("https://new.book/1", 12)
        assertNotNull(consumed)
        assertEquals(anchor, consumed)
        assertFalse(ReaderTextPositionStore.hasChangeSourceAnchor("https://new.book/1", 12))
        assertNull(ReaderTextPositionStore.consumeChangeSourceAnchor("https://new.book/1", 12))
    }

    @Test
    fun `source switch quality policy flags load failures empty and short chapters`() {
        val failedEval = SourceSwitchQualityPolicy.evaluateChapter(
            rawContent = "获取正文失败\nRead timed out",
            processedParagraphs = listOf("获取正文失败", "Read timed out"),
            isImageOrMedia = false
        )
        assertEquals(SourceSwitchQualityPolicy.ChapterIssue.LOAD_FAILED, failedEval.issue)

        val shortEval = SourceSwitchQualityPolicy.evaluateChapter(
            rawContent = "   正在手打中，请稍后刷新重试   ",
            processedParagraphs = listOf("正在手打中，请稍后刷新重试"),
            isImageOrMedia = false
        )
        assertEquals(SourceSwitchQualityPolicy.ChapterIssue.EMPTY_OR_SHORT, shortEval.issue)
        assertTrue(shortEval.effectiveCharCount < SourceSwitchQualityPolicy.MIN_EFFECTIVE_CHARS)

        val normalText = "这是一段足够长的正常小说正文内容，用来确认超过五十个有效字符的章节不会被误判为异常短章，读者可以正常阅读下去。"
        val okEval = SourceSwitchQualityPolicy.evaluateChapter(
            rawContent = normalText,
            processedParagraphs = listOf(normalText),
            isImageOrMedia = false
        )
        assertEquals(SourceSwitchQualityPolicy.ChapterIssue.NONE, okEval.issue)

        val imgEval = SourceSwitchQualityPolicy.evaluateChapter(
            rawContent = "<img src=\"https://img.test/1.jpg\"/>",
            processedParagraphs = listOf("<img src=\"https://img.test/1.jpg\"/>"),
            isImageOrMedia = false
        )
        assertEquals(SourceSwitchQualityPolicy.ChapterIssue.NONE, imgEval.issue)
    }
}
