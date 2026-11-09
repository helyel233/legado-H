package io.legado.app.ui.book.read

import android.content.Context
import android.database.ContentObserver
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadMenuCustomButton
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.library.LibraryCloudState
import io.legado.app.help.book.isEpub
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.review.reviewoutbox.ReviewOutboxStore
import io.legado.app.help.source.getSourceType
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.rememberThemeUiPalette
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.book.read.config.rememberReaderMenuDialogStyle
import io.legado.app.model.ReadBook
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.glass.GlassPanelRect
import io.legado.app.ui.glass.applyLiquidGlassSpec
import io.legado.app.ui.glass.contentLiquidGlassSpec
import io.legado.app.ui.glass.glassPanelFillYielded
import io.legado.app.ui.glass.glassPanelRect
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.explore.ExploreGlassBackdrop
import io.legado.app.ui.main.explore.resolvedExploreGlassMode
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.StableLiquidGlassView
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.utils.activity
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.openUrl
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.startActivity
import splitties.init.appCtx

class ReadMenu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    // region Public API
    var canShowMenu: Boolean = false

    fun reset() {
        updateColorConfig()
        updateButtonLayout()
        upBookView()
        upSeekBar()
    }

    fun refreshMenuColorFilter() {
        updateColorConfig()
    }

    fun upBrightnessState() {
        brightnessAuto = context.getPrefBoolean(PreferKey.brightnessAuto, true) || !showBrightnessView
        setScreenBrightness(AppConfig.readBrightness.toFloat())
    }

    fun setScreenBrightness(value: Float) {
        activity?.run {
            fun setBrightness(v: Float) {
                val params = window.attributes
                params.screenBrightness = v
                window.attributes = params
            }
            val autoBrightness = BRIGHTNESS_OVERRIDE_NONE
            if (brightnessAuto || value == autoBrightness) {
                setBrightness(autoBrightness)
                return
            }
            val brightness = if (value < 1f) 0.004f else value / 255f
            var isSunMax = false
            if (brightness == 1f) {
                val sysBrightness = getCurrentBrightness(context)
                if (sysBrightness == 255) {
                    isSunMax = true
                }
            }
            if (isSunMax) {
                //注册前先反注册旧 observer，避免重复调用累积多个监听
                contentObserver?.let {
                    context.contentResolver.unregisterContentObserver(it)
                }
                contentObserver = object : ContentObserver(buildMainHandler()) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        super.onChange(selfChange, uri)
                        if (contentObserver == null) return
                        if (uri == Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)) {
                            val sysBrightness = getCurrentBrightness(context)
                            if (sysBrightness < 200) {
                                setBrightness(brightness)
                                contentObserver?.let {
                                    context.contentResolver.unregisterContentObserver(it)
                                }
                                contentObserver = null
                            } else if (sysBrightness < 255) {
                                setBrightness(brightness)
                            } else {
                                setBrightness(autoBrightness)
                            }
                        }
                    }
                }
                val brightnessUri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
                context.contentResolver.registerContentObserver(
                    brightnessUri, false, contentObserver!!
                )
                setBrightness(autoBrightness)
            } else {
                setBrightness(brightness)
            }
        }
    }

    fun runMenuIn(anim: Boolean = !AppConfig.isEInkMode) {
        callBack.onMenuShow()
        this.visibility = VISIBLE
        callBack.upSystemUiVisibility()
        isTopBarVisible = true
        isBottomMenuVisible = true
        refreshMenuGlass()
        // 每次唤出菜单都刷新待发条数：三点图标小圆点与「发送离线评论（N）」都吃这个值
        refreshOfflineReviewCounts()
        if (!LocalConfig.readMenuHelpVersionIsLast) {
            callBack.showHelp()
        }
    }

    fun runMenuOut(anim: Boolean = !AppConfig.isEInkMode, onMenuOutEnd: (() -> Unit)? = null) {
        if (isMenuOutAnimating) return
        isMenuOutAnimating = true
        callBack.onMenuHide()
        this.onMenuOutEnd = onMenuOutEnd
        isTopBarVisible = false
        isBottomMenuVisible = false
        // 延迟清理状态（等待动画结束）
        postDelayed({
            this.visibility = INVISIBLE
            hideMenuGlass()
            callBack.onMenuHidden()
            canShowMenu = false
            isMenuOutAnimating = false
            onMenuOutEnd?.invoke()
            callBack.upSystemUiVisibility()
        }, if (anim) 300L else 0L)
    }

    fun bottomMenuBoundsIn(target: android.view.View): RectF? {
        val bounds = bottomMenuBounds ?: return null
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        if (target.width <= 0 || target.height <= 0) return null
        val targetLocation = IntArray(2)
        target.getLocationInWindow(targetLocation)
        return RectF(
            bounds.left - targetLocation[0],
            bounds.top - targetLocation[1],
            bounds.right - targetLocation[0],
            bounds.bottom - targetLocation[1]
        )
    }

    fun upBookView() {
        val currentBook = ReadBook.book
        directReader = callBack.isEpubCoreBook()
        replaceRulesSupported = callBack.supportsReplaceRules()
        currentBookName = currentBook?.name
        if (callBack.isEpubCoreBook()) {
            // EPUB 直读的章标题/URL 要查库（直读不用 TextChapter，内存里没有），
            // 放到 IO 里异步填：这里是每次唤出菜单都会走的路径，主线程查库
            // 在低端机/库较大时会让人感觉到菜单弹出卡顿
            loadEpubCoreChapterAsync()
            upSeekBar()
            canGoPrev = ReadBook.durChapterIndex != 0
            canGoNext = ReadBook.durChapterIndex != ReadBook.simulatedChapterSize - 1
            return
        }
        val currentTextChapter = ReadBook.curTextChapter
        val chapter = currentTextChapter?.chapter
        if (chapter != null) {
            // 内存里已有当前章，直接填，不必查库
            applyTextChapter(chapter, currentTextChapter.title)
            upSeekBar()
            canGoPrev = ReadBook.durChapterIndex != 0
            canGoNext = ReadBook.durChapterIndex != ReadBook.simulatedChapterSize - 1
            return
        }
        val book = currentBook ?: run {
            currentChapterUrl = null
            currentChapterName = null
            return
        }
        // 兜底：内存里没有当前章（异常/恢复场景），查库也放到 IO
        viewScope()?.launch {
            val loaded = withContext(Dispatchers.IO) {
                appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
            }
            if (loaded != null) {
                applyTextChapter(loaded, null)
                upSeekBar()
                canGoPrev = ReadBook.durChapterIndex != 0
                canGoNext = ReadBook.durChapterIndex != ReadBook.simulatedChapterSize - 1
            } else {
                currentChapterUrl = null
                currentChapterName = book.durChapterTitle?.takeIf { it.isNotBlank() }
            }
        }
    }

    /**
     * 离线评论条数：唤出菜单时刷一次，三点菜单的发送/导出/清除入口据此显隐。
     * 查询走 IO，主线程只拿结果赋值。
     */
    private fun refreshOfflineReviewCounts() {
        viewScope()?.launch {
            val (pending, total) = withContext(Dispatchers.IO) {
                ReviewOutboxStore.countSendable() to ReviewOutboxStore.countAll()
            }
            offlineReviewPendingCount = pending
            offlineReviewRecordCount = total
        }
    }

    /** 只做 UI 赋值，不含任何 IO */
    private fun applyTextChapter(
        chapter: io.legado.app.data.entities.BookChapter,
        fallbackTitle: String?
    ) {
        currentChapterName = fallbackTitle?.takeIf { it.isNotBlank() }
            ?: chapter.title.takeIf { it.isNotBlank() }
            ?: ReadBook.book?.durChapterTitle?.takeIf { it.isNotBlank() }
        currentChapterUrl = if (ReadBook.isLocalBook) null else resolveChapterUrl(chapter)
    }

    private fun loadEpubCoreChapterAsync() {
        viewScope()?.launch {
            val title = withContext(Dispatchers.IO) { callBack.epubCoreChapterTitle() }
            val url = withContext(Dispatchers.IO) { callBack.epubCoreChapterUrl() }
            currentChapterName = title.orEmpty()
            currentChapterUrl = url
        }
    }

    /** View 自己的协程作用域：没 attach 上就不干，避免空 activity */
    private fun viewScope(): CoroutineScope? = activity?.lifecycleScope

    fun upSeekBar() {
        when (AppConfig.progressBarBehavior) {
            "page" -> {
                val epubPageCount = callBack.epubCorePageCount()
                if (epubPageCount > 0) {
                    seekMax = epubPageCount - 1
                    seekProgress = callBack.epubCorePageIndex().coerceIn(0, seekMax)
                } else {
                    ReadBook.curTextChapter?.let {
                        seekMax = it.pageSize - 1
                        seekProgress = ReadBook.durPageIndex
                    }
                }
            }
            "chapter" -> {
                seekMax = ReadBook.simulatedChapterSize - 1
                seekProgress = ReadBook.durChapterIndex
            }
        }
    }

    fun setSeekPage(seek: Int) {
        seekProgress = seek
    }

    fun setAutoPage(autoPage: Boolean) {
        autoPageActive = autoPage
    }

    fun updateCloudLibraryState(state: LibraryCloudState) {
        cloudState = state
        showCloudIcon = callBack.isLibraryCloudEnabled()
    }
    // endregion

    // region Private state
    private val callBack: CallBack get() = activity as CallBack
    private var composeView: ComposeView? = null
    /**
     * 底部菜单液态玻璃。采样源是阅读页正文视图（与 [ReadMenu] 平级的兄弟子树），
     * 菜单打开时绑定、关闭动画结束后解绑——底层库每帧把采样源整树录制进
     * `RenderNode`，常驻绑定会让阅读过程中持续付出这一开销。
     */
    private val bottomGlass = StableLiquidGlassView(context).apply {
        visibility = GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    /**
     * 顶部菜单液态玻璃：与底部菜单同源同材质，采样源仍是阅读页正文视图。
     *
     * 半径取 0——TitleBar / ActionBar 本身就是贴边全宽的矩形 Surface，玻璃层只有
     * 单一半径，加圆角会在屏幕顶边两角留下缺口；方形贴边与现有顶栏形状一致。
     */
    private val topGlass = StableLiquidGlassView(context).apply {
        visibility = GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var liquidGlassSource: ViewGroup? = null
    private var bottomGlassActive by mutableStateOf(false)
    /** 玻璃层最终矩形（逐帧更新，不参与重组）。 */
    private var bottomGlassRect: GlassPanelRect? = null
    /** 是否已完成过一次定位（只在 false→true 时触发一次重组，决定面板能否让出底色）。 */
    private var bottomGlassGeometried by mutableStateOf(false)
    private var topGlassActive by mutableStateOf(false)
    private var topGlassRect: GlassPanelRect? = null
    private var topGlassGeometried by mutableStateOf(false)
    private var confirmSkipToChapter: Boolean = false
    private var isMenuOutAnimating = false
    private var onMenuOutEnd: (() -> Unit)? = null
    private var contentObserver: ContentObserver? = null

    private val immersiveMenu: Boolean
        get() = AppConfig.readBarStyleFollowPage && ReadBookConfig.durConfig.curBgType() == 0

    private val showBrightnessView: Boolean
        get() = context.getPrefBoolean(PreferKey.showBrightnessView, true)

    // Compose state
    private var isTopBarVisible by mutableStateOf(false)
    private var isBottomMenuVisible by mutableStateOf(false)
    private var seekProgress by mutableIntStateOf(0)
    private var seekMax by mutableIntStateOf(0)
    private var canGoPrev by mutableStateOf(false)
    private var canGoNext by mutableStateOf(false)
    private var brightnessAuto by mutableStateOf(true)
    private var currentBookName by mutableStateOf<String?>(null)
    private var currentChapterName by mutableStateOf<String?>(null)
    private var currentChapterUrl by mutableStateOf<String?>(null)
    private var cloudState by mutableStateOf(LibraryCloudState.DISABLED)
    private var showCloudIcon by mutableStateOf(false)
    private var autoPageActive by mutableStateOf(false)
    private var buttonLayout by mutableStateOf(ReadMenuButtonConfig.defaultLayout())
    private var directReader by mutableStateOf(false)
    private var replaceRulesSupported by mutableStateOf(false)
    private var customButtonMetadata by mutableStateOf(emptyMap<Long, ReadMenuCustomButton>())
    private var bottomMenuBounds by mutableStateOf<RectF?>(null)
    private var colorTick by mutableIntStateOf(0)
    /** 离线评论待发送 / 全部记录数：决定三点菜单里是否出现发送、导出、清除 */
    private var offlineReviewPendingCount by mutableIntStateOf(0)
    private var offlineReviewRecordCount by mutableIntStateOf(0)
    // endregion

    init {
        // 设置 FrameLayout 背景透明，避免退出时闪白屏
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        // 玻璃层必须在 Compose 内容之前加入：同层子 View 的绘制顺序即叠放顺序
        addView(topGlass, LayoutParams(0, 0))
        addView(bottomGlass, LayoutParams(0, 0))
        composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { ReadMenuContent() }
        }
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        upBrightnessState()
        updateButtonLayout()
    }

    /** 设置底部菜单液态玻璃的采样源（阅读页正文视图，必须是本视图的兄弟子树）。 */
    fun setLiquidGlassSource(source: ViewGroup?) {
        liquidGlassSource = source
    }

    /**
     * 按当前档位 / 设备能力决定顶部与底部菜单是否走液态玻璃。
     *
     * 每次唤出菜单时调用一次：采样源是否可见、是否 EPUB 直读（WebView 不可采样）
     * 都可能随书籍切换变化。返回 null（solid 档 / API<33 / 低内存 / 墨水屏 / 无源）
     * 时两处都保持原有实底菜单，不做任何布局改动。
     */
    private fun refreshMenuGlass() {
        val source = liquidGlassSource
            ?.takeIf { !directReader && it.isShown && it.width > 0 && it.height > 0 }
        val spec = contentLiquidGlassSpec(
            params = GlassMaterialPreset.current(resolvedExploreGlassMode()),
            density = resources.displayMetrics.density,
            sdkInt = Build.VERSION.SDK_INT,
            sourceAvailable = source != null,
            isLowRam = ExploreGlassBackdrop.isLowRamDevice(context),
            isEInk = AppConfig.isEInkMode
        )
        if (spec == null || source == null) {
            bottomGlassActive = false
            bottomGlassRect = null
            bottomGlassGeometried = false
            bottomGlass.bind(null)
            bottomGlass.visibility = GONE
            topGlassActive = false
            topGlassRect = null
            topGlassGeometried = false
            topGlass.bind(null)
            topGlass.visibility = GONE
            return
        }
        bottomGlassActive = true
        bottomGlass.setCornerRadius(UiCorner.panelRadius(context))
        bottomGlass.applyLiquidGlassSpec(spec)
        bottomGlass.bind(source)
        bottomGlass.visibility = VISIBLE
        applyBottomGlassRect()
        topGlassActive = true
        topGlass.setCornerRadius(0f)
        topGlass.applyLiquidGlassSpec(spec)
        topGlass.bind(source)
        topGlass.visibility = VISIBLE
        applyTopGlassRect()
    }

    /** 菜单收起动画结束后解绑：不采样就没有每帧整树录制。 */
    private fun hideMenuGlass() {
        bottomGlass.bind(null)
        bottomGlass.visibility = GONE
        bottomGlassRect = null
        bottomGlassGeometried = false
        bottomGlassActive = false
        topGlass.bind(null)
        topGlass.visibility = GONE
        topGlassRect = null
        topGlassGeometried = false
        topGlassActive = false
    }

    /**
     * 面板在窗口中的位置 → 玻璃层本地矩形。
     *
     * 由 Compose 的 `onGloballyPositioned` 每帧回调，因此滑入 / 滑出动画期间
     * 玻璃与面板逐帧对齐，不需要另写一套动画。
     */
    private fun updateBottomGlassGeometry(
        windowLeft: Float,
        windowTop: Float,
        width: Int,
        height: Int
    ) {
        if (!bottomGlassActive || width <= 0 || height <= 0) return
        val location = IntArray(2)
        getLocationInWindow(location)
        bottomGlassRect = glassPanelRect(
            panelLeft = windowLeft - location[0],
            panelTop = windowTop - location[1],
            panelWidth = width.toFloat(),
            panelHeight = height.toFloat(),
            cornerRadiusPx = UiCorner.panelRadius(context)
        )
        if (bottomGlassRect != null) bottomGlassGeometried = true
        applyBottomGlassRect()
    }

    /**
     * 玻璃层按面板矩形定位。
     *
     * 必须显式测量：玻璃层以 `LayoutParams(0, 0)` 加入（位置完全由面板决定），
     * 父容器测不到有效尺寸，内部玻璃子 View 会停在 0×0、整层空绘制——真机表现
     * 就是「面板已让出底色、玻璃却没画出来，菜单背景整个消失」。这里把
     * `LayoutParams` 同步成目标尺寸（父容器后续布局不会再把它压回 0×0），
     * 并在尺寸变化时按 EXACTLY 测量一次。
     */
    private fun applyBottomGlassRect() {
        val rect = bottomGlassRect ?: return
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        if (width <= 0 || height <= 0) return
        val params = bottomGlass.layoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            bottomGlass.layoutParams = params
        }
        if (bottomGlass.measuredWidth != width || bottomGlass.measuredHeight != height) {
            bottomGlass.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        }
        bottomGlass.layout(rect.left, rect.top, rect.right, rect.bottom)
    }

    /**
     * 顶栏在窗口中的位置 → 顶部玻璃层本地矩形。
     *
     * 半径传 0：顶栏贴边全宽且四角为直角，`glassPanelRect` 不会像底部菜单那样
     * 把底边向下延伸（延伸只为藏住屏幕底边的两个圆角）。
     */
    private fun updateTopGlassGeometry(
        windowLeft: Float,
        windowTop: Float,
        width: Int,
        height: Int
    ) {
        if (!topGlassActive || width <= 0 || height <= 0) return
        val location = IntArray(2)
        getLocationInWindow(location)
        topGlassRect = glassPanelRect(
            panelLeft = windowLeft - location[0],
            panelTop = windowTop - location[1],
            panelWidth = width.toFloat(),
            panelHeight = height.toFloat(),
            cornerRadiusPx = 0f
        )
        if (topGlassRect != null) topGlassGeometried = true
        applyTopGlassRect()
    }

    /** 与 [applyBottomGlassRect] 同一套测量纪律：玻璃层尺寸由面板矩形决定。 */
    private fun applyTopGlassRect() {
        val rect = topGlassRect ?: return
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        if (width <= 0 || height <= 0) return
        val params = topGlass.layoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            topGlass.layoutParams = params
        }
        if (topGlass.measuredWidth != width || topGlass.measuredHeight != height) {
            topGlass.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        }
        topGlass.layout(rect.left, rect.top, rect.right, rect.bottom)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 父容器布局会按 LayoutParams 把玻璃层摆回 (0,0)，这里按面板矩形复位。
        applyBottomGlassRect()
        applyTopGlassRect()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        contentObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            contentObserver = null
        }
    }

    // region Compose content
    @Composable
    private fun ReadMenuContent() {
        colorTick
        val themeSignature = rememberThemeUiPalette().signature
        val style = rememberReadMenuStyle()
        val bodyFontFamily = remember(context, themeSignature) {
            androidx.compose.ui.text.font.FontFamily(context.uiTypeface())
        }
        val titleFontFamily = remember(context, themeSignature) {
            androidx.compose.ui.text.font.FontFamily(context.titleTypeface())
        }

        CompositionLocalProvider(
            androidx.compose.material3.LocalTextStyle provides
                androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = bodyFontFamily)
        ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 点击空白区域关闭菜单（只响应 tap，不干扰拖拽）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { runMenuOut() }
                    }
            )

            // 顶栏（带状态栏 padding）
            AnimatedVisibility(
                visible = isTopBarVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = if (AppConfig.isEInkMode) snap() else tween(150)
                ),
                exit = slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = if (AppConfig.isEInkMode) snap() else tween(150)
                )
            ) {
                val statusBarHeight = WindowInsets.statusBars
                    .asPaddingValues()
                    .calculateTopPadding()
                // 顶栏玻璃与底部菜单同一纪律：只有玻璃层真正定位过才让出底色，
                // 否则会露出一块透明空面板。
                val topGlassYielded = glassPanelFillYielded(topGlassActive, topGlassGeometried)
                val topStyle = if (topGlassYielded) {
                    style.copy(surface = Color.Transparent)
                } else {
                    style
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            val pos = coordinates.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                            val size = coordinates.size
                            updateTopGlassGeometry(pos.x, pos.y, size.width, size.height)
                        }
                ) {
                    // 状态栏占位（带背景色，衔接顶栏）
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(statusBarHeight)
                            .background(topStyle.surface)
                    )
                    // Toolbar（书名 + 换源/刷新/缓存 + 三点菜单）
                    ReadMenuTitleBar(
                        state = ReadMenuTitleBarState(
                            bookName = currentBookName,
                            isLocalBook = ReadBook.isLocalBook,
                            isEpub = directReader,
                            supportsReplaceRules = replaceRulesSupported,
                            offlineReviewPending = offlineReviewPendingCount,
                            offlineReviewRecords = offlineReviewRecordCount
                        ),
                        actions = ReadMenuTitleBarActions(
                            onBackClick = { callBack.returnToBookshelf() },
                            onBookClick = { callBack.openBookInfoActivity() },
                            onChangeSourceClick = { callBack.changeSource() },
                            onChangeSourceLongClick = { callBack.changeSourceSingle() },
                            onRefreshClick = { callBack.refreshContent() },
                            onRefreshLongClick = { callBack.showRefreshOptions() },
                            onCacheClick = { callBack.showCacheDialog() },
                            onAddBookmarkClick = { callBack.addBookmark() },
                            onEditContentClick = { callBack.editContent() },
                            onPageAnimClick = { callBack.showPageAnim() },
                            onMenuEditClick = { callBack.editMenu() },
                            onGetProgressClick = { callBack.showGetProgress() },
                            onCoverProgressClick = { callBack.showCoverProgress() },
                            onReverseContentClick = { callBack.reverseContent() },
                            onSimulatedReadingClick = { (activity as? BaseReadBookActivity)?.showSimulatedReading() },
                            onChangeReplaceRuleClick = { callBack.changeReplaceRuleState() },
                            onSameTitleRemovedClick = { callBack.showSameTitleRemoved() },
                            onReSegmentClick = { callBack.showReSegment() },
                            onImageStyleClick = { callBack.showImageStyle() },
                            onUpdateTocClick = { callBack.updateToc() },
                            onParagraphRuleClick = { callBack.showParagraphRuleManage() },
                            onHighlightRuleClick = { callBack.showHighlightRuleManage() },
                            onEffectiveReplacesClick = { callBack.showEffectiveReplaces() },
                            onLogClick = { callBack.showLog() },
                            onHelpClick = { callBack.showHelp() },
                            onToggleCacheReviewClick = {
                                callBack.toggleCacheReview()
                            },
                            onReviewOpenModeClick = {
                                callBack.showReviewOpenModeDialog()
                            },
                            onReviewSnapshotTtlClick = {
                                callBack.showReviewSnapshotTtlDialog()
                            },
                            onTogglePreheatWifiOnlyClick = {
                                callBack.togglePreheatWifiOnly()
                            },
                            onToggleOfflineReviewModeClick = {
                                callBack.toggleOfflineReviewMode()
                            },
                            onSendOfflineReviewsClick = {
                                callBack.sendOfflineReviews()
                            },
                            onExportOfflineReviewsClick = {
                                callBack.exportOfflineReviews()
                            },
                            onClearOfflineReviewsClick = {
                                callBack.clearOfflineReviews()
                            }
                        ),
                        style = topStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (AppConfig.showReadTitleBarAddition) {
                        // 操作栏（章节信息 + 操作按钮）
                        ReadMenuActionBar(
                            state = ReadMenuActionBarState(
                                chapterName = currentChapterName,
                                isLocalBook = ReadBook.isLocalBook,
                                sourceName = ReadBook.bookSource?.bookSourceName,
                                showCustomButton = ReadBook.bookSource?.customButton == true,
                                showCloudIcon = showCloudIcon,
                                cloudState = cloudState,
                                hasLogin = !ReadBook.bookSource?.loginUrl.isNullOrEmpty(),
                                hasVipChapter = !ReadBook.bookSource?.loginUrl.isNullOrEmpty()
                                        && ReadBook.curTextChapter?.isVip == true
                                        && ReadBook.curTextChapter?.isPay != true
                            ),
                            actions = ReadMenuActionBarActions(
                                onChapterClick = { handleChapterClick() },
                                onChapterLongClick = { handleChapterLongClick() },
                                onLoginClick = { callBack.showLogin() },
                                onPayClick = { callBack.payAction() },
                                onEditSourceClick = { callBack.openSourceEditActivity() },
                                onDisableSourceClick = { callBack.disableSource() },
                                onCustomButtonClick = { handleCustomButtonClick() },
                                onCustomButtonLongClick = { handleCustomButtonLongClick() },
                                onCloudClick = { callBack.showLibraryCloudChapters(refresh = false) },
                                onCloudLongClick = {
                                    if (io.legado.app.BuildConfig.DEBUG) {
                                        callBack.showLibraryCloudDebug()
                                    } else {
                                        callBack.showLibraryCloudChapters(refresh = true)
                                    }
                                    true
                                }
                            ),
                            style = topStyle,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 底部菜单
            AnimatedVisibility(
                visible = isBottomMenuVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = if (AppConfig.isEInkMode) snap() else tween(150)
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = if (AppConfig.isEInkMode) snap() else tween(150)
                )
            ) {
                ReadMenuBottomPanel(style = style)
            }
        }
        } // CompositionLocalProvider
    }

    @Composable
    private fun ReadMenuBottomPanel(style: AppDialogStyle) {
        val layout = if (directReader) {
            ReadMenuButtonConfig.forDirectReader(
                buttonLayout,
                allowReplaceRules = replaceRulesSupported,
                templateOnly = ReadBook.usesPageTemplate()
            )
        } else {
            buttonLayout
        }
        val hasButtons = layout.firstRow.isNotEmpty() || layout.secondRow.isNotEmpty()
        val showBrightness = showBrightnessView && hasButtons
        val navigationBarBottom = WindowInsets.navigationBars
            .asPaddingValues()
            .calculateBottomPadding()

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    val pos = coordinates.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                    val size = coordinates.size
                    bottomMenuBounds = RectF(
                        pos.x, pos.y,
                        pos.x + size.width, pos.y + size.height
                    )
                    updateBottomGlassGeometry(pos.x, pos.y, size.width, size.height)
                },
            shape = RoundedCornerShape(topStart = style.panelRadius, topEnd = style.panelRadius),
            // 玻璃层只有真正定位过（拿到面板矩形）才让出底色：没定位就让出会得到一块透明空面板。
            color = if (glassPanelFillYielded(bottomGlassActive, bottomGlassGeometried)) {
                Color.Transparent
            } else {
                style.surface
            },
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 14.dp,
                        top = 10.dp,
                        end = 14.dp,
                        bottom = 10.dp + navigationBarBottom
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // SeekBar 区域
                ReadMenuSeekBarRow(
                    seekProgress = seekProgress,
                    seekMax = seekMax,
                    canGoPrev = canGoPrev,
                    canGoNext = canGoNext,
                    style = style,
                    onPrevClick = {
                        if (callBack.isEpubCoreBook()) {
                            callBack.openPreviousEpubCoreChapter()
                        } else {
                            ReadBook.moveToPrevChapter(upContent = true, toLast = false)
                        }
                    },
                    onNextClick = {
                        if (callBack.isEpubCoreBook()) {
                            callBack.openNextEpubCoreChapter()
                        } else {
                            ReadBook.moveToNextChapter(true)
                        }
                    },
                    onSeekStart = {
                        // 拖拽开始时禁用遮罩点击
                    },
                    onSeekStop = { progress ->
                        when (AppConfig.progressBarBehavior) {
                            "page" -> {
                                if (!callBack.skipToEpubCorePage(progress)) {
                                    ReadBook.skipToPage(progress)
                                }
                            }
                            "chapter" -> {
                                if (confirmSkipToChapter) {
                                    callBack.skipToChapter(progress)
                                } else {
                                    context.alert(appCtx.getString(R.string.ui_book_confirm_chapter_jump), appCtx.getString(R.string.ui_book_jump_this_chapter)) {
                                        yesButton {
                                            confirmSkipToChapter = true
                                            callBack.skipToChapter(progress)
                                        }
                                        noButton { upSeekBar() }
                                        onCancelled { upSeekBar() }
                                    }
                                }
                            }
                        }
                    }
                )

                // 亮度区
                if (showBrightness) {
                    ReadMenuBrightnessRow(
                        brightness = AppConfig.readBrightness,
                        isAuto = brightnessAuto,
                        showBrightnessView = true,
                        style = style,
                        onAutoClick = {
                            context.putPrefBoolean(PreferKey.brightnessAuto, !brightnessAuto)
                            upBrightnessState()
                        },
                        onBrightnessChange = { setScreenBrightness(it.toFloat()) },
                        onBrightnessStop = { AppConfig.readBrightness = it }
                    )
                }

                // 按钮区
                if (hasButtons) {
                    ReadMenuButtonGrid(
                        firstRow = layout.firstRow,
                        secondRow = layout.secondRow,
                        customButtonMetadata = customButtonMetadata,
                        autoPageActive = autoPageActive,
                        isNightTheme = AppConfig.isNightTheme,
                        style = style,
                        onClick = { ref -> handleMenuButtonClick(ref) },
                        onLongClick = { ref -> handleMenuButtonLongClick(ref) }
                    )
                }
            }
        }
    }

    @Composable
    private fun rememberReadMenuStyle(): AppDialogStyle {
        val bgColor = if (immersiveMenu && !ReadBook.usesPageTemplate()) {
            runCatching {
                ReadBookConfig.durConfig.curBgStr().toColorInt()
            }.getOrDefault(context.bottomBackground)
        } else {
            context.bottomBackground
        }
        return rememberReaderMenuDialogStyle(bgColor)
    }
    // endregion

    // region Button handling
    private fun updateButtonLayout() {
        val layout = ReadMenuButtonConfig.load(context)
        customButtonMetadata = if ((layout.firstRow + layout.secondRow).any { it.type == ReadMenuButtonConfig.TYPE_CUSTOM }) {
            appDb.readMenuCustomButtonDao.all().associateBy { it.id }
        } else {
            emptyMap()
        }
        buttonLayout = layout
    }

    private fun handleMenuButtonClick(ref: ReadMenuButtonConfig.ButtonRef) {
        if (callBack.isEpubCoreBook() &&
            !ReadMenuButtonConfig.supportsDirectReader(
                ref,
                allowReplaceRules = callBack.supportsReplaceRules(),
                templateOnly = ReadBook.usesPageTemplate()
            )
        ) return
        when (ref.type) {
            ReadMenuButtonConfig.TYPE_BUILTIN -> handleBuiltinButtonClick(ref.id)
            ReadMenuButtonConfig.TYPE_CUSTOM -> ref.id.toLongOrNull()?.let {
                callBack.runCustomReadMenuButton(it)
            }
        }
    }

    private fun handleBuiltinButtonClick(id: String) {
        when (id) {
            ReadMenuButtonConfig.Builtin.SEARCH -> runMenuOut {
                callBack.openSearchActivity(null)
            }
            ReadMenuButtonConfig.Builtin.AUTO_PAGE -> runMenuOut {
                callBack.autoPage()
            }
            ReadMenuButtonConfig.Builtin.REPLACE_RULE -> callBack.openReplaceRule()
            ReadMenuButtonConfig.Builtin.NIGHT_THEME -> {
                AppConfig.isNightTheme = !AppConfig.isNightTheme
                ThemeConfig.applyDayNight(context)
                callBack.onNightThemeChanged()
            }
            ReadMenuButtonConfig.Builtin.CATALOG -> runMenuOut {
                callBack.openChapterList()
            }
            ReadMenuButtonConfig.Builtin.READ_ALOUD -> runMenuOut {
                callBack.showReadAloudDialog()
            }
            ReadMenuButtonConfig.Builtin.READ_STYLE -> runMenuOut {
                callBack.showReadStyle()
            }
            ReadMenuButtonConfig.Builtin.SETTING -> runMenuOut {
                callBack.showMoreSetting()
            }
            ReadMenuButtonConfig.Builtin.READ_ASSISTANT -> runMenuOut {
                callBack.openReadAssistant()
            }
            ReadMenuButtonConfig.Builtin.AI_SUMMARY -> runMenuOut {
                callBack.openReadAiSummary()
            }
            ReadMenuButtonConfig.Builtin.PARAGRAPH_RULES -> runMenuOut {
                callBack.showParagraphRuleQuickDialog()
            }
            ReadMenuButtonConfig.Builtin.BUBBLE -> runMenuOut {
                callBack.showBubbleQuickSwitch()
            }
            ReadMenuButtonConfig.Builtin.CHARACTERS -> runMenuOut {
                callBack.openBookCharacters()
            }
        }
    }

    private fun handleMenuButtonLongClick(ref: ReadMenuButtonConfig.ButtonRef): Boolean {
        return when {
            ref.type == ReadMenuButtonConfig.TYPE_BUILTIN &&
                    ref.id == ReadMenuButtonConfig.Builtin.READ_ALOUD -> {
                runMenuOut { callBack.showReadAloudDialog() }
                true
            }
            ref.type == ReadMenuButtonConfig.TYPE_CUSTOM -> {
                ref.id.toLongOrNull()?.let { id ->
                    runMenuOut { callBack.loginCustomReadMenuButton(id) }
                }
                true
            }
            else -> false
        }
    }
    // endregion

    // region Source popup (handled by ReadMenuTopBar DropdownMenu)
    // endregion

    // region Chapter click
    private fun handleChapterClick() {
        if (ReadBook.isLocalBook) return
        val url = currentChapterUrl?.trim().orEmpty().takeIf { it.isNotBlank() } ?: return
        Coroutine.async(Coroutine.defaultScope) {
            context.startActivity<WebViewActivity> {
                val bookSource = ReadBook.bookSource
                putExtra("title", currentChapterName)
                putExtra("url", url)
                putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                putExtra("sourceName", bookSource?.bookSourceName)
                putExtra("sourceType", bookSource?.getSourceType())
            }
        }
    }

    private fun handleChapterLongClick() {
        if (ReadBook.isLocalBook) return
        val url = currentChapterUrl?.trim().orEmpty().takeIf { it.isNotBlank() } ?: return
        context.alert(R.string.open_fun) {
            setMessage(R.string.use_browser_open)
            okButton { context.openUrl(url) }
            noButton()
        }
    }
    // endregion

    // region Custom button
    private fun handleCustomButtonClick() {
        val book = ReadBook.book ?: return
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
        activity?.let { act ->
            SourceCallBack.callBackBtn(
                act, SourceCallBack.CLICK_CUSTOM_BUTTON,
                ReadBook.bookSource, book, chapter, BookType.text
            )
        }
    }

    private fun handleCustomButtonLongClick() {
        val book = ReadBook.book ?: return
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
        activity?.let { act ->
            SourceCallBack.callBackBtn(
                act, SourceCallBack.LONG_CLICK_CUSTOM_BUTTON,
                ReadBook.bookSource, book, chapter, BookType.text
            )
        }
    }
    // endregion

    // region Utility
    private fun updateColorConfig() {
        colorTick++
    }

    private fun getCurrentBrightness(ctx: Context): Int {
        return try {
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Settings.SettingNotFoundException) {
            -1
        }
    }

    private fun resolveChapterUrl(chapter: io.legado.app.data.entities.BookChapter): String? {
        val candidates = listOf(
            chapter.url,
            runCatching { chapter.getAbsoluteURL() }.getOrNull(),
            chapter.baseUrl,
            ReadBook.book?.bookUrl
        )
        return candidates.asSequence()
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() }
    }
    // endregion

    interface CallBack {
        fun autoPage()
        fun openReplaceRule()
        fun openChapterList()
        fun openSearchActivity(searchWord: String?)
        fun openSourceEditActivity()
        fun openBookInfoActivity()
        fun returnToBookshelf()
        fun showReadStyle()
        fun showMoreSetting()
        fun openReadAiSummary()
        fun showReadAloudDialog()
        fun upSystemUiVisibility()
        fun onClickReadAloud()
        fun showHelp()
        fun showLogin()
        fun payAction()
        fun disableSource()
        fun skipToChapter(index: Int)
        fun onMenuShow()
        fun onMenuHide()
        /** Called after the menu exit animation has made the host view invisible. */
        fun onMenuHidden() = Unit
        fun epubCorePageCount(): Int = 0
        fun epubCorePageIndex(): Int = 0
        fun skipToEpubCorePage(index: Int): Boolean = false
        fun isEpubCoreBook(): Boolean = false
        fun supportsReplaceRules(): Boolean = !isEpubCoreBook()
        fun epubCoreChapterTitle(): String? = null
        fun epubCoreChapterUrl(): String? = null
        fun openPreviousEpubCoreChapter() = Unit
        fun openNextEpubCoreChapter() = Unit
        fun onNightThemeChanged() = Unit
        fun isLibraryCloudEnabled(): Boolean = false
        fun libraryCloudState(): LibraryCloudState = LibraryCloudState.DISABLED
        fun showLibraryCloudChapters(refresh: Boolean) = Unit
        fun showLibraryCloudDebug() = Unit
        fun openReadAssistant() = Unit
        fun showParagraphRuleQuickDialog() = Unit
        fun showBubbleQuickSwitch() = Unit
        fun openBookCharacters() = Unit
        fun runCustomReadMenuButton(id: Long) = Unit
        fun editCustomReadMenuButton(id: Long) = Unit
        fun loginCustomReadMenuButton(id: Long) = Unit
        fun refreshContent()
        fun changeSource()
        fun changeSourceSingle()
        fun showRefreshOptions()
        fun showCacheDialog()
        fun addBookmark()
        fun editContent()
        fun showPageAnim()
        fun editMenu()
        fun updateToc()
        fun reverseContent()
        fun changeReplaceRuleState()
        fun showReSegment()
        fun showSameTitleRemoved()
        fun showImageStyle()
        fun showHighlightRuleManage()
        fun showParagraphRuleManage()
        fun showEffectiveReplaces()
        fun showLog()
        fun showGetProgress()
        fun showCoverProgress()

        /** 评论/段评设置（阅读页三点菜单真实入口） */
        fun toggleCacheReview() = Unit
        fun showReviewOpenModeDialog() = Unit
        fun showReviewSnapshotTtlDialog() = Unit
        fun togglePreheatWifiOnly() = Unit
        fun toggleOfflineReviewMode() = Unit

        /** 离线评论：发送 / 导出 / 清除（三点菜单只在有记录时显示这三项） */
        fun sendOfflineReviews() = Unit
        fun exportOfflineReviews() = Unit
        fun clearOfflineReviews() = Unit
    }
}
