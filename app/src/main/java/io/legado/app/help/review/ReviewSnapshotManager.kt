package io.legado.app.help.review

import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookImgClick
import io.legado.app.help.book.ParagraphRuleProcessor
import io.legado.app.help.http.StrResponse
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout

/**
 * 评论按钮提取与目标评论页解析工具。
 */
object ReviewSnapshotManager {

    /** 评论网络打开链路有本地快照时的网络加载上限。 */
    const val NETWORK_FALLBACK_LOAD_TIMEOUT_MS = 5_000L

    /** 单个按钮解析评论页地址的超时。 */
    private const val RESOLVE_TIMEOUT_MS = 20_000L

    /** 评论按钮模型：段落规则 pclick / click / 旧源 js */
    data class ReviewButton(
        val src: String,
        val click: String? = null,
        val js: String? = null,
        val urlNoOption: String? = null,
        /** 段落规则点击值（[ParagraphRuleProcessor.wrapClick] 包装后的 pclick） */
        val pclick: String? = null
    ) {
        val hasAction: Boolean
            get() = !pclick.isNullOrBlank() || !click.isNullOrBlank() || !js.isNullOrBlank()
    }

    data class ReviewPage(
        val url: String?,
        /** showBrowser 路径书源已取到的渲染 HTML（可为 null） */
        val html: String?,
        /** showBrowser 路径书源传入的 preloadJs（页面 JS bridge 需要） */
        val preloadJs: String? = null,
        /** 段落规则点击产生的源键（paragraph_rule_<id>），供抓拍阶段恢复规则宿主 */
        val sourceKey: String? = null,
    )

    data class ButtonExtraction(
        val buttons: List<ReviewButton>,
        val imgCount: Int,
        val optionParseFailed: Int,
        val nonTextStyle: Int,
        val noAction: Int
    )

    internal fun resolveCaptureSource(bookSource: BookSource, sourceKey: String?): BaseSource {
        return ParagraphRuleProcessor.resolveSource(sourceKey) ?: bookSource
    }

    suspend fun resolveReviewPageUrl(
        book: Book,
        bookSource: BookSource,
        chapter: BookChapter,
        button: ReviewButton
    ): ReviewPage {
        val resolvedPage = CompletableDeferred<ReviewPage>()
        fun record(
            url: String,
            html: String? = null,
            preloadJs: String? = null,
            sourceKey: String? = null,
        ) {
            resolvedPage.complete(ReviewPage(url, html, preloadJs, sourceKey))
        }
        return withTimeout(RESOLVE_TIMEOUT_MS) {
            when {
                !button.pclick.isNullOrBlank() -> {
                    // 段落规则点击：与用户点击同一执行环境（[ParagraphRuleProcessor.evalClick]），
                    // 仅把 java 宿主换成录音回调，不弹窗。evalClick 是阻塞式 Rhino 求值，
                    // 传入当前协程上下文，翻章取消/超时可穿透进脚本。
                    val callback = object : ParagraphRuleProcessor.BrowserCallback {
                        override fun showBrowser(
                            url: String,
                            html: String?,
                            preloadJs: String?,
                            config: String?,
                            sourceKey: String?
                        ): Boolean {
                            record(url, html, preloadJs, sourceKey)
                            return true
                        }
                    }
                    val accepted = ParagraphRuleProcessor.evalClick(
                        book,
                        chapter,
                        button.pclick,
                        button.src,
                        callback,
                        currentCoroutineContext()
                    )
                    if (!accepted) {
                        // 规则已删除/点击值解析失败：脚本不会触发 showBrowser，
                        // 立即以「无 URL」收场，不空转等满超时。
                        resolvedPage.complete(ReviewPage("", null, null))
                    }
                }
                !button.click.isNullOrBlank() -> {
                    val host = object : SourceLoginJsExtensions(null, bookSource, BookType.text) {
                        override fun startBrowser(url: String, title: String) {
                            record(url)
                        }

                        override fun startBrowser(url: String, title: String, html: String?) {
                            record(url, html)
                        }

                        override fun startBrowserAwait(url: String, title: String): StrResponse {
                            record(url)
                            return StrResponse(url, "")
                        }

                        override fun startBrowserAwait(
                            url: String,
                            title: String,
                            refetchAfterSuccess: Boolean
                        ): StrResponse {
                            record(url)
                            return StrResponse(url, "")
                        }

                        override fun startBrowserAwait(
                            url: String,
                            title: String,
                            refetchAfterSuccess: Boolean,
                            html: String?
                        ): StrResponse {
                            record(url, html)
                            return StrResponse(url, "")
                        }

                        /** 改版 app 弹窗路径：书源评论实际走这里 */
                        override fun showBrowser(
                            url: String,
                            html: String?,
                            preloadJs: String?,
                            config: String?
                        ) {
                            record(url, html, preloadJs)
                        }

                        /** qread 弹窗路径兼容 */
                        fun startBrowserDp(url: String, title: String) {
                            record(url)
                        }
                    }
                    BookImgClick.executeClick(book, bookSource, chapter, button.click, button.src) { host }
                }
                else -> {
                    val js = button.js.orEmpty()
                    val urlNoOption = button.urlNoOption.orEmpty()
                    BookImgClick.executeJs(book, bookSource, chapter, js, urlNoOption) {
                        onBrowserOpenRequestedHook = { url, _, html ->
                            record(url, html = html)
                            true
                        }
                        onBrowserAwaitRequestedHook = { url, _, html ->
                            record(url, html = html)
                            StrResponse(url, "")
                        }
                    }
                }
            }
            resolvedPage.await()
        }
    }

    /**
     * 提取正文里的评论按钮：img 标签选项 JSON 带 style=TEXT 的评论泡。
     * click 与旧源 js 原样带出，交给与用户点击共用的执行逻辑。
     * 段落规则 pclick 与 click 同权识别（pclick 优先，与排版端
     * `ImageInfo.click = pclick ?: click` 一致）。
     */
    fun extractReviewButtons(content: String): List<ReviewButton> {
        return extractReviewButtonsWithStats(content).buttons
    }

    fun extractReviewButtonsWithStats(content: String): ButtonExtraction {
        if (!content.contains("<img", ignoreCase = true)) {
            return ButtonExtraction(emptyList(), 0, 0, 0, 0)
        }
        var imgCount = 0
        var optionParseFailed = 0
        var nonTextStyle = 0
        var noAction = 0
        val result = linkedMapOf<String, ReviewButton>()
        val matcher = AppPattern.imgPattern.matcher(content)
        while (matcher.find()) {
            val src = matcher.group(1) ?: continue
            imgCount++
            val options = extractUrlOptions(src)
            if (options == null) {
                optionParseFailed++
                continue
            }
            val style = options["style"]
            if (style == null || !style.equals("TEXT", ignoreCase = true)) {
                nonTextStyle++
                continue
            }
            val key = src.trim()
            if (result.containsKey(key)) continue
            val urlNoOption = AnalyzeUrl.paramPattern.matcher(src).let { m ->
                if (m.find()) src.take(m.start()) else null
            }
            val pclick = options["pclick"]?.takeIf { it.isNotBlank() }
            val click = options["click"]?.takeIf { it.isNotBlank() }
            val button = ReviewButton(
                src = key,
                click = click,
                js = options["js"]?.takeIf { it.isNotBlank() },
                urlNoOption = urlNoOption,
                pclick = pclick
            )
            if (!button.hasAction) noAction++
            result[key] = button
        }
        return ButtonExtraction(result.values.toList(), imgCount, optionParseFailed, nonTextStyle, noAction)
    }

    /** 与 AnalyzeUrl.paramPattern 一致：URL 后第一个 `,` 到 `{` 的选项 JSON */
    private fun extractUrlOptions(src: String): Map<String, String>? {
        val matcher = AnalyzeUrl.paramPattern.matcher(src)
        if (!matcher.find()) return null
        val optionJson = src.substring(matcher.end())
        return GSON.fromJsonObject<Map<String, String>>(optionJson).getOrNull()
    }
}
