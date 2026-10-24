package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.help.review.ReviewSnapshotResourceStore
import io.legado.app.help.review.reviewPageIdentity
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.ui.rss.read.VisibleWebView
import io.legado.app.utils.configureOfflineResourceLoading
import io.legado.app.utils.setDarkeningAllowed
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.util.ArrayDeque
import kotlin.random.Random

class CommentWebViewSession {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val allWebViews = mutableListOf<PooledWebView>()
    private val idlePool = ArrayDeque<PooledWebView>()
    private val resettingIds = mutableSetOf<String>()
    private val resetTokens = mutableMapOf<String, Int>()
    private var destroyed = false

    /** 池里还没有实例时挂起的预渲染请求（只保留最新一页，主线程访问） */
    private var pendingPrerender: PrerenderRequest? = null

    private class PrerenderRequest(
        val html: String,
        val baseUrl: String?,
        val book: Book?,
    )

    val isPrepared: Boolean
        get() = !destroyed && allWebViews.any { !it.isInUse }

    fun prepare(context: Context) {
        runOnMain {
            prepareOnMain(context.applicationContext)
        }
    }

    fun acquire(context: Context): PooledWebView {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "CommentWebViewSession.acquire must run on main thread"
        }
        if (destroyed) {
            clearStateOnMain()
            destroyed = false
        }
        val pooled = pollIdle()
            ?: allWebViews.firstOrNull { !it.isInUse && it.id !in resettingIds }
            ?: if (allWebViews.size < MAX_WEB_VIEW_COUNT) {
                createNewWebView().also(allWebViews::add)
            } else {
                allWebViews
                    .filter { !it.isInUse }
                    .minByOrNull { it.lastUseTime }
                    ?: error("No idle comment WebView")
            }
        invalidateReset(pooled)
        idlePool.remove(pooled)
        resettingIds.remove(pooled.id)
        pooled.upContext(context)
        pooled.isInUse = true
        pooled.realWebView.run {
            animate().cancel()
            clearAnimation()
            settings.setDarkeningAllowed(AppConfig.isNightTheme)
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 1f
            visibility = WebView.VISIBLE
            translationY = 0f
            resumeTimers()
            onResume()
        }
        return pooled
    }

    /**
     * 在空闲实例上提前把一页快照渲染好，弹窗打开时直接接管，省掉
     * `loadDataWithBaseURL` 的整段「HTML 跨进程传递 + 解析 + 布局 + 首帧」。
     *
     * 只在池里已有空闲实例时进行（不会为预渲染新建 WebView）；池里还没有实例时
     * 先挂起，等 [prepare] 建出实例后自动接着渲染（两者时序都由阅读页控制）。
     *
     * 内容指纹在这里用池实例自身的 textZoom 计算，与弹窗接管时的判定同源，
     * 保证字号变化后不会拿到上一套排版的成品页。
     *
     * @param baseUrl 与弹窗正常加载时一致的 base 地址，保证两种路径的页面 origin 相同
     */
    fun prerender(
        html: String,
        baseUrl: String?,
        book: Book?,
    ) {
        if (html.isEmpty()) return
        val request = PrerenderRequest(html, baseUrl, book)
        runOnMain {
            if (destroyed) return@runOnMain
            val pooled = idlePool.peekLast()?.takeIf {
                !it.isInUse && it.id !in resettingIds && allWebViews.contains(it)
            }
            if (pooled == null) {
                // 还没有可用实例（WebView 预热尚未完成）：记住最新一页，等池就绪后渲染
                pendingPrerender = request
                return@runOnMain
            }
            startPrerenderOnMain(pooled, request)
        }
    }

    /** 池里出现空闲实例后，把挂起的那一页渲染出来（只保留最新一页）。 */
    private fun flushPendingPrerenderOnMain() {
        val request = pendingPrerender ?: return
        if (destroyed) {
            pendingPrerender = null
            return
        }
        val pooled = idlePool.peekLast()?.takeIf {
            !it.isInUse && it.id !in resettingIds && allWebViews.contains(it)
        } ?: return
        pendingPrerender = null
        startPrerenderOnMain(pooled, request)
    }

    /**
     * 判断 [pooled] 上是否已经渲染好了 [identity] 对应的页面，可直接接管。
     * 超过 [PRERENDER_TTL_MS] 的成品页视为过期（快照可能已被后台刷新）。
     */
    fun isPagePrerendered(pooled: PooledWebView, identity: String): Boolean {
        if (pooled.prerenderIdentity != identity) return false
        if (!pooled.prerenderReady) return false
        return System.currentTimeMillis() - pooled.prerenderTime <= PRERENDER_TTL_MS
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startPrerenderOnMain(
        pooled: PooledWebView,
        request: PrerenderRequest,
    ) {
        val webView = pooled.realWebView
        // 指纹必须用池实例自己的 textZoom：弹窗接管时用的就是这个值
        val identity = reviewPageIdentity(
            request.html, AppConfig.isNightTheme, webView.settings.textZoom
        )
        if (pooled.prerenderIdentity == identity) return
        invalidateReset(pooled)
        pooled.upContext(appCtx)
        pooled.prerenderIdentity = identity
        pooled.prerenderReady = false
        pooled.prerenderTime = System.currentTimeMillis()
        val book = request.book
        val html = request.html
        val baseUrl = request.baseUrl
        webView.run {
            webChromeClient = null
            settings.apply {
                javaScriptEnabled = true
                blockNetworkImage = false
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                setDarkeningAllowed(AppConfig.isNightTheme)
                configureOfflineResourceLoading(true)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (pooled.prerenderIdentity != identity || pooled.isInUse) return null
                    val url = request.url.toString()
                    val key = ReviewSnapshotResourceStore.keyFromReference(url)
                    if (key != null) {
                        val resource = book?.let { ReviewSnapshotResourceStore.open(it, key) }
                            ?: return emptyResponse()
                        return WebResourceResponse(resource.mimeType, null, resource.inputStream)
                    }
                    // 离线快照页不联网：图片、字体等外部请求一律空应答
                    val scheme = request.url.scheme
                    if (scheme == "http" || scheme == "https") return emptyResponse()
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (pooled.prerenderIdentity != identity || pooled.isInUse) return
                    pooled.prerenderReady = true
                    pooled.prerenderTime = System.currentTimeMillis()
                    view?.onPause()
                }
            }
            setBackgroundColor(Color.TRANSPARENT)
            loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", baseUrl)
        }
    }

    private fun emptyResponse() = WebResourceResponse(
        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
    )

    fun detachForReuse(pooled: PooledWebView) {
        runOnMain {
            if (!allWebViews.contains(pooled)) {
                destroyPooled(pooled)
                return@runOnMain
            }
            resetForReuseOnMain(pooled)
        }
    }

    fun releaseAfterDelay(delayMillis: Long = 0L) {
        destroy()
    }

    fun destroy() {
        runOnMain {
            destroyOnMain()
        }
    }

    fun trimMemory(level: Int) {
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        runOnMain {
            trimIdleOnMain()
        }
    }

    private fun prepareOnMain(context: Context) {
        if (destroyed) {
            clearStateOnMain()
            destroyed = false
        }
        if (idlePool.isNotEmpty() || allWebViews.size >= MAX_WEB_VIEW_COUNT) return
        createNewWebView().also { pooled ->
            pooled.upContext(context)
            pooled.isInUse = false
            pooled.lastUseTime = System.currentTimeMillis()
            pooled.realWebView.run {
                setBackgroundColor(Color.TRANSPARENT)
                visibility = WebView.INVISIBLE
                alpha = 0f
                onPause()
            }
            allWebViews.add(pooled)
            offerIdle(pooled)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createNewWebView(): PooledWebView {
        val webView = VisibleWebView(MutableContextWrapper(appCtx))
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.settings.apply {
            javaScriptEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess = true
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
        }
        webView.setBackgroundColor(Color.TRANSPARENT)
        return PooledWebView(webView, generateId())
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resetForReuseOnMain(pooled: PooledWebView) {
        val webView = pooled.realWebView
        (webView.parent as? ViewGroup)?.removeView(webView)
        idlePool.remove(pooled)
        pooled.isInUse = false
        pooled.lastUseTime = System.currentTimeMillis()
        pooled.prerenderIdentity = null
        pooled.prerenderReady = false
        pooled.prerenderTime = 0
        pooled.upContext(appCtx)
        val resetToken = nextResetToken(pooled)
        resettingIds.add(pooled.id)
        webView.run {
            animate().cancel()
            clearAnimation()
            stopLoading()
            clearFocus()
            setOnLongClickListener(null)
            setOnTouchListener(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setOnScrollChangeListener(null)
            }
            setDownloadListener(null)
            outlineProvider = null
            clipToOutline = false
            webChromeClient = null
            removeJavascriptInterface(WebJsExtensions.nameBasic)
            removeJavascriptInterface(WebJsExtensions.nameJava)
            removeJavascriptInterface(WebJsExtensions.nameSource)
            removeJavascriptInterface(WebJsExtensions.nameCache)
            settings.apply {
                blockNetworkImage = false
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = false
                loadWithOverviewMode = false
                textZoom = 100
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != BLANK_HTML || resetTokens[pooled.id] != resetToken || pooled.isInUse) {
                        return
                    }
                    view?.settings?.apply {
                        javaScriptEnabled = false
                        javaScriptEnabled = true
                        blockNetworkImage = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        useWideViewPort = false
                        loadWithOverviewMode = false
                        textZoom = 100
                    }
                    view?.onPause()
                    resettingIds.remove(pooled.id)
                    offerIdle(pooled)
                }
            }
            loadUrl(BLANK_HTML)
            clearHistory()
            alpha = 1f
            visibility = WebView.VISIBLE
            translationY = 0f
            onPause()
        }
    }

    private fun destroyOnMain() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        allWebViews.toList().forEach(::destroyPooled)
        clearStateOnMain()
    }

    private fun trimIdleOnMain() {
        allWebViews.filter { !it.isInUse }.toList().forEach(::destroyPooled)
        if (allWebViews.isEmpty()) {
            destroyed = true
        }
    }

    private fun destroyPooled(pooled: PooledWebView) {
        idlePool.remove(pooled)
        resettingIds.remove(pooled.id)
        resetTokens.remove(pooled.id)
        allWebViews.remove(pooled)
        kotlin.runCatching {
            val webView = pooled.realWebView
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.clearHistory()
            webView.clearCache(false)
            webView.removeAllViews()
            webView.destroy()
        }
    }

    private fun clearStateOnMain() {
        idlePool.clear()
        resettingIds.clear()
        resetTokens.clear()
        allWebViews.clear()
        pendingPrerender = null
    }

    private fun pollIdle(): PooledWebView? {
        while (idlePool.isNotEmpty()) {
            val pooled = idlePool.pollLast()
            if (pooled != null && !pooled.isInUse && pooled.id !in resettingIds && allWebViews.contains(pooled)) {
                return pooled
            }
        }
        return null
    }

    private fun offerIdle(pooled: PooledWebView) {
        if (destroyed || pooled.isInUse || pooled.id in resettingIds || !allWebViews.contains(pooled)) return
        idlePool.remove(pooled)
        idlePool.offerLast(pooled)
        // 池里刚出现可用实例：若有挂起的预渲染请求，接着把它渲染出来
        flushPendingPrerenderOnMain()
    }

    private fun nextResetToken(pooled: PooledWebView): Int {
        val token = (resetTokens[pooled.id] ?: 0) + 1
        resetTokens[pooled.id] = token
        return token
    }

    private fun invalidateReset(pooled: PooledWebView) {
        resetTokens[pooled.id] = (resetTokens[pooled.id] ?: 0) + 1
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun generateId(): String {
        return "comment_web_${System.currentTimeMillis()}_${Random.nextLong()}"
    }

    companion object {
        val shared: CommentWebViewSession by lazy { CommentWebViewSession() }
        private const val MAX_WEB_VIEW_COUNT = 2
        private const val BLANK_HTML = "about:blank"

        /** 预渲染成品页的保鲜期：超过即视为过期，走正常加载（防止显示已被刷新的旧快照）。 */
        private const val PRERENDER_TTL_MS = 5 * 60 * 1000L
    }
}