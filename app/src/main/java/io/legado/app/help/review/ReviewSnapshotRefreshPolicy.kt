package io.legado.app.help.review

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.security.MessageDigest

/**
 * SNAPSHOT_FIRST 后台静默刷新的内容指纹对比与清洗策略：
 * - 单次解析 Jsoup 文档，同时输出内容变化决策、评论页内容门判定、评论卡片数与回复项数；
 * - 后台刷新回写快照库前复用纯静态清洗（规则以 `ReviewSnapshotCapture.buildCleanDomJs` 为准绳），
 *   防止未展开首屏生肉 HTML 截断完整快照、冲掉已展开回复层或持久化可执行脚本。
 */
internal object ReviewSnapshotRefreshPolicy {

    enum class RefreshDecision {
        /** 内容无实质变化：仅静默刷新元数据时间戳，不触发 WebView 重绘 */
        NO_CHANGE,
        /** 内容发生实质变化（新增评论/回复等）：提示并允许平滑刷新 */
        CONTENT_CHANGED,
    }

    enum class RefreshUiAction {
        /** 无实质变化：仅刷新 savedAt 与角标 */
        TOUCH_ONLY,
        /** 内容门未过或用户正在二级页/输入中：展示顶部更新横幅 */
        SHOW_BANNER,
        /** 内容门通过且用户空闲：直接自动切换 */
        AUTO_SWITCH,
    }

    data class RefreshEvaluation(
        val decision: RefreshDecision,
        val refreshedIsCommentPage: Boolean,
        val refreshedCardCount: Int,
        val refreshedReplyItemCount: Int,
    )

    internal fun decideRefreshUiAction(
        evaluation: RefreshEvaluation,
        canAutoSwitchContent: Boolean,
        canGoBack: Boolean,
        isUserBusy: Boolean,
    ): RefreshUiAction = when {
        evaluation.decision == RefreshDecision.NO_CHANGE -> RefreshUiAction.TOUCH_ONLY
        !canAutoSwitchContent || canGoBack || isUserBusy -> RefreshUiAction.SHOW_BANNER
        else -> RefreshUiAction.AUTO_SWITCH
    }

    private val RELATIVE_TIME_REGEX =
        Regex("""\b\d+\s*(?:秒|分钟|小时|天)前\b|刚刚""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val FONT_FACE_REGEX = Regex("""@font-face\s*\{[^}]*\}""", RegexOption.IGNORE_CASE)
    private val CSS_IMPORT_REGEX = Regex(
        """@import\s+(?:url\s*\([^)]*\)|["'][^"']*["'])[^;]*;?""",
        RegexOption.IGNORE_CASE,
    )
    private val NON_RESOLVABLE_IMG_SRC_REGEX =
        Regex("""^(?:data:|javascript:|about:|#)""", RegexOption.IGNORE_CASE)
    private const val COMMENT_CARD_SELECTOR = "[data-comment-id], .comment-card"
    private const val REPLY_ITEM_SELECTOR = ".reply-item"

    /**
     * 判定后台刷新页是否允许自动切换/覆盖已有快照：
     * - 新页评论卡片数必须 > 0 且不少于旧快照（防止首屏未展开页面截断已抓齐的多页快照）；
     * - 当 [cacheReplies] 为 true 且旧快照含 `.reply-item` 时，若新页不含任何 `.reply-item` 则拒绝覆写，
     *   防止未展开回复层的页面冲掉已展开回复。
     */
    internal fun shouldOverwriteSnapshot(
        existingHtml: String?,
        refreshedCardCount: Int,
        refreshedReplyItemCount: Int = 0,
        cacheReplies: Boolean = false,
    ): Boolean {
        if (refreshedCardCount <= 0) return false
        val existingDoc = existingHtml?.takeIf { it.isNotBlank() }?.let(Jsoup::parse) ?: return true
        val existingCardCount = existingDoc.select(COMMENT_CARD_SELECTOR).size
        if (refreshedCardCount < existingCardCount) return false
        if (cacheReplies) {
            val existingReplyCount = existingDoc.select(REPLY_ITEM_SELECTOR).size
            if (existingReplyCount > 0 && refreshedReplyItemCount == 0) return false
        }
        return true
    }

    /**
     * 清洗后台刷新得到的原始页面 HTML 以便安全回写离线快照库。
     * 清洗规则以 `ReviewSnapshotCapture.buildCleanDomJs` 为准绳：
     * 剥离 script/noscript/iframe、外链样式与字体 preload、@font-face/@import，
     * 可选移除回复层，并将相对 img[src]（排除 data:/javascript:/about:/#）解析为绝对 URL。
     */
    internal fun sanitizeForSnapshot(
        html: String,
        baseUrl: String,
        cacheReplies: Boolean = true,
    ): String {
        if (html.isBlank()) return html
        val doc = Jsoup.parse(html, baseUrl)
        doc.select("script, noscript, iframe").remove()
        doc.select("link").forEach { link ->
            val rel = link.attr("rel").lowercase()
            val asAttr = link.attr("as").lowercase()
            if (rel.contains("stylesheet") ||
                (rel.contains("preload") && asAttr == "font")
            ) {
                link.remove()
            }
        }
        doc.select("style").forEach { style ->
            val cleaned = CSS_IMPORT_REGEX.replace(
                FONT_FACE_REGEX.replace(style.data(), ""),
                ""
            )
            style.text(cleaned)
        }
        if (!cacheReplies) {
            doc.select(".reply-section").remove()
        }
        doc.select("img[src]").forEach { img ->
            val rawSrc = img.attr("src").trim()
            if (rawSrc.isNotEmpty() && !NON_RESOLVABLE_IMG_SRC_REGEX.containsMatchIn(rawSrc)) {
                val abs = img.absUrl("src").trim()
                if (abs.isNotEmpty()) {
                    img.attr("src", abs)
                }
            }
        }
        return doc.outerHtml()
    }

    internal fun evaluateRefreshDetail(
        currentHtml: String?,
        refreshedHtml: String?,
    ): RefreshEvaluation {
        val refreshed = refreshedHtml?.takeIf { it.isNotBlank() }
            ?: return RefreshEvaluation(
                decision = RefreshDecision.NO_CHANGE,
                refreshedIsCommentPage = false,
                refreshedCardCount = 0,
                refreshedReplyItemCount = 0,
            )
        val refreshedDoc = Jsoup.parse(refreshed)
        val refreshedCardCount = refreshedDoc.select(COMMENT_CARD_SELECTOR).size
        val refreshedReplyItemCount = refreshedDoc.select(REPLY_ITEM_SELECTOR).size
        val refreshedHasComments = refreshedCardCount > 0
        val current = currentHtml?.takeIf { it.isNotBlank() }
            ?: return RefreshEvaluation(
                decision = RefreshDecision.CONTENT_CHANGED,
                refreshedIsCommentPage = refreshedHasComments,
                refreshedCardCount = refreshedCardCount,
                refreshedReplyItemCount = refreshedReplyItemCount,
            )
        val currentDoc = Jsoup.parse(current)
        val currentHasComments = currentDoc.select(COMMENT_CARD_SELECTOR).isNotEmpty()
        val refreshedHasScripts = refreshedDoc.select("script").isNotEmpty()
        // 若新抓回的是纯 JS/AJAX 壳页（无静态评论节点但含脚本），而当前展示页已有静态评论，
        // 无法直接靠静态 HTML 断言内容相同，交由更新流程按变更处理
        val decision = if (currentHasComments && !refreshedHasComments && refreshedHasScripts) {
            RefreshDecision.CONTENT_CHANGED
        } else if (contentFingerprint(currentDoc) == contentFingerprint(refreshedDoc)) {
            RefreshDecision.NO_CHANGE
        } else {
            RefreshDecision.CONTENT_CHANGED
        }
        return RefreshEvaluation(
            decision = decision,
            refreshedIsCommentPage = refreshedHasComments,
            refreshedCardCount = refreshedCardCount,
            refreshedReplyItemCount = refreshedReplyItemCount,
        )
    }

    private fun contentFingerprint(sourceDoc: Document): String {
        val doc = sourceDoc.clone()
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
                    val replyCount = card.select(REPLY_ITEM_SELECTOR).size
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

    private fun normalizeText(text: String): String {
        return RELATIVE_TIME_REGEX.replace(text, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }
}
