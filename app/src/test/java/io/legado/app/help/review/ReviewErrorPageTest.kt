package io.legado.app.help.review

import io.legado.app.exception.NoStackTraceException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败页的回归网：分类决定用户看到哪句话、以及有没有重试按钮，
 * HTML 生成决定这段反馈会不会自己再崩一次（转义/缺参数）。
 */
class ReviewErrorPageTest {

    @Test
    fun socketTimeoutIsTimeout() {
        assertEquals(ReviewErrorKind.TIMEOUT, classifyReviewError(SocketTimeoutException()))
    }

    @Test
    fun unknownHostIsNetwork() {
        assertEquals(ReviewErrorKind.NETWORK, classifyReviewError(UnknownHostException("a.com")))
    }

    @Test
    fun ioExceptionFallsBackToNetwork() {
        assertEquals(ReviewErrorKind.NETWORK, classifyReviewError(IOException("broken pipe")))
    }

    @Test
    fun plainTimeoutExceptionIsTimeout() {
        // 加载看门狗等「等超了」的场景走的是裸 TimeoutException，不带网络栈痕迹
        assertEquals(
            ReviewErrorKind.TIMEOUT,
            classifyReviewError(java.util.concurrent.TimeoutException("not ready in 45s"))
        )
    }

    @Test
    fun nullErrorIsUnknown() {
        assertEquals(ReviewErrorKind.UNKNOWN, classifyReviewError(null))
    }

    @Test
    fun plainRuntimeExceptionIsUnknown() {
        assertEquals(ReviewErrorKind.UNKNOWN, classifyReviewError(RuntimeException("boom")))
    }

    @Test
    fun missingSnapshotHasItsOwnKind() {
        // 消息取自 BottomWebViewDialog 进程重建路径的真实抛出点
        assertEquals(
            ReviewErrorKind.SNAPSHOT_MISSING,
            classifyReviewError(
                NoStackTraceException("review snapshot is missing after recreate")
            )
        )
    }

    @Test
    fun corruptedSnapshotIsRetryable() {
        // 快照在、资源索引缺失：可重抓修复，不能判成「本地没保存过」而藏掉重试
        assertEquals(
            ReviewErrorKind.UNKNOWN,
            classifyReviewError(
                NoStackTraceException("review snapshot is missing resourceKeys: /a/b.json")
            )
        )
    }

    @Test
    fun emptyContentHasItsOwnKind() {
        assertEquals(
            ReviewErrorKind.EMPTY_CONTENT,
            classifyReviewError(NoStackTraceException("html is NullOrEmpty"))
        )
    }

    @Test
    fun deletedRuleHasItsOwnKind() {
        // 评论侧暂无该措辞的真实抛出点，此处锁的是分类器的防御性契约
        assertEquals(
            ReviewErrorKind.RULE,
            classifyReviewError(NoStackTraceException("pclick rule no find: paragraphRule:1"))
        )
    }

    @Test
    fun loadWatchdogMessageIsTimeoutNotUnknown() {
        // 看门狗真实抛出的消息（BottomWebViewDialog 45s 超时）：不含 "timeout" 字样
        assertEquals(
            ReviewErrorKind.TIMEOUT,
            classifyReviewError(NoStackTraceException("review page not ready in 45000ms"))
        )
    }

    @Test
    fun pageContainsTitleMessageAndBothButtons() {
        val html = reviewErrorPageHtml(
            ReviewErrorPageContent(
                title = "段评加载失败",
                message = "网络好像不太稳定",
                retryLabel = "重试",
                closeLabel = "关闭",
                detailLabel = "技术细节",
                detail = "java.io.IOException: boom",
                night = false
            )
        )
        assertTrue(html.contains("段评加载失败"))
        assertTrue(html.contains("网络好像不太稳定"))
        assertTrue(html.contains("$REVIEW_ERROR_BRIDGE_NAME.retry()"))
        assertTrue(html.contains("$REVIEW_ERROR_BRIDGE_NAME.close()"))
        assertTrue(html.contains("技术细节"))
    }

    @Test
    fun retryButtonDisappearsWhenRetryIsPointless() {
        val html = reviewErrorPageHtml(
            ReviewErrorPageContent(
                title = "没有找到这份段评",
                message = "本地没有保存过这一页",
                retryLabel = null,
                closeLabel = "关闭",
                detailLabel = "技术细节"
            )
        )
        assertFalse(html.contains("$REVIEW_ERROR_BRIDGE_NAME.retry()"))
        assertTrue(html.contains("$REVIEW_ERROR_BRIDGE_NAME.close()"))
    }

    @Test
    fun detailIsEscapedAndTruncated() {
        val dangerous = "<script>alert(1)</script>" + "x".repeat(1000)
        val html = reviewErrorPageHtml(
            ReviewErrorPageContent(
                title = "t",
                message = "m",
                retryLabel = "重试",
                closeLabel = "关闭",
                detailLabel = "技术细节",
                detail = dangerous
            )
        )
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        // 详情被截到 400 字符：原文尾部那串 x 不可能整个出现在页面里
        val detailText = html.substringAfter("<pre>").substringBefore("</pre>")
        assertTrue(detailText.length < dangerous.length)
        assertFalse(detailText.contains("x".repeat(600)))
    }

    @Test
    fun blankDetailRendersNoDetailBlock() {
        val html = reviewErrorPageHtml(
            ReviewErrorPageContent(
                title = "t",
                message = "m",
                retryLabel = "重试",
                closeLabel = "关闭",
                detailLabel = "技术细节",
                detail = "   "
            )
        )
        assertFalse(html.contains("<details>"))
    }

    @Test
    fun nightAndDayUseDifferentBackgrounds() {
        val day = reviewErrorPageHtml(content(night = false))
        val night = reviewErrorPageHtml(content(night = true))
        assertTrue(day.contains("#FFFFFF"))
        assertTrue(night.contains("#151515"))
    }

    private fun content(night: Boolean) = ReviewErrorPageContent(
        title = "t",
        message = "m",
        retryLabel = "重试",
        closeLabel = "关闭",
        detailLabel = "技术细节",
        night = night
    )
}
