package io.legado.app.ui.host

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.WebCacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.readingHttpClient as okHttpClient
import io.legado.app.help.http.text
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_INJECTION
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_URL
import io.legado.app.help.webView.WebJsExtensions.Companion.basicJs
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebJsExtensions.Companion.nameUrl
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.WebViewPool.BLANK_HTML
import io.legado.app.help.webView.WebViewPool.DATA_HTML
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.Download
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.HostSourceLoginActivity
import io.legado.app.ui.rss.article.ReadRecordDialog
import io.legado.app.ui.rss.favorites.RssFavoritesDialog
import io.legado.app.ui.rss.read.ReadRssViewModel
import io.legado.app.ui.rss.read.RssJsExtensions
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.utils.ACache
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.isTrue
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.openUrl
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.textArray
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.commons.text.StringEscapeUtils
import org.jsoup.Jsoup
import splitties.systemservices.powerManager
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.net.URLDecoder
import java.util.regex.PatternSyntaxException

/**
 * RSS 阅读页 V5 版（业务搬运自 io.legado.app.ui.rss.read.ReadRssActivity）。
 *
 * 呈现层换成 Compose（HostV5Page 玻璃顶栏 + 背景层），业务层逐行保留：
 * WebViewPool 复用、CustomWebChromeClient / CustomWebViewClient 全部重写、
 * JS 注入、返回栈算法、TTS、收藏、下载、图片长按保存。
 *
 * 关键约束：
 * ① WebView 只在 Activity.onDestroy() 里 WebViewPool.release；
 *    AndroidView.onRelease 只解绑宿主（配置变更/重组也会走到 onRelease，不能那时送池去重流水线）。
 * ② 全屏视频容器常驻组合，靠 visibility 切换，不做条件移除组合（否则中断正在播放的视频）。
 * ③ 标题统一写入 titleText 单个 State（旧 upTitleData 与 onPageFinished 两个来源合并）。
 */
class HostReadRssActivity : AppCompatActivity(), RssFavoritesDialog.Callback {

    private val viewModel by viewModels<ReadRssViewModel>()

    private lateinit var pooledWebView: PooledWebView
    private lateinit var currentWebView: WebView

    // ---------- Compose 状态（旧 binding 的等价物） ----------
    /** 标题唯一来源：upTitleData 与 onPageFinished 都写这里，避免互相覆盖 */
    var titleText by mutableStateOf("")
    var loadProgress by mutableIntStateOf(0)
    var progressVisible by mutableStateOf(false)
    var starred by mutableStateOf(false)
    var ttsPlaying by mutableStateOf(false)
    var noImgIframe by mutableStateOf(AppConfig.rssNoImgIframe)
    var menuOpen by mutableStateOf(false)
    /** rssSource 就绪后自增，用于重建溢出菜单（menu_login 可见性依赖它） */
    var sourceVersion by mutableIntStateOf(0)
    val snackbarHostState = SnackbarHostState()

    // ---------- 旧 Activity 的普通字段 ----------
    private var isFullscreen = false
    private var wasScreenOff = false
    private var customWebViewCallback: WebChromeClient.CustomViewCallback? = null
    private var interfaceInjected: String? = null
    private var needClearHistory = true
    private val refreshNameList: MutableList<String> by lazy { mutableListOf() }

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

    /** WebView 区域宿主容器（由 AndroidView factory 赋值），全屏时切 INVISIBLE */
    private var webContainer: FrameLayout? = null

    private val selectImageDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            viewModel.saveImage(it.value, uri)
        }
    }

    private val rssJsExtensions by lazy { RssJsExtensions(this, viewModel.rssSource) }

    private val editSourceResult = registerForActivityResult(
        StartActivityContract(RssSourceEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_OK) {
            refresh()
        }
    }

    // ---------- 生命周期 ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pooledWebView = WebViewPool.acquire(this)
        currentWebView = pooledWebView.realWebView
        initWebView()
        initLiveData()
        viewModel.initData(intent)
        currentWebView.clearHistory()
        setV5Content { HostReadRssScreen(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        loadProgress = 30
        progressVisible = true
        setIntent(intent)
        viewModel.initData(intent)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("SwitchIntDef")
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }

            Configuration.ORIENTATION_PORTRAIT -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
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
        // 池实例只在 Activity 真正销毁时归还；不能放 AndroidView.onRelease（配置变更会提前触发）
        if (::pooledWebView.isInitialized) {
            WebViewPool.release(pooledWebView)
        }
        super.onDestroy()
    }

    // ---------- RssFavoritesDialog.Callback ----------

    override fun updateFavorite(title: String?, group: String?) {
        viewModel.rssArticle?.let {
            if (title != null) {
                it.title = title
            }
            if (group != null) {
                it.group = group
            }
        }
        viewModel.updateFavorite()
    }

    override fun deleteFavorite() {
        viewModel.delFavorite()
    }

    // ---------- 返回栈（旧 onBackPressedDispatcher 算法，Compose 用 BackHandler 调它） ----------

    internal fun handleBack() {
        if (customViewContainer.childCount > 0) { //关闭全屏
            customWebViewCallback?.onCustomViewHidden()
            return
        }
        if (currentWebView.canGoBack()) {
            val list = currentWebView.copyBackForwardList() //获取历史列表
            val size = list.size
            if (size == 1) {
                finish()
                return
            }
            val currentIndex = list.currentIndex
            val currentItem = list.currentItem
            val currentUrl = currentItem?.originalUrl ?: BLANK_HTML
            val currentTitle = currentItem?.title
            //从后往前找，找到第一个不同链接的页面，计算需要回退多少步 避免刷新后导致返回不灵
            var steps = 1
            for (i in currentIndex - 1 downTo 0) {
                val item = list.getItemAtIndex(i)
                val itemTitle = item.title
                val index = refreshNameList.indexOf(itemTitle)
                if (index != -1) {
                    refreshNameList.removeAt(index)
                    steps++
                    continue
                }
                val itemUrl = item.originalUrl
                if (itemUrl == BLANK_HTML) {
                    finish()
                    return
                }
                if (itemUrl != currentUrl || itemTitle != currentTitle) {
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

    // ---------- 菜单动作（旧 optionsMenu 10 项 → 溢出菜单） ----------

    internal fun buildMenuActions(): List<Pair<String, () -> Unit>> {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.refresh) to { refresh() })
        // 降级：AppMenuSheet 无 checkbox 语义，用 ✓ 前缀表达复选态（开关行为与 toast 完全一致）
        val noImgLabel = getString(R.string.rss_no_img_iframe)
        actions.add((if (noImgIframe) "✓ " else "") + noImgLabel to { toggleNoImgIframe() })
        actions.add(
            getString(if (starred) R.string.in_favorites else R.string.out_favorites) to { onStarClick() }
        )
        actions.add(getString(R.string.share) to { shareCurrent() })
        actions.add(
            getString(if (ttsPlaying) R.string.aloud_stop else R.string.read_aloud) to { readAloud() }
        )
        // 旧 onMenuOpened 的可见性判定搬到构建期
        if (!viewModel.rssSource?.loginUrl.isNullOrBlank()) {
            actions.add(getString(R.string.login) to { openLogin() })
        }
        actions.add(getString(R.string.open_in_browser) to { openInBrowser() })
        actions.add(
            getString(R.string.read_record) to {
                showDialogFragment(ReadRecordDialog(viewModel.rssSource?.sourceUrl))
            }
        )
        actions.add(getString(R.string.edit_source) to { editSource() })
        actions.add(getString(R.string.log) to { showDialogFragment<AppLogDialog>() })
        return actions
    }

    private fun refresh() {
        if (viewModel.rssSource?.singleUrl == true) {
            currentWebView.reload()
            return
        }
        currentWebView.title?.let {
            refreshNameList.add(it)
        }
        viewModel.rssArticle?.let {
            start(this@HostReadRssActivity, true, it.origin, it.title, it.link)
        } ?: run {
            viewModel.initData(intent)
        }
    }

    private fun toggleNoImgIframe() {
        AppConfig.rssNoImgIframe = !AppConfig.rssNoImgIframe
        noImgIframe = AppConfig.rssNoImgIframe
        toastOnUi(
            if (AppConfig.rssNoImgIframe) R.string.rss_no_img_iframe_on
            else R.string.rss_no_img_iframe_off
        )
    }

    private fun onStarClick() {
        if (viewModel.rssArticle != null) {
            viewModel.addFavorite()
            viewModel.rssArticle?.let {
                showDialogFragment(RssFavoritesDialog(it))
            }
        } else {
            // 网页态（无文章对象）：切换收藏当前网页（仅支持 http/https）
            val url = currentWebView.url?.takeIf { it.startsWith("http", true) }
            if (url == null) {
                toastOnUi(R.string.null_url)
            } else {
                viewModel.toggleWebFavorite(url, currentWebView.title) { added ->
                    toastOnUi(if (added) R.string.in_favorites else R.string.out_favorites)
                }
            }
        }
    }

    private fun shareCurrent() {
        currentWebView.url?.let {
            share(it)
        } ?: viewModel.rssArticle?.let {
            share(it.link)
        } ?: toastOnUi(R.string.null_url)
    }

    private fun openLogin() {
        startActivity<HostSourceLoginActivity> {
            putExtra("type", "rssSource")
            putExtra("key", viewModel.rssSource?.sourceUrl)
        }
    }

    private fun openInBrowser() {
        currentWebView.url?.let {
            openUrl(it)
        } ?: toastOnUi("url null")
    }

    private fun editSource() {
        viewModel.rssSource?.sourceUrl?.let {
            editSourceResult.launch {
                putExtra("sourceUrl", it)
            }
        }
    }

    // ---------- WebView 初始化 ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        currentWebView.webChromeClient = CustomWebChromeClient()
        //添加屏幕方向控制，网页关闭
        currentWebView.addJavascriptInterface(JSInterface(this), nameBasic)
        currentWebView.webViewClient = CustomWebViewClient()
        currentWebView.setOnLongClickListener {
            val hitTestResult = currentWebView.hitTestResult
            if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            ) {
                hitTestResult.extra?.let { webPic ->
                    selector(
                        arrayListOf(
                            SelectItem(getString(R.string.action_save), "save"),
                            SelectItem(getString(R.string.select_folder), "selectFolder")
                        )
                    ) { _, charSequence, _ ->
                        when (charSequence.value) {
                            "save" -> saveImage(webPic)
                            "selectFolder" -> selectSaveFolder(null)
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
            // 降级：旧 View.longSnackbar(fileName, 下载) → Compose SnackbarHost（长时 + 动作）
            showSnack(fileName, getString(R.string.action_download)) {
                Download.start(this, url, fileName)
            }
        }
    }

    private fun saveImage(webPic: String) {
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            selectSaveFolder(webPic)
        } else {
            viewModel.saveImage(webPic, path.toUri())
        }
    }

    private fun selectSaveFolder(webPic: String?) {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(imagePathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        selectImageDir.launch {
            otherActions = default
            value = webPic
        }
    }

    /** AndroidView factory：每次重组/配置变更都可能重跑，只做重新挂载，不碰池实例 */
    internal fun createWebViewContainer(context: Context, backgroundColor: Int): FrameLayout {
        return FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 不透明底色：WebView 默认透明，复杂背景图下正文会直接透出底图难以阅读；
            // 同时避免透明 WebView 每帧与背景层重新合成导致的屏幕闪烁
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
            visibility = if (isFullscreen) View.INVISIBLE else View.VISIBLE
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun initLiveData() {
        viewModel.upStarMenuData.observe(this) { upStarMenu() }
        viewModel.upTtsMenuData.observe(this) { upTtsMenu(it) }
        viewModel.upTitleData.observe(this) { titleText = it }
        viewModel.contentLiveData.observe(this) { content ->
            viewModel.rssArticle?.let {
                upWebviewSettings()
                initJavascriptInterface()
                val rssSource = viewModel.rssSource
                val html = viewModel.clHtml(content, rssSource?.style)
                val url = NetworkUtils.getAbsoluteURL(it.origin, it.link).substringBefore("@js")
                val baseUrl = if (rssSource?.loadWithBaseUrl == false) null else url
                currentWebView.loadDataWithBaseURL(
                    baseUrl, html, "text/html", "utf-8", url
                )
            }
        }
        viewModel.urlLiveData.observe(this) { urlState ->
            upWebviewSettings(urlState.getUserAgent())
            initJavascriptInterface()
            CookieManager.applyToWebView(urlState.url)
            currentWebView.loadUrl(urlState.url, urlState.headerMap)
        }
        viewModel.htmlLiveData.observe(this) { html ->
            viewModel.rssSource?.let {
                upWebviewSettings()
                initJavascriptInterface()
                val baseUrl = if (it.loadWithBaseUrl) it.sourceUrl else null
                currentWebView.loadDataWithBaseURL(
                    baseUrl, html, "text/html", "utf-8", it.sourceUrl
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun upWebviewSettings(userAgent: String? = null) {
        viewModel.rssSource?.let { s ->
            currentWebView.settings.run {
                userAgentString = userAgent ?: viewModel.headerMap[AppConst.UA_NAME] ?: AppConfig.userAgent
                javaScriptEnabled = s.enableJs
                cacheMode = if (s.cacheFirst) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initJavascriptInterface() {
        viewModel.rssSource?.let {
            if (interfaceInjected != it.sourceUrl) {
                interfaceInjected = it.sourceUrl
                if (!viewModel.hasPreloadJs) return
                val webJsExtensions = WebJsExtensions(it, this, currentWebView)
                currentWebView.addJavascriptInterface(webJsExtensions, nameJava)
                currentWebView.addJavascriptInterface(it, nameSource)
                currentWebView.addJavascriptInterface(WebCacheManager, nameCache)
            }
        }
    }

    private fun upStarMenu() {
        // 源就绪后刷新一次菜单（menu_login 可见性依赖 rssSource）
        sourceVersion++
        if (viewModel.rssArticle == null) {
            starred = false
            upWebStarMenu()
        } else {
            starred = viewModel.rssStar != null
        }
    }

    /** 网页态（无文章对象）下按当前网页地址回查收藏状态 */
    private fun upWebStarMenu() {
        if (viewModel.rssArticle != null) return
        val origin = viewModel.origin ?: return
        val url = currentWebView.url?.takeIf { it.startsWith("http", true) } ?: return
        lifecycleScope.launch(IO) {
            val isStar = appDb.rssStarDao.get(origin, url) != null
            launch(Main) {
                if (viewModel.rssArticle == null &&
                    currentWebView.url == url && !isFinishing && !isDestroyed
                ) {
                    starred = isStar
                }
            }
        }
    }

    private fun upTtsMenu(isPlaying: Boolean) {
        lifecycleScope.launch {
            ttsPlaying = isPlaying
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun readAloud() {
        if (viewModel.tts?.isSpeaking == true) {
            viewModel.tts?.stop()
            upTtsMenu(false)
        } else {
            currentWebView.settings.javaScriptEnabled = true
            currentWebView.evaluateJavascript("document.documentElement.outerHTML") {
                val html = StringEscapeUtils.unescapeJson(it).replace("^\"|\"$".toRegex(), "")
                viewModel.readAloud(
                    Jsoup.parse(html).textArray().joinToString("\n")
                )
            }
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

    // ---------- JS 接口 ----------

    @Suppress("unused")
    private class JSInterface(activity: HostReadRssActivity) {
        private val activityRef: WeakReference<HostReadRssActivity> = WeakReference(activity)

        @JavascriptInterface
        fun lockOrientation(orientation: String) {
            val ctx = activityRef.get()
            if (ctx != null && ctx.isFullscreen && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    ctx.requestedOrientation = when (orientation) {
                        "portrait", "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
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
                    ctx.finish()
                }
            }
        }
    }

    // ---------- WebChromeClient / WebViewClient（逐行搬运） ----------

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
            isFullscreen = true
            // 旧 llView.invisible()：隐藏 WebView 区用 visibility，不做条件移除组合（否则中断视频）
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
            if (viewModel.rssSource?.enableJs == false) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            }
        }

        override fun onHideCustomView() {
            isFullscreen = false
            customViewContainer.removeAllViews()
            customViewContainer.visibility = View.GONE
            webContainer?.visibility = View.VISIBLE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            keepScreenOn(false)
            toggleSystemBar(true)
        }

        /* 覆盖 window.close() */
        override fun onCloseWindow(window: WebView?) {
            finish()
        }

        /* 监听网页日志 */
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            viewModel.rssSource?.let { source ->
                if (source.showWebLog) {
                    val messageLevel = consoleMessage.messageLevel().name
                    val message = consoleMessage.message()
                    AppLog.put(
                        "${source.getTag()}${messageLevel}: $message",
                        NoStackTraceException("\n${message}\n- Line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
                    )
                    return true
                }
            }
            return false
        }
    }

    inner class CustomWebViewClient : WebViewClient() {

        private var jsInjected = false

        override fun shouldOverrideUrlLoading(
            view: WebView, request: WebResourceRequest
        ): Boolean {
            return shouldOverrideUrlLoading(request.url)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            return shouldOverrideUrlLoading(url.toUri())
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (needClearHistory) {
                needClearHistory = false
                currentWebView.clearHistory() //清除历史
            }
            super.onPageStarted(view, url, favicon)
            currentWebView.evaluateJavascript(basicJs, null)
        }

        /**
         * 如果有黑名单,黑名单匹配返回空白,
         * 没有黑名单再判断白名单,在白名单中的才通过,都没有不做处理
         */
        override fun shouldInterceptRequest(
            view: WebView, request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url.toString()
            val source = viewModel.rssSource ?: return super.shouldInterceptRequest(view, request)
            if (request.isForMainFrame) {
                if (viewModel.hasPreloadJs) {
                    jsInjected = false
                    if (url.startsWith("data:text/html;") || request.method == "POST") {
                        return super.shouldInterceptRequest(view, request)
                    }
                    return runBlocking(IO) {
                        getModifiedContentWithJs(url, request)
                            ?: super.shouldInterceptRequest(view, request)
                    }
                }
            } else if (!jsInjected && url == nameUrl) {
                jsInjected = true
                val preloadJs = source.preloadJs ?: ""
                return WebResourceResponse(
                    "text/javascript",
                    "utf-8",
                    ByteArrayInputStream("(() => {$JS_INJECTION\n$preloadJs\n})();".toByteArray())
                )
            }
            val blacklist = source.contentBlacklist?.splitNotBlank(",")
            if (!blacklist.isNullOrEmpty()) {
                blacklist.forEach {
                    try {
                        if (url.startsWith(it) || url.matches(it.toRegex())) {
                            return createEmptyResource()
                        }
                    } catch (e: PatternSyntaxException) {
                        AppLog.put("黑名单规则正则语法错误 源名称:${source.sourceName} 正则:$it", e)
                    }
                }
            } else {
                val whitelist = source.contentWhitelist?.splitNotBlank(",")
                if (!whitelist.isNullOrEmpty()) {
                    whitelist.forEach {
                        try {
                            if (url.startsWith(it) || url.matches(it.toRegex())) {
                                return super.shouldInterceptRequest(view, request)
                            }
                        } catch (e: PatternSyntaxException) {
                            val msg = "白名单规则正则语法错误 源名称:${source.sourceName} 正则:$it"
                            AppLog.put(msg, e)
                        }
                    }
                    return createEmptyResource()
                }
            }
            return super.shouldInterceptRequest(view, request)
        }

        private suspend fun getModifiedContentWithJs(
            url: String,
            request: WebResourceRequest
        ): WebResourceResponse? {
            try {
                val cookie = webCookieManager.getCookie(url)
                val res = okHttpClient.newCallResponse {
                    url(url)
                    method(request.method, null)
                    if (!cookie.isNullOrEmpty()) {
                        addHeader("Cookie", cookie)
                    }
                    request.requestHeaders?.forEach { (key, value) ->
                        addHeader(key, value)
                    }
                }
                res.headers("Set-Cookie").forEach { setCookie ->
                    webCookieManager.setCookie(url, setCookie)
                }
                val body = res.body
                val contentType = body.contentType()
                val mimeType = contentType?.toString()?.substringBefore(";") ?: "text/html"
                val charset = contentType?.charset() ?: Charsets.UTF_8
                val bodyText = body.text().let { originalText ->
                    val headIndex = originalText.indexOf("<head", ignoreCase = true)
                    if (headIndex >= 0) {
                        val closingHeadIndex = originalText.indexOf('>', startIndex = headIndex)
                        if (closingHeadIndex >= 0) {
                            StringBuilder(originalText).insert(closingHeadIndex + 1, JS_URL).toString()
                        } else {
                            originalText
                        }
                    } else {
                        originalText
                    }
                }
                return WebResourceResponse(
                    mimeType,
                    charset.name(),
                    ByteArrayInputStream(bodyText.toByteArray(charset))
                )
            } catch (_: Exception) {
                return null
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            upStarMenu()
            view.title?.let { title ->
                if (title != url
                    && title != view.url
                    && title.isNotBlank()
                    && url != BLANK_HTML
                    && !url.contains(title)
                ) {
                    titleText = title
                } else {
                    viewModel.upTitleData.value?.let { titleText = it }
                }
            }
            viewModel.rssSource?.injectJs?.let {
                if (it.isNotBlank()) {
                    view.evaluateJavascript(it, null)
                }
            }
        }

        private fun createEmptyResource(): WebResourceResponse {
            return WebResourceResponse(
                "text/plain", "utf-8", ByteArrayInputStream("".toByteArray())
            )
        }

        private fun shouldOverrideUrlLoading(url: Uri): Boolean {
            viewModel.rssSource?.let { source ->
                source.shouldOverrideUrlLoading?.takeUnless(String::isNullOrBlank)?.let { js ->
                    val startTime = SystemClock.uptimeMillis()
                    val result = runCatching {
                        runScriptWithContext(lifecycleScope.coroutineContext) {
                            source.evalJS(js) {
                                put("java", rssJsExtensions)
                                put("url", url.toString())
                            }.toString()
                        }
                    }.onFailure {
                        AppLog.put("${source.getTag()}: url跳转拦截js出错", it)
                    }.getOrNull()
                    if (SystemClock.uptimeMillis() - startTime > 99) {
                        AppLog.put("${source.getTag()}: url跳转拦截js执行耗时过长")
                    }
                    if (result.isTrue()) return true
                }
            }
            return handleCommonSchemes(url)
        }

        private fun handleCommonSchemes(url: Uri): Boolean {
            return when (url.scheme) {
                "http", "https" -> false
                "legado", "yuedu" -> {
                    startActivity<OnLineImportActivity> { data = url }
                    true
                }

                else -> {
                    // 降级：旧 View.longSnackbar(跳转其它应用, 确认) → Compose SnackbarHost
                    showSnack(getString(R.string.jump_to_another_app), getString(R.string.confirm)) {
                        openUrl(url)
                    }
                    true
                }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?, handler: SslErrorHandler?, error: SslError?
        ) {
            handler?.proceed()
        }
    }

    companion object {
        fun start(
            context: Context,
            singleTop: Boolean,
            origin: String,
            title: String? = null,
            url: String? = null,
            startHtml: String? = null
        ) {
            context.startActivity<HostReadRssActivity> {
                putExtra("origin", origin)
                putExtra("title", title)
                putExtra("openUrl", url)
                putExtra("startHtml", startHtml)
                if (singleTop) {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            }
        }

        /** 知晓 rssArticle 的打开 */
        fun start(context: Context, origin: String, title: String?, link: String, sort: String) {
            context.startActivity<HostReadRssActivity> {
                putExtra("origin", origin)
                putExtra("title", title)
                putExtra("link", link)
                putExtra("sort", sort)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) //栈顶复用
            }
        }

        private val webCookieManager by lazy { android.webkit.CookieManager.getInstance() }
    }
}

/** 从旧父容器摘除（AndroidView factory/onRelease 复用同一个 View 实例时需要） */
private fun View.detachFromParent() {
    (parent as? ViewGroup)?.removeView(this)
}

@Composable
private fun HostReadRssScreen(activity: HostReadRssActivity) {
    // 旧 onBackPressedDispatcher 的返回栈算法
    BackHandler { activity.handleBack() }

    // WebView 区底色跟随当前主题（不透明，防透底与闪烁）
    val colors = LocalSemanticColors.current
    val webViewBackground = colors.surface.toArgb()

    val actions = remember(
        activity.sourceVersion,
        activity.starred,
        activity.ttsPlaying,
        activity.noImgIframe
    ) {
        activity.buildMenuActions()
    }

    HostV5Page(
        title = activity.titleText,
        // 顶栏返回与系统返回走同一套返回栈算法
        onBack = { activity.handleBack() },
        trailing = {
            io.legado.app.uikit.v5.components.AppOverflowMenuButton(
                contentDescription = stringResource(R.string.more),
                onClick = { activity.menuOpen = true },
            )
        },
        // WebView 不进 haze 采样源：硬件层逐帧回读会导致屏幕频繁闪烁
        contentInHazeSource = false,
    ) {
        Box(Modifier.fillMaxSize()) {
            // ① WebView 区：常驻组合，全屏时切 INVISIBLE（条件移除组合会中断正在播放的视频）
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                factory = { context ->
                    activity.createWebViewContainer(context, webViewBackground)
                },
                // 只解绑宿主，绝不 release/destroy（见 createWebViewContainer 注释）
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
                io.legado.app.uikit.v5.components.AppProgressBar(
                    progress = activity.loadProgress / 100f,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            // ④ snackbar 锚点（下载确认 / 跳转其它应用确认）
            SnackbarHost(
                hostState = activity.snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (activity.menuOpen) {
        io.legado.app.uikit.v5.components.AppMenuSheet(
            title = activity.titleText,
            actions = actions,
            onDismiss = { activity.menuOpen = false },
        )
    }
}
