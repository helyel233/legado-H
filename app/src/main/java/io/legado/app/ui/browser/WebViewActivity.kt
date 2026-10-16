package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.WebCacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.CookieManager as AppCookieManager
import io.legado.app.help.review.ReviewSnapshotImages
import io.legado.app.help.review.ReviewSnapshotManager
import io.legado.app.help.review.ReviewSnapshotResourceStore
import io.legado.app.help.review.reviewoutbox.ReviewOutboxBridge
import io.legado.app.help.review.reviewoutbox.ReviewOutboxContext
import io.legado.app.help.review.reviewoutbox.ReviewOutboxStore
import io.legado.app.help.review.reviewoutbox.ReviewOutboxWireUp
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.basicJs
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.WebViewPool.BLANK_HTML
import io.legado.app.help.webView.WebViewPool.DATA_HTML
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.Download
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppProgressBar
import io.legado.app.utils.ACache
import io.legado.app.utils.configureOfflineResourceLoading
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startActivity
import io.legado.app.utils.toggleSystemBar
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import kotlinx.coroutines.launch
import splitties.systemservices.powerManager
import java.lang.ref.WeakReference
import java.net.URLDecoder

class WebViewActivity : AppCompatActivity() {
    companion object {
        // 是否输出日志
        var sessionShowWebLog = false
        private const val FIRST_PAGE_REVEAL_TIMEOUT = 1500L
    }

    internal val viewModel by viewModels<WebViewModel>()

    private lateinit var pooledWebView: PooledWebView
    private lateinit var currentWebView: WebView

    // ---------- Compose 状态（旧 binding 的等价物） ----------
    var titleText by mutableStateOf("")
    var subtitleText by mutableStateOf<String?>(null)
    var loadProgress by mutableIntStateOf(0)
    var progressVisible by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)
    var showWebLog by mutableStateOf(sessionShowWebLog)
    /** sourceOrigin 就绪后自增，用于重建溢出菜单（停用/删除来源可见性依赖它） */
    var sourceVersion by mutableIntStateOf(0)
    var deleteConfirm by mutableStateOf(false)
    val snackbarHostState = SnackbarHostState()

    // ---------- 旧 Activity 的普通字段 ----------
    private var customWebViewCallback: WebChromeClient.CustomViewCallback? = null
    private var webPic: String? = null
    private var isCloudflareChallenge = false
    private var buttonFullScreen = false
    private var videoFullscreen = false
    private var wasScreenOff = false
    private var needClearHistory = true
    private var waitingFirstPageVisible = false
    private var firstPageVisibleToken = 0
    /** 评论“网络优先”兜底快照：网络加载失败/超时时自动切换到本地快照 */
    private var fallbackHtml: String? = null
    private var fallbackReviewResourceBook: Book? = null
    private var fallbackApplied = false
    private var fallbackTimeoutRunnable: Runnable? = null
    private val mHandler = Handler(Looper.getMainLooper())
    /** 离线评论入队上下文：评论“网络优先”兜底打开时由 fallback 书籍构建 */
    private var outboxContext: ReviewOutboxContext? = null

    /** 全屏视频容器：常驻（visibility 切换），Compose 里永不条件移除 */
    private val customViewContainer by lazy {
        FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
    }

    /** WebView 区域宿主容器（由 AndroidView factory 赋值），视频全屏时切 INVISIBLE */
    private var webContainer: FrameLayout? = null

    private val saveImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            viewModel.saveImage(webPic, uri.toString())
        }
    }

    private fun refresh() {
        currentWebView.reload()
    }

    /** 评论“网络优先”兜底：主框架开始加载时启动真正的超时定时任务 */
    private fun scheduleFallbackTimeout() {
        if (fallbackHtml == null) return
        fallbackTimeoutRunnable?.let { mHandler.removeCallbacks(it) }
        fallbackTimeoutRunnable = Runnable { applyFallbackSnapshot() }
        mHandler.postDelayed(
            fallbackTimeoutRunnable!!,
            ReviewSnapshotManager.NETWORK_FALLBACK_LOAD_TIMEOUT_MS
        )
    }

    private fun cancelFallbackTimeout() {
        fallbackTimeoutRunnable?.let { mHandler.removeCallbacks(it) }
        fallbackTimeoutRunnable = null
    }

    /**
     * 评论“网络优先”兜底：网络加载失败/超时后切换为本地评论快照。
     * 只影响携带 fallbackHtml 的评论打开链路，其余 WebView 行为不变。
     */
    private fun applyFallbackSnapshot() {
        val html = fallbackHtml ?: return
        if (fallbackApplied) return
        fallbackApplied = true
        cancelFallbackTimeout()
        currentWebView.settings.configureOfflineResourceLoading(true)
        currentWebView.loadDataWithBaseURL(
            viewModel.baseUrl.ifBlank { "https://localhost/" },
            html,
            "text/html",
            "utf-8",
            null
        )
        progressVisible = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pooledWebView = WebViewPool.acquire(this)
        currentWebView = pooledWebView.realWebView
        currentWebView.alpha = 0f
        currentWebView.post {
            currentWebView.clearHistory()
        }
        titleText = intent.getStringExtra("title") ?: getString(R.string.loading)
        subtitleText = intent.getStringExtra("sourceName")
        fallbackHtml = intent.getStringExtra("fallbackHtml")
        @Suppress("DEPRECATION")
        run {
            fallbackReviewResourceBook = intent.getParcelableExtra("fallbackReviewResourceBook")
        }
        outboxContext = fallbackReviewResourceBook?.let { book ->
            ReviewOutboxContext(
                bookUrl = book.bookUrl,
                bookName = book.name,
                chapterUrl = "",
                chapterIndex = 0,
                chapterTitle = "",
                origin = intent.getStringExtra("sourceOrigin"),
                buttonSrc = null,
                pageUrl = intent.getStringExtra("url").orEmpty(),
            )
        }
        setV5Content { WebViewScreen(this) }
        viewModel.initData(intent) {
            val url = viewModel.baseUrl
            val headerMap = viewModel.headerMap
            initWebView(url, headerMap)
            val html = viewModel.html
            if (html.isNullOrEmpty()) {
                currentWebView.loadUrl(url, headerMap)
            } else {
                if (viewModel.localHtml) {
                    viewModel.source?.let {
                        val webJsExtensions = WebJsExtensions(it, this, currentWebView)
                        currentWebView.addJavascriptInterface(webJsExtensions, nameJava)
                    }
                    currentWebView.addJavascriptInterface(WebCacheManager, nameCache)
                }
                currentWebView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
                revealWebViewAfterVisualState()
            }
            sourceVersion++
        }
        currentWebView.clearHistory()
    }

    /** 旧 onBackPressedDispatcher 返回栈算法 */
    internal fun handleBack() {
        if (customViewContainer.childCount > 0) { //网页全屏
            customWebViewCallback?.onCustomViewHidden()
            return
        }
        if (buttonFullScreen) { //按钮全屏
            toggleFullScreen()
            return
        }
        if (currentWebView.canGoBack()) {
            val list = currentWebView.copyBackForwardList()
            val size = list.size
            if (size == 1) {
                finish()
                return
            }
            val currentIndex = list.currentIndex
            val currentItem = list.currentItem
            val currentUrl = currentItem?.originalUrl ?: BLANK_HTML
            val currentTitle = currentItem?.title
            var steps = 1
            for (i in currentIndex - 1 downTo 0) {
                val item = list.getItemAtIndex(i)
                val itemUrl = item.originalUrl
                if (itemUrl == BLANK_HTML) {
                    finish()
                    return
                }
                if (itemUrl != currentUrl || currentTitle != item.title) {
                    break
                }
                if (currentUrl == DATA_HTML) {
                    break
                }
                steps++
            }
            if (steps == size) {
                finish()
                return
            }
            currentWebView.goBackOrForward(-steps)
            return
        }
        finish()
    }

    internal fun buildMenuActions(): List<Pair<String, () -> Unit>> {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.refresh) to { refresh() })
        actions.add(getString(R.string.open_in_browser) to { openUrl(viewModel.baseUrl) })
        actions.add(getString(R.string.copy_url) to { sendToClip(viewModel.baseUrl) })
        actions.add(getString(R.string.full_screen) to { toggleFullScreen() })
        val webLogLabel = getString(R.string.show_web_log)
        actions.add((if (showWebLog) "✓ " else "") + webLogLabel to { toggleShowWebLog() })
        if (viewModel.sourceOrigin.isNotEmpty()) {
            actions.add(getString(R.string.disable_source) to { disableSource() })
            actions.add(getString(R.string.delete_source) to { deleteConfirm = true })
        }
        return actions
    }

    /** 旧 menu_ok（完成） */
    internal fun onOkClick() {
        if (viewModel.sourceVerificationEnable) {
            viewModel.saveVerificationResult(currentWebView) {
                finish()
            }
        } else {
            finish()
        }
    }

    internal fun doDeleteSource() {
        viewModel.deleteSource {
            finish()
        }
    }

    private fun disableSource() {
        viewModel.disableSource {
            finish()
        }
    }

    private fun toggleShowWebLog() {
        sessionShowWebLog = !sessionShowWebLog
        showWebLog = sessionShowWebLog
    }

    //实现starBrowser调起页面全屏
    private fun toggleFullScreen() {
        buttonFullScreen = !buttonFullScreen
        toggleSystemBar(!buttonFullScreen)
        // 降级：Compose 玻璃顶栏不随按钮全屏隐藏，仅切系统栏（全屏阅读体验略降级）
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(url: String, headerMap: HashMap<String, String>) {
        hideWebViewUntilReady()
        currentWebView.webChromeClient = CustomWebChromeClient()
        // 添加 JavaScript 接口
        currentWebView.addJavascriptInterface(JSInterface(this), nameBasic)
        currentWebView.webViewClient = CustomWebViewClient()
        outboxContext?.let { context ->
            currentWebView.addJavascriptInterface(
                ReviewOutboxBridge(context),
                ReviewOutboxWireUp.bridgeName
            )
        }
        currentWebView.settings.apply {
            useWideViewPort = true
            loadWithOverviewMode = true
            headerMap[AppConst.UA_NAME]?.let {
                userAgentString = it
            }
        }
        AppCookieManager.applyToWebView(url)
        currentWebView.setOnLongClickListener {
            val hitTestResult = currentWebView.hitTestResult
            if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                hitTestResult.extra?.let { webPic ->
                    selector(
                        arrayListOf(
                            SelectItem(getString(R.string.action_save), "save"),
                            SelectItem(getString(R.string.select_folder), "selectFolder")
                        )
                    ) { _, charSequence, _ ->
                        when (charSequence.value) {
                            "save" -> saveImage(webPic)
                            "selectFolder" -> selectSaveFolder()
                        }
                    }
                    return@setOnLongClickListener true
                }
            }
            return@setOnLongClickListener false
        }
        currentWebView.setDownloadListener { url, _, contentDisposition, _, _ ->
            var fileName = URLUtil.guessFileName(url, contentDisposition, null)
            fileName = URLDecoder.decode(fileName, "UTF-8")
            showSnack(fileName, getString(R.string.action_download)) {
                Download.start(this, url, fileName)
            }
        }
    }

    private fun hideWebViewUntilReady() {
        firstPageVisibleToken++
        val token = firstPageVisibleToken
        waitingFirstPageVisible = true
        currentWebView.alpha = 0f
        currentWebView.postDelayed({
            revealWebViewIfWaiting(token)
        }, FIRST_PAGE_REVEAL_TIMEOUT)
    }

    private fun revealWebViewAfterVisualState() {
        if (!waitingFirstPageVisible) return
        val token = firstPageVisibleToken
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            currentWebView.postVisualStateCallback(
                System.nanoTime(),
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        revealWebViewIfWaiting(token)
                    }
                }
            )
        } else {
            currentWebView.post {
                revealWebViewIfWaiting(token)
            }
        }
    }

    private fun revealWebViewIfWaiting(token: Int) {
        if (!waitingFirstPageVisible || token != firstPageVisibleToken) return
        waitingFirstPageVisible = false
        currentWebView.alpha = 1f
        currentWebView.visibility = View.VISIBLE
    }

    private fun cancelFirstPageReveal() {
        waitingFirstPageVisible = false
        firstPageVisibleToken++
    }

    private fun saveImage(webPic: String) {
        this.webPic = webPic
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            selectSaveFolder()
        } else {
            viewModel.saveImage(webPic, path)
        }
    }

    private fun selectSaveFolder() {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(imagePathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        saveImage.launch {
            otherActions = default
        }
    }

    override fun finish() {
        SourceVerificationHelp.checkResult(viewModel.sourceOrigin)
        super.finish()
    }

    private fun close() {
        if (!isCloudflareChallenge) {
            if (viewModel.sourceVerificationEnable) {
                viewModel.saveVerificationResult(currentWebView) {
                    finish()
                }
            }
            else {
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (powerManager.isInteractive) {
            wasScreenOff = false
            currentWebView.onPause()
        } else {
            wasScreenOff = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (!wasScreenOff) {
            currentWebView.onResume()
        }
    }

    override fun onDestroy() {
        cancelFirstPageReveal()
        WebViewPool.release(pooledWebView)
        super.onDestroy()
    }

    @Suppress("unused")
    private class JSInterface(activity: WebViewActivity) {
        private val activityRef: WeakReference<WebViewActivity> = WeakReference(activity)
        @JavascriptInterface
        fun lockOrientation(orientation: String) {
            val ctx = activityRef.get()
            if (ctx != null && ctx.videoFullscreen && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    ctx.requestedOrientation = when (orientation) {
                        "portrait", "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE //横屏且受重力控制正反
                        "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE //正向横屏
                        "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE //反向横屏
                        "any", "unspecified" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
            }
        }

        @JavascriptInterface
        fun onCloseRequested() {
            val ctx = activityRef.get()
            if (ctx != null && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    ctx.close()
                }
            }
        }
    }

    inner class CustomWebChromeClient : WebChromeClient() {
        override fun getDefaultVideoPoster(): Bitmap {
            return super.getDefaultVideoPoster() ?: createBitmap(100, 100)
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            loadProgress = newProgress
            progressVisible = newProgress != 100
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            videoFullscreen = true
            webContainer?.visibility = View.INVISIBLE
            customViewContainer.apply {
                removeAllViews()
                view?.let {
                    addView(
                        it,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
                visibility = View.VISIBLE
            }
            customWebViewCallback = callback
            keepScreenOn(true)
            toggleSystemBar(false)
        }

        override fun onHideCustomView() {
            videoFullscreen = false
            customViewContainer.removeAllViews()
            customViewContainer.visibility = View.GONE
            webContainer?.visibility = View.VISIBLE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            keepScreenOn(false)
            toggleSystemBar(true)
        }

        /* 覆盖window.close() */
        override fun onCloseWindow(window: WebView?) {
            close()
        }

        /* 监听网页日志 */
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            viewModel.source?.let { source ->
                if (sessionShowWebLog) {
                    val messageLevel = consoleMessage.messageLevel().name
                    val message = consoleMessage.message()
                    AppLog.put("${source.getTag()}${messageLevel}: $message",
                        NoStackTraceException("\n${message}\n- Line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}"))
                    return true
                }
            }
            return false
        }
        
    }

    inner class CustomWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            request?.let {
                return shouldOverrideUrlLoading(it.url)
            }
            return true
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            url?.let {
                return shouldOverrideUrlLoading(it.toUri())
            }
            return true
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            super.onPageCommitVisible(view, url)
            revealWebViewAfterVisualState()
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (needClearHistory) {
                needClearHistory = false
                currentWebView.clearHistory() //清除历史
            }
            super.onPageStarted(view, url, favicon)
            currentWebView.evaluateJavascript(basicJs, null)
            // 评论“网络优先”兜底：主框架开始加载时启动真正的超时定时任务
            scheduleFallbackTimeout()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            // 评论“网络优先”兜底：主框架加载失败且有快照 → 切换快照
            if (fallbackHtml != null && !fallbackApplied &&
                request?.isForMainFrame == true
            ) {
                applyFallbackSnapshot()
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            viewModel.reviewResourceBook = fallbackReviewResourceBook
            if (fallbackApplied && view != null) {
                ReviewSnapshotImages.install(view)
            }
            // 页面加载成功：取消超时定时任务
            cancelFallbackTimeout()
            // 离线评论模式：评论兜底打开的页面注入接管脚本（在线形态拦截发评请求）
            val context = outboxContext
            if (context != null && AppConfig.offlineReviewMode) {
                view?.evaluateJavascript(ReviewOutboxWireUp.buildJs(), null)
                AppLog.putDebug(
                    "${ReviewOutboxStore.LogTag} 接管脚本已注入(浏览器页) " +
                        "url=${url ?: ""} 书=${context.bookName}"
                )
            }
            revealWebViewAfterVisualState()
            val cookieManager = CookieManager.getInstance()
            url?.let {
                CookieStore.setCookie(it, cookieManager.getCookie(it))
            }
            view?.title?.let { title ->
                if (title != url && title != view.url && title.isNotBlank()) {
                    titleText = title
                } else {
                    titleText = intent.getStringExtra("title").orEmpty()
                }
                view.evaluateJavascript("!!window._cf_chl_opt") {
                    if (it == "true") {
                        isCloudflareChallenge = true
                    } else if (isCloudflareChallenge && viewModel.sourceVerificationEnable) {
                        viewModel.saveVerificationResult(currentWebView) {
                            finish()
                        }
                    }
                }
            }
        }

        private fun shouldOverrideUrlLoading(url: Uri): Boolean {
            if (fallbackApplied && ReviewSnapshotImages.open(
                    this@WebViewActivity, fallbackReviewResourceBook, url
                )
            ) return true
            return when (url.scheme) {
                "http", "https" -> false
                "legado", "yuedu" -> {
                    startActivity<OnLineImportActivity> {
                        data = url
                    }
                    true
                }

                else -> {
                    showSnack(getString(R.string.jump_to_another_app), getString(R.string.confirm)) {
                        openUrl(url)
                    }
                    true
                }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            handler?.proceed()
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val resourceUrl = request.url.toString()
            ReviewSnapshotResourceStore.keyFromReference(resourceUrl)?.let { key ->
                val book = checkNotNull(fallbackReviewResourceBook) {
                    "评论快照资源引用缺少书籍上下文: $resourceUrl"
                }
                val resource = checkNotNull(ReviewSnapshotResourceStore.open(book, key)) {
                    "评论快照资源不存在: $resourceUrl"
                }
                return WebResourceResponse(resource.mimeType, null, resource.inputStream)
            }
            return super.shouldInterceptRequest(view, request)
        }

    }

    /** Compose 版 snackbar（旧 View.longSnackbar 需要 View 锚点，V5 页没有 binding 根布局） */
    private fun showSnack(
        message: CharSequence,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        lifecycleScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = message.toString(),
                actionLabel = actionLabel,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                onAction?.invoke()
            }
        }
    }

    internal fun createWebViewContainer(context: Context, backgroundColor: Int): FrameLayout {
        return FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 不透明底色：WebView 默认透明，复杂背景图下加载期/透明网页会透出底图，
            // 且透明 WebView 每帧与背景层重新合成会导致屏幕频繁闪烁
            setBackgroundColor(backgroundColor)
            (currentWebView.parent as? ViewGroup)?.removeView(currentWebView)
            currentWebView.setBackgroundColor(backgroundColor)
            addView(
                currentWebView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            visibility = if (videoFullscreen) View.INVISIBLE else View.VISIBLE
            webContainer = this
        }
    }

    internal fun releaseWebViewContainer(container: FrameLayout) {
        // 只摘除宿主，绝不在此 WebViewPool.release / destroy：
        // 配置变更或重组也会走到这里，池实例仍归本页使用，统一在 Activity.onDestroy 释放
        container.removeAllViews()
        if (webContainer === container) webContainer = null
    }

    internal fun createCustomViewContainer(): FrameLayout {
        return customViewContainer.apply { detachFromParent() }
    }
}

@Composable
private fun WebViewScreen(activity: WebViewActivity) {
    // 旧 onBackPressedDispatcher 的返回栈算法
    BackHandler { activity.handleBack() }

    // WebView 区底色跟随当前主题（不透明，防透底与闪烁）
    val colors = LocalSemanticColors.current
    val webViewBackground = colors.surface.toArgb()

    val actions = remember(activity.sourceVersion, activity.showWebLog) {
        activity.buildMenuActions()
    }

    HostV5Page(
        title = activity.titleText,
        subtitle = activity.subtitleText,
        onBack = { activity.handleBack() },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppFilledButton(
                    text = stringResource(R.string.ok),
                    onClick = { activity.onOkClick() },
                )
                AppOverflowMenuButton(
                    contentDescription = stringResource(R.string.more),
                    onClick = { activity.menuOpen = true },
                )
            }
        },
        // WebView 不进 haze 采样源：硬件层逐帧回读会导致屏幕频繁闪烁
        contentInHazeSource = false,
    ) {
        Box(Modifier.fillMaxSize()) {
            // ① WebView 区：常驻组合，视频全屏时切 INVISIBLE（条件移除组合会中断正在播放的视频）
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    activity.createWebViewContainer(context, webViewBackground)
                },
                onRelease = { activity.releaseWebViewContainer(it) },
            )
            // ② 全屏视频容器：覆盖最上层，常驻组合，靠 visibility 切换
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { activity.createCustomViewContainer() },
                onRelease = { it.detachFromParent() },
            )
            // ③ 顶部加载进度（旧 RefreshProgressBar → AppProgressBar）
            if (activity.progressVisible) {
                AppProgressBar(
                    progress = activity.loadProgress / 100f,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            // ④ Snackbar
            SnackbarHost(
                hostState = activity.snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (activity.menuOpen) {
        AppMenuSheet(
            title = activity.titleText,
            actions = actions,
            onDismiss = { activity.menuOpen = false },
        )
    }

    if (activity.deleteConfirm) {
        AppConfirmDialog(
            title = stringResource(R.string.draw),
            message = stringResource(R.string.sure_del) + "\n" + activity.viewModel.sourceName,
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                activity.deleteConfirm = false
                activity.doDeleteSource()
            },
            onDismiss = { activity.deleteConfirm = false },
        )
    }
}

private fun View.detachFromParent() {
    (parent as? ViewGroup)?.removeView(this)
}
