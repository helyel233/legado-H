package io.legado.app.model.webBook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TocEarlyChapterDispatchPolicyTest {

    @Test
    fun `allows first page early dispatch only when non-reversed, no formatJs, and target in page 1`() {
        assertTrue(
            TocEarlyChapterDispatchPolicy.canDispatchAfterFirstPage(
                earlyDispatchEnabled = true,
                isLocal = false,
                isAudioOrVideo = false,
                isReverseSourceRule = false,
                isBookReverseToc = false,
                formatJs = null,
                targetChapterIndex = 0,
                firstPageChapterCount = 30,
                hasNextTocPages = true
            )
        )
    }

    @Test
    fun `blocks first page early dispatch for reverse source, book reverseToc, or global formatJs`() {
        assertFalse(
            TocEarlyChapterDispatchPolicy.canDispatchAfterFirstPage(
                earlyDispatchEnabled = true,
                isLocal = false,
                isAudioOrVideo = false,
                isReverseSourceRule = true,
                isBookReverseToc = false,
                formatJs = null,
                targetChapterIndex = 0,
                firstPageChapterCount = 30,
                hasNextTocPages = true
            )
        )
        assertFalse(
            TocEarlyChapterDispatchPolicy.canDispatchAfterFirstPage(
                earlyDispatchEnabled = true,
                isLocal = false,
                isAudioOrVideo = false,
                isReverseSourceRule = false,
                isBookReverseToc = true,
                formatJs = "",
                targetChapterIndex = 0,
                firstPageChapterCount = 30,
                hasNextTocPages = true
            )
        )
        assertFalse(
            TocEarlyChapterDispatchPolicy.canDispatchAfterFirstPage(
                earlyDispatchEnabled = true,
                isLocal = false,
                isAudioOrVideo = false,
                isReverseSourceRule = false,
                isBookReverseToc = false,
                formatJs = "title.trim()",
                targetChapterIndex = 0,
                firstPageChapterCount = 30,
                hasNextTocPages = true
            )
        )
    }

    @Test
    fun `blocks first page dispatch when target chapter is beyond page 1 or caller opted out`() {
        assertFalse(
            TocEarlyChapterDispatchPolicy.canDispatchAfterFirstPage(
                earlyDispatchEnabled = true,
                isLocal = false,
                isAudioOrVideo = false,
                isReverseSourceRule = false,
                isBookReverseToc = false,
                formatJs = null,
                targetChapterIndex = 45,
                firstPageChapterCount = 30,
                hasNextTocPages = true
            )
        )
        assertFalse(
            TocEarlyChapterDispatchPolicy.canDispatchAfterFirstPage(
                earlyDispatchEnabled = false,
                isLocal = false,
                isAudioOrVideo = false,
                isReverseSourceRule = false,
                isBookReverseToc = false,
                formatJs = null,
                targetChapterIndex = 0,
                firstPageChapterCount = 30,
                hasNextTocPages = true
            )
        )
    }

    @Test
    fun `final list dispatch runs when first page did not dispatch or url shifted after dedup`() {
        assertTrue(
            TocEarlyChapterDispatchPolicy.shouldDispatchAfterFinalList(
                earlyDispatchEnabled = true,
                isLocal = false,
                isAudioOrVideo = false,
                earlyDispatchedUrl = null,
                resolvedChapterUrl = "https://example.test/ch/0"
            )
        )
        assertTrue(
            TocEarlyChapterDispatchPolicy.shouldDispatchAfterFinalList(
                earlyDispatchEnabled = true,
                isLocal = false,
                isAudioOrVideo = false,
                earlyDispatchedUrl = "https://example.test/ch/pinned",
                resolvedChapterUrl = "https://example.test/ch/0"
            )
        )
        assertFalse(
            TocEarlyChapterDispatchPolicy.shouldDispatchAfterFinalList(
                earlyDispatchEnabled = true,
                isLocal = false,
                isAudioOrVideo = false,
                earlyDispatchedUrl = "https://example.test/ch/0",
                resolvedChapterUrl = "https://example.test/ch/0"
            )
        )
    }
}
