package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst.charsets
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.ActivityBookReadBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.cache.CacheCoordinator
import io.legado.app.help.cache.CacheRequestSource
import io.legado.app.help.storage.ReaderDataRepair
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplateStore
import io.legado.app.ui.book.read.config.BgTextConfigDialog
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.config.PaddingConfigDialog
import io.legado.app.ui.book.read.config.PageKeyDialog
import io.legado.app.ui.book.read.config.ReaderDownloadDialog
import io.legado.app.ui.book.read.config.ReaderTemplateDialog
import io.legado.app.ui.book.read.config.SimulatedReadingDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.glass.readerGlassMenuWantsE2e
import io.legado.app.ui.glass.readerGlassNavBarColor
import io.legado.app.ui.glass.readerNavStripYieldsToGlass
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.compose.showComposeSuggestionTextInputDialog
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.dpToPx
import io.legado.app.utils.find
import io.legado.app.utils.getPrefString
import io.legado.app.utils.gone
import io.legado.app.utils.isHuaweiSystemDevice
import io.legado.app.utils.isTv
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.statusBarHeight
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.time.format.DateTimeFormatter
import splitties.init.appCtx

internal data class PopupAnchorRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/**
 * 阅读界面
 */
abstract class BaseReadBookActivity :
    VMBaseActivity<ActivityBookReadBinding, ReadBookViewModel>(imageBg = false) {

    // 阅读器正文用 PageView 的 vwStatusBar + 自身的 systemUiVisibility / e2e 逻辑
    // 完整管理状态栏（含玻璃菜单、朗读全面板两种沉浸态），跳过全局 inset + backdrop，
    // 避免透明栏与实色 backdrop 同屏或内容二次下移。属「覆盖状态栏」开关例外页。
    override val applyGlobalStatusBarInsetEnabled: Boolean
        get() = false

    override val binding by viewBinding(ActivityBookReadBinding::inflate)
    override val viewModel by viewModels<ReadBookViewModel>()
    protected val menuLayoutIsVisible
        get() = bottomDialog > 0 || binding.readMenu.isVisible || binding.searchMenu.bottomMenuVisible

    var bottomDialog = 0
        set(value) {
            if (field != value) {
                field = value
                onBottomDialogChange()
            }
        }
    internal var readerMenuPopupHandle: ModernActionPopup.Handle? = null
    internal var readerOverflowAnchor: PopupAnchorRect? = null
    internal var readerRefreshAnchor: PopupAnchorRect? = null
    private val selectBookFolderResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ReadBook.book?.let { book ->
                FileDoc.fromUri(uri, true).find(book.originName)?.let { doc ->
                    book.bookUrl = doc.uri.toString()
                    book.save()
                    viewModel.loadChapterList(book)
                } ?: ReadBook.upMsg(appCtx.getString(R.string.ui_book_file_not_found))
            }
        } ?: ReadBook.upMsg(appCtx.getString(R.string.ui_book_no_access_permission))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ReaderDataRepair.repairOnAppStart()
        ReadBook.msg = null
        setOrientation()
        upLayoutInDisplayCutoutMode()
        super.onCreate(savedInstanceState)
        binding.navigationBar.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams {
                height = insets.bottom
            }
            // 沉浸阅读收起菜单时导航条隐藏、弹出菜单时复现：高度随 inset 回填的
            // 那一刻可能晚于 upNavigationBarColor，这里补一次重涂，否则条带停留在
            // 高度未就绪时的均值回退色。
            upNavigationBar()
            windowInsets
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.navigationBar.setBackgroundColor(bottomBackground)
        viewModel.permissionDenialLiveData.observe(this) {
            selectBookFolderResult.launch {
                mode = HandleFileContract.DIR_SYS
                title = appCtx.getString(R.string.ui_book_select_folder_containing_book)
            }
        }
        if (!LocalConfig.readHelpVersionIsLast) {
            if (isTv) {
                showCustomPageKeyConfig()
            } else {
                showClickRegionalConfig()
            }
        }
    }

    private fun onBottomDialogChange() {
        when (bottomDialog) {
            0 -> onMenuHide()
            1 -> onMenuShow()
        }
    }

    open fun onMenuShow() {

    }

    open fun onMenuHide() {

    }

    fun showPaddingConfig() {
        if (ReadBook.usesPageTemplate()) showDialogFragment<ReaderTemplateDialog>()
        else showDialogFragment<PaddingConfigDialog>()
    }

    fun showBgTextConfig() {
        if (ReadBook.usesPageTemplate()) showDialogFragment<ReaderTemplateDialog>()
        else showDialogFragment<BgTextConfigDialog>()
    }

    fun showClickRegionalConfig() {
        showDialogFragment<ClickActionConfigDialog>()
    }

    private fun showCustomPageKeyConfig() {
        PageKeyDialog(this).show()
    }

    /**
     * 屏幕方向
     */
    @SuppressLint("SourceLockedOrientationActivity")
    fun setOrientation() {
        when (AppConfig.screenOrientation) {
            "0" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            "1" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "2" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "3" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            "4" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            "5" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        }
    }

    /**
     * 更新状态栏,导航栏
     */
    fun upSystemUiVisibility(
        isInMultiWindow: Boolean,
        toolBarHide: Boolean = true,
        useBgMeanColor: Boolean = false
    ) {
        upLayoutInDisplayCutoutMode()
        if (!toolBarHide && readerGlassMenuWantsE2e(isInMultiWindow, readGlassMenuActive())) {
            applyGlassMenuSystemBars(useBgMeanColor)
            return
        }
        restoreDecorFitsSystemWindows()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.run {
                if (toolBarHide && ReadBookConfig.hideNavigationBar) {
                    hide(WindowInsets.Type.navigationBars())
                } else {
                    show(WindowInsets.Type.navigationBars())
                }
                if (toolBarHide && ReadBookConfig.hideStatusBar) {
                    hide(WindowInsets.Type.statusBars())
                } else {
                    show(WindowInsets.Type.statusBars())
                }
            }
        }
        upSystemUiVisibilityO(isInMultiWindow, toolBarHide)
        if (toolBarHide) {
            if (ReadBook.usesPageTemplate()) {
                @Suppress("DEPRECATION")
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                val template = EpubReaderTemplateStore.ensureReaderSelection()
                val darkSky = template.css == EpubReaderTemplateStore.defaultTemplate.css
                setLightStatusBar(!darkSky && ReadBookConfig.durConfig.curStatusIconDark())
            } else {
                setLightStatusBar(ReadBookConfig.durConfig.curStatusIconDark())
            }
        } else {
            setLightStatusBar(resolveMenuStatusBarLight(useBgMeanColor))
        }
    }

    /**
     * 菜单可见时的状态栏图标明暗极性。
     *
     * 悬浮圆角顶栏开启时，顶栏下沉至状态栏下方，状态栏区域直接透出阅读正文背景，
     * 图标极性必须跟正文走，避免浅色主题+深色阅读背景（或反向）导致状态栏图标隐身。
     */
    private fun resolveMenuStatusBarLight(useBgMeanColor: Boolean): Boolean {
        val topBarFloating = binding.readMenu.isVisible && binding.readMenu.isTopBarFloating
        val usesPageTemplate = ReadBook.usesPageTemplate()
        val darkSkyTemplate = if (topBarFloating && usesPageTemplate) {
            EpubReaderTemplateStore.ensureReaderSelection().css == EpubReaderTemplateStore.defaultTemplate.css
        } else {
            false
        }
        return resolveReadMenuStatusBarLight(
            topBarFloating = topBarFloating,
            usesPageTemplate = usesPageTemplate,
            darkSkyTemplate = darkSkyTemplate,
            pageStatusIconDark = ReadBookConfig.durConfig.curStatusIconDark(),
            readBarStyleFollowPage = AppConfig.readBarStyleFollowPage,
            isColorBgType = ReadBookConfig.durConfig.curBgType() == 0,
            useBgMeanColor = useBgMeanColor,
            bgMeanColorIsLight = ColorUtils.isColorLight(ReadBookConfig.bgMeanColor),
            themeStatusBarIsLight = ColorUtils.isColorLight(
                ThemeStore.statusBarColor(this, AppConfig.isTransparentStatusBar)
            )
        )
    }

    /** 底部 / 搜索两个菜单任一处于玻璃激活态。玻璃激活蕴含菜单可见（收起即解绑）。 */
    private fun readGlassMenuActive(): Boolean =
        binding.readMenu.isBottomGlassActive || binding.searchMenu.isBottomGlassActive

    /**
     * 玻璃菜单 / 朗读全面板曾把窗口切到 edge-to-edge（Window 级
     * `setDecorFitsSystemWindows(false)` 是粘性的，仅 API 30+ 存在此旗标）。
     * 回到常规路径时复位为 `true`，即 Activity 初始已知良好状态——否则玻璃菜单
     * 开过一次后，API 30-34 上 `statusBarColor` / `navigationBarColor` 将被
     * 系统永久忽略，阅读页正文条带配色失效。API 35+ 强制 e2e，复位为无害空操作。
     */
    protected var windowForcedEdgeToEdge = false
        protected set

    private fun restoreDecorFitsSystemWindows() {
        if (!windowForcedEdgeToEdge) return
        windowForcedEdgeToEdge = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }
    }

    /**
     * 玻璃菜单激活时的系统条带：窗口切 edge-to-edge。
     *
     * 真机第六轮根因：常规路径（`upSystemUiVisibilityO`）只有
     * `LAYOUT_FULLSCREEN` 没有 `LAYOUT_HIDE_NAVIGATION`，decor 内容被布局在
     * 导航栏上方，屏幕底部导航栏高度的一段不在应用视图树内，只能由窗口级
     * navColor 上色——面板、玻璃层、App 内条带都画不到（前五轮在配色上打转
     * 的根因）。这里与朗读全面板（`applyReadAloudPlayerSystemBars`）同一先例：
     * `setDecorFitsSystemWindows(false)` + 三个 `LAYOUT_*` 旗标，让玻璃层真正
     * 铺到屏幕底；菜单关闭后走常规路径重设旗标并重涂 navColor，窗口保持
     * 非收合（PageView 的 `vw_navigation_bar` 内边距已处理内容避让）。
     * Window 级 `setDecorFitsSystemWindows(false)` 是粘性的，故置位
     * `windowForcedEdgeToEdge`，由常规路径的 `restoreDecorFitsSystemWindows`
     * 在菜单关闭后复位。仅在非分屏、且菜单可见（`toolBarHide` = false）时走本分支；
     * 分屏回退到 `upNavigationBarColor` 的玻璃近似配色。
     */
    @Suppress("DEPRECATION")
    private fun applyGlassMenuSystemBars(useBgMeanColor: Boolean) {
        windowForcedEdgeToEdge = true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.run {
                show(WindowInsets.Type.navigationBars())
                show(WindowInsets.Type.statusBars())
            }
        }
        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setLightStatusBar(resolveMenuStatusBarLight(useBgMeanColor))
    }

    @Suppress("DEPRECATION")
    private fun upSystemUiVisibilityO(
        isInMultiWindow: Boolean,
        toolBarHide: Boolean = true
    ) {
        var flag = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_IMMERSIVE
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        if (!isInMultiWindow) {
            flag = flag or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        if (ReadBookConfig.hideNavigationBar) {
            flag = flag or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (toolBarHide) {
                flag = flag or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            }
        }
        if (ReadBookConfig.hideStatusBar && toolBarHide) {
            flag = flag or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
        window.decorView.systemUiVisibility = flag
    }

    override fun upNavigationBarColor() {
        upNavigationBar()
        when {
            // 系统导航栏在 App 内容之外（玻璃层画不到），仅影响 Android 14 及以下
            // 非 e2e 设备：玻璃菜单弹出时把面板的最终色算出来铺上去，磨砂档避免
            // 「面板偏透明 + 导航栏偏白」两层。Android 15+ 强制 e2e 会忽略此色，
            // 那些设备上靠条带让位（见 upNavigationBar）保证观感。
            binding.readMenu.isBottomGlassActive -> setNavigationBarColorAuto(
                readerGlassNavBarColor(
                    tintAlpha = binding.readMenu.glassTintAlpha,
                    backdropColor = ReadBookConfig.bgMeanColor
                )
            )
            binding.searchMenu.isBottomGlassActive -> setNavigationBarColorAuto(
                readerGlassNavBarColor(
                    tintAlpha = binding.searchMenu.glassTintAlpha,
                    backdropColor = ReadBookConfig.bgMeanColor
                )
            )
            binding.readMenu.isVisible -> super.upNavigationBarColor()
            binding.searchMenu.bottomMenuVisible -> super.upNavigationBarColor()
            bottomDialog > 0 -> super.upNavigationBarColor()
            !AppConfig.immNavigationBar -> super.upNavigationBarColor()
            else -> setNavigationBarColorAuto(ReadBookConfig.bgMeanColor)
        }
    }

    /**
     * App 内实色导航条压在玻璃层之上（布局声明在 `read_menu` 之后）。
     *
     * 玻璃底部菜单激活时让位隐藏：玻璃层已经把底边折射区整段推出屏幕（见
     * `glassBottomEdgeExtensionPx`），页面导航条区域又没有内容（正文与页脚都
     * 约束在它上方），玻璃盖上去就是连续磨砂到屏幕底——条带无论怎么画都追不上
     * 活采样（第一至四轮真机四连败的教训），不如不画。搜索菜单的玻璃面板悬在
     * 导航条上方、不伸进导航条区域，条带保留主题底栏色兜底。
     */
    @SuppressLint("RtlHardcoded")
    private fun upNavigationBar() {
        val readMenuGlassActive = readerNavStripYieldsToGlass(
            glassActive = binding.readMenu.isBottomGlassActive,
            menuVisible = binding.readMenu.isVisible
        )
        if (!readMenuGlassActive) {
            binding.navigationBar.setBackgroundColor(bottomBackground)
        }
        binding.navigationBar.gone(readMenuGlassActive || !menuLayoutIsVisible)
    }

    /**
     * 保持亮屏
     */
    fun keepScreenOn(on: Boolean) {
        val isScreenOn =
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (on == isScreenOn) return
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * 适配刘海
     */
    private fun upLayoutInDisplayCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val mode = if (ReadBook.usesPageTemplate() || ReadBookConfig.readBodyToLh || isHuaweiSystemDevice) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
            if (window.attributes.layoutInDisplayCutoutMode != mode) {
                window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = mode }
            }
        }
    }

    internal fun showReaderActionPopup(
        actions: List<ModernActionPopup.Action>,
        anchor: PopupAnchorRect? = readerOverflowAnchor
    ) {
        val resolved = anchor ?: defaultTopRightPopupAnchor()
        readerMenuPopupHandle = ModernActionPopup.show(
            context = this,
            anchorLeft = resolved.left,
            anchorTop = resolved.top,
            anchorRight = resolved.right,
            anchorBottom = resolved.bottom,
            actions = actions,
            previousPopup = readerMenuPopupHandle
        )
    }

    private fun defaultTopRightPopupAnchor(): PopupAnchorRect {
        val screenWidth = window?.decorView?.width?.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val sizePx = 44.dpToPx()
        val right = (screenWidth - 12.dpToPx()).coerceAtLeast(sizePx)
        val left = (right - sizePx).coerceAtLeast(0)
        val top = statusBarHeight + 8.dpToPx()
        return PopupAnchorRect(
            left = left,
            top = top,
            right = right,
            bottom = top + sizePx
        )
    }

    fun showDownloadDialog() {
        ReadBook.book?.let { book ->
            // 起点取内存里的实时阅读进度：book.durChapterIndex 是落库进度，
            // 本次会话跳章后未必已同步（仍显示「第 1 章」）。评论增量缓存
            // 跟随正文缓存任务的章节集合，起点错了评论也会从第一章开始抓。
            // 末章 clamp：目录刷新变短后 durChapterIndex 可能越界，反向区间会让提交直接报错
            val startIndex = maxOf(ReadBook.durChapterIndex, book.durChapterIndex)
                .coerceAtMost((book.totalChapterNum - 1).coerceAtLeast(0))
            showDialogFragment(
                ReaderDownloadDialog.create(
                    startChapter = startIndex + 1,
                    endChapter = book.totalChapterNum
                ) { start, end ->
                    runCatching {
                        CacheCoordinator.submitBookDownload(
                            book = book,
                            chapterIndexes = (start - 1)..(end - 1),
                            source = CacheRequestSource.READER,
                        )
                    }.onFailure {
                        toastOnUi(R.string.error)
                    }
                }
            )
        }
    }

    fun showSimulatedReading() {
        val book = ReadBook.book ?: return
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        showDialogFragment(
            SimulatedReadingDialog.create(
                enabled = book.getReadSimulating(),
                startDate = book.getStartDate()?.format(dateFormatter).orEmpty(),
                startChapter = book.getStartChapter(),
                dailyChapters = book.getDailyChapters(),
                totalChapters = book.totalChapterNum
            ) { enabled, date, start, num ->
                book.setStartDate(date)
                book.setDailyChapters(num)
                book.setStartChapter(start)
                book.setReadSimulating(enabled)
                book.save()
                ReadBook.clearTextChapter()
                viewModel.initData(intent)
            }
        )
    }

    fun showCharsetConfig() {
        showComposeSuggestionTextInputDialog(
            title = getString(R.string.set_charset),
            hint = "charset",
            initialValue = ReadBook.book?.charset.orEmpty(),
            suggestions = charsets.toList(),
            onPositive = { ReadBook.setCharset(it) }
        )
    }

    fun showPageAnimConfig(success: (previousPageAnim: Int) -> Unit) {
        if (ReadBook.usesPageTemplate()) {
            val id = ReadBookConfig.config.readerTemplateId.ifEmpty { EpubReaderTemplateStore.defaultId }
            if (EpubReaderTemplateStore.resolve(id)?.isScrolling == true) {
                showDialogFragment<ReaderTemplateDialog>()
                return
            }
        }
        val items = listOf(
            getString(R.string.btn_default_s) to null,
            getString(R.string.page_anim_cover) to PageAnim.coverPageAnim,
            getString(R.string.page_anim_linked_cover) to PageAnim.linkedCoverPageAnim,
            getString(R.string.page_anim_slide) to PageAnim.slidePageAnim,
            getString(R.string.page_anim_simulation) to PageAnim.simulationPageAnim,
            getString(R.string.page_anim_scroll) to PageAnim.scrollPageAnim,
            getString(R.string.page_anim_none) to PageAnim.noAnim
        )
        val currentSelected: Int? = if (ReadBook.usesPageTemplate()) {
            val templateId = ReadBookConfig.config.readerTemplateId.ifEmpty { EpubReaderTemplateStore.defaultId }
            EpubReaderTemplateStore.pageAnimation(templateId)
        } else {
            ReadBook.book?.config?.pageAnim?.takeIf { it >= 0 }
        }
        showReaderActionPopup(
            actions = items.map { (label, selected) ->
                ModernActionPopup.Action(
                    title = label,
                    checked = selected == currentSelected
                ) {
                    val previousPageAnim = ReadBook.pageAnim()
                    if (ReadBook.usesPageTemplate()) {
                        val templateId = ReadBookConfig.config.readerTemplateId.ifEmpty { EpubReaderTemplateStore.defaultId }
                        EpubReaderTemplateStore.savePageAnimation(templateId, selected)
                    } else {
                        ReadBook.book?.setPageAnim(selected ?: -1)
                    }
                    if (ReadBook.pageAnim() != previousPageAnim) {
                        success(previousPageAnim)
                    }
                }
            }
        )
    }

    fun isPrevKey(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return false
        }
        val prevKeysStr = getPrefString(PreferKey.prevKeys)
        return prevKeysStr?.split(",")?.contains(keyCode.toString()) ?: false
    }

    fun isNextKey(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return false
        }
        val nextKeysStr = getPrefString(PreferKey.nextKeys)
        return nextKeysStr?.split(",")?.contains(keyCode.toString()) ?: false
    }
}

/**
 * 纯 JVM 可测：阅读页菜单可见时的状态栏图标明暗极性判定。
 */
internal fun resolveReadMenuStatusBarLight(
    topBarFloating: Boolean,
    usesPageTemplate: Boolean,
    darkSkyTemplate: Boolean,
    pageStatusIconDark: Boolean,
    readBarStyleFollowPage: Boolean,
    isColorBgType: Boolean,
    useBgMeanColor: Boolean,
    bgMeanColorIsLight: Boolean,
    themeStatusBarIsLight: Boolean
): Boolean {
    if (topBarFloating) {
        return if (usesPageTemplate) {
            !darkSkyTemplate && pageStatusIconDark
        } else {
            pageStatusIconDark
        }
    }
    return if (readBarStyleFollowPage && isColorBgType || useBgMeanColor) {
        bgMeanColorIsLight
    } else {
        themeStatusBarIsLight
    }
}
