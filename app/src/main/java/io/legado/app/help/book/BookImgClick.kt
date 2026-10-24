package io.legado.app.help.book

import androidx.appcompat.app.AppCompatActivity
import com.script.rhino.rhinoContext
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.BackstageWebView
import io.legado.app.help.http.StrResponse
import io.legado.app.help.review.ReviewSnapshot
import io.legado.app.help.review.ReviewParagraphTemplate
import io.legado.app.help.review.ReviewPreheatCoordinator
import io.legado.app.help.review.ReviewSnapshotCapture
import io.legado.app.help.review.ReviewSnapshotManager
import io.legado.app.help.review.ReviewSnapshotStore
import io.legado.app.help.review.SyntheticParaContent
import io.legado.app.help.review.reviewoutbox.ReviewOutboxContext
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.paramPattern
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.widget.dialog.BottomWebViewDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.coroutineContext

/**
 * 图片/评论点击统一入口。
 *
 * “评论打开方式”三模式（[AppConfig.reviewOpenMode]，默认网络优先）：
 * - 网络优先：点击评论立即按原 click/js 正常打开真实评论页，绝不因存在快照
 *   截断原链路；网络加载失败/超时且有快照 → 自动切换快照；无快照 → 正常错误；
 * - 快照优先：有快照 → 立即显示快照（0 秒可见），后台继续执行原 click/js 解析
 *   并加载最新网络评论页，成功后当前窗口覆盖为在线页；失败/超时停留快照；
 *   无快照 → 正常网络打开；
 * - 仅使用快照：有快照 → 打开快照，绝不执行 click/js、绝不联网；无快照 →
 *   明确提示“当前章节没有缓存评论”，不回退网络。
 *
 * 同一套 click/js 执行逻辑同时服务用户点击与后台抓取：抓取时传入拦截宿主
 * （click 分支替换 java 宿主；js 分支挂 AnalyzeRule 钩子），其余环境完全一致。
 */
object BookImgClick {

    private fun openMode(): String = AppConfig.reviewOpenMode

    /** 与图片点击入口共用同一判定，避免给没有 click/js 的零评论泡暴露段评菜单。 */
    fun hasAction(src: String, click: String?): Boolean {
        if (!click.isNullOrBlank()) return true
        val options = parseSrcOptions(src)?.second ?: return false
        return !options["click"].isNullOrBlank() || !options["js"].isNullOrBlank()
    }

    /**
     * 评论快照定位不依赖网络执行条件。书源缺失时仍可用 book/chapter 读取并打开
     * 已落盘的快照；只有真正执行 click/js 时才解析并要求书源存在。
     */
    private data class ReviewContext(
        val book: Book,
        val chapter: BookChapter,
        val useCurrentReadSource: Boolean,
    )

    /** 已在网络执行前读出的快照，失败回退时禁止重新查询或重新解析上下文。 */
    private data class CachedReviewSnapshot(
        val book: Book,
        val chapter: BookChapter,
        val snapshot: ReviewSnapshot,
    )

    private fun currentChapter(hostChapter: BookChapter?): BookChapter? {
        return hostChapter
            ?: ReadBook.book?.let {
                appDb.bookChapterDao.getChapter(it.bookUrl, ReadBook.durChapterIndex)
            }
    }

    /**
     * 解析评论所属文字书上下文。这里不读取书源：本地快照的主键只需要
     * book/chapter，不能因为任何网络执行条件而错过离线快照。
     */
    private fun reviewContext(chapter: BookChapter, src: String): ReviewContext? {
        // legado-H 暂无音频书与文字书的融合挂载体系，评论一律归属当前阅读书。
        val book = ReadBook.book ?: return null
        return ReviewContext(book, chapter, useCurrentReadSource = true)
    }

    private fun cachedSnapshot(context: ReviewContext, src: String, syntheticPara: SyntheticParaContent? = null): CachedReviewSnapshot? {
        // 快照文件损坏/身份不符（require 抛出）按无快照处理：
        // 调用方按各自模式回落网络或提示，不让一个坏文件卡死点击链路
        val snapshot = runCatching {
            ReviewSnapshotStore.get(context.book, context.chapter, src.trim())
        }.getOrElse { error ->
            AppLog.put("ReviewSnapshot 快照读取失败，按无快照处理\n${error.localizedMessage}", error)
            null
        } ?: return null
        val page = if (syntheticPara?.snapshotFallbackAllowed == false) {
            ReviewParagraphTemplate.create(snapshot, syntheticPara)
        } else snapshot
        return CachedReviewSnapshot(context.book, context.chapter, page)
    }

    private fun resolveNetworkSource(context: ReviewContext): BookSource? {
        return if (context.useCurrentReadSource) {
            ReadBook.bookSource
        } else {
            appDb.bookSourceDao.getBookSource(context.book.origin)
        }
    }

    private fun showSnapshot(
        context: AppCompatActivity,
        cached: CachedReviewSnapshot,
        source: BookSource? = null,
        networkRefresher: (suspend () -> Pair<String, String>?)? = null,
        offlineOnly: Boolean,
        sourceKeyOverride: String? = null,
        onDismiss: (() -> Unit)? = null,
    ): Boolean {
        if (context.isFinishing || context.isDestroyed) return false
        // 段落规则段评：弹窗按 paragraph_rule_<id> 源键加载规则宿主（页内 ajax /
        // 发表评论都走规则的登录态），与点击网络打开时的源键保持一致。
        val sourceKey = sourceKeyOverride ?: source?.getKey().orEmpty()
        context.runOnUiThread {
            if (context.isFinishing || context.isDestroyed) return@runOnUiThread
            context.showDialogFragment(
                BottomWebViewDialog(
                    sourceKey,
                    BookType.text,
                    cached.snapshot.url.ifBlank { "about:blank" },
                    cached.snapshot.html,
                    networkRefresher = networkRefresher,
                    offlineOnly = offlineOnly,
                    reviewResourceBook = cached.book,
                    outboxContext = ReviewOutboxContext(
                        bookUrl = cached.book.bookUrl,
                        bookName = cached.book.name,
                        chapterUrl = cached.chapter.url,
                        chapterIndex = cached.chapter.index,
                        chapterTitle = cached.chapter.title,
                        origin = sourceKey.ifBlank { cached.book.origin },
                        buttonSrc = cached.snapshot.buttonSrc.ifBlank { null },
                        pageUrl = cached.snapshot.url,
                    ),
                    // 内容来自 ReviewSnapshotStore 的快照：身份为离线，允许离线接管
                    isSnapshotHtml = true,
                    onDismiss = onDismiss,
                )
            )
        }
        return true
    }

    /**
     * 打开评论快照。
     * @param refreshToNetwork 快照优先：后台刷新为最新网络评论页（成功后覆盖）
     * @param offlineOnly 仅使用快照：WebView 禁止一切 http/https 网络请求
     * @param pclick 段落规则点击值；非空时后台刷新走规则点击脚本解析真实评论页
     * @return true 表示已用快照打开
     */
    private fun openSnapshotIfCached(
        context: AppCompatActivity,
        src: String,
        hostChapter: BookChapter?,
        refreshToNetwork: Boolean,
        offlineOnly: Boolean,
        syntheticPara: SyntheticParaContent? = null,
        pclick: String? = null,
        onDismiss: (() -> Unit)? = null,
    ): Boolean {
        val chapter = currentChapter(hostChapter) ?: return false
        // 先持有本地快照；网络刷新条件不得影响离线快照可用性。
        val resolvedContext = reviewContext(chapter, src) ?: return false
        val cached = cachedSnapshot(resolvedContext, src, syntheticPara) ?: return false
        // 快照优先：后台解析真实评论页并加载在线内容，成功后覆盖当前快照
        var refresher: (suspend () -> Pair<String, String>?)? = null
        val snapshotSource = resolveNetworkSource(resolvedContext)
        if (refreshToNetwork && snapshotSource != null) {
            val button = reviewButtonOf(src)
            if (button != null) {
                refresher = refresh@{
                    if (syntheticPara?.snapshotFallbackAllowed == false) {
                        return@refresh fetchOnlineHtml(cached.snapshot.url, snapshotSource)
                    }
                    val page = ReviewSnapshotManager.resolveReviewPageUrl(
                        cached.book, snapshotSource, cached.chapter, button
                    )
                    val onlineUrl = page.url ?: return@refresh null
                    // showBrowser 已带回渲染 HTML 且有效：直接用，不再重复请求
                    val onlineHtml = when {
                        !page.html.isNullOrBlank() &&
                            ReviewSnapshotCapture.isValidCommentHtml(page.html) -> page.html
                        else -> fetchOnlineHtml(onlineUrl, snapshotSource)?.second
                    } ?: return@refresh null
                    onlineUrl to onlineHtml
                }
            }
        }
        val ruleSourceKey = pclick
            ?.let { ParagraphRuleProcessor.ruleIdOfClick(it) }
            ?.let { "paragraph_rule_$it" }
        if (offlineOnly) {
            // 仅快照模式没有「打开时刷新」器：过期快照（超过保鲜期）由后台
            // 刷新覆盖，弹窗本身仍纯离线渲染，先用旧快照保证 0 秒可见
            val ttlDays = AppConfig.reviewSnapshotTtlDays
            if (ReviewPreheatCoordinator.isStale(
                    cached.snapshot.savedAt, System.currentTimeMillis(), ttlDays
                )
            ) {
                ReviewPreheatCoordinator.refreshButton(cached.book, cached.chapter, src)
            }
        }
        return showSnapshot(
            context, cached, snapshotSource, refresher, offlineOnly,
            sourceKeyOverride = ruleSourceKey, onDismiss = onDismiss
        )
    }

    /**
     * 段评图标直点路径的快照消费（与选中文字后的段评菜单同一套三模式语义）：
     * - 仅使用快照：有快照 → 立即打开；无快照 → 提示且不回落网络；
     * - 快照优先：有快照 → 立即打开（后台刷新最新网络页）；无快照 → 回落网络；
     * - 网络优先（默认）：直接回落网络，绝不因存在快照改变点击行为。
     *
     * 在 IO 线程调用（内部会读快照文件），弹窗经 runOnUiThread 展示。
     *
     * @param pclick 段落规则点击值；图标直点段落规则段评时传入，用于后台刷新与弹窗源键
     * @param onDismiss 快照弹窗关闭回调（调用方据此复位防重入标记）
     */
    fun clickImgSnapshotFirst(
        context: AppCompatActivity,
        src: String,
        hostChapter: BookChapter? = null,
        pclick: String? = null,
        onDismiss: (() -> Unit)? = null,
    ): ReviewSnapshotConsumption {
        when (openMode()) {
            AppConfig.ReviewOpenMode.SNAPSHOT_ONLY -> {
                val opened = openSnapshotIfCached(
                    context, src, hostChapter,
                    refreshToNetwork = false, offlineOnly = true,
                    pclick = pclick, onDismiss = onDismiss
                )
                if (opened) return ReviewSnapshotConsumption.Opened
                context.toastOnUi(R.string.review_no_cached_snapshot)
                return ReviewSnapshotConsumption.Miss
            }
            AppConfig.ReviewOpenMode.SNAPSHOT_FIRST -> {
                val opened = openSnapshotIfCached(
                    context, src, hostChapter,
                    refreshToNetwork = true, offlineOnly = false,
                    pclick = pclick, onDismiss = onDismiss
                )
                return if (opened) {
                    ReviewSnapshotConsumption.Opened
                } else {
                    ReviewSnapshotConsumption.NotConsumed
                }
            }
            else -> return ReviewSnapshotConsumption.NotConsumed
        }
    }

    /** [clickImgSnapshotFirst] 的消费结果 */
    sealed interface ReviewSnapshotConsumption {
        /** 已用快照打开弹窗：调用方不得再执行 click/js */
        object Opened : ReviewSnapshotConsumption

        /** 未消费：调用方继续执行 click/js 网络链路 */
        object NotConsumed : ReviewSnapshotConsumption

        /** 仅快照模式且无快照：已提示用户，调用方应停止 */
        object Miss : ReviewSnapshotConsumption
    }

    /**
     * 执行 src 附带的 click JS（用户点击路径）。
     * @param hostChapter 当前展示章节；融合挂载的评论按钮据此反查文字书
     * 上下文执行；为 null 时按当前阅读上下文（ReadBook）执行。
     */
    fun clickImg(
        context: AppCompatActivity,
        scope: CoroutineScope,
        click: String,
        src: String,
        hostChapter: BookChapter? = null,
        syntheticPara: SyntheticParaContent? = null,
    ) {
        // 合成入口只复用缓存地址创建本段本地模板，不展示锚点段的评论。
        when (openMode()) {
            AppConfig.ReviewOpenMode.SNAPSHOT_ONLY -> {
                // 仅使用快照：绝不执行 click/js，也绝不允许快照内残留资源联网
                if (openSnapshotIfCached(
                        context, src, hostChapter,
                        refreshToNetwork = false, offlineOnly = true, syntheticPara = syntheticPara
                    )
                ) {
                    return
                }
                context.toastOnUi(R.string.review_no_cached_snapshot)
            }
            AppConfig.ReviewOpenMode.SNAPSHOT_FIRST -> {
                if (openSnapshotIfCached(
                        context, src, hostChapter,
                        refreshToNetwork = true, offlineOnly = false, syntheticPara = syntheticPara
                    )
                ) {
                    return
                }
                // 无快照 → 直接按正常网络评论打开
                openNetwork(context, scope, click, null, null, src, hostChapter, syntheticPara)
            }
            else -> openNetwork(context, scope, click, null, null, src, hostChapter, syntheticPara)
        }
    }

    /**
     * 兼容旧源：click/js 写在 src 的 url 选项里（无独立 click 字段时走此入口）。
     * @param hostChapter 当前展示章节；融合挂载的评论按钮据此反查文字书上下文。
     * @return true 表示已处理
     */
    fun oldClickImg(
        context: AppCompatActivity,
        scope: CoroutineScope,
        src: String,
        hostChapter: BookChapter? = null,
    ): Boolean {
        val parsed = parseSrcOptions(src)
        if (parsed == null) {
            // 旧源选项 JSON 损坏时，网络链路没有可执行的 click/js。若已缓存，仍应
            // 直接展示持有的离线评论；无快照则交回原图片链路暴露其原始失败。
            return if (openMode() == AppConfig.ReviewOpenMode.NETWORK &&
                paramPattern.matcher(src).find()
            ) {
                openSnapshotIfCached(
                    context, src, hostChapter,
                    refreshToNetwork = false, offlineOnly = true
                )
            } else {
                false
            }
        }
        val (urlNoOption, options) = parsed
        val click = options["click"]
        val js = options["js"]
        if (click.isNullOrBlank() && js.isNullOrBlank()) {
            return if (openMode() == AppConfig.ReviewOpenMode.NETWORK) {
                openSnapshotIfCached(
                    context, src, hostChapter,
                    refreshToNetwork = false, offlineOnly = true
                )
            } else {
                false
            }
        }
        when (openMode()) {
            AppConfig.ReviewOpenMode.SNAPSHOT_ONLY -> {
                if (!openSnapshotIfCached(
                        context, src, hostChapter,
                        refreshToNetwork = false, offlineOnly = true
                    )
                ) {
                    context.toastOnUi(R.string.review_no_cached_snapshot)
                }
            }
            AppConfig.ReviewOpenMode.SNAPSHOT_FIRST -> {
                if (!openSnapshotIfCached(
                        context, src, hostChapter,
                        refreshToNetwork = true, offlineOnly = false
                    )
                ) {
                    openNetwork(context, scope, click, js, urlNoOption, src, hostChapter)
                }
            }
            else -> openNetwork(context, scope, click, js, urlNoOption, src, hostChapter)
        }
        return true
    }

    /**
     * 网络打开（网络优先默认路径 / 快照优先无快照回退）。
     * click 与旧源 js 走与用户点击完全一致的执行环境；
     * 网络优先模式下载入时若该按钮存在快照，将其作为“加载失败/超时兜底”
     * 传给浏览器（绝不因存在快照截断原链路）。
     */
    private fun openNetwork(
        context: AppCompatActivity,
        scope: CoroutineScope,
        click: String?,
        js: String?,
        urlNoOption: String?,
        src: String,
        hostChapter: BookChapter?,
        syntheticPara: SyntheticParaContent? = null,
    ) {
        Coroutine.async(scope, Dispatchers.IO) {
            val chapter = currentChapter(hostChapter)
                ?: error("无法定位当前章节，无法执行评论网络打开")
            // 快照必须在任何网络执行条件、click/js 解析之前读出并持有。
            // 合成入口从锚点缓存地址创建独立段评模板。
            val resolvedContext = reviewContext(chapter, src)
            val fallback = resolvedContext?.let { cachedSnapshot(it, src, syntheticPara) }
            try {
                val execution = resolvedContext
                    ?: error("无法解析评论所属书籍或章节，无法执行评论网络打开")
                val execSource = resolveNetworkSource(execution)
                    ?: error("评论书源不存在，无法执行评论网络打开")
                when {
                    !click.isNullOrBlank() -> {
                        // 评论 click 统一宿主：有无快照兜底都走本宿主，保证
                        // showBrowser 弹窗路径统一携带离线评论上下文
                        val host = SnapshotFallbackJsExtensions(
                            context,
                            execSource,
                            BookType.text,
                            fallback?.snapshot?.html,
                            execution.book,
                            execution.chapter,
                            src.takeUnless { syntheticPara?.snapshotFallbackAllowed == false },
                            syntheticPara,
                        )
                        executeClick(
                            execution.book, execSource, execution.chapter, click, src
                        ) {
                            host
                        }
                        if (!host.browserRequested) {
                            error("评论 click 未发起浏览器打开")
                        }
                    }
                    else -> {
                        val fallbackHtml = fallback?.snapshot?.html
                        var browserRequested = false
                        executeJs(
                            execution.book, execSource, execution.chapter,
                            js.orEmpty(), urlNoOption.orEmpty()
                        ) {
                            fallbackBrowserHtml = fallbackHtml
                            fallbackReviewResourceBook = fallback?.book
                            if (fallbackHtml != null) {
                                onBrowserOpenRequestedHook = { _, _, _ ->
                                    browserRequested = true
                                    false
                                }
                                onBrowserAwaitRequestedHook = { _, _, _ ->
                                    browserRequested = true
                                    null
                                }
                            }
                        }
                        if (fallbackHtml != null && !browserRequested) {
                            error("评论 js 未发起浏览器打开")
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (fallback != null && showSnapshot(
                        context, fallback,
                        networkRefresher = null,
                        offlineOnly = true
                    )
                ) {
                    AppLog.put(
                        "评论网络打开失败，已回退本地快照\n${error.localizedMessage}",
                        error
                    )
                } else {
                    throw error
                }
            }
        }.onError {
            AppLog.put("执行图片链接click/js键值出错\n${it.localizedMessage}", it, true)
        }
    }

    /** 从 src 构造评论按钮模型（后台解析/刷新用） */
    private fun reviewButtonOf(src: String): ReviewSnapshotManager.ReviewButton? {
        val (urlNoOption, options) = parseSrcOptions(src) ?: return null
        return ReviewSnapshotManager.ReviewButton(
            src = src.trim(),
            click = options["click"]?.takeIf { it.isNotBlank() },
            js = options["js"]?.takeIf { it.isNotBlank() },
            urlNoOption = urlNoOption,
            pclick = options["pclick"]?.takeIf { it.isNotBlank() }
        )
    }

    /** 后台加载真实网络评论页（带书源 cookie/UA），失败或超时返回 null */
    private suspend fun fetchOnlineHtml(
        url: String,
        source: BookSource
    ): Pair<String, String>? {
        return runCatching {
            val analyzeUrl = AnalyzeUrl(url, source = source)
            val body = BackstageWebView(
                url = analyzeUrl.url,
                headerMap = analyzeUrl.headerMap,
                tag = source.getKey(),
                timeout = FETCH_TIMEOUT_MS
            ).getStrResponse().body
            body?.takeIf { it.isNotBlank() }?.let { analyzeUrl.url to it } ?: return null
        }.getOrNull()
    }

    /**
     * 解析 src 的选项 JSON：返回 (去选项地址, 选项Map)。无选项时返回 null。
     */
    fun parseSrcOptions(src: String): Pair<String, Map<String, String>>? {
        val urlMatcher = paramPattern.matcher(src)
        if (!urlMatcher.find()) return null
        val urlNoOption = src.take(urlMatcher.start())
        val urlOptionStr = src.substring(urlMatcher.end())
        val options = GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull() ?: return null
        return urlNoOption to options
    }

    /**
     * click 分支统一执行：与用户点击完全一致的环境，java 宿主可替换为拦截宿主。
     */
    suspend fun executeClick(
        book: Book,
        source: BookSource,
        chapter: BookChapter,
        click: String,
        src: String,
        javaBuilder: () -> io.legado.app.help.JsExtensions
    ) {
        runScriptWithContext {
            source.evalJS(click) {
                val java = javaBuilder()
                put("java", java)
                put("book", book)
                put("chapter", chapter)
                put("result", src)
            }
        }
    }

    /**
     * 旧源 js 分支统一执行：AnalyzeRule 规则引擎环境，与用户点击完全一致。
     * 评论快照抓取可通过 [AnalyzeRule.onBrowserOpenRequestedHook] 拦截浏览器请求；
     * 网络优先的失败兜底可通过 [AnalyzeRule.fallbackBrowserHtml] 指定快照。
     */
    suspend fun executeJs(
        book: Book,
        source: BookSource,
        chapter: BookChapter,
        js: String,
        urlNoOption: String,
        ruleHook: (AnalyzeRule.() -> Unit)? = null
    ) {
        AnalyzeRule(book, source).apply {
            setCoroutineContext(coroutineContext)
            setBaseUrl(chapter.url)
            setChapter(chapter)
            ruleHook?.invoke(this)
            evalJS(js, urlNoOption)
        }
    }

    /**
     * 网络优先模式的浏览器宿主：打开真实评论页时附带快照兜底，
     * 网络加载失败/超时由 WebViewActivity 自动切换到快照。
     */
    private class SnapshotFallbackJsExtensions(
        context: AppCompatActivity?,
        source: BookSource?,
        bookType: Int,
        private val fallbackHtml: String?,
        private val reviewResourceBook: Book,
        private val chapter: BookChapter?,
        private val buttonSrc: String?,
        private val syntheticPara: SyntheticParaContent? = null,
    ) : SourceLoginJsExtensions(context, source, bookType) {

        var browserRequested = false
            private set

        override fun startBrowser(url: String, title: String) {
            startBrowser(url, title, null)
        }

        override fun startBrowser(url: String, title: String, html: String?) {
            browserRequested = true
            rhinoContext.ensureActive()
            SourceVerificationHelp.startBrowser(
                getSource(),
                url,
                title,
                html = html,
                fallbackHtml = fallbackHtml,
                fallbackReviewResourceBook = reviewResourceBook,
            )
        }

        override fun startBrowserAwait(url: String, title: String): StrResponse {
            return startBrowserAwait(url, title, true, null)
        }

        override fun startBrowserAwait(
            url: String,
            title: String,
            refetchAfterSuccess: Boolean
        ): StrResponse {
            return startBrowserAwait(url, title, refetchAfterSuccess, null)
        }

        override fun startBrowserAwait(
            url: String,
            title: String,
            refetchAfterSuccess: Boolean,
            html: String?
        ): StrResponse {
            browserRequested = true
            rhinoContext.ensureActive()
            val pair = SourceVerificationHelp.getVerificationResult(
                getSource(), url, title, true, refetchAfterSuccess, html,
                fallbackHtml = fallbackHtml,
                fallbackReviewResourceBook = reviewResourceBook,
            )
            val (url2, body) = pair
            return StrResponse(url2.ifEmpty { url }, body)
        }

        /** 评论底部弹窗路径同样支持“网络优先”：失败/超时切快照 */
        override fun showBrowser(
            url: String,
            html: String?,
            preloadJs: String?,
            config: String?
        ) {
            browserRequested = true
            val activity = activityRef.get() ?: return
            val source = getSource() ?: return
            if (callbackRef.get()?.showBrowser(url, html, preloadJs, config) == true) {
                return
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                activity.showDialogFragment(
                    BottomWebViewDialog(
                        source.getKey(),
                        bookType,
                        url,
                        html,
                        preloadJs,
                        config,
                        networkRefresher = null,
                        fallbackHtml = fallbackHtml,
                        reviewResourceBook = reviewResourceBook,
                        syntheticParaContent = syntheticPara,
                        outboxContext = ReviewOutboxContext(
                            bookUrl = reviewResourceBook.bookUrl,
                            bookName = reviewResourceBook.name,
                            chapterUrl = chapter?.url.orEmpty(),
                            chapterIndex = chapter?.index ?: 0,
                            chapterTitle = chapter?.title.orEmpty(),
                            origin = getSource()?.getKey(),
                            buttonSrc = buttonSrc,
                            pageUrl = url,
                        ),
                    )
                )
            }
        }
    }

    private const val FETCH_TIMEOUT_MS = 60_000L
}
