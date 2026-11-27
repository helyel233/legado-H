package io.legado.app.help.review

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.net.http.SslError
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import com.google.gson.stream.JsonReader
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.WebCacheManager
import io.legado.app.help.cache.CacheOperationDiagnostics
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieManager as AppCookieManager
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_URL
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebJsExtensions.Companion.nameUrl
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.WebViewRequestLifecycle
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.configureOfflineResourceLoading
import io.legado.app.utils.get
import io.legado.app.utils.runOnUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.apache.commons.text.StringEscapeUtils
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 评论页快照抓取引擎。
 *
 * 支持两条路径：
 * 1. Data Engine（JSON 数据直拉）：协议受支持时通过 [ReviewDataCapture] 直接拉取并拼装干净 HTML；
 * 2. Web Engine（无头 WebView 抓拍）：加载评论页并循环穷尽「展开/查看回复/加载更多/懒加载」，
 *    稳定后在主线程直接抽取干净 DOM HTML（不下载/内联 CSS、字体或图片资源）。
 */
object ReviewSnapshotCapture {

    private val mHandler = Handler(Looper.getMainLooper())

    /** 超时看门狗不能依赖 WebView 所在的主线程，否则主线程卡顿会同时拖迟所有 Capture。 */
    private val timeoutScheduler = ScheduledThreadPoolExecutor(2) { runnable ->
        Thread(runnable, "ReviewSnapshotTimeout").apply { isDaemon = true }
    }.apply {
        setRemoveOnCancelPolicy(true)
    }

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 页面加载、评论展开与干净 DOM 抽取共享的执行预算。 */
    private val PAGE_EXECUTION_TIMEOUT_MS get() = ReviewDownloadConfig.Number.PAGE_TIMEOUT.value.toLong()
    private const val HEADLESS_VIEWPORT_WIDTH = 1080
    private const val HEADLESS_VIEWPORT_HEIGHT = 1920

    /** 每轮之间的等待 */
    private const val EXPAND_ROUND_INTERVAL_MS = 800L

    /** 连续几轮稳定才判定完成（含慢加载评论） */
    private const val STABLE_ROUNDS_TO_FINISH = 3

    private val pagePermits = Semaphore(1)

    private suspend fun <T> Semaphore.withPermit(block: suspend () -> T): T {
        runInterruptible(Dispatchers.IO) { acquire() }
        return try {
            block()
        } finally {
            release()
        }
    }

    private class CaptureStageTimeoutException(message: String) : NoStackTraceException(message)

    /**
     * 抓取结果（供诊断日志）：快照 + 展开检测轮数 + 实际点击“展开/加载更多”次数。
     */
    data class CaptureOutcome(
        val snapshot: ReviewSnapshot,
        /** 展开检测轮询轮数（包含“没点按钮”的稳定轮） */
        val expandRounds: Int,
        /** 实际点击“展开/回复/加载更多”按钮的次数（stats.clicked 累计） */
        val expandClickCount: Int,
        /** 兼容保留字段，纯 DOM 快照恒为 0 */
        val droppedResources: Int = 0,
        val engine: String = "web",
    )

    /**
     * showBrowser 带回的 HTML 有效性校验。
     *
     * 判为无效的情形：
     * - 空或纯空白
     * - 纯 JSON 错误负载（无任何 HTML 标签）
     * - 无 HTML 标签的纯文本且过短（<512 字符，基本是错误提示）
     * - 有 HTML 结构但极短（<256 字符）
     */
    fun isValidCommentHtml(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        val trimmed = html.trimStart()
        if ((trimmed.startsWith("{") || trimmed.startsWith("[")) && !html.contains("<")) {
            return false
        }
        if (!html.contains("<")) {
            return html.length >= 512
        }
        return html.length >= 256
    }

    private val HTML_TAG_REGEX = Regex("<[a-zA-Z!/]")

    /**
     * 非纯 JSON 负载且含 HTML 标签的初始文档（含短 HTML 壳页）：
     * 即使长度小于 256 字符，也允许进入 WebView 让 preloadJs/页面脚本继续渲染，
     * 最终有效性由抽取阶段抓拍到的 DOM 判定。
     */
    private fun isRenderableHtmlShell(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        val trimmed = html.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return false
        return HTML_TAG_REGEX.containsMatchIn(html)
    }

    /**
     * showBrowser 只给了 preloadJs、没给 html 时，先按书源规则取回页面文档作为初始 HTML。
     */
    private suspend fun prefetchCommentHtml(
        url: String,
        source: BaseSource,
        preloadJs: String?,
    ): String? {
        if (preloadJs.isNullOrBlank()) return null
        val fetched = try {
            AnalyzeUrl(url, source = source, coroutineContext = currentCoroutineContext())
                .getStrResponseAwait()
                .body
                ?.takeIf { it.isNotBlank() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AppLog.put("评论页预取失败，按失败处理: URL=$url", error)
            throw error
        } ?: throw NoStackTraceException("评论页预取内容为空（URL=$url）")
        if (!isValidCommentHtml(fetched) && !isRenderableHtmlShell(fetched)) {
            throw NoStackTraceException(
                "评论页预取内容非有效评论页（${fetched.length} 字符，疑似错误页/反爬页）"
            )
        }
        return fetched
    }

    /**
     * 抓取真实评论页快照并抽取干净 DOM。
     */
    internal suspend fun capture(
        source: BaseSource,
        book: Book,
        chapter: BookChapter,
        buttonSrc: String,
        url: String,
        initialHtml: String? = null,
        preloadJs: String? = null,
        diagnostics: CacheOperationDiagnostics.Context? = null,
        @Suppress("UNUSED_PARAMETER")
        commitIfLeaseActive: ((() -> Unit) -> Boolean) = { action -> action(); true },
        incremental: Boolean = false,
    ): CaptureOutcome {
        val effectiveInitialHtml = initialHtml?.takeIf { it.isNotBlank() }
        val trace = diagnostics?.let {
            CacheOperationDiagnostics.begin(
                it.forChapter(chapter.index),
                "CAPTURE",
                CacheOperationDiagnostics.Metrics(inputChars = effectiveInitialHtml?.length),
                startAlways = true,
            )
        }
        return try {
            val protocol = (source as? BookSource)?.ruleReview?.snapshotProtocol?.trim().orEmpty()
            val engine = ReviewDownloadConfig.engine
            val supported = ReviewDataCapture.supports(url, effectiveInitialHtml, protocol)
            var useData = when (engine) {
                ReviewDownloadConfig.Engine.WEB -> false
                ReviewDownloadConfig.Engine.AUTO -> supported
                ReviewDownloadConfig.Engine.DATA -> supported.also { ok ->
                    if (!ok) AppLog.put("评论引擎=数据接口，但当前评论类型/书源不支持，已回退网页快照")
                }
            }
            var preparedHtml = effectiveInitialHtml
            if (useData) {
                preparedHtml = try {
                    ReviewDataCapture.capture(
                        url,
                        AnalyzeUrl(
                            url,
                            source = source,
                            coroutineContext = currentCoroutineContext(),
                        ).headerMapWithCookie(),
                        effectiveInitialHtml,
                        trace,
                    )
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (engine != ReviewDownloadConfig.Engine.AUTO) throw error
                    AppLog.put("评论数据接口抓取失败，自动模式回退网页快照", error)
                    useData = false
                    effectiveInitialHtml
                }
            }
            trace?.mark(if (useData) "ENGINE_DATA" else "ENGINE_WEB", force = true)
            if (!useData && effectiveInitialHtml != null &&
                !isValidCommentHtml(effectiveInitialHtml) &&
                !isRenderableHtmlShell(effectiveInitialHtml)
            ) {
                if (url.toHttpUrlOrNull() != null) {
                    AppLog.put(
                        "showBrowser 带回的 HTML 无效（${effectiveInitialHtml.length} 字符），回退 URL 抓取: $url"
                    )
                    preparedHtml = null
                } else {
                    throw NoStackTraceException(
                        "showBrowser 带回的 HTML 非有效评论页（疑似 ajax 错误/异常文本，" +
                            "${effectiveInitialHtml.length} 字符，已按失败处理）"
                    )
                }
            }
            val page = if (useData) {
                SnapshotPageResult(
                    html = requireNotNull(preparedHtml),
                    expandRounds = 0,
                    expandClickCount = 0,
                )
            } else {
                val pageHtml = preparedHtml ?: prefetchCommentHtml(url, source, preloadJs)
                pagePermits.withPermit {
                    snapshotPage(
                        url = url,
                        source = source,
                        initialHtml = pageHtml,
                        preloadJs = preloadJs,
                        diagnostics = trace,
                        paginationEnabled = !incremental,
                    )
                }
            }
            val engineName = if (useData) "data" else "web"
            val snapshot = ReviewSnapshot(
                bookUrl = book.bookUrl,
                chapterUrl = chapter.url,
                chapterIndex = chapter.index,
                chapterTitle = chapter.title,
                buttonSrc = buttonSrc,
                url = url,
                title = "",
                html = page.html,
                resourceKeys = emptyList(),
                partial = false,
                savedAt = System.currentTimeMillis(),
                downloadEngine = engineName,
                resourceFailures = emptyList(),
            )
            CaptureOutcome(
                snapshot = snapshot,
                expandRounds = page.expandRounds,
                expandClickCount = page.expandClickCount,
                droppedResources = 0,
                engine = engineName,
            ).also {
                trace?.done(CacheOperationDiagnostics.Metrics(outputChars = page.html.length))
            }
        } catch (error: Throwable) {
            trace?.fail(error)
            throw error
        }
    }

    private data class SnapshotPageResult(
        val html: String,
        val expandRounds: Int,
        val expandClickCount: Int,
    )

    private suspend fun snapshotPage(
        url: String,
        source: BaseSource,
        initialHtml: String? = null,
        preloadJs: String? = null,
        diagnostics: CacheOperationDiagnostics.Operation? = null,
        paginationEnabled: Boolean = true,
    ): SnapshotPageResult {
        val analyzeUrl = AnalyzeUrl(url, source = source, coroutineContext = currentCoroutineContext())
        val headerMap = analyzeUrl.headerMap
        val effectiveHtml = initialHtml?.takeIf { it.isNotBlank() }
        return suspendCancellableCoroutine { block ->
            val pooledRef = AtomicReference<PooledWebView?>()
            val sessionRef = AtomicReference<SnapshotSession?>()
            val webViewLifecycle = WebViewRequestLifecycle(bridgeScope)
            val released = AtomicBoolean(false)

            fun releaseOnce(discard: Boolean = false) {
                webViewLifecycle.close()
                val pooled = pooledRef.getAndSet(null) ?: return
                if (released.compareAndSet(false, true)) {
                    runOnUI {
                        if (discard) WebViewPool.discard(pooled) else WebViewPool.release(pooled)
                    }
                }
            }

            block.invokeOnCancellation {
                val session = sessionRef.get()
                if (session == null) {
                    releaseOnce()
                } else {
                    session.destroy()
                }
            }

            runOnUI {
                if (!block.isActive) return@runOnUI
                try {
                    val pooled = WebViewPool.acquire(appCtx)
                    pooledRef.set(pooled)
                    val webView = pooled.realWebView
                    webView.onResume()
                    webView.setBackgroundColor(android.graphics.Color.WHITE)
                    if (webView.width <= 0 || webView.height <= 0) {
                        val widthSpec = View.MeasureSpec.makeMeasureSpec(
                            HEADLESS_VIEWPORT_WIDTH,
                            View.MeasureSpec.EXACTLY,
                        )
                        val heightSpec = View.MeasureSpec.makeMeasureSpec(
                            HEADLESS_VIEWPORT_HEIGHT,
                            View.MeasureSpec.EXACTLY,
                        )
                        webView.measure(widthSpec, heightSpec)
                        webView.layout(0, 0, HEADLESS_VIEWPORT_WIDTH, HEADLESS_VIEWPORT_HEIGHT)
                    }
                    webView.settings.apply {
                        configureOfflineResourceLoading(false)
                        userAgentString = headerMap.get(AppConst.UA_NAME, true)
                    }
                    AppCookieManager.applyToWebView(url)
                    val bridgeToken = WebJsExtensions.newBridgeToken()
                    val hasInitialHtml = !effectiveHtml.isNullOrBlank()
                    val jsBridge = if (hasInitialHtml || !preloadJs.isNullOrBlank()) {
                        PageJsBridge(preloadJs, bridgeToken)
                    } else {
                        null
                    }
                    val session = SnapshotSession(
                        webView = webView,
                        url = url,
                        jsBridge = jsBridge,
                        webViewLifecycle = webViewLifecycle,
                        diagnostics = diagnostics,
                        paginationEnabled = paginationEnabled,
                    ) { result, error, rounds, clicks, discardWebView ->
                        sessionRef.set(null)
                        releaseOnce(discardWebView)
                        if (block.isActive) {
                            if (error != null) {
                                block.resumeWithException(error)
                            } else {
                                block.resume(SnapshotPageResult(result ?: "", rounds, clicks))
                            }
                        }
                    }
                    sessionRef.set(session)
                    if (!block.isActive) {
                        session.destroy()
                        return@runOnUI
                    }
                    webView.webViewClient = session.client
                    if (hasInitialHtml && jsBridge != null) {
                        webView.addJavascriptInterface(WebCacheManager, nameCache)
                        webView.addJavascriptInterface(source, nameSource)
                        webView.addJavascriptInterface(
                            WebJsExtensions(
                                source,
                                null,
                                webView,
                                BookType.text,
                                requestLifecycle = webViewLifecycle,
                                bridgeToken = bridgeToken,
                            ),
                            nameJava,
                        )
                    } else {
                        webView.removeJavascriptInterface(nameCache)
                        webView.removeJavascriptInterface(nameSource)
                        webView.removeJavascriptInterface(nameJava)
                    }
                    if (hasInitialHtml) {
                        webView.loadDataWithBaseURL(
                            url, spliceJsUrl(effectiveHtml), "text/html", "utf-8", url
                        )
                    } else {
                        webView.loadUrl(url, webViewExtraHeaders(headerMap))
                    }
                } catch (e: Throwable) {
                    val session = sessionRef.get()
                    if (session == null) {
                        releaseOnce()
                        if (block.isActive) block.resumeWithException(e)
                    } else {
                        session.abort(e)
                    }
                }
            }
        }
    }

    private fun webViewExtraHeaders(headerMap: Map<String, String>): Map<String, String> {
        return HashMap(headerMap).apply {
            keys.removeAll { it.equals(AppConst.UA_NAME, ignoreCase = true) }
        }
    }

    private fun spliceJsUrl(html: String): String {
        val headIndex = html.indexOf("<head", ignoreCase = true)
        if (headIndex >= 0) {
            val closingHeadIndex = html.indexOf('>', startIndex = headIndex)
            if (closingHeadIndex >= 0) {
                return StringBuilder(html).insert(closingHeadIndex + 1, JS_URL).toString()
            }
        }
        return JS_URL + html
    }

    private class PageJsBridge(val preloadJs: String?, val bridgeToken: String)

    private class SnapshotSession(
        private val webView: WebView,
        private val url: String,
        private val jsBridge: PageJsBridge?,
        private val webViewLifecycle: WebViewRequestLifecycle,
        private val diagnostics: CacheOperationDiagnostics.Operation?,
        private val paginationEnabled: Boolean = true,
        private val done: (String?, Throwable?, Int, Int, Boolean) -> Unit,
    ) {
        private data class TerminalResult(
            val html: String?,
            val error: Throwable?,
            val discardWebView: Boolean,
        )

        private data class PageStats(
            val clicked: Int,
            val textLen: Int,
            val height: Int,
            val nodes: Int,
        )

        private data class CleanDomJsResult(
            val h: String? = null,
        )

        @Volatile
        private var destroyed = false
        private var expandRounds = 0
        private var totalExpandClicks = 0
        private var stableRounds = 0
        private var lastTextLen = -1
        private var lastHeight = -1
        private var lastNodes = -1
        private var expandLoopActive = false
        private val cacheReplies = AppConfig.cacheReviewReplies
        private var jsInjectedForPage = false
        private val extractionStarted = AtomicBoolean(false)
        private val lifecycleLock = Any()
        private var terminalFinalized = false
        @Volatile
        private var stageTimeout: ScheduledFuture<*>? = null

        init {
            startPageTimeout()
        }

        val client = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                if (destroyed) return
                mHandler.postDelayed({ expandRound() }, 1500L)
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?,
            ) {
                handler?.proceed()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    fail(NoStackTraceException("评论页加载失败 code=${error?.errorCode} ${error?.description}"))
                }
            }

            @Deprecated("API 23 以下")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                if (failingUrl == url || failingUrl == url.substringBefore("#")) {
                    fail(NoStackTraceException("评论页加载失败 code=$errorCode $description"))
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) {
                    fail(NoStackTraceException("评论页 HTTP 错误 ${errorResponse?.statusCode}"))
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                return request?.isForMainFrame == true &&
                    !request.url.toString().startsWith(url.substringBefore("#"))
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val bridge = jsBridge
                if (bridge != null && !jsInjectedForPage && request.url.toString() == nameUrl) {
                    jsInjectedForPage = true
                    val injection = WebJsExtensions.buildInjectionScript(bridge.bridgeToken)
                    return WebResourceResponse(
                        "text/javascript",
                        "utf-8",
                        ByteArrayInputStream(
                            ("(() => {$injection\n${bridge.preloadJs.orEmpty()}\n})();").toByteArray()
                        )
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            @RequiresApi(android.os.Build.VERSION_CODES.O)
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                fail(
                    NoStackTraceException("评论页 WebView 渲染进程已退出"),
                    discardWebView = true,
                )
                return true
            }
        }

        fun destroy() {
            completeTerminal(
                TerminalResult(
                    html = null,
                    error = CancellationException("评论快照 Capture 已取消"),
                    discardWebView = true,
                ),
                reportStageError = false,
            )
        }

        fun abort(error: Throwable) {
            fail(error, discardWebView = true)
        }

        private fun finish(html: String) {
            completeTerminal(
                TerminalResult(
                    html = html,
                    error = null,
                    discardWebView = false,
                ),
                reportStageError = false,
            )
        }

        private fun fail(
            error: Throwable,
            discardWebView: Boolean = error is CaptureStageTimeoutException,
        ) {
            completeTerminal(
                TerminalResult(
                    html = null,
                    error = error,
                    discardWebView = discardWebView,
                ),
                reportStageError = true,
            )
        }

        private fun completeTerminal(result: TerminalResult, reportStageError: Boolean) {
            synchronized(lifecycleLock) {
                if (terminalFinalized) return
                terminalFinalized = true
                destroyed = true
            }
            clearStageTimeout()
            webViewLifecycle.close()
            if (result.error == null) {
                diagnostics?.stageDone(
                    "PAGE_EXECUTION",
                    CacheOperationDiagnostics.Metrics(outputChars = result.html?.length),
                )
            } else if (reportStageError) {
                diagnostics?.stageFail("PAGE_EXECUTION", result.error)
            }
            runOnUI {
                done(result.html, result.error, expandRounds, totalExpandClicks, result.discardWebView)
            }
        }

        private fun startPageTimeout() {
            val timeoutMs = PAGE_EXECUTION_TIMEOUT_MS
            diagnostics?.stageStart("PAGE_EXECUTION", startAlways = true)
            if (timeoutMs == 0L) return
            stageTimeout = timeoutScheduler.schedule({
                fail(
                    CaptureStageTimeoutException("评论页加载/展开执行超时 ${timeoutMs / 1000}s"),
                    discardWebView = true,
                )
            }, timeoutMs, TimeUnit.MILLISECONDS)
        }

        private fun clearStageTimeout() {
            stageTimeout?.cancel(false)
            stageTimeout = null
        }

        private fun expandRound() {
            if (destroyed) return
            if (expandLoopActive) return
            expandLoopActive = true
            webView.evaluateJavascript(expandJs(cacheReplies, paginationEnabled)) { json ->
                mHandler.post {
                    if (destroyed) return@post
                    expandLoopActive = false
                    val stats = parseStats(json)
                    if (stats == null) {
                        expandRounds++
                        mHandler.postDelayed({ expandRound() }, EXPAND_ROUND_INTERVAL_MS)
                        return@post
                    }
                    val stable = stats.clicked == 0 &&
                        stats.textLen == lastTextLen &&
                        stats.height == lastHeight &&
                        stats.nodes == lastNodes
                    totalExpandClicks += stats.clicked
                    lastTextLen = stats.textLen
                    lastHeight = stats.height
                    lastNodes = stats.nodes
                    stableRounds = if (stable) stableRounds + 1 else 0
                    expandRounds++
                    if (stableRounds >= STABLE_ROUNDS_TO_FINISH) {
                        afterExpandLoop()
                    } else {
                        mHandler.postDelayed({ expandRound() }, EXPAND_ROUND_INTERVAL_MS)
                    }
                }
            }
        }

        private fun afterExpandLoop() {
            if (destroyed) return
            if (!cacheReplies) {
                extractCleanDom()
                return
            }
            forceExpandReplies()
        }

        private fun forceExpandReplies() {
            if (destroyed) return
            webView.evaluateJavascript(FORCE_EXPAND_JS) { json ->
                mHandler.post {
                    if (destroyed) return@post
                    val clicked = parseForceClicked(json) ?: run {
                        fail(IllegalStateException("无法读取评论回复展开结果"))
                        return@post
                    }
                    diagnostics?.mark(
                        "FORCE_EXPAND_PASS",
                        CacheOperationDiagnostics.Metrics(resourceCount = clicked),
                    )
                    if (clicked > 0) {
                        mHandler.postDelayed({ forceExpandReplies() }, EXPAND_ROUND_INTERVAL_MS)
                    } else {
                        extractCleanDom()
                    }
                }
            }
        }

        private fun extractCleanDom() {
            if (destroyed || !extractionStarted.compareAndSet(false, true)) return
            diagnostics?.stageStart("SANITIZE")
            webView.evaluateJavascript(buildCleanDomJs(cacheReplies)) { raw ->
                mHandler.post {
                    if (destroyed) return@post
                    diagnostics?.mark(
                        "WEBVIEW_HTML_READY",
                        CacheOperationDiagnostics.Metrics(inputChars = raw?.length),
                    )
                    val decoded = runCatching { decodeCleanDomHtml(raw) }
                    decoded.fold(
                        onSuccess = { html ->
                            val failureMessage = when {
                                html.isNullOrBlank() -> "评论页快照序列化为空 $url"
                                !isValidCommentHtml(html) ->
                                    "评论页快照抓取 DOM 内容无效或过短（${html.length} 字符）$url"
                                else -> null
                            }
                            if (html.isNullOrBlank() || failureMessage != null) {
                                val error = NoStackTraceException(failureMessage ?: "评论页快照序列化为空 $url")
                                diagnostics?.stageFail("SANITIZE", error)
                                fail(error)
                            } else {
                                diagnostics?.stageDone(
                                    "SANITIZE",
                                    CacheOperationDiagnostics.Metrics(outputChars = html.length),
                                )
                                finish(html)
                            }
                        },
                        onFailure = { error ->
                            diagnostics?.stageFail("SANITIZE", error)
                            fail(error)
                        },
                    )
                }
            }
        }

        private fun decodeCleanDomHtml(raw: String?): String? {
            if (raw == null || raw == "null") return null
            val json = JsonReader(StringReader(raw)).use { reader -> reader.nextString() }
            return GSON.fromJson(json, CleanDomJsResult::class.java)?.h
        }

        private fun parseForceClicked(json: String?): Int? {
            json ?: return null
            return runCatching {
                val s = StringEscapeUtils.unescapeJson(json).trim('"')
                if (s == "null") return null
                val obj = GSON.fromJson(s, Map::class.java)
                (obj?.get("c") as? Double)?.toInt() ?: 0
            }.getOrNull()
        }

        private fun parseStats(json: String?): PageStats? {
            json ?: return null
            return runCatching {
                val s = StringEscapeUtils.unescapeJson(json).trim('"')
                if (s == "null") return null
                val obj = GSON.fromJson(s, Map::class.java)
                PageStats(
                    clicked = (obj?.get("c") as? Double)?.toInt() ?: 0,
                    textLen = (obj?.get("t") as? Double)?.toInt() ?: 0,
                    height = (obj?.get("h") as? Double)?.toInt() ?: 0,
                    nodes = (obj?.get("n") as? Double)?.toInt() ?: 0,
                )
            }.getOrNull()
        }
    }

    /**
     * 展开循环脚本。每轮点击文本命中“展开/加载更多”等模式的元素（每轮限量），
     * 并滚动到页底触发懒加载。
     */
    private fun expandJs(includeReplies: Boolean, paginationEnabled: Boolean = true): String {
        val pattern = when {
            includeReplies && paginationEnabled ->
                "(展开|更多回复|查看回复|加载更多|查看更多|查看全部|显示全部|点击查看|继续阅读|load\\s*more|show\\s*more|view\\s*more|expand)"
            includeReplies -> "(展开|更多回复|查看回复|expand)"
            paginationEnabled ->
                "(加载更多|查看更多|查看全部|显示全部|点击查看|继续阅读|load\\s*more|show\\s*more|view\\s*more)"
            else -> null
        }
        val replySkip = if (includeReplies) {
            ""
        } else {
            "if(el.closest&&el.closest('.reply-section'))continue;"
        }
        val clickLoop = if (pattern == null) {
            ""
        } else {
            "var pat=/$pattern/i;" +
                "var els=document.querySelectorAll('a,button,[role=\"button\"],[onclick],div,span,p');" +
                "for(var i=0;i<els.length;i++){var el=els[i];" +
                replySkip +
                "var t=(el.innerText||'').trim();" +
                "if(!t||t.length>24||!pat.test(t))continue;" +
                "var r=el.getBoundingClientRect();" +
                "if(r.width<1||r.height<1)continue;" +
                "if(el.children.length>2)continue;" +
                "el.scrollIntoView({block:'center'});" +
                "try{el.click();}catch(e){}" +
                "try{el.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));" +
                "el.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));" +
                "el.dispatchEvent(new TouchEvent('touchend',{bubbles:true}));}catch(e){}" +
                "clicked++;if(clicked>=6)break;}"
        }
        return "(function(){" +
            "var clicked=0;" +
            clickLoop +
            "try{window.scrollTo(0,document.body?document.body.scrollHeight:0);}catch(e){}" +
            "var h=document.body?document.body.scrollHeight:0;" +
            "var n=document.getElementsByTagName('*').length;" +
            "return JSON.stringify({c:clicked,t:document.body?document.body.innerText.length:0,h:h,n:n});" +
            "})()"
    }

    /** 楼中楼兜底强展脚本：不限每轮数量地点击全部未展开的回复 toggle。 */
    private const val FORCE_EXPAND_JS =
        "(function(){" +
            "var pat=/(展开\\s*\\d*\\s*条回复|展开回复|查看回复|全部回复|更多回复|共\\s*\\d+\\s*条回复|load\\s*more\\s*repl)/i;" +
            "var clicked=0;" +
            "var els=document.querySelectorAll('a,button,[role=\"button\"],[onclick],div,span,p');" +
            "for(var i=0;i<els.length;i++){var el=els[i];" +
            "var t=(el.innerText||'').trim();" +
            "if(!t||t.length>30||!pat.test(t))continue;" +
            "var r=el.getBoundingClientRect();" +
            "if(r.width<1||r.height<1)continue;" +
            "if(el.children.length>2)continue;" +
            "el.scrollIntoView({block:'center'});" +
            "try{el.click();}catch(e){}" +
            "try{el.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));" +
            "el.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));}catch(e){}" +
            "clicked++;}" +
            "return JSON.stringify({c:clicked});" +
            "})()"

    /**
     * 轻量干净 DOM 抽取脚本：克隆当前文档，剔除脚本/内嵌框架/外链样式与字体声明，
     * 视设置移除回复层，并将保留图片的相对 src 解析为绝对地址后序列化输出。
     */
    private fun buildCleanDomJs(cacheReplies: Boolean): String {
        val stripRepliesJs = if (cacheReplies) {
            ""
        } else {
            "root.querySelectorAll('.reply-section').forEach(function(el){" +
                "if(el.parentNode)el.parentNode.removeChild(el);});"
        }
        return "(function(){" +
            "var root=document.documentElement.cloneNode(true);" +
            "root.querySelectorAll('script,noscript,iframe').forEach(function(el){" +
            "if(el.parentNode)el.parentNode.removeChild(el);});" +
            "root.querySelectorAll('link').forEach(function(el){" +
            "var rel=(el.getAttribute('rel')||'').toLowerCase();" +
            "var asAttr=(el.getAttribute('as')||'').toLowerCase();" +
            "if(rel.indexOf('stylesheet')>=0||(rel.indexOf('preload')>=0&&asAttr==='font')){" +
            "if(el.parentNode)el.parentNode.removeChild(el);}});" +
            "root.querySelectorAll('style').forEach(function(el){" +
            "var text=el.textContent||'';" +
            "text=text.replace(/@font-face\\s*\\{[^}]*\\}/ig,'');" +
            "text=text.replace(/@import\\s+(?:url\\s*\\([^)]*\\)|[\"'][^\"']*[\"'])[^;]*;?/ig,'');" +
            "el.textContent=text;});" +
            stripRepliesJs +
            "root.querySelectorAll('img[src]').forEach(function(el){" +
            "var raw=(el.getAttribute('src')||'').trim();" +
            "if(!raw||/^(?:data:|javascript:|about:|#)/i.test(raw))return;" +
            "try{var abs=new URL(raw,document.baseURI).href;" +
            "if(abs)el.setAttribute('src',abs);}catch(e){" +
            "if(el.src)el.setAttribute('src',el.src);}});" +
            "return JSON.stringify({h:root.outerHTML});" +
            "})()"
    }
}
