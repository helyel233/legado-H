package io.legado.app.help.review

import org.jsoup.Jsoup
import java.security.MessageDigest

/**
 * SNAPSHOT_FIRST 后台静默刷新的内容指纹对比策略：
 * 对当前展示快照与后台拉取的最新页面提取标准化评论内容摘要（剥离脚本、样式、
 * 图片地址差异、相对时间文本等易变噪声），
 * 仅在检测到新评论或实质内容变化时才触发页面更新，避免无变化时整页重绘闪烁。
 */
internal object ReviewSnapshotRefreshPolicy {

    enum class RefreshDecision {
        /** 内容无实质变化：仅静默刷新元数据时间戳，不触发 WebView 重绘 */
        NO_CHANGE,
        /** 内容发生实质变化（新增评论/回复等）：提示并允许平滑刷新 */
        CONTENT_CHANGED,
    }

    private val RELATIVE_TIME_REGEX =
        Regex("""\b\d+\s*(?:秒|分钟|小时|天)前\b|刚刚""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    fun contentFingerprint(html: String): String {
        if (html.isBlank()) return ""
        val doc = Jsoup.parse(html)
        doc.select(
            "script, style, noscript, #commentModalOverlay, #bottomBar, [id^=legadoSupplementSection]"
        ).remove()
        doc.select(".reply-toggle").forEach { it.text("") }

        val commentNodes = doc.select("[data-comment-id]").takeIf { it.isNotEmpty() }
            ?: doc.select(".comment-card")
        val digestInput = if (commentNodes.isNotEmpty()) {
            buildString {
                append("cards:").append(commentNodes.size)
                commentNodes.forEach { card ->
                    val id = card.attr("data-comment-id").trim()
                    val replyCount = card.select(".reply-item").size
                    val imgCount = card.select("img").size
                    val cardClone = card.clone()
                    cardClone.select(".comment-time, time").remove()
                    val bodyText = normalizeText(cardClone.text())
                    append('|').append(id)
                    append('#').append(replyCount)
                    append('#').append(imgCount)
                    append('#').append(bodyText)
                }
            }
        } else {
            val rawBody = doc.body()?.text().orEmpty()
            val imgCount = doc.select("img").size
            "body:$imgCount:${normalizeText(rawBody)}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(digestInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "${commentNodes.size}:$digest"
    }

    /**
     * 判断后台拉取的在线页面是否为有效评论页。
     *
     * shortcut: 只认静态评论卡片节点，JS 驱动型源永不自动切换，出现整站壳页化时升级。
     */
    internal fun isCommentPage(html: String?): Boolean {
        val content = html?.takeIf { it.isNotBlank() } ?: return false
        return Jsoup.parse(content).select("[data-comment-id], .comment-card").isNotEmpty()
    }

    fun evaluateRefresh(currentHtml: String?, refreshedHtml: String?): RefreshDecision {
        val current = currentHtml?.takeIf { it.isNotBlank() } ?: return RefreshDecision.CONTENT_CHANGED
        val refreshed = refreshedHtml?.takeIf { it.isNotBlank() } ?: return RefreshDecision.NO_CHANGE

        val currentDoc = Jsoup.parse(current)
        val refreshedDoc = Jsoup.parse(refreshed)
        val currentHasComments = currentDoc.select("[data-comment-id], .comment-card").isNotEmpty()
        val refreshedHasComments = refreshedDoc.select("[data-comment-id], .comment-card").isNotEmpty()
        val refreshedHasScripts = refreshedDoc.select("script").isNotEmpty()
        // 若新抓回的是纯 JS/AJAX 壳页（无静态评论节点但含脚本），而当前展示页已有静态评论，
        // 无法直接靠静态 HTML 断言内容相同，交由更新流程按变更处理
        if (currentHasComments && !refreshedHasComments && refreshedHasScripts) {
            return RefreshDecision.CONTENT_CHANGED
        }
        return if (contentFingerprint(current) == contentFingerprint(refreshed)) {
            RefreshDecision.NO_CHANGE
        } else {
            RefreshDecision.CONTENT_CHANGED
        }
    }

    private fun normalizeText(text: String): String {
        return RELATIVE_TIME_REGEX.replace(text, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }
}
