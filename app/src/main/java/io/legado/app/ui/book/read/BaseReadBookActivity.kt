package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.app.DatePickerDialog
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
import io.legado.app.databinding.DialogDownloadChoiceBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogSimulatedReadingBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.cache.CacheCoordinator
import io.legado.app.help.cache.CacheRequestSource
import io.legado.app.help.storage.ReaderDataRepair
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplateStore
import io.legado.app.ui.book.read.config.BgTextConfigDialog
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.config.PaddingConfigDialog
import io.legado.app.ui.book.read.config.PageKeyDialog
import io.legado.app.ui.book.read.config.ReaderTemplateDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.glass.readerGlassMenuWantsE2e
import io.legado.app.ui.glass.readerGlassNavBarColor
import io.legado.app.ui.glass.readerNavStripYieldsToGlass
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.find
import io.legado.app.utils.getPrefString
import io.legado.app.utils.gone
import io.legado.app.utils.isHuaweiSystemDevice
import io.legado.app.utils.isTv
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import splitties.init.appCtx

/**
 * 阅读界面
 */
abstract class BaseReadBookActivity :
    VMBaseActivity<ActivityBookReadBinding, ReadBookViewModel>(imageBg = false) {

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
            val statusBarColor =
                if (AppConfig.readBarStyleFollowPage
                    && ReadBookConfig.durConfig.curBgType() == 0
                    || useBgMeanColor
                ) {
                    ReadBookConfig.bgMeanColor
                } else {
                    ThemeStore.statusBarColor(this, AppConfig.isTransparentStatusBar)
                }
            setLightStatusBar(ColorUtils.isColorLight(statusBarColor))
        }
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
        val statusBarColor =
            if (AppConfig.readBarStyleFollowPage
                && ReadBookConfig.durConfig.curBgType() == 0
                || useBgMeanColor
            ) {
                ReadBookConfig.bgMeanColor
            } else {
                ThemeStore.statusBarColor(this, AppConfig.isTransparentStatusBar)
            }
        setLightStatusBar(ColorUtils.isColorLight(statusBarColor))
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

    @SuppressLint("InflateParams", "SetTextI18n")
    fun showDownloadDialog() {
        ReadBook.book?.let { book ->
            // 起点取内存里的实时阅读进度：book.durChapterIndex 是落库进度，
            // 本次会话跳章后未必已同步（仍显示「第 1 章」）。评论增量缓存
            // 跟随正文缓存任务的章节集合，起点错了评论也会从第一章开始抓。
            // 末章 clamp：目录刷新变短后 durChapterIndex 可能越界，反向区间会让提交直接报错
            val startIndex = maxOf(ReadBook.durChapterIndex, book.durChapterIndex)
                .coerceAtMost((book.totalChapterNum - 1).coerceAtLeast(0))
            alert(titleResource = R.string.offline_cache) {
                val alertBinding = DialogDownloadChoiceBinding.inflate(layoutInflater).apply {
                    editStart.setText((startIndex + 1).toString())
                    editEnd.setText(book.totalChapterNum.toString())
                }
                customView { alertBinding.root }
                okButton {
                    alertBinding.run {
                        val start = editStart.text!!.toString().let {
                            if (it.isEmpty()) 0 else it.toInt()
                        }
                        val end = editEnd.text!!.toString().let {
                            if (it.isEmpty()) book.totalChapterNum else it.toInt()
                        }
                        runCatching {
                            CacheCoordinator.submitBookDownload(
                                book = book,
                                chapterIndexes = (start - 1)..(end - 1),
                                source = CacheRequestSource.READER,
                                reviewIncremental = true,
                            )
                        }.onFailure {
                            toastOnUi(R.string.error)
                        }
                    }
                }
                cancelButton()
            }
        }
    }

    fun showSimulatedReading() {
        val book = ReadBook.book ?: return
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val alertBinding = DialogSimulatedReadingBinding.inflate(layoutInflater).apply {
            srEnabled.isChecked = book.getReadSimulating()
            editStart.setText(book.getStartChapter().toString())
            editNum.setText(book.getDailyChapters().toString())
            startDate.setText(book.getStartDate()?.format(dateFormatter))
            startDate.isFocusable = false // 设置为false，不允许获得焦点
            startDate.isCursorVisible = false // 不显示光标
            startDate.setOnClickListener {
                // 获取当前日期
                val localStartDate = LocalDate.parse(startDate.text)
                // 创建 DatePickerDialog
                val datePickerDialog = DatePickerDialog(
                    root.context,
                    { _, yy, mm, dayOfMonth ->
                        // 使用Java 8的日期和时间API来格式化日期
                        val date = LocalDate.of(yy, mm + 1, dayOfMonth) // Java 8的LocalDate，月份从1开始
                        val formattedDate = date.format(dateFormatter)
                        startDate.setText(formattedDate)
                    }, localStartDate.year,
                    localStartDate.monthValue - 1,
                    localStartDate.dayOfMonth
                )
                datePickerDialog.show()
            }
        }
        alert(titleResource = R.string.simulated_reading) {
            customView { alertBinding.root }
            okButton {
                alertBinding.run {
                    val start = editStart.text!!.toString().let {
                        if (it.isEmpty()) 0 else it.toInt()
                    }
                    val num = editNum.text!!.toString().let {
                        if (it.isEmpty()) book.totalChapterNum else it.toInt()
                    }
                    val enabled = srEnabled.isChecked
                    val date = startDate.text!!.toString().let {
                        if (it.isEmpty()) LocalDate.now()
                        else LocalDate.parse(it, dateFormatter)
                    }
                    book.setStartDate(date)
                    book.setDailyChapters(num)
                    book.setStartChapter(start)
                    book.setReadSimulating(enabled)
                    book.save()
                    ReadBook.clearTextChapter()
                    viewModel.initData(intent)
                }
            }
            cancelButton()
        }
    }

    fun showCharsetConfig() {
        alert(R.string.set_charset) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "charset"
                editView.setFilterValues(charsets)
                editView.setText(ReadBook.book?.charset)
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    ReadBook.setCharset(it)
                }
            }
            cancelButton()
        }
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
        selector(R.string.page_anim, items.map { it.first }) { _, i ->
            val previousPageAnim = ReadBook.pageAnim()
            val selected = items.getOrNull(i)?.second
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
