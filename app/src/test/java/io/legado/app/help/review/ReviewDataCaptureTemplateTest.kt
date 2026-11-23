package io.legado.app.help.review

import com.google.gson.JsonObject
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * ReviewDataCapture 模板升级后的纯 JVM 契约测试：
 * 验证 DOM 契约（#contentArea、.comment-card、.reply-toggle、#commentTrigger、#commentModalOverlay）、
 * 主题 CSS 变量（明暗模式自适应，无硬编码 #ccc）与可读时间戳格式化。
 */
class ReviewDataCaptureTemplateTest {

    private fun sampleCommentItem(
        commentId: String,
        userName: String,
        text: String,
        timestampSeconds: Long,
        diggCount: Int,
    ): JsonObject {
        val user = JsonObject().apply {
            addProperty("user_name", userName)
            addProperty("user_avatar", "https://example.com/avatar/$commentId.png")
        }
        val userInfo = JsonObject().apply {
            add("base_info", user)
        }
        val content = JsonObject().apply {
            addProperty("text", text)
        }
        val common = JsonObject().apply {
            add("user_info", userInfo)
            add("content", content)
            addProperty("create_timestamp", timestampSeconds.toString())
        }
        val stat = JsonObject().apply {
            addProperty("digg_count", diggCount)
            addProperty("reply_count", 1)
        }
        val comment = JsonObject().apply {
            addProperty("comment_id", commentId)
            add("common", common)
            add("stat", stat)
        }
        return JsonObject().apply {
            add("comment", comment)
        }
    }

    private fun sampleReply(userName: String, text: String, timestampSeconds: Long): JsonObject {
        val user = JsonObject().apply {
            addProperty("user_name", userName)
            addProperty("user_avatar", "https://example.com/avatar/reply.png")
        }
        val common = JsonObject().apply {
            add("user_info", JsonObject().apply { add("base_info", user) })
            add("content", JsonObject().apply { addProperty("text", text) })
            addProperty("create_timestamp", timestampSeconds.toString())
        }
        return JsonObject().apply {
            addProperty("reply_id", "r1")
            add("common", common)
            add("stat", JsonObject().apply { addProperty("digg_count", 2) })
        }
    }

    @Test
    fun `buildHtml emits consumer DOM contract, theme CSS vars and formatted timestamps`() {
        val nowMillis = 1_700_600_000_000L
        val oldTimestampSec = 1_700_000_000L
        val recentTimestampSec = (nowMillis - 5 * 60_000L) / 1000L
        val items = listOf(
            sampleCommentItem("c1", "读者甲", "这段写得真好", oldTimestampSec, 12),
        )
        val replies = listOf(
            listOf(sampleReply("读者乙", "同感，节奏很稳", recentTimestampSec))
        )

        val html = ReviewDataCapture.buildHtml(
            url = "https://example.com/idea_comment?book_id=1&item_id=2&para=3",
            sourceText = "原文段落引用",
            items = items,
            replies = replies,
            emoji = emptyMap(),
            nowMillis = nowMillis,
            isNightTheme = true,
            accentColorHex = "#0A84FF",
            cacheAvatars = true,
            cacheImages = true,
            timeZone = TimeZone.getTimeZone("Asia/Shanghai"),
        )

        val doc = Jsoup.parse(html)
        assertEquals("idea_comment", doc.body().attr("data-review-protocol"))
        assertEquals("dark", doc.selectFirst("html")?.attr("data-theme"))

        // 消费端 DOM 契约校验
        assertNotNull(doc.selectFirst("#quoteCard"))
        val contentArea = doc.selectFirst("#contentArea")
        assertNotNull(contentArea)
        val cards = contentArea!!.select(".comment-card")
        assertEquals(1, cards.size)
        assertEquals("c1", cards[0].attr("data-comment-id"))

        val replyToggle = cards[0].selectFirst(".reply-toggle")
        assertNotNull(replyToggle)
        assertEquals("收起回复", replyToggle!!.attr("data-collapse-label"))
        val replyList = cards[0].selectFirst(".reply-list")
        assertNotNull(replyList)
        assertTrue(replyList!!.attr("style").contains("display:none"))
        assertEquals(1, replyList.select(".reply-item").size)

        assertNotNull(doc.selectFirst("#commentTrigger"))
        assertNotNull(doc.selectFirst("#commentModalOverlay"))
        assertNotNull(doc.selectFirst("#commentModalOverlay .modal-close"))
        assertNotNull(doc.selectFirst("#commentTextarea"))
        assertNotNull(doc.selectFirst("#modalSubmitBtn"))

        // 主题 CSS 变量存在且告别硬编码 #ccc
        val styleText = doc.selectFirst("style")?.data().orEmpty()
        assertTrue(styleText.contains("--bg:"))
        assertTrue(styleText.contains("--surface:"))
        assertTrue(styleText.contains("--border:"))
        assertTrue(styleText.contains("--text-primary:"))
        assertTrue(styleText.contains("--text-secondary:"))
        assertTrue(styleText.contains("--accent:#0A84FF"))
        assertTrue(styleText.contains("prefers-color-scheme:dark"))
        assertFalse(styleText.contains("#ccc"))

        // 原始时间戳已格式化，绝不直接暴露原始秒级整数
        val times = doc.select(".comment-time").map { it.text() }
        assertTrue(times.any { it.contains("2023-11-15 06:13") })
        assertTrue(times.any { it == "5分钟前" })
        assertFalse(html.contains(oldTimestampSec.toString()))
        assertFalse(html.contains(recentTimestampSec.toString()))
    }

    @Test
    fun `formatReviewTimestamp formats seconds, millis and relative durations cleanly`() {
        val tz = TimeZone.getTimeZone("UTC")
        val now = 1_700_600_000_000L
        assertEquals("刚刚", ReviewDataCapture.formatReviewTimestamp(((now - 20_000L) / 1000L).toString(), now, tz))
        assertEquals("12分钟前", ReviewDataCapture.formatReviewTimestamp(((now - 12 * 60_000L) / 1000L).toString(), now, tz))
        assertEquals("3小时前", ReviewDataCapture.formatReviewTimestamp(((now - 3 * 3_600_000L) / 1000L).toString(), now, tz))
        assertEquals("2023-11-14 22:13", ReviewDataCapture.formatReviewTimestamp("1700000000", now, tz))
        assertEquals("2023-11-14 22:13", ReviewDataCapture.formatReviewTimestamp("1700000000000", now, tz))
        assertEquals("昨天 18:00", ReviewDataCapture.formatReviewTimestamp("昨天 18:00", now, tz))
    }

    @Test
    fun `built HTML integrates cleanly with ReviewSnapshotMerger`() {
        val now = 1_700_600_000_000L
        val baseHtml = ReviewDataCapture.buildHtml(
            url = "https://example.com/idea_comment?book_id=1&item_id=2&para=3",
            sourceText = "正文段",
            items = listOf(sampleCommentItem("c1", "甲", "第一条评论", 1_700_000_000L, 1)),
            replies = listOf(emptyList()),
            emoji = emptyMap(),
            nowMillis = now,
            isNightTheme = false,
            accentColorHex = "#3B82F6",
        )
        val incomingHtml = ReviewDataCapture.buildHtml(
            url = "https://example.com/idea_comment?book_id=1&item_id=2&para=3",
            sourceText = "正文段",
            items = listOf(
                sampleCommentItem("c2", "乙", "新增评论", 1_700_001_000L, 0),
                sampleCommentItem("c1", "甲", "第一条评论", 1_700_000_000L, 1),
            ),
            replies = listOf(emptyList(), emptyList()),
            emoji = emptyMap(),
            nowMillis = now,
            isNightTheme = false,
            accentColorHex = "#3B82F6",
        )
        val merged = ReviewSnapshotMerger.merge(baseHtml, incomingHtml)
        assertNotNull(merged)
        assertEquals(1, merged!!.addedCount)
        val doc = Jsoup.parse(merged.html)
        assertEquals(2, doc.select(".comment-card[data-comment-id]").size)
    }
}
