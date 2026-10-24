package io.legado.app.help.review

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
