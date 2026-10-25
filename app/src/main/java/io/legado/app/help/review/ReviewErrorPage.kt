package io.legado.app.help.review

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * 段评页加载失败的原因分类。
 *
 * 分类只决定两件事：给用户看哪句话、以及「重试」有没有意义——
 * 快照缺失是本地压根没有这份数据，重试必然还是失败，所以不给重试按钮。
 */
enum class ReviewErrorKind {
    NETWORK,
    TIMEOUT,
    RULE,
    EMPTY_CONTENT,
    SNAPSHOT_MISSING,
    UNKNOWN
}

/** 失败页上的按钮通过这个 JS 桥回到原生。 */
internal const val REVIEW_ERROR_BRIDGE_NAME = "legadoReviewErrorBridge"

/** 失败页展示的技术细节上限：够定位问题，又不至于再把一屏堆栈糊到用户脸上。 */
private const val MAX_DETAIL_CHARS = 400

/**
 * 把异常归类成用户能理解的原因。
 *
 * 判定顺序：先看类型（网络类最明确），再看文案——项目里规则/内容的失败
 * 都是 [io.legado.app.exception.NoStackTraceException] 带固定措辞抛出的。
 */
internal fun classifyReviewError(error: Throwable?): ReviewErrorKind {
    if (error == null) return ReviewErrorKind.UNKNOWN
    return when {
        error is TimeoutCancellationException -> ReviewErrorKind.TIMEOUT
        error is SocketTimeoutException -> ReviewErrorKind.TIMEOUT
        // 看门狗等「等超了」的场景：不一定走协程/网络栈，可能是裸的 TimeoutException
        error is TimeoutException -> ReviewErrorKind.TIMEOUT
        error is UnknownHostException -> ReviewErrorKind.NETWORK
        error is ConnectException -> ReviewErrorKind.NETWORK
        error is SocketException -> ReviewErrorKind.NETWORK
        error is SSLException -> ReviewErrorKind.NETWORK
        error is IOException -> ReviewErrorKind.NETWORK
        // 快照文件在、但资源索引缺失/损坏：重抓有救，不能谎报「本地没保存过」并藏掉重试
        error.hasMessage("is missing resourceKeys") -> ReviewErrorKind.UNKNOWN
        error.hasMessage("snapshot is missing") -> ReviewErrorKind.SNAPSHOT_MISSING
        error.hasMessage("NullOrEmpty") -> ReviewErrorKind.EMPTY_CONTENT
        error.hasMessage("no find") -> ReviewErrorKind.RULE
        // 看门狗的真实措辞（"review page not ready in <n>ms"）不含 timeout 字样
        error.hasMessage("not ready in") -> ReviewErrorKind.TIMEOUT
        error.hasMessage("timeout") -> ReviewErrorKind.TIMEOUT
        else -> ReviewErrorKind.UNKNOWN
    }
}

private fun Throwable.hasMessage(fragment: String): Boolean =
    message?.contains(fragment, ignoreCase = true) == true

/**
 * 失败页的内容。
 *
 * 文案全部由调用方本地化后传进来，本对象只负责结构与配色，因此可以纯 JVM 单测。
 * [retryLabel] 为 null 表示这次失败重试没有意义（如快照缺失），页面只留「关闭」。
 */
internal data class ReviewErrorPageContent(
    val title: String,
    val message: String,
    val retryLabel: String?,
    val closeLabel: String,
    val detailLabel: String,
    val detail: String? = null,
    val night: Boolean = false
)

/**
 * 生成失败页 HTML。
 *
 * 为什么要有这一层：旧实现失败时直接把 `error.stackTraceToString()` 丢给 WebView，
 * 用户看到的是整屏异常堆栈——既读不懂发生了什么，也没有任何自救入口，
 * 唯一出路是关掉弹窗、重新点一次图标。这里给「一句人话 + 重试/关闭」。
 */
internal fun reviewErrorPageHtml(content: ReviewErrorPageContent): String {
    val bg = if (content.night) "#151515" else "#FFFFFF"
    val fg = if (content.night) "#EDEDED" else "#1A1A1A"
    val muted = if (content.night) "#9A9A9A" else "#757575"
    val border = if (content.night) "#3A3A3A" else "#E0E0E0"
    val accent = if (content.night) "#8AB4F8" else "#2E6BE6"

    val retryButton = content.retryLabel?.let { label ->
        "<button class=\"primary\" onclick=\"$REVIEW_ERROR_BRIDGE_NAME.retry()\">" +
            escapeHtml(label) + "</button>"
    } ?: ""
    val closeButton = "<button class=\"ghost\" onclick=\"$REVIEW_ERROR_BRIDGE_NAME.close()\">" +
        escapeHtml(content.closeLabel) + "</button>"

    val detailBlock = content.detail
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { detail ->
            "<details><summary>" + escapeHtml(content.detailLabel) + "</summary><pre>" +
                escapeHtml(detail.take(MAX_DETAIL_CHARS)) + "</pre></details>"
        } ?: ""

    return """
        <!DOCTYPE html>
        <html lang="zh">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
        <meta name="color-scheme" content="light dark">
        <title>${escapeHtml(content.title)}</title>
        <style>
        :root { color-scheme: light dark; }
        html, body { margin: 0; padding: 0; }
        body {
            min-height: 100vh;
            box-sizing: border-box;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 24px;
            background: $bg;
            color: $fg;
            font-family: -apple-system, "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", sans-serif;
            -webkit-text-size-adjust: 100%;
        }
        .wrap { width: 100%; max-width: 420px; text-align: center; }
        .mark { margin-bottom: 14px; }
        h1 { margin: 0 0 10px; font-size: 17px; font-weight: 600; line-height: 1.5; }
        p { margin: 0 0 22px; font-size: 14px; line-height: 1.7; color: $muted; word-break: break-word; }
        .actions { display: flex; gap: 12px; justify-content: center; flex-wrap: wrap; }
        button {
            font: inherit;
            font-size: 15px;
            line-height: 1.2;
            padding: 11px 26px;
            border-radius: 10px;
            border: 1px solid transparent;
            cursor: pointer;
            -webkit-tap-highlight-color: transparent;
        }
        .primary { background: $accent; color: #FFFFFF; }
        .primary:active { opacity: .85; }
        .ghost { background: transparent; color: $muted; border-color: $border; }
        .ghost:active { opacity: .7; }
        details { margin-top: 24px; text-align: left; font-size: 12px; color: $muted; }
        summary { cursor: pointer; opacity: .85; outline: none; }
        pre { margin: 8px 0 0; font-size: 11px; line-height: 1.5; white-space: pre-wrap; word-break: break-all; opacity: .8; }
        </style>
        </head>
        <body>
        <div class="wrap">
          <div class="mark">
            <svg width="44" height="44" viewBox="0 0 44 44" fill="none" aria-hidden="true">
              <circle cx="22" cy="22" r="19" stroke="$accent" stroke-width="2.5" opacity="0.45"/>
              <path d="M22 13v12" stroke="$accent" stroke-width="3" stroke-linecap="round"/>
              <circle cx="22" cy="31.5" r="2" fill="$accent"/>
            </svg>
          </div>
          <h1>${escapeHtml(content.title)}</h1>
          <p>${escapeHtml(content.message)}</p>
          <div class="actions">$retryButton$closeButton</div>
          $detailBlock
        </div>
        </body>
        </html>
    """.trimIndent()
}

/** 失败页里的文案与详情都要转义：详情是异常信息，可能带尖括号/引号。 */
internal fun escapeHtml(text: String): String = buildString(text.length + 16) {
    for (ch in text) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }
}
