package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.databinding.DialogWebViewBinding
import io.legado.app.help.book.ParagraphRuleJsExtensions
import io.legado.app.help.WebCacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_INJECTION
import io.legado.app.help.webView.WebJsExtensions.Companion.basicJs
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.WebViewRequestLifecycle
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.utils.invisible
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.setLayout
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import kotlinx.coroutines.launch
import androidx.core.view.size
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.imageHttpClient
import io.legado.app.help.http.readingHttpClient as okHttpClient
import io.legado.app.help.http.text
import io.legado.app.lib.theme.themeColorOrNull
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_URL
import io.legado.app.help.webView.WebJsExtensions.Companion.nameUrl
import io.legado.app.help.webView.WebViewPool.BLANK_HTML
import io.legado.app.help.webView.WebViewPool.DATA_HTML
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.Download
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.get
import io.legado.app.utils.writeBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.URLDecoder
import java.util.Date
import androidx.core.graphics.createBitmap
import io.legado.app.data.entities.Book
import io.legado.app.help.review.REVIEW_ERROR_BRIDGE_NAME
import io.legado.app.help.review.ReviewErrorKind
import io.legado.app.help.review.ReviewErrorPageContent
import io.legado.app.help.review.ReviewParaContentInjector
import io.legado.app.help.review.ReviewSnapshotImages
import io.legado.app.help.review.ReviewSnapshotManager
import io.legado.app.help.review.ReviewSnapshotResourceStore
import io.legado.app.help.review.ReviewSnapshotStore
import io.legado.app.help.review.ReviewSupplementInjector
import io.legado.app.help.review.SyntheticParaContent
import io.legado.app.help.review.classifyReviewError
import io.legado.app.help.review.injectSnapshotImageDeferralStyle
import io.legado.app.help.review.reviewErrorPageHtml
import io.legado.app.help.review.reviewPageIdentity
import io.legado.app.help.review.reviewoutbox.ReviewOutboxBridge
import io.legado.app.help.review.reviewoutbox.ReviewOutboxContext
import io.legado.app.help.review.reviewoutbox.ReviewOutboxStore
import io.legado.app.help.review.reviewoutbox.ReviewOutboxWireUp
import io.legado.app.help.webView.WebViewHtmlStore
import io.legado.app.utils.configureOfflineResourceLoading
import kotlinx.coroutines.withTimeout

class BottomWebViewDialog() : BottomSheetDialogFragment(R.layout.dialog_web_view), WebJsExtensions.Callback {

    private companion object {
        const val PARAGRAPH_RULE_SOURCE_PREFIX = "paragraph_rule_"
        const val ARG_HTML_FILE = "htmlFile"
        const val ARG_FALLBACK_HTML_FILE = "fallbackHtmlFile"
        const val ARG_IS_SNAPSHOT_HTML = "isSnapshotHtml"
        const val ARG_REVIEW_RESOURCE_BOOK = "reviewResourceBook"
        const val ARG_SNAPSHOT_CHAPTER_URL = "snapshotChapterUrl"
        const val ARG_SNAPSHOT_BUTTON_SRC = "snapshotButtonSrc"

        /** 快照补充注入的延后时长：让首帧先绘制、用户先能滚动，再补 tab/楼中楼。 */
        const val SNAPSHOT_DEFERRED_INJECT_DELAY_MS = 120L

        /** 骨架屏进入慢加载态的时长：超过就不再只是灰条，补一行「仍在加载」提示。 */
        const val LOADING_SLOW_HINT_DELAY_MS = 4_000L

        /**
         * 加载看门狗：无快照兜底时，网络挂起会让页面永远等不到首帧，
         * 超过此时长直接渲染失败页（可重试）。取值与一般网络超时上限对齐，
         * 慢但在动的加载宁可让它跑完，也不中途打断。
         */
        const val LOAD_WATCHDOG_TIMEOUT_MS = 45_000L
    }

    /**
     * “快照优先”模式的后台网络刷新器：返回最新在线评论页 (url, html)，
     * 加载成功后覆盖当前快照；失败/超时返回 null 则继续停留快照。
     */
    private var networkRefresher: (suspend () -> Pair<String, String>?)? = null

    /**
     * “网络优先”模式的兜底快照：网络加载失败/超时时切换为本地快照。
     */
    private var fallbackHtml: String? = null
    private var htmlFileReference: String? = null
    private var fallbackHtmlFileReference: String? = null

    /**
     * 快照 HTML 内存直传（快照点击链路用）：首次渲染不走临时文件。
     * 进程重建后为 null，此时按快照身份从本地重新读取。
     */
    private var htmlDirect: String? = null

    /** 快照身份（进程重建用）：与 [reviewResourceBook] 一起定位本地快照文件 */
    private var snapshotChapterUrl: String? = null
    private var snapshotButtonSrc: String? = null
    private var fallbackApplied = false
    private var fallbackTimeoutRunnable: Runnable? = null

    /** 兜底快照提示只弹一次：超时与 onReceivedError 可能先后触发同一件事 */
    private var fallbackNoticeShown = false

    /**
     * 当前 WebView 显示的是「首屏加载失败页」（见 [showInitialLoadError]）。
     *
     * 失败页也是 loadDataWithBaseURL 进来的普通页面，会照常走 onPageFinished →
     * [handlePageReady]。这里必须显式区分身份，否则会在失败页上注入段落回填、
     * 离线接管、快照补充 section 等一堆对失败页毫无意义甚至有害的脚本。
     */
    @Volatile
    private var displayingErrorPage = false

    /**
     * 主框架资源不可用的错误描述，等 onPageFinished 再渲染失败页。
     *
     * 为什么不在 onReceivedError 里立刻渲染：错误回调之后 WebView 还会把
     * 自己的默认错误页推进来，直接渲染会被盖掉；而且部分可恢复错误
     * （如 ERR_ABORTED）之后页面仍可能正常绘制，见 onPageCommitVisible 的清空逻辑。
     */
    private var pendingMainFrameError: String? = null

    /** 骨架屏兜底：迟迟无首帧时进入慢加载态（灰条 + 「仍在加载」小字）。 */
    private val loadingSlowHintRunnable = Runnable {
        currentBinding?.reviewLoadingSkeleton?.enterSlowMode(
            getString(R.string.review_loading_slow)
        )
    }

    /**
     * 加载看门狗：无快照兜底且首帧一直没来时，与其让用户对着空白/骨架
     * 无限等，不如给出失败页和重试入口。取消时机 = 任何一次页面就绪
     * （handlePageReady，预渲染接管路径也会走到）或内容已画出首帧。
     */
    private val loadWatchdogRunnable = Runnable {
        val lease = requestLifecycle ?: return@Runnable
        if (displayingSnapshotHtml || displayingErrorPage) return@Runnable
        if (!ownsLease(lease)) return@Runnable
        showInitialLoadError(
            NoStackTraceException("review page not ready in ${LOAD_WATCHDOG_TIMEOUT_MS}ms"),
            "about:blank",
            lease
        )
    }

    /**
     * 离线模式（仅使用快照/快照兜底已启用）：WebView 禁止一切 http/https 网络请求，
     * 残余外部资源一律拦截，只允许 data: 与 review-resource:// 本地资源离线渲染。
     */
    private var offlineMode = false

    /** Set only for review snapshots that may contain review-resource:// references. */
    private var reviewResourceBook: Book? = null

    /**
     * 当前 WebView 显示的是否为评论快照内容。
     *
     * 身份定义（越权禁令）：
     * - 快照内容 = 唯一来源 [io.legado.app.help.review.ReviewSnapshotStore]，
     *   抓取端已剥离 script、冻结 DOM、资源 review-resource 化，身份为离线；
     *   仅它允许离线接管（资源拦截、章评/书评补充注入、楼中楼收展）。
     * - 在线内容 = showBrowser/网络带回的活页 HTML（脚本存活、评论靠 AJAX），
     *   身份为在线；禁止一切离线接管。
     * 该标记只能由构造时的内容来源决定（[isSnapshotHtml]），以及快照兜底/
     * 在线覆盖两处明确的状态切换修改；禁止再用“html 是否非空”推断身份。
     */
    @Volatile
    private var displayingSnapshotHtml = false

    /** 离线评论入队上下文：非空时页面加载完成后注入离线评论接管脚本 */
    private var outboxContext: ReviewOutboxContext? = null

    /** 合成段评入口（无泡段落）的目标段落：页面加载完成后注入段落原文回填脚本 */
    private var syntheticParaContent: SyntheticParaContent? = null

    private val mHandler = android.os.Handler(android.os.Looper.getMainLooper())

    constructor(
        sourceKey: String,
        bookType: Int,
        url: String,
        html: String? = null,
        preloadJs: String? = null,
        config: String? = null,
        webViewSession: CommentWebViewSession? = null,
        onDismiss: (() -> Unit)? = null,
        networkRefresher: (suspend () -> Pair<String, String>?)? = null,
        fallbackHtml: String? = null,
        offlineOnly: Boolean = false,
        reviewResourceBook: Book? = null,
        outboxContext: ReviewOutboxContext? = null,
        syntheticParaContent: SyntheticParaContent? = null,
        isSnapshotHtml: Boolean = false,
        htmlFileReference: String? = null,
        htmlDirect: String? = null,
        snapshotChapterUrl: String? = null,
        snapshotButtonSrc: String? = null,
    ) : this() {
        this.webViewSession = webViewSession
        this.onDismissAction = onDismiss
        this.networkRefresher = networkRefresher
        this.fallbackHtml = fallbackHtml
        // 调用方（快照点击链路，已在 IO 协程）可预先写盘传入引用，
        // 避免主线程构造对话框时同步写大 HTML；未传则按原逻辑在此写盘。
        this.htmlFileReference = htmlFileReference ?: html?.let(WebViewHtmlStore::write)
        fallbackHtmlFileReference = fallbackHtml?.let(WebViewHtmlStore::write)
        // 快照 HTML 内存直传：不过 Bundle、不写临时文件，主线程构造即可渲染。
        // 进程重建时 htmlDirect 丢失，靠 snapshotChapterUrl/snapshotButtonSrc
        // + reviewResourceBook 从 arguments 恢复身份后重新读本地快照。
        this.htmlDirect = htmlDirect
        this.snapshotChapterUrl = snapshotChapterUrl
        this.snapshotButtonSrc = snapshotButtonSrc
        offlineMode = offlineOnly
        this.reviewResourceBook = reviewResourceBook
        this.outboxContext = outboxContext
        this.syntheticParaContent = syntheticParaContent
        arguments = Bundle().apply {
            putString("sourceKey", sourceKey)
            putInt("bookType", bookType)
            putString("url", url)
            // Large HTML (especially snapshots with inline images) must not enter
            // Fragment arguments: Android serializes arguments into the state Bundle.
            putString(ARG_HTML_FILE, htmlFileReference)
            putString(ARG_FALLBACK_HTML_FILE, fallbackHtmlFileReference)
            putString("preloadJs", preloadJs)
            putString("config", config)
            putBoolean("useCommentWebViewSession", webViewSession != null)
            putBoolean(ARG_IS_SNAPSHOT_HTML, isSnapshotHtml)
            putParcelable(ARG_REVIEW_RESOURCE_BOOK, reviewResourceBook)
            // 快照身份：进程重建时按主键重读本地快照（替代把大 HTML 塞进 Bundle）
            putString(ARG_SNAPSHOT_CHAPTER_URL, snapshotChapterUrl)
            putString(ARG_SNAPSHOT_BUTTON_SRC, snapshotButtonSrc)
            outboxContext?.putTo(this)
            syntheticParaContent?.putTo(this)
        }
    }

    private var currentBinding: DialogWebViewBinding? = null
    private val binding: DialogWebViewBinding
        get() = checkNotNull(currentBinding) { "Comment dialog view is not available" }
    private var cachedBottomSheet: View? = null
    private var cachedBehavior: BottomSheetBehavior<View>? = null
    private val bottomSheet: View?
        get() = cachedBottomSheet ?: dialog
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.also { cachedBottomSheet = it }
    private val behavior: BottomSheetBehavior<View>?
        get() = cachedBehavior ?: bottomSheet
            ?.let { sheet -> BottomSheetBehavior.from(sheet) }
            ?.also { cachedBehavior = it }
    private val displayMetrics by lazy { resources.displayMetrics }
    private var pendingImageSave: Pair<WebViewRequestLifecycle, WebViewRequestLifecycle.Page>? = null
    private val selectImageDir = registerForActivityResult(HandleFileContract()) {
        val pending = pendingImageSave ?: return@registerForActivityResult
        pendingImageSave = null
        if (!ownsPage(pending.first, pending.second)) return@registerForActivityResult
        it.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            saveImage(it.value, uri)
        }
    }
    private lateinit var pooledWebView: PooledWebView
    private lateinit var currentWebView: WebView
    @Volatile
    private var requestLifecycle: WebViewRequestLifecycle? = null
    private var ownsPooledWebView = false
    private var webViewSession: CommentWebViewSession? = null
    private var onDismissAction: (() -> Unit)? = null
    private var dismissActionNotified = false
    private var source: BaseSource? = null
    private var preloadJs: String? = null
    private var isFullScreen = false
    private var customWebViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originOrientation: Int? = null
    private var needClearHistory = true
    private var pendingConfig: Config? = null
    private var pendingConfigFirst = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (webViewSession == null && arguments?.getBoolean("useCommentWebViewSession") == true) {
            webViewSession = CommentWebViewSession.shared
        }
    }

    private fun ownsLease(lease: WebViewRequestLifecycle): Boolean =
        requestLifecycle === lease && lease.isActive && ownsPooledWebView &&
            pooledWebView.isInUse && !pooledWebView.isDestroyed

    private fun ownsPage(lease: WebViewRequestLifecycle, page: WebViewRequestLifecycle.Page): Boolean =
        ownsLease(lease) && page.isCurrent

    private fun closeViewRequests() {
        requestLifecycle?.close()
        requestLifecycle = null
    }

    @Suppress("DEPRECATION")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.let { window ->
            window.decorView.systemUiVisibility = activity?.window?.decorView?.systemUiVisibility ?: 0
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val lease = requestLifecycle ?: return
        // Dispatch like View.post, but cancel the work with this view's lease.
        lease.scope.launch(Dispatchers.Main) {
            if (ownsLease(lease)) applyPendingConfig()
        }
    }

    override fun show(manager: FragmentManager, tag: String?) {
        kotlin.runCatching {
            manager.beginTransaction().remove(this).commit()
            super.show(manager, tag)
        }.onFailure {
            AppLog.put("显示对话框失败 tag:$tag", it)
            notifyDismissAction()
        }
    }

    private fun submitConfig(config: Config, first: Boolean = false) {
        pendingConfig = config
        pendingConfigFirst = pendingConfigFirst || first
        applyPendingConfig()
    }

    private fun applyPendingConfig() {
        val config = pendingConfig ?: return
        if (bottomSheet == null) return
        setConfig(config, pendingConfigFirst)
        pendingConfigFirst = false
    }

    private fun setConfig(config: Config, first: Boolean = false) {
        val lease = requestLifecycle?.takeIf(::ownsLease) ?: return
        if (!isAdded || context == null) {
            return
        }
        behavior?.let { behavior ->
            config.state?.let { behavior.state = it }
            config.peekHeight?.let { behavior.peekHeight = it }
            config.isHideable?.let { behavior.isHideable = it }
            config.skipCollapsed?.let { behavior.skipCollapsed = it }
            config.setHalfExpandedRatio?.let { behavior.setHalfExpandedRatio(it) }
            config.setExpandedOffset?.let { behavior.setExpandedOffset(it) }
            config.setFitToContents?.let { behavior.setFitToContents(it) }
            config.isDraggable?.let { behavior.isDraggable = it }
            config.isDraggableOnNestedScroll?.let { behavior.isDraggableOnNestedScroll = it }
            config.significantVelocityThreshold?.let { behavior.significantVelocityThreshold = it }
            config.hideFriction?.let { behavior.hideFriction = it }
            config.maxWidth?.let { behavior.maxWidth = it }
            config.maxHeight?.let { behavior.maxHeight = it }
            config.isGestureInsetBottomIgnored?.let { behavior.isGestureInsetBottomIgnored = it }
            config.setUpdateImportantForAccessibilityOnSiblings?.let {
                behavior.setUpdateImportantForAccessibilityOnSiblings(it)
            }
        }

        config.expandedCornersRadius?.let {
            val radius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, it, displayMetrics
            )
            applySheetCorners(radius)
        }

        dialog?.let { dialog ->
            config.backgroundDimAmount?.let { amount ->
                dialog.window?.setDimAmount(amount)
            }
            config.shouldDimBackground?.let { shouldDim ->
                if (!shouldDim) {
                    dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                }
            }
            config.dismissOnTouchOutside?.let { touchOutside ->
                isCancelable = touchOutside
            }
            config.hardwareAccelerated?.let { hwAccel ->
                if (hwAccel) {
                    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                }
            }
        }

        currentWebView.let { webView ->
            config.webViewInitialScale?.let { scale ->
                webView.settings.apply {
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    textZoom = scale
                }
            }
            config.webViewCacheMode?.let { cacheMode ->
                webView.settings.cacheMode = cacheMode
            }
            config.isNestedScrollingEnabled?.let { enabled ->
                webView.isNestedScrollingEnabled = enabled
            }
        }

        bottomSheet?.let { sheet ->
            val params = sheet.layoutParams
            var hasChanged = false
            config.widthPercentage?.let { percentage ->
                if (percentage in 0.0..1.0) {
                    val width = (displayMetrics.widthPixels * percentage).toInt()
                    params.width = width
                    hasChanged = true
                }
            }

            val dialogHeight = config.dialogHeight ?: if (first) -1 else null
            dialogHeight?.let { height ->
                params.height = height
                hasChanged = true
            }
            config.heightPercentage?.let { percentage ->
                if (percentage in 0.0..1.0) {
                    val height = (displayMetrics.heightPixels * percentage).toInt()
                    params.height = height
                    // 同时更新peekHeight和最大高度
                    if (config.peekHeight == null) {
                        behavior?.peekHeight = height
                    }
                    if (config.maxHeight == null) {
                        behavior?.maxHeight = height
                    }
                    hasChanged = true
                }
            }
            if (hasChanged) {
                sheet.layoutParams = params
            }
        }

        config.responsiveBreakpoint?.let { breakpoint ->
            val screenWidth = displayMetrics.widthPixels
            if (screenWidth < breakpoint) {
                // 移动端布局（小屏幕）设置
                behavior?.peekHeight = config.peekHeight ?: 300
                config.widthPercentage?.let { percentage ->
                    if (percentage > 0.8f) {
                        // 小屏幕上最大宽度限制
                        val maxWidth = (screenWidth * 0.9).toInt()
                        behavior?.maxWidth = maxWidth
                    }
                }
            } else {
                // 平板/大屏幕布局设置
                behavior?.peekHeight = config.peekHeight ?: 400
                config.widthPercentage?.let { percentage ->
                    if (percentage < 0.6f) {
                        // 大屏幕上居中显示
                        bottomSheet?.layoutParams?.width =
                            (screenWidth * percentage).toInt()
                        (bottomSheet?.layoutParams as? FrameLayout.LayoutParams)?.gravity =
                            Gravity.CENTER_HORIZONTAL
                    }
                }
            }
        }

        val scrollNoDraggable = config.scrollNoDraggable ?: if (first) true else null
        scrollNoDraggable?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (it) {
                    currentWebView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                        if (ownsLease(lease)) behavior?.isDraggable = scrollY == 0
                    }
                } else {
                    currentWebView.setOnScrollChangeListener(null)
                }
            }
        }

        val longClickSaveImg = config.longClickSaveImg ?: if (first) true else null
        longClickSaveImg?.let {
            if (it) {
                setLongClickSaveImg()
            } else {
                currentWebView.setOnLongClickListener(null)
            }
        }
    }

    private fun applySheetCorners(radius: Float) {
        val hasRadius = radius > 0f
        val surfaceColor = requireContext().themeColorOrNull(PreferKey.themeCardColor)
            ?: ContextCompat.getColor(requireContext(), R.color.dialog_surface)
        bottomSheet?.let { sheet ->
            sheet.backgroundTintList = null
            sheet.background = if (hasRadius) topRoundDrawable(Color.TRANSPARENT, radius) else null
            sheet.clipToOutline = hasRadius
            setTopRoundOutline(sheet, radius)
        }
        binding.nativeSheetSurface.background = if (hasRadius) {
            topRoundDrawable(surfaceColor, radius)
        } else {
            topRoundDrawable(surfaceColor, 0f)
        }
        listOf(binding.nativeSheetSurface, binding.webViewContainer, binding.customWebView).forEach { view ->
            view.clipToPadding = false
            view.clipToOutline = hasRadius
            setTopRoundOutline(view, radius)
        }
        currentWebView.setBackgroundColor(Color.TRANSPARENT)
        currentWebView.clipToOutline = false
        currentWebView.outlineProvider = null
        currentWebView.setLayerType(View.LAYER_TYPE_NONE, null)
        logConfigApply(radius)
    }

    private fun resetWebViewPresentationState() {
        currentWebView.run {
            setLayerType(View.LAYER_TYPE_NONE, null)
            outlineProvider = null
            clipToOutline = false
            setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun topRoundDrawable(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadii = floatArrayOf(
                radius, radius,
                radius, radius,
                0f, 0f,
                0f, 0f
            )
        }
    }

    private fun setTopRoundOutline(view: View, radius: Float) {
        if (radius <= 0f) {
            view.outlineProvider = null
            return
        }
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height + radius.toInt(), radius)
            }
        }
    }

    private fun logConfigApply(radius: Float) {
        AppLog.putDebug(
            "BottomWebViewDialog configApply " +
                "radius=$radius " +
                "sdk=${Build.VERSION.SDK_INT} " +
                "manufacturer=${Build.MANUFACTURER} " +
                "brand=${Build.BRAND} " +
                "sheetAttached=${bottomSheet != null} " +
                "webAttached=${currentWebView.parent != null} " +
                "webLayer=${currentWebView.layerType}"
        )
    }

    private fun setLongClickSaveImg() {
        val lease = requestLifecycle?.takeIf(::ownsLease) ?: return
        currentWebView.setOnLongClickListener {
            val page = lease.currentPage() ?: return@setOnLongClickListener false
            if (!ownsPage(lease, page)) return@setOnLongClickListener false
            val hitTestResult = currentWebView.hitTestResult
            if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                hitTestResult.extra?.let { webPic ->
                    requireContext().selector(
                        arrayListOf(
                            SelectItem(getString(R.string.action_save), "save"),
                            SelectItem(getString(R.string.select_folder), "selectFolder")
                        )
                    ) { _, charSequence, _ ->
                        if (ownsPage(lease, page)) {
                            when (charSequence.value) {
                                "save" -> saveImage(webPic)
                                "selectFolder" -> selectSaveFolder(null)
                            }
                        }
                    }
                    return@setOnLongClickListener true
                }
            }
            return@setOnLongClickListener false
        }
        currentWebView.setDownloadListener { url, _, contentDisposition, _, _ ->
            val page = lease.currentPage() ?: return@setDownloadListener
            if (!ownsPage(lease, page)) return@setDownloadListener
            var fileName = URLUtil.guessFileName(url, contentDisposition, null)
            fileName = URLDecoder.decode(fileName, "UTF-8")
            currentWebView.longSnackbar(fileName, getString(R.string.action_download)) {
                if (ownsPage(lease, page)) Download.start(requireContext(), url, fileName)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentBinding = DialogWebViewBinding.bind(view)
        // A Fragment can recreate its view without another onAttach. Each view
        // must acquire its own lease, and invalidate it before returning to a pool.
        pooledWebView = webViewSession?.acquire(requireContext()) ?: WebViewPool.acquire(requireContext())
        currentWebView = pooledWebView.realWebView
        ownsPooledWebView = true
        val lease = WebViewRequestLifecycle(viewLifecycleOwner.lifecycleScope)
        requestLifecycle = lease
        source = null
        preloadJs = null
        needClearHistory = true
        view.setBackgroundColor(0)
        binding.webViewContainer.addView(currentWebView)
        // 首帧出来之前的这段窗口里 WebView 是透明的，用户看到的是空壳。
        // 先铺骨架给出「正在加载」的反馈，内容可见后由 onPageCommitVisible 收起。
        showLoadingSkeleton()
        val args = arguments?.let(::Bundle)
        val initialPage = lease.currentPage() ?: return
        launchInitialLoad(lease, args, initialPage)
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                return@setOnKeyListener handleBackPressed()
            }
            false
        }
        registerBackPressedCallbacks()
    }

    /**
     * 首屏加载（含失败重试，见 [ReviewErrorBridge.retry]）。
     *
     * 抽成独立函数是为了让失败页的「重试」能原样再跑一遍同一条链路，
     * 而不是复制一份加载逻辑——两份代码一旦漂移，重试就会走到不同行为。
     */
    private fun launchInitialLoad(
        lease: WebViewRequestLifecycle,
        args: Bundle?,
        page: WebViewRequestLifecycle.Page
    ) {
        displayingErrorPage = false
        // 重试前摘掉上一次失败页的桥：同一实例上不应残留只服务旧失败页的接口
        currentWebView.removeJavascriptInterface(REVIEW_ERROR_BRIDGE_NAME)
        page.scope.launch(Dispatchers.Main) {
            if (args == null) {
                dismiss()
                return@launch
            }
            val sourceKey = args.getString("sourceKey") ?: return@launch
            val url = args.getString("url") ?: return@launch
            // 评论快照/离线评论上下文：配置变更重建时构造函数不会重新执行，
            // 必须从 arguments 恢复，不能依赖构造时赋值的字段。
            @Suppress("DEPRECATION")
            reviewResourceBook = args.getParcelable(ARG_REVIEW_RESOURCE_BOOK)
            outboxContext = ReviewOutboxContext.fromBundle(args)
            syntheticParaContent = SyntheticParaContent.fromBundle(args)
            // 快照身份从 arguments 恢复：进程重建后 htmlDirect 丢失，靠它重新读本地快照
            snapshotChapterUrl = args.getString(ARG_SNAPSHOT_CHAPTER_URL) ?: snapshotChapterUrl
            snapshotButtonSrc = args.getString(ARG_SNAPSHOT_BUTTON_SRC) ?: snapshotButtonSrc
            try {
                args.getString("config")?.let { json ->
                    try {
                        GSON.fromJsonObject<Config>(json).getOrThrow().let { config ->
                            submitConfig(config, true)
                        }
                        true
                    } catch (e: Exception) {
                        AppLog.put("config err", e)
                        null
                    }
                } ?: run {
                    bottomSheet?.let { sheet ->
                        val layoutParams = sheet.layoutParams
                        layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                        sheet.layoutParams = layoutParams
                    }
                    setLongClickSaveImg()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        currentWebView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                            if (ownsLease(lease)) behavior?.isDraggable = scrollY == 0
                        }
                    }
                }
                // HTML 三源优先级：内存直传 > 临时文件引用 > 快照身份重读（进程重建）。
                // 文件读取一律下沉 IO：主线程读百 KB~MB 级快照会卡弹窗出现动画。
                val htmlArgument = htmlDirect ?: args.getString(ARG_HTML_FILE)?.let { reference ->
                    htmlFileReference = reference
                    withContext(IO) {
                        WebViewHtmlStore.read(reference)
                            ?: throw NoStackTraceException("WebView HTML file is missing: $reference")
                    }
                } ?: run {
                    val identityBook = reviewResourceBook
                    val identityChapterUrl = snapshotChapterUrl
                    val identityButtonSrc = snapshotButtonSrc
                    if (identityBook != null && !identityChapterUrl.isNullOrBlank() &&
                        !identityButtonSrc.isNullOrBlank()
                    ) {
                        // 进程重建：按身份重读本地快照（内存缓存优先，文件兜底）
                        withContext(IO) {
                            ReviewSnapshotStore.getByKey(
                                identityBook, identityChapterUrl, identityButtonSrc
                            )
                        }?.also { displayingSnapshotHtml = true }
                            ?.html?.takeIf { it.isNotBlank() }
                            ?: throw NoStackTraceException("review snapshot is missing after recreate")
                    } else {
                        null
                    }
                }
                if (htmlArgument != null) {
                    // 身份只认构造时传入的来源标记，不再用 html 是否非空推断：
                    // showBrowser 活页 html 同样非空，但身份是在线，禁止离线接管。
                    displayingSnapshotHtml =
                        args.getBoolean(ARG_IS_SNAPSHOT_HTML, false) || displayingSnapshotHtml
                }
                val fallbackReference = args.getString(ARG_FALLBACK_HTML_FILE)
                if (fallbackReference != null) {
                    fallbackHtmlFileReference = fallbackReference
                    fallbackHtml = withContext(IO) {
                        WebViewHtmlStore.read(fallbackReference)
                            ?: throw NoStackTraceException("WebView fallback HTML file is missing: $fallbackReference")
                    }
                }
                val loadedSource = withContext(IO) {
                    if (sourceKey.startsWith(PARAGRAPH_RULE_SOURCE_PREFIX)) {
                        val ruleId = sourceKey.removePrefix(PARAGRAPH_RULE_SOURCE_PREFIX).toLongOrNull()
                        ruleId?.let { appDb.paragraphRuleDao.get(it) }?.let(::ParagraphRuleJsExtensions)
                    } else {
                        appDb.bookSourceDao.getBookSource(sourceKey)
                    }
                }
                if (!ownsPage(lease, page)) return@launch
                if (loadedSource == null && htmlArgument == null) {
                    // 评论快照等本地 HTML 可在无书源时离线渲染，不再强制要求书源存在
                    activity?.toastOnUi(getString(R.string.review_error_missing_args))
                    dismiss()
                    return@launch
                }
                source = loadedSource
                val requestedPreloadJs = args.getString("preloadJs")
                preloadJs = requestedPreloadJs
                val argHtml = htmlArgument
                val bookType = args.getInt("bookType", 0)
                val document = withContext(IO) {
                    val analyzeUrl = AnalyzeUrl(url, source = loadedSource, coroutineContext = coroutineContext)
                    val loadDirectly = shouldLoadUrlDirectly(url, argHtml, requestedPreloadJs)
                    // 网络优先兜底：WebView 启动前的网络获取若直接失败/超时，
                    // 也必须切换到快照显示，而不是显示异常文本
                    val fetchedHtml = if (loadDirectly) {
                        null
                    } else if (argHtml != null) {
                        argHtml
                    } else {
                        try {
                            if (fallbackHtml.isNullOrBlank()) {
                                analyzeUrl.getStrResponseAwait().body
                            } else {
                                withTimeout(ReviewSnapshotManager.NETWORK_FALLBACK_LOAD_TIMEOUT_MS) {
                                    analyzeUrl.getStrResponseAwait().body
                                }
                            }
                        } catch (error: Throwable) {
                            // 内层 withTimeout 的超时（TCE 是 CancellationException 子类）同样是
                            // 「网络获取失败」，应走快照兜底；只有真正的取消（离开页面）才向上抛。
                            if (error is CancellationException &&
                                error !is kotlinx.coroutines.TimeoutCancellationException
                            ) {
                                throw error
                            }
                            fallbackHtml?.takeIf { it.isNotBlank() }?.also {
                                // 已用快照兜底：进入离线模式，不再允许任何网络请求；
                                // 内容即快照，身份同步置为快照（同一权责的另一面）。
                                offlineMode = true
                                displayingSnapshotHtml = true
                                // 内容来源已从在线变为缓存，告知用户（只提示一次）
                                notifyFallbackShown()
                            } ?: throw error
                        }
                    }
                    val spliceHtml = if (loadDirectly) {
                        null
                    } else {
                        val html = fetchedHtml
                        if (html.isNullOrEmpty()) throw NoStackTraceException("html is NullOrEmpty")
                        val spliced = if (requestedPreloadJs.isNullOrEmpty()) {
                            html
                        } else {
                            val headIndex = html.indexOf("<head", ignoreCase = true)
                            val headEnd = if (headIndex >= 0) html.indexOf('>', headIndex) else -1
                            if (headEnd >= 0) StringBuilder(html).insert(headEnd + 1, JS_URL).toString()
                            else JS_URL + html
                        }
                        // 快照页图片让位：视口外图片不参与首屏布局与解码，正文先出来
                        if (displayingSnapshotHtml) injectSnapshotImageDeferralStyle(spliced) else spliced
                    }
                    Triple(analyzeUrl.url, analyzeUrl.headerMap, spliceHtml)
                }
                if (!ownsPage(lease, page)) return@launch
                val html = document.third
                if (html == null) {
                    initWebViewForUrl(document.first, document.second, bookType, lease)
                } else {
                    val session = webViewSession
                    // 预热阶段可能已经把这一页渲染好了：内容指纹一致就直接接管，
                    // 省掉 loadDataWithBaseURL 的整段「跨进程传 HTML + 解析 + 布局 + 首帧」
                    val prerenderIdentity = if (displayingSnapshotHtml && session != null) {
                        reviewPageIdentity(html, AppConfig.isNightTheme, currentWebView.settings.textZoom)
                    } else {
                        null
                    }
                    if (prerenderIdentity != null && session != null &&
                        session.isPagePrerendered(pooledWebView, prerenderIdentity)
                    ) {
                        adoptPrerenderedPage(document.first, document.second, bookType, lease)
                    } else {
                        initWebView(document.first, html, document.second, bookType, lease)
                    }
                }
                currentWebView.clearHistory()
                // “快照优先”：快照先显示，后台刷新真实网络评论页成功后覆盖。
                // 后台抓取必须跑在 IO，耗时网络/WebView 解析不能卡主线程；
                // 刷新成功后切回主线程更新 WebView
                val refresher = networkRefresher
                if (refresher != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val refreshed = runCatching { refresher() }.getOrNull()
                        if (refreshed != null && isAdded && !isHidden) {
                            withContext(Dispatchers.Main) {
                                if (ownsLease(lease)) {
                                    // 在线覆盖页加载后不再是快照内容，撤回注入标记
                                    displayingSnapshotHtml = false
                                    currentWebView.loadDataWithBaseURL(
                                        refreshed.first.ifBlank { document.first },
                                        refreshed.second,
                                        "text/html",
                                        "utf-8",
                                        refreshed.first.ifBlank { document.first }
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!ownsPage(lease, page)) return@launch
                showInitialLoadError(error, url, lease)
            }
        }
    }

    /**
     * 首屏加载失败的兜底页。
     *
     * 旧实现把 `error.stackTraceToString()` 整个丢进 WebView：用户看到一屏异常堆栈，
     * 既读不懂发生了什么，也没有任何自救入口——唯一出路是关掉弹窗重新点一次图标。
     * 这里换成「一句人话 + 重试/关闭（+ 可折叠的技术细节）」，并把重试接回同一条
     * 加载链路（见 [launchInitialLoad]）。
     *
     * 重试有没有意义由 [classifyReviewError] 决定：快照缺失是本地没有数据，
     * 重试必然再失败，所以失败页只留「关闭」（见 [ReviewErrorPageContent.retryLabel]）。
     */
    private fun showInitialLoadError(
        error: Throwable,
        baseUrl: String,
        lease: WebViewRequestLifecycle
    ) {
        // 失败页一出现就不能再让骨架屏盖着，也不能留兜底超时/看门狗在后台偷偷换页
        cancelFallbackTimeout()
        cancelLoadingTimers()
        hideLoadingSkeleton()
        displayingErrorPage = true
        currentWebView.webViewClient = CustomWebViewClient(lease)
        // 失败页的按钮通过 JS 桥回到原生：重试 / 关闭
        currentWebView.addJavascriptInterface(
            ReviewErrorBridge(this, lease),
            REVIEW_ERROR_BRIDGE_NAME
        )
        val content = reviewErrorPageContent(error)
        currentWebView.loadDataWithBaseURL(
            baseUrl,
            reviewErrorPageHtml(content),
            "text/html",
            "utf-8",
            baseUrl
        )
        currentWebView.clearHistory()
        AppLog.put("段评首屏加载失败：${content.title} / ${error.javaClass.simpleName}")
    }

    /** 把异常分类翻译成失败页文案；细节保留一句 message，够定位又不糊一屏堆栈。 */
    private fun reviewErrorPageContent(error: Throwable): ReviewErrorPageContent {
        val kind = classifyReviewError(error)
        val titleRes: Int
        val messageRes: Int
        when (kind) {
            ReviewErrorKind.NETWORK -> {
                titleRes = R.string.review_error_title_network
                messageRes = R.string.review_error_message_network
            }

            ReviewErrorKind.TIMEOUT -> {
                titleRes = R.string.review_error_title_timeout
                messageRes = R.string.review_error_message_timeout
            }

            ReviewErrorKind.RULE -> {
                titleRes = R.string.review_error_title_rule
                messageRes = R.string.review_error_message_rule
            }

            ReviewErrorKind.EMPTY_CONTENT -> {
                titleRes = R.string.review_error_title_empty
                messageRes = R.string.review_error_message_empty
            }

            ReviewErrorKind.SNAPSHOT_MISSING -> {
                titleRes = R.string.review_error_title_snapshot
                messageRes = R.string.review_error_message_snapshot
            }

            ReviewErrorKind.UNKNOWN -> {
                titleRes = R.string.review_error_title_unknown
                messageRes = R.string.review_error_message_unknown
            }
        }
        return ReviewErrorPageContent(
            title = getString(titleRes),
            message = getString(messageRes),
            retryLabel = if (kind == ReviewErrorKind.SNAPSHOT_MISSING) null else getString(R.string.retry),
            closeLabel = getString(R.string.close),
            detailLabel = getString(R.string.review_error_detail),
            detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
            night = AppConfig.isNightTheme
        )
    }

    /** 重试：把首屏加载原样再跑一遍（失败页由 [launchInitialLoad] 自己撤掉）。 */
    private fun retryInitialLoad() {
        // 只在失败页显示中受理：快速连点第二次时首屏加载已经启动
        // （displayingErrorPage 已被 launchInitialLoad 复位），直接忽略防并发双载
        if (!displayingErrorPage) return
        val lease = requestLifecycle ?: return
        // 失败页本身也是一次页面加载，lease 的 currentPage 已经换代，
        // 不能用首屏那个 page 续用（它已被判定过期）。
        val page = lease.currentPage() ?: return
        if (!ownsPage(lease, page)) return
        val args = arguments?.let(::Bundle) ?: return
        showLoadingSkeleton()
        launchInitialLoad(lease, args, page)
    }

    private fun registerBackPressedCallbacks() {
        (dialog as? ComponentDialog)?.onBackPressedDispatcher?.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackPressed()
                }
            }
        )
        activity?.onBackPressedDispatcher?.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackPressed()
                }
            }
        )
    }

    private fun handleBackPressed(): Boolean {
        if (requestLifecycle?.let(::ownsLease) != true) return true
        if (binding.customWebView.size > 0) { //网页全屏
            customWebViewCallback?.onCustomViewHidden()
            return true
        }
        if (currentWebView.canGoBack()) {
            val list = currentWebView.copyBackForwardList()
            val size = list.size
            if (size == 1) {
                dismiss()
                return true
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
                    dismiss()
                    return true
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
                dismiss()
                return true
            }
            currentWebView.goBackOrForward(-steps)
            return true
        }
        dismiss()
        return true
    }

    private fun shouldLoadUrlDirectly(rawUrl: String, html: String?, preloadJs: String?): Boolean {
        if (webViewSession == null || html != null || !preloadJs.isNullOrEmpty()) return false
        val url = rawUrl.trim()
        return URLUtil.isNetworkUrl(url) &&
            !url.contains(",{", ignoreCase = false) &&
            !url.contains("{{", ignoreCase = false) &&
            !url.contains("}}", ignoreCase = false) &&
            !url.contains("@js", ignoreCase = true) &&
            !url.contains("<js", ignoreCase = true)
    }

    private fun initWebViewForUrl(
        url: String,
        headerMap: HashMap<String, String>,
        bookType: Int,
        lease: WebViewRequestLifecycle
    ) {
        // 真实内容开始加载：看门狗此前若已弹出失败页，现在必须撤销其身份，
        // 否则内容回来后 handlePageReady 会把这次加载当失败页、跳过全部注入
        displayingErrorPage = false
        prepareWebView(headerMap, bookType, lease)
        currentWebView.loadUrl(url, webViewExtraHeaders(headerMap))
    }

    private fun initWebView(
        url: String,
        html: String,
        headerMap: HashMap<String, String>,
        bookType: Int,
        lease: WebViewRequestLifecycle
    ) {
        // 同上：真实内容覆盖失败页时要复位身份，恢复段落回填/离线接管/补充 section
        displayingErrorPage = false
        prepareWebView(headerMap, bookType, lease)
        currentWebView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
    }

    /**
     * 接管预热阶段已经渲染完成的快照页。
     *
     * 这里只做「换 client / 挂 JS 桥」，不重新加载内容：页面 DOM 与首帧绘制都是现成的，
     * 用户点开弹窗看到的就是成品，省掉 loadDataWithBaseURL 的整段链路。
     *
     * 注意：换 client 之后，那一次加载的 onPageFinished 不会再派发给新 client，
     * 因此必须手动补跑 [handlePageReady]，否则补充 section 与离线接管脚本会缺失。
     */
    private fun adoptPrerenderedPage(
        url: String,
        headerMap: HashMap<String, String>,
        bookType: Int,
        lease: WebViewRequestLifecycle
    ) {
        prepareWebView(headerMap, bookType, lease)
        currentWebView.resumeTimers()
        currentWebView.onResume()
        hideLoadingSkeleton()
        handlePageReady(currentWebView, url, lease)
    }

    /**
     * 页面加载结束后的全部后置动作。
     *
     * 抽成独立函数的原因：正常路径由 [CustomWebViewClient.onPageFinished] 触发，
     * 而「接管预渲染页」路径不会再触发一次 onPageFinished（页面早就加载完了，
     * 只是换了 client），必须手动补跑同一份逻辑——两条路径共用本函数才不会漂移。
     */
    private fun handlePageReady(
        view: WebView,
        url: String?,
        lease: WebViewRequestLifecycle
    ) {
        // 页面加载成功：取消超时定时任务与看门狗（预渲染接管路径没有
        // onPageCommitVisible，必须在这里兜底取消）
        cancelFallbackTimeout()
        cancelLoadingTimers()
        if (displayingErrorPage) {
            // 失败页不需要任何注入：段落回填/离线接管/快照补充 section 全都没有意义，
            // 注入反而可能往失败页里塞脚本、抢渲染线程。收起骨架即收工。
            hideLoadingSkeleton()
            return
        }
        val mainFrameError = pendingMainFrameError
        if (mainFrameError != null) {
            // 主框架资源压根没拿到：此时 WebView 里是系统默认错误页（英文错误码），
            // 换成带「重试/关闭」的失败页，用户至少有一条自救路径。
            pendingMainFrameError = null
            showInitialLoadError(IOException(mainFrameError), "about:blank", lease)
            return
        }
        // 合成段评入口（无泡段落）：注入段落原文回填脚本，评论弹窗经
        // ?api=1 拉取到空原文时回填目标段落真实文本，保证发评引用正确；
        // 注入幂等，发生在用户打开弹窗之前，时序安全
        syntheticParaContent?.let { entry ->
            view.evaluateJavascript(ReviewParaContentInjector.buildJs(entry), null)
        }
        // 离线评论模式：页面加载完成后注入接管脚本（快照=全接管，在线=拦截发评请求），
        // 快照优先的在线覆盖页同样生效；注入幂等，脚本内部自带安装标记
        val context = outboxContext
        if (context != null && AppConfig.offlineReviewMode) {
            view.evaluateJavascript(ReviewOutboxWireUp.buildJs(), null)
            AppLog.putDebug(
                "${ReviewOutboxStore.LogTag} 接管脚本已注入 " +
                    "url=${url ?: ""} 书=${context.bookName} 章=${context.chapterTitle}"
            )
        }
        // 快照显示中：注入章评/书评补充 section 与离线 tab/楼中楼交互。
        // 这三件事都不影响首屏是否可见，却会在页面刚出来那一刻抢主线程与渲染线程
        // （JS 编译执行 + 读库读快照文件），表现为「内容出来了但卡一下才能滑」。
        // 推迟到首帧之后再注入，让用户先看到、先能滚动。
        if (displayingSnapshotHtml) {
            view.postDelayed({
                if (!ownsLease(lease) || view !== currentWebView) return@postDelayed
                ReviewSnapshotImages.install(view)
                expandSnapshotSheet()
                injectReviewSupplements(view)
            }, SNAPSHOT_DEFERRED_INJECT_DELAY_MS)
        }
        // 骨架收起的兜底：主流机型在 onPageCommitVisible 就已收起，API 21/22
        // 没有该回调，靠这里在加载结束时收起，避免占位条一直盖着内容。
        hideLoadingSkeleton()
    }

    /**
     * 离线评论快照弹窗直接展开：折叠态 sheet 底部必然溢出屏幕，
     * fixed 在布局底的发送栏会被裁掉一半；展开后底回到屏内，栏自然完整。
     * 只动快照路径，在线活页不受影响。
     */
    private fun expandSnapshotSheet() {
        if (!displayingSnapshotHtml) return
        behavior?.let {
            it.skipCollapsed = true
            it.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    /**
     * 读取本章章评、本书书评补充快照并注入当前快照页：
     * 章评/书评 tab 从死链变成离线可切换的 section，楼中楼默认收起、
     * 点击 toggle 离线展开/收起。
     * 异步读取数据库与磁盘，evaluateJavascript 回到主线程执行。
     */
    private fun injectReviewSupplements(view: WebView) {
        val context = outboxContext ?: return
        val book = reviewResourceBook ?: return
        viewLifecycleOwner.lifecycleScope.launch(IO) {
            val js = runCatching {
                val chapter = context.chapterUrl.takeIf { it.isNotBlank() }?.let { chapterUrl ->
                    appDb.bookChapterDao.getChapterByUrl(book.bookUrl, chapterUrl)
                }
                val chapterTab = chapter?.let {
                    ReviewSnapshotStore.getChapterTab(book, it)
                }
                val bookTab = ReviewSnapshotStore.getBookTab(book)
                ReviewSupplementInjector.buildInjectionJs(chapterTab, bookTab)
            }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                if (!isAdded || isHidden) return@withContext
                view.evaluateJavascript(js) { result ->
                    AppLog.putDebug(
                        "[评论快照] 章评/书评补充注入：${result ?: "null"} " +
                            "书=${context.bookName} 章=${context.chapterTitle}"
                    )
                }
            }
        }
    }

    /** 内容已可见：收起加载骨架。重复调用无副作用。 */
    private fun hideLoadingSkeleton() {
        // 兜底任务（超时/失败页）可能在视图销毁后仍被投递到主线程，
        // 这里用可空 binding 而不是 checkNotNull 的 binding，避免收尾阶段抛 ISE。
        currentBinding?.reviewLoadingSkeleton?.hide()
    }

    /** 重新铺骨架（首屏加载与失败重试共用），并重置慢加载提示与看门狗计时。 */
    private fun showLoadingSkeleton() {
        val binding = currentBinding ?: return
        binding.reviewLoadingSkeleton.show()
        armLoadingTimers()
    }

    private fun armLoadingTimers() {
        mHandler.removeCallbacks(loadingSlowHintRunnable)
        mHandler.removeCallbacks(loadWatchdogRunnable)
        mHandler.postDelayed(loadingSlowHintRunnable, LOADING_SLOW_HINT_DELAY_MS)
        mHandler.postDelayed(loadWatchdogRunnable, LOAD_WATCHDOG_TIMEOUT_MS)
    }

    private fun cancelLoadingTimers() {
        mHandler.removeCallbacks(loadingSlowHintRunnable)
        mHandler.removeCallbacks(loadWatchdogRunnable)
    }

    private fun prepareWebView(
        headerMap: HashMap<String, String>,
        bookType: Int,
        lease: WebViewRequestLifecycle
    ) {
        currentWebView.webChromeClient = CustomWebChromeClient(lease)
        currentWebView.addJavascriptInterface(JSInterface(this, lease), nameBasic)
        currentWebView.webViewClient = CustomWebViewClient(lease)
        outboxContext?.let { context ->
            currentWebView.addJavascriptInterface(
                ReviewOutboxBridge(context),
                ReviewOutboxWireUp.bridgeName
            )
        }
        currentWebView.settings.userAgentString = headerMap.get(AppConst.UA_NAME, true)
        // 离线快照禁止 http/https，但必须让 review-resource:// 图片进入资源拦截器。
        currentWebView.settings.configureOfflineResourceLoading(offlineMode)
        source?.let { source ->
            (activity as? AppCompatActivity)?.let { currentActivity ->
                val webJsExtensions =
                    WebJsExtensions(
                        source, currentActivity, currentWebView, bookType,
                        callback = this, requestLifecycle = lease
                    )
                currentWebView.addJavascriptInterface(webJsExtensions, nameJava)
            }
            currentWebView.addJavascriptInterface(source, nameSource)
            currentWebView.addJavascriptInterface(WebCacheManager, nameCache)
        }
    }

    private fun webViewExtraHeaders(headerMap: HashMap<String, String>): Map<String, String> {
        return HashMap(headerMap).apply {
            remove(AppConst.UA_NAME)
            remove("User-Agent")
            remove("user-agent")
        }
    }

    /** 网络优先兑底：主框架开始加载时启动真正的超时定时任务 */
    private fun scheduleFallbackTimeout() {
        if (fallbackHtml == null) return
        // 失败页也是一次页面加载；此时再用快照覆盖会把刚给用户看的
        // 「重试/关闭」入口顶掉，且兜底快照在前面的加载链路里已被尝试过了
        if (displayingErrorPage) return
        cancelLoadingTimers()
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

    /** 网络优先兑底：网络加载失败/真正超时后切换为本地评论快照 */
    private fun applyFallbackSnapshot() {
        val html = fallbackHtml ?: return
        if (fallbackApplied) return
        fallbackApplied = true
        cancelFallbackTimeout()
        if (!ownsLease(requestLifecycle ?: return)) return
        // 兑底快照属于离线内容：切换后禁止一切网络请求
        offlineMode = true
        displayingSnapshotHtml = true
        // 内容来源从在线换成了缓存：得让用户知道「现在看到的是旧内容」，
        // 否则会把过期评论当成最新状态去点赞/回复
        notifyFallbackShown()
        currentWebView.settings.configureOfflineResourceLoading(true)
        currentWebView.loadDataWithBaseURL(
            currentWebView.url ?: "https://localhost/",
            html,
            "text/html",
            "utf-8",
            null
        )
    }

    /**
     * 提示「现在显示的是本地缓存快照」。
     *
     * 只在第一次切换时提示：网络优先模式下超时与 onReceivedError 可能先后触发，
     * 重复弹 toast 会更像故障而不是说明。
     */
    private fun notifyFallbackShown() {
        if (fallbackNoticeShown) return
        fallbackNoticeShown = true
        activity?.toastOnUi(getString(R.string.review_fallback_snapshot_toast))
    }

    private fun saveImage(webPic: String) {
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            selectSaveFolder(webPic)
        } else {
            saveImage(webPic, path.toUri())
        }
    }

    private fun selectSaveFolder(webPic: String?) {
        val lease = requestLifecycle ?: return
        val page = lease.currentPage() ?: return
        if (!ownsPage(lease, page)) return
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(imagePathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        pendingImageSave = lease to page
        selectImageDir.launch {
            otherActions = default
            value = webPic
        }
    }

    private fun saveImage(webPic: String?, uri: Uri) {
        webPic ?: return
        val lease = requestLifecycle ?: return
        val page = lease.currentPage() ?: return
        if (!ownsPage(lease, page)) return
        val context = requireContext()
        Coroutine.async(page.scope) {
            val local = ReviewSnapshotImages.readLocal(reviewResourceBook, webPic)
            val fileName = "${AppConst.fileNameFormat.format(Date(System.currentTimeMillis()))}.${local?.first ?: "jpg"}"
            val byteArray = local?.second ?: webData2bitmap(webPic) ?: throw NoStackTraceException("NULL")
            uri.writeBytes(context, fileName, byteArray)
        }.onError {
            if (!ownsPage(lease, page)) return@onError
            ACache.get().remove(imagePathKey)
            context.toastOnUi("保存图片失败:${it.localizedMessage}")
        }.onSuccess {
            if (ownsPage(lease, page)) context.toastOnUi("保存成功")
        }
    }

    private suspend fun webData2bitmap(data: String): ByteArray? {
        return if (URLUtil.isValidUrl(data)) {
            imageHttpClient.newCallResponseBody {
                url(data)
            }.bytes()
        } else {
            Base64.decode(data.split(",").toTypedArray()[1], Base64.DEFAULT)
        }
    }

    private fun notifyDismissAction() {
        if (dismissActionNotified) return
        dismissActionNotified = true
        onDismissAction?.invoke()
        onDismissAction = null
    }

    override fun onDestroyView() {
        closeViewRequests()
        cancelFallbackTimeout()
        cancelLoadingTimers()
        customWebViewCallback?.onCustomViewHidden()
        customWebViewCallback = null
        isFullScreen = false
        binding.customWebView.removeAllViews()
        dialog?.keepScreenOn(false)
        if (ownsPooledWebView) {
            resetWebViewPresentationState()
            ownsPooledWebView = false
            webViewSession?.detachForReuse(pooledWebView) ?: WebViewPool.release(pooledWebView)
        }
        notifyDismissAction()
        originOrientation?.let {
            activity?.requestedOrientation = it
        }
        originOrientation = null
        cachedBottomSheet = null
        cachedBehavior = null
        pendingConfig = null
        pendingConfigFirst = false
        pendingImageSave = null
        source = null
        preloadJs = null
        currentBinding = null
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (dialog === this.dialog) {
            closeViewRequests()
            notifyDismissAction()
        }
        super.onDismiss(dialog)
    }

    override fun onDestroy() {
        // Configuration changes recreate the Fragment from its arguments. Keep
        // the files in that case so the restored dialog can read them again.
        if (activity?.isChangingConfigurations != true) {
            WebViewHtmlStore.delete(htmlFileReference)
            WebViewHtmlStore.delete(fallbackHtmlFileReference)
        }
        super.onDestroy()
    }

    override fun upConfig(config: String) {
        val lease = requestLifecycle ?: return
        val page = lease.currentPage() ?: return
        page.scope.launch(Dispatchers.Main) {
            if (!ownsPage(lease, page)) return@launch
            try {
                GSON.fromJsonObject<Config>(config).getOrThrow().let { config ->
                    submitConfig(config)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.put("config err", error)
            }
        }
    }

    /**
     * 失败页的两个按钮：重试 / 关闭。
     *
     * 与 [JSInterface] 同一套约定：持有 WebView 回调线程 → 切主线程 → 校验 lease，
     * 只保留弱引用，避免失败页把对话框钉在 WebView 的 JS 桥里。
     */
    @Suppress("unused")
    private class ReviewErrorBridge(
        dialog: BottomWebViewDialog,
        lease: WebViewRequestLifecycle
    ) {
        private val dialogRef: WeakReference<BottomWebViewDialog> = WeakReference(dialog)

        // lease 也只持弱引用：桥对象会随 WebView 池化复用长期存活，
        // 强引用会把已销毁 Fragment 的 lifecycleScope 钉在池里。
        private val leaseRef: WeakReference<WebViewRequestLifecycle> = WeakReference(lease)

        @JavascriptInterface
        fun retry() {
            val fra = dialogRef.get() ?: return
            val lease = leaseRef.get() ?: return
            val page = lease.currentPage() ?: return
            page.scope.launch(Dispatchers.Main) {
                if (!fra.ownsPage(lease, page)) return@launch
                fra.retryInitialLoad()
            }
        }

        @JavascriptInterface
        fun close() {
            val fra = dialogRef.get() ?: return
            fra.mHandler.post {
                if (fra.isAdded) fra.dismissAllowingStateLoss()
            }
        }
    }

    @Suppress("unused")
    private class JSInterface(
        dialog: BottomWebViewDialog,
        private val lease: WebViewRequestLifecycle
    ) {
        private val dialogRef: WeakReference<BottomWebViewDialog> = WeakReference(dialog)

        @JavascriptInterface
        fun lockOrientation(orientation: String) {
            val fra = dialogRef.get() ?: return
            val page = lease.currentPage() ?: return
            page.scope.launch(Dispatchers.Main) {
                if (!fra.ownsPage(lease, page)) return@launch
                val ctx = fra.activity ?: return@launch
                if (fra.isFullScreen && fra.dialog?.isShowing == true) {
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
            val fra = dialogRef.get() ?: return
            val page = lease.currentPage() ?: return
            page.scope.launch(Dispatchers.Main) {
                if (fra.ownsPage(lease, page) && fra.dialog?.isShowing == true) {
                    fra.dismiss()
                }
            }
        }
    }

    @Keep
    data class Config(
        // 底部弹窗状态相关配置
        var state: Int? = null, // 设置弹窗的初始状态： 3 STATE_EXPANDED(展开) 、 4 STATE_COLLAPSED(折叠) 、 5 STATE_HIDDEN(隐藏) 、 6 STATE_HALF_EXPANDED(半展开)
        var peekHeight: Int? = null, // 设置折叠状态下的高度（像素）
        var isHideable: Boolean? = null, // 设置弹窗是否可以通过向下拖拽隐藏
        var skipCollapsed: Boolean? = null, // 设置是否跳过折叠状态，下滑对话框时直接关闭
        var setHalfExpandedRatio: Float? = null, // 设置半展开状态的比例（0.0-1.0），相对于父容器的高度
        var setExpandedOffset: Int? = null, // 设置完全展开状态时顶部距离父容器顶部的偏移量（像素）
        var setFitToContents: Boolean? = null, // 设置展开时的高度计算方式true（默认值）自适应内容、false 固定比例

        // 交互行为相关配置
        var isDraggable: Boolean? = null, // 设置弹窗是否可以通过拖拽交互
        var isDraggableOnNestedScroll: Boolean? = null, //是否允许webview滚动控制折叠展开  默认值为true允许
        var significantVelocityThreshold: Int? = null, // 设置判定为快速滑动的速度阈值（像素/秒）
        var hideFriction: Float? = null, // 设置隐藏时的摩擦系数，影响拖拽回弹效果（0.0-1.0）

        // 视觉和布局相关配置
        var maxWidth: Int? = null, // 设置弹窗的最大宽度（像素）
        var maxHeight: Int? = null, // 设置弹窗的最大高度（像素）
        var isGestureInsetBottomIgnored: Boolean? = null, // 是否忽略系统手势区域（如下方的导航条）
        var expandedCornersRadius: Float? = null, // 展开状态的圆角半径

        // 无障碍功能相关配置
        var setUpdateImportantForAccessibilityOnSiblings: Boolean? = null, // 设置是否在弹窗展开时更新兄弟视图的无障碍重要性

        // 背景相关配置
        var backgroundDimAmount: Float? = null, // 背景遮罩透明度（0.0-1.0）
        var shouldDimBackground: Boolean? = null, // 是否显示背景遮罩

        // WebView特定配置
        var webViewInitialScale: Int? = null, // WebView初始缩放比例 默认100
        var webViewCacheMode: Int? = null, // WebView缓存模式： -1 LOAD_DEFAULT 、 1 LOAD_NO_CACHE 、 2 LOAD_CACHE_ONLY 、 3 LOAD_CACHE_ELSE_NETWORK

        // 生命周期配置
        var dismissOnTouchOutside: Boolean? = null, // 点击外部是否关闭弹窗

        // 性能优化配置
        var hardwareAccelerated: Boolean? = null, // 是否启用硬件加速
        var isNestedScrollingEnabled: Boolean? = null, // 是否启用嵌套滚动

        // 响应式设计相关配置
        var widthPercentage: Float? = null, // 弹窗宽度占屏幕宽度的百分比（0.0-1.0）
        var heightPercentage: Float? = null, // 弹窗高度占屏幕高度的百分比（0.0-1.0）
        var responsiveBreakpoint: Int? = null, // 响应式断点（像素），小于此宽度时使用移动端布局
        var dialogHeight: Int? = null, //弹窗高度（像素），默认为-1（父容器最大高度）、-2（最大内容高度）

        //阅读功能自定义配置
        var longClickSaveImg : Boolean? = null, //是否启用长按图片保存功能，默认启用
        var scrollNoDraggable : Boolean? = null, //网页有滚动时禁止对话框拖拽，默认启用
    )

    private inner class CustomWebChromeClient(
        private val lease: WebViewRequestLifecycle
    ) : WebChromeClient() {
        override fun getDefaultVideoPoster(): Bitmap {
            return super.getDefaultVideoPoster() ?: createBitmap(100, 100)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (!ownsLease(lease)) {
                callback?.onCustomViewHidden()
                return
            }
            originOrientation = activity?.requestedOrientation //先记录原始方向，避免被js控制的影响
            isFullScreen = true
            binding.webViewContainer.invisible()
            binding.customWebView.addView(view)
            customWebViewCallback = callback
            dialog?.keepScreenOn(true)
            behavior?.state = BottomSheetBehavior.STATE_EXPANDED
        }

        override fun onHideCustomView() {
            if (!ownsLease(lease)) return
            originOrientation?.let {
                activity?.requestedOrientation = it
                originOrientation = null
            }
            isFullScreen = false
            binding.webViewContainer.visible()
            binding.customWebView.removeAllViews()
            customWebViewCallback = null
            dialog?.keepScreenOn(false)
        }

        /* 覆盖window.close() */
        override fun onCloseWindow(window: WebView?) {
            if (ownsLease(lease)) dismiss()
        }

        /* 监听网页日志 */
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            if (!ownsLease(lease)) return false
            if (!AppConfig.recordLog) return false
            val source = source ?: return false
            val messageLevel = consoleMessage.messageLevel().name
            val message = consoleMessage.message()
            AppLog.put(
                "${source.getTag()}${messageLevel}: $message",
                NoStackTraceException("\n${message}\n- Line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
            )
            return true
        }
    }

    private inner class CustomWebViewClient(
        private val lease: WebViewRequestLifecycle
    ) : WebViewClient() {
        private val documentPreloadJs = preloadJs
        private var firstDocumentStarted = false

        override fun shouldOverrideUrlLoading(
            view: WebView?, request: WebResourceRequest?
        ): Boolean {
            if (!ownsLease(lease) || view !== currentWebView) return true
            request?.let {
                return shouldOverrideUrlLoading(it.url)
            }
            return true
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            if (!ownsLease(lease) || view !== currentWebView) return true
            url?.let {
                return shouldOverrideUrlLoading(it.toUri())
            }
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (!ownsLease(lease) || view !== currentWebView) return
            // The initial load already owns the lease's first page. Only a later
            // navigation invalidates that document's outstanding bridge requests.
            if (firstDocumentStarted) lease.startPage() else firstDocumentStarted = true
            if (needClearHistory) {
                needClearHistory = false
                currentWebView.clearHistory() //清除历史
            }
            super.onPageStarted(view, url, favicon)
            pendingMainFrameError = null
            currentWebView.evaluateJavascript(basicJs, null)
            // 网络优先：主框架开始加载时启动真正的超时定时任务
            scheduleFallbackTimeout()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            if (view == null || !ownsLease(lease) || view !== currentWebView) return
            handlePageReady(view, url, lease)
        }

        /**
         * 首帧已经绘制出来：这是隐藏骨架屏最准确的时机（内容真的可见了），
         * 比 onPageFinished 更早，用户不会看到「骨架闪一下再变内容」。
         */
        override fun onPageCommitVisible(view: WebView?, url: String?) {
            super.onPageCommitVisible(view, url)
            if (!ownsLease(lease) || view !== currentWebView) return
            // 只要有内容画出第一帧，就说明主框架其实是可用的
            // （曾收到过 ERR_ABORTED 之类可恢复错误的页面会走到这里），
            // 此时不该再拿失败页去覆盖它，看门狗与慢加载提示也一并收掉。
            pendingMainFrameError = null
            cancelLoadingTimers()
            hideLoadingSkeleton()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onReceivedError(
            view: WebView?,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            super.onReceivedError(view, errorCode, description, failingUrl)
            if (!ownsLease(lease)) return
            if (fallbackHtml != null && !fallbackApplied) {
                applyFallbackSnapshot()
                return
            }
            // 老回调在部分 ROM 上连子资源失败也会报上来（API 23 以下没有
            // isForMainFrame 可用）。只认「失败的正是当前主文档」这一种，
            // 否则图片挂了也会被换成一整页错误提示。
            if (failingUrl == null || failingUrl != view?.url) return
            rememberMainFrameError(description)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: android.webkit.WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (!ownsLease(lease) || request?.isForMainFrame != true) return
            if (fallbackHtml != null && !fallbackApplied) {
                applyFallbackSnapshot()
                return
            }
            rememberMainFrameError(error?.description?.toString())
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (!ownsLease(lease) || request?.isForMainFrame != true) return
            val code = errorResponse?.statusCode ?: return
            if (fallbackHtml != null && !fallbackApplied) {
                applyFallbackSnapshot()
                return
            }
            // 4xx/5xx：站点活着但评论页拿不到，与网络层错误一样交给
            // onPageFinished 统一渲染失败页（detail 里能看到状态码）
            rememberMainFrameError("HTTP $code")
        }

        /**
         * 记下主框架错误，交给 onPageFinished 统一决定是否渲染失败页。
         *
         * 有缓存快照时前面已经切快照了；已经在显示快照（离线内容）或失败页时
         * 更不能覆盖——那会把「已经能看的内容」换成错误提示。
         */
        private fun rememberMainFrameError(description: String?) {
            if (displayingSnapshotHtml || displayingErrorPage) return
            pendingMainFrameError = description ?: "net::ERR_UNKNOWN"
        }

        private fun shouldOverrideUrlLoading(url: Uri): Boolean {
            val page = lease.currentPage() ?: return true
            if (!ownsPage(lease, page)) return true
            if (displayingSnapshotHtml &&
                ReviewSnapshotImages.open(requireActivity(), reviewResourceBook, url)
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
                    binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                        if (ownsPage(lease, page)) activity?.openUrl(url)
                    }
                    true
                }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?, handler: SslErrorHandler?, error: SslError?
        ) {
            if (ownsLease(lease) && view === currentWebView) handler?.proceed()
            else handler?.cancel()
        }

        @Volatile
        private var jsInjected = false
        override fun shouldInterceptRequest(
            view: WebView, request: WebResourceRequest
        ): WebResourceResponse? {
            if (!ownsLease(lease) || view !== currentWebView) return cancelledResponse()
            val url = request.url.toString()
            ReviewSnapshotResourceStore.keyFromReference(url)?.let { key ->
                val book = checkNotNull(reviewResourceBook) {
                    "评论快照资源引用缺少书籍上下文: $url"
                }
                val resource = checkNotNull(ReviewSnapshotResourceStore.open(book, key)) {
                    "评论快照资源不存在: $url"
                }
                return WebResourceResponse(resource.mimeType, null, resource.inputStream)
            }
            // 仅使用快照/快照兑底（离线模式）：http/https 请求一律拦掉，
            // 快照只允许 data:// 或 review-resource:// 本地资源离线渲染，残余外部资源不联网
            if (offlineMode &&
                (request.url.scheme == "http" || request.url.scheme == "https")
            ) {
                return cancelledResponse()
            }
            if (request.isForMainFrame) {
                if (!documentPreloadJs.isNullOrEmpty()) {
                    jsInjected = false
                    if (url.startsWith("data:text/html;") || request.method == "POST") {
                        return super.shouldInterceptRequest(view, request)
                    }
                    return try {
                        runBlocking(lease.scope.coroutineContext + IO) {
                            getModifiedContentWithJs(url, request) ?: super.shouldInterceptRequest(view, request)
                        }
                    } catch (_: CancellationException) {
                        // WebView's synchronous interception API has no cancellation
                        // channel. Do not restart a cancelled request via the network.
                        cancelledResponse()
                    }
                }
            } else if (!jsInjected && url == nameUrl) {
                jsInjected = true
                val preloadJs = documentPreloadJs ?: ""
                return WebResourceResponse(
                    "text/javascript",
                    "utf-8",
                    ByteArrayInputStream("(() => {$JS_INJECTION\n$preloadJs\n})();".toByteArray())
                )
            }
            return super.shouldInterceptRequest(view, request)
        }

        private fun cancelledResponse() = WebResourceResponse(
            "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
        )
        private val webCookieManager by lazy { android.webkit.CookieManager.getInstance() }
        private suspend fun getModifiedContentWithJs(url: String, request: WebResourceRequest): WebResourceResponse? {
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
                res.use {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    if (!ownsLease(lease)) {
                        return cancelledResponse()
                    }
                    res.headers("Set-Cookie").forEach { setCookie ->
                        webCookieManager.setCookie(url, setCookie)
                    }
                    val body = res.body
                    val contentType = body.contentType()
                    val mimeType = contentType?.toString()?.substringBefore(";") ?: "text/html"
                    val charset = contentType?.charset() ?: Charsets.UTF_8
                    val charsetSre = charset.name()
                    val bodyText = body.text().let { originalText ->
                        val headIndex = originalText.indexOf("<head", ignoreCase = true)
                        if (headIndex >= 0) {
                            val closingHeadIndex = originalText.indexOf('>', startIndex = headIndex)
                            if (closingHeadIndex >= 0) {
                                val insertPos = closingHeadIndex + 1
                                StringBuilder(originalText).insert(insertPos, JS_URL).toString()
                            } else {
                                originalText
                            }
                        } else {
                            originalText
                        }
                    }
                    return WebResourceResponse(
                        mimeType,
                        charsetSre,
                        ByteArrayInputStream(bodyText.toByteArray(charset))
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return null
            }
        }
    }

}
