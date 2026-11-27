package io.legado.app.help.review

/**
 * 离线评论快照极简日夜渲染模板：
 * 1. 实时同步 `<html data-theme="dark|light">` 属性（覆盖缓存时固化的主题态）；
 * 2. 缺失 viewport meta 时补入移动端视口声明；
 * 3. 对未带结构化卡片样式（[DATA_TEMPLATE_MARKER]）或未注入本模板的 HTML，幂等注入轻量日夜基础 CSS；
 * 4. 离线模式（[offlineMode]）下同步 `data-offline="1"` 并幂等注入外链破图隐藏样式，避免断网破图占位。
 */
internal object ReviewLiteTemplate {

    private const val STYLE_TAG_ID = "legado-review-lite-style"
    private const val OFFLINE_IMG_STYLE_TAG_ID = "legado-review-offline-img"
    internal const val DATA_TEMPLATE_MARKER = "legado-review-data-style"
    private const val DEFAULT_ACCENT_HEX = "#3B82F6"
    private const val VIEWPORT_META =
        """<meta name="viewport" content="width=device-width,initial-scale=1">"""
    private const val OFFLINE_IMG_STYLE_TAG =
        """<style id="$OFFLINE_IMG_STYLE_TAG_ID">html[data-offline="1"] img[src^="http"]{display:none!important;}</style>"""

    private val HTML_OPEN_TAG_REGEX = Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val HEAD_OPEN_TAG_REGEX = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val DATA_THEME_ATTR_REGEX =
        Regex("""\bdata-theme\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)""", RegexOption.IGNORE_CASE)
    private val DATA_OFFLINE_ATTR_REGEX =
        Regex("""\s+\bdata-offline(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+))?""", RegexOption.IGNORE_CASE)
    private val VIEWPORT_META_REGEX =
        Regex("""name\s*=\s*["']viewport["']""", RegexOption.IGNORE_CASE)
    private val ACCENT_HEX_REGEX = Regex("^#[0-9a-fA-F]{6}$")

    fun apply(
        html: String,
        isNight: Boolean,
        accentHex: String = DEFAULT_ACCENT_HEX,
        offlineMode: Boolean = false,
    ): String {
        if (html.isBlank()) return html

        val theme = if (isNight) "dark" else "light"
        val safeAccent = accentHex.trim().takeIf { ACCENT_HEX_REGEX.matches(it) }
            ?: DEFAULT_ACCENT_HEX

        val htmlMatch = HTML_OPEN_TAG_REGEX.find(html)
        var result = if (htmlMatch == null) {
            val offlineAttr = if (offlineMode) """ data-offline="1"""" else ""
            "<!DOCTYPE html><html data-theme=\"$theme\"$offlineAttr><head><meta charset=\"utf-8\">" +
                "$VIEWPORT_META</head><body>$html</body></html>"
        } else {
            val openTag = htmlMatch.value
            val themedTag = if (DATA_THEME_ATTR_REGEX.containsMatchIn(openTag)) {
                DATA_THEME_ATTR_REGEX.replace(openTag, "data-theme=\"$theme\"")
            } else {
                openTag.dropLast(1) + " data-theme=\"$theme\">"
            }
            val updatedTag = if (offlineMode) {
                if (DATA_OFFLINE_ATTR_REGEX.containsMatchIn(themedTag)) {
                    DATA_OFFLINE_ATTR_REGEX.replace(themedTag, """ data-offline="1"""")
                } else {
                    themedTag.dropLast(1) + """ data-offline="1">"""
                }
            } else {
                DATA_OFFLINE_ATTR_REGEX.replace(themedTag, "")
            }
            html.replaceRange(htmlMatch.range, updatedTag)
        }

        if (!VIEWPORT_META_REGEX.containsMatchIn(result)) {
            result = insertAfterHeadOpen(result, VIEWPORT_META, prependIfMissing = false)
        }

        if (!result.contains(STYLE_TAG_ID) && !result.contains(DATA_TEMPLATE_MARKER)) {
            result = insertAfterHeadOpen(result, buildLiteStyleTag(safeAccent), prependIfMissing = true)
        }

        if (offlineMode && !result.contains(OFFLINE_IMG_STYLE_TAG_ID)) {
            result = insertAfterHeadOpen(result, OFFLINE_IMG_STYLE_TAG, prependIfMissing = true)
        }

        return result
    }

    private fun insertAfterHeadOpen(
        html: String,
        snippet: String,
        prependIfMissing: Boolean,
    ): String {
        val headMatch = HEAD_OPEN_TAG_REGEX.find(html)
        return when {
            headMatch != null -> {
                val pos = headMatch.range.last + 1
                html.substring(0, pos) + snippet + html.substring(pos)
            }
            prependIfMissing -> snippet + html
            else -> html
        }
    }

    private fun buildLiteStyleTag(safeAccent: String): String = """
        <style id="$STYLE_TAG_ID">
        :root{color-scheme:light dark;--rv-bg:#f6f7f9;--rv-card:#ffffff;--rv-text:#18181b;--rv-muted:#64748b;--rv-border:rgba(15,23,42,0.08);--rv-accent:$safeAccent;}
        html[data-theme="dark"]{--rv-bg:#121214;--rv-card:#1c1c1f;--rv-text:#f4f4f5;--rv-muted:#a1a1aa;--rv-border:rgba(255,255,255,0.10);}
        *{box-sizing:border-box;}
        html,body{margin:0;padding:12px 14px;background:var(--rv-bg);color:var(--rv-text);font:15px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;word-break:break-word;overflow-wrap:anywhere;}
        a{color:var(--rv-accent);text-decoration:none;}
        img{max-width:100%;height:auto;border-radius:8px;vertical-align:middle;}
        blockquote{margin:10px 0;padding:10px 12px;background:var(--rv-card);border-left:3px solid var(--rv-accent);border-radius:0 8px 8px 0;color:var(--rv-muted);}
        hr{border:0;border-top:1px solid var(--rv-border);margin:12px 0;}
        ul,ol{margin:8px 0;padding-left:20px;}
        button,input,textarea{font:inherit;color:inherit;}
        </style>
    """.trimIndent()
}
