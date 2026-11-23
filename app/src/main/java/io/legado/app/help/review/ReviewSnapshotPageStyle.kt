package io.legado.app.help.review

import org.jsoup.Jsoup
import java.security.MessageDigest

/**
 * 评论快照页的「首屏让位」样式注入。
 *
 * 快照页的图片是 `review-resource://` 自定义 scheme，每张都要跨进程回调到
 * `shouldInterceptRequest` 再取字节。若图片参与首屏布局与解码，正文就要等
 * 图片排完才出现。给图片加 `content-visibility:auto` 后，视口外的图片既不解码
 * 也不布局，文字先出来；`contain-intrinsic-size` 的 `auto <fallback>` 让浏览器
 * 记住已渲染过的真实高度，回滚时不会二次跳动。
 */
internal const val SNAPSHOT_IMAGE_DEFERRAL_STYLE_TAG = "legado-snapshot-deferral"

private const val SNAPSHOT_IMAGE_DEFERRAL_STYLE =
    "<style id=\"$SNAPSHOT_IMAGE_DEFERRAL_STYLE_TAG\">" +
        "img{content-visibility:auto;contain-intrinsic-size:auto 160px;}" +
        "</style>"

/**
 * 在 [html] 的 `<head>` 里插入图片让位样式；幂等（已注入则原样返回）。
 *
 * 插入位置必须在 `<head>` 开标签之后、任何内容之前，样式才能参与首次布局；
 * 找不到 `<head>` 时前置，交给浏览器自行归位。
 */
internal fun injectSnapshotImageDeferralStyle(html: String): String {
    if (html.isEmpty()) return html
    if (html.contains(SNAPSHOT_IMAGE_DEFERRAL_STYLE_TAG)) return html
    val headIndex = html.indexOf("<head", ignoreCase = true)
    if (headIndex >= 0) {
        val headEnd = html.indexOf('>', headIndex)
        if (headEnd >= 0) {
            return StringBuilder(html.length + SNAPSHOT_IMAGE_DEFERRAL_STYLE.length)
                .append(html, 0, headEnd + 1)
                .append(SNAPSHOT_IMAGE_DEFERRAL_STYLE)
                .append(html, headEnd + 1, html.length)
                .toString()
        }
    }
    return SNAPSHOT_IMAGE_DEFERRAL_STYLE + html
}

/**
 * 快照页内容指纹：只有内容与渲染条件完全一致，才允许复用已经渲染好的 WebView。
 *
 * 参与指纹的除了 HTML 内容本身，还有会影响渲染结果的两个外部条件：
 * 夜间模式（算法暗化会改写绘制）与文字缩放比例（会改写布局）。
 * 任何一项变化都必须判定为不同页面，否则会拿到上一种配色/字号的成品页。
 */
internal fun reviewPageIdentity(html: String, nightMode: Boolean, textZoom: Int): String =
    buildString(48) {
        append(html.length)
        append(':')
        append(html.hashCode())
        append(':')
        append(if (nightMode) 'N' else 'D')
        append(':')
        append(textZoom)
    }

/**
 * SNAPSHOT_FIRST 后台静默刷新的内容指纹对比策略：
 * 对当前展示快照与后台拉取的最新页面提取标准化评论内容摘要（剥离脚本、样式、
 * `review-resource://` 与 `https://` 图片地址差异、相对时间文本等易变噪声），
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
