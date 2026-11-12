package io.legado.app.base

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.view.Display
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.viewbinding.ViewBinding
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.Theme
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiMenuTypefaceDeep
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.glass.GlassSurface
import io.legado.app.ui.glass.wallpaperGlassSignature
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyOpenTint
import io.legado.app.utils.applyTint
import io.legado.app.utils.disableAutoFill
import io.legado.app.utils.fullScreen
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.windowSize

abstract class BaseActivity<VB : ViewBinding>(
    val fullScreen: Boolean = true,
    private val theme: Theme = Theme.Auto,
    private val toolBarTheme: Theme = Theme.Auto,
    private val transparent: Boolean = false,
    private val imageBg: Boolean = true,
    private val showOpenMenuIcon: Boolean = true
) : AppCompatActivity() {

    protected abstract val binding: VB
    private var lastThemeValuesChanged = 0L
    private var lastNightMode = false

    val isInMultiWindow: Boolean
        @SuppressLint("ObsoleteSdkInt")
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                isInMultiWindowMode
            } else {
                false
            }
        }

    /**
     * 本页内容背后是否真实露出壁纸（壁纸域玻璃的采样前提：页面内卡片与对话框共用）。
     *
     * 取值与 [applyRootBackgroundPolicy] 同源：只有「背景图可用的普通页面」根背景
     * 才会被置空、透出壁纸。自己用不透明内容铺满整页的页面（书籍详情封面、视频、
     * 音频播放）必须覆盖为 false——否则从这些页面弹出的对话框、页面内的卡片会采到
     * 用户根本看不见的壁纸。
     */
    open val showsWallpaperBehindContent: Boolean
        get() = imageBg && !AppConfig.isEInkMode && ThemeConfig.hasUsableBgImage(this)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppContextWrapper.wrap(newBase))
    }

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? {
        val view = super.onCreateView(parent, name, context, attrs)
        if (AppConst.menuViewNames.contains(name)) {
            val menuContainer = parent?.parent
            if (menuContainer is FrameLayout) {
                applyPopupMenuSurface(menuContainer, context)
            }
            val menuView = view ?: parent
            menuView?.post {
                menuView.applyUiMenuTypefaceDeep(context)
            }
        }
        return view
    }

    /**
     * 弹出菜单（AppCompat overflow 的 `ListMenuItemView`）容器表面：壁纸域玻璃优先，实底回退。
     *
     * **为什么只改这里就够、且主题资源必须保持原样**：PopupWindow 把主题里的
     * `Style.PopupMenu.android:popupBackground`（`@drawable/bg_popup_action_modern`）
     * 挂在 `PopupBackgroundView`（`parent.parent`，FrameLayout）这**一格**背景上，
     * `View.setBackground` 替换的就是同一格，不存在「叠一层」的问题。因此主题资源保持
     * 原样（它仍是 `preparePopup` 时的初始表面、6dp 内边距与圆角初值的来源），
     * 由本方法决定最终画什么；反过来若把主题 drawable 改成透明而某次弹出没有走到这里
     * （非 `ListMenuItemView` 的自定义菜单），菜单就会变成全透明——这才是要避免的。
     *
     * **采样为什么正确**：菜单窗口的根视图只有菜单那么大，按根视图换算采样矩阵会把整张
     * 壁纸缩进菜单里。[GlassSurface.dialogBackground] 内部固定 `screenAligned = true`：
     * 尺寸取 `displayMetrics`、根原点强制 (0,0)、宿主坐标仍用 `getLocationOnScreen`，
     * 与壁纸位图的构建口径（`ExploreGlassBackdrop.decodeRaw` 按 `displayMetrics` 居中裁剪）
     * 完全一致，因此菜单采到的就是「它此刻压着的那块屏幕区域」。这与对话框走的是
     * 同一个入口（见 `BaseDialogFragment.onViewCreated` 与
     * `AndroidAlertBuilder.windowSurfaceBackground`），不新增任何采样参数。
     *
     * **回退逐值不变**：[GlassSurface.dialogBackground] 在无背景图 / 无玻璃档 / 墨水屏 /
     * 低内存 / 动图纯色底时返回 null，此时走的是**改动前逐字相同**的那一句
     * `background = opaqueRounded(themeCardColorOrDefault(), panelRadius)`
     * ——同一份 drawable、同一个颜色、同一个圆角，连内边距的副作用都不变。
     *
     * **玻璃分支为什么要显式保留内边距**：菜单的 6dp 内边距来自
     * `bg_popup_action_modern` 的 `<padding>`。`View.setBackground` 只在**新** drawable 的
     * `getPadding()` 返回 true 时才会重算内边距（玻璃层不返回 padding，实际上是原样保留），
     * 所以这句还原是保险而非必需——但一旦将来玻璃层开始带 padding，没有它水波纹就会画到
     * 玻璃圆角外沿。回退分支保持不动，才能保证「玻璃不可用时逐值等于今天」。
     *
     * 菜单项自身的 `selectableItemBackground`（按压 / 悬停反馈）属于 item view，
     * 本方法只换容器那一格背景，按构造不会影响它。
     */
    private fun applyPopupMenuSurface(container: View, context: Context) {
        // 每个菜单项都会走一次 onCreateView，同一个容器只该铺一次。
        // 签名用 wallpaperGlassSignature（观感 + 壁纸此刻是否可用）而不是
        // GlassSurface.configSignature：后者不含背景图开关，是本项目已复发三次的坑。
        // 这里之所以今天不会因此出错，是因为 PopupWindow 每次 show 都新建 PopupBackgroundView，
        // 标签天然失效——但口径仍按纪律走，避免将来把去重挪到常驻 View 上时第四次复发。
        val signature = wallpaperGlassSignature(context)
        if (container.getTag(POPUP_MENU_SURFACE_TAG) == signature) return
        container.setTag(POPUP_MENU_SURFACE_TAG, signature)
        val radius = UiCorner.panelRadius(context)
        val glass = GlassSurface.dialogBackground(container, radius)
        if (glass == null) {
            container.background = UiCorner.opaqueRounded(
                context.themeCardColorOrDefault(),
                radius
            )
            return
        }
        val paddingLeft = container.paddingLeft
        val paddingTop = container.paddingTop
        val paddingRight = container.paddingRight
        val paddingBottom = container.paddingBottom
        container.background = glass
        container.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
    }

    @SuppressLint("ObsoleteSdkInt")
    override fun onCreate(savedInstanceState: Bundle?) {
        window.decorView.disableAutoFill()
        initTheme()
        super.onCreate(savedInstanceState)
        lastNightMode = AppConfig.isNightTheme
        applyPreferredRefreshRate()
        setupSystemBar()
        setContentView(binding.root)
        LiveEventBus.get(EventBus.MAIN_THEME_BACKGROUND_CHANGED, Boolean::class.java)
            .observe(this) { isNightTheme ->
                if (isNightTheme == AppConfig.isNightTheme) {
                    lastNightMode = isNightTheme
                    lastThemeValuesChanged = ThemeStore.valuesChanged(this)
                    applyRootBackgroundPolicy()
                    upBackgroundImage()
                }
            }
        if (!AppConfig.isEInkMode) {
            binding.root.applyUiBodyTypefaceDeep(uiTypeface())
        }
        applyRootBackgroundPolicy()
        upBackgroundImage()
        applyTitleBarWallpaperGlass()
        lastThemeValuesChanged = ThemeStore.valuesChanged(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            findViewById<TitleBar>(R.id.title_bar)
                ?.onMultiWindowModeChanged(isInMultiWindowMode, fullScreen)
        }
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }
        observeLiveBus()
        onActivityCreated(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        applyPreferredRefreshRate()
        refreshThemeBackgroundIfChanged()
        // 无条件重判顶栏玻璃：玻璃档位 / 强度 / 描边色 / 壁纸都可能在别的页面改过，
        // 而这些变化不一定进 refreshThemeBackgroundIfChanged 的 valuesChanged 分支
        // （那里只覆盖主题与背景图）。签名未变时 applyWallpaperGlass 只比一次签名就返回
        // （不重建 drawable、不重采壁纸），判定翻否时则会把旧玻璃撤掉——两条都由
        // TitleBar 内部签名去重承担。
        applyTitleBarWallpaperGlass()
    }

    private fun refreshThemeBackgroundIfChanged() {
        val valuesChanged = ThemeStore.valuesChanged(this)
        if (valuesChanged != lastThemeValuesChanged) {
            lastThemeValuesChanged = valuesChanged
            setupSystemBar()
            applyRootBackgroundPolicy()
            upBackgroundImage()
            // 主题 / 背景图 / 夜间模式变了，顶栏玻璃要跟着换一份（签名变化才会重建，
            // 未变化时 applyWallpaperGlass 是空操作）
            applyTitleBarWallpaperGlass()
        }
    }

    /**
     * 让本页 `@id/title_bar` 自动接上壁纸域玻璃。
     *
     * 此前是逐个二级页在 `onActivityCreated` 里手写 `applyWallpaperGlass()`（17 个调用点），
     * 剩下的二级页因此长期停在实底顶栏上——「漏接线」这种缺口靠人工清单是守不住的。
     * 这里改成结构默认：只要布局里的 [TitleBar] 没写 `app:wallpaperGlass="false"`、
     * 且本页真的露出壁纸（[showsWallpaperBehindContent]），就自动 opt-in；
     * 内容域页面（相机预览 / WebView 预览）与 `opaque` 顶栏由判定函数逐条否决。
     *
     * 幂等：同档重复调用不重建 drawable（见 [TitleBar.applyWallpaperGlass]），
     * 因此已手写调用点的页面不会因为多调一次而多付采样开销。反方向也成立：
     * 判定翻否（关背景图 / 切 solid / 开墨水屏）时这条路径会把顶栏恢复成原背景，
     * 所以 `onResume` 可以无条件重判。
     */
    private fun applyTitleBarWallpaperGlass() {
        findViewById<TitleBar>(R.id.title_bar)
            ?.applyAutoWallpaperGlass(showsWallpaperBehindContent)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        findViewById<TitleBar>(R.id.title_bar)
            ?.onMultiWindowModeChanged(isInMultiWindowMode, fullScreen)
        setupSystemBar()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isNightMode = AppConfig.isNightTheme
        val nightModeChanged = isNightMode != lastNightMode
        lastNightMode = isNightMode
        applyPreferredRefreshRate()
        findViewById<TitleBar>(R.id.title_bar)
            ?.onMultiWindowModeChanged(isInMultiWindow, fullScreen)
        setupSystemBar()
        if (nightModeChanged) {
            applyRootBackgroundPolicy()
            upBackgroundImage()
            applyTitleBarWallpaperGlass()
        }
    }

    abstract fun onActivityCreated(savedInstanceState: Bundle?)

    final override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val bool = onCompatCreateOptionsMenu(menu)
        menu.applyTint(this, toolBarTheme)
        return bool
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.applyOpenTint(this, showOpenMenuIcon)
        return super.onMenuOpened(featureId, menu)
    }

    open fun onCompatCreateOptionsMenu(menu: Menu) = super.onCreateOptionsMenu(menu)

    final override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            supportFinishAfterTransition()
            return true
        }
        return onCompatOptionsItemSelected(item)
    }

    open fun onCompatOptionsItemSelected(item: MenuItem) = super.onOptionsItemSelected(item)

    open fun initTheme() {
        when (theme) {
            Theme.Transparent -> setTheme(R.style.AppTheme_Transparent)
            Theme.Dark -> {
                setTheme(R.style.AppTheme_Dark)
                applyInitialWindowBackground()
            }

            Theme.Light -> {
                setTheme(R.style.AppTheme_Light)
                applyInitialWindowBackground()
            }

            else -> {
                if (AppConfig.isNightTheme) {
                    setTheme(R.style.AppTheme_Dark)
                } else {
                    setTheme(R.style.AppTheme_Light)
                }
                applyInitialWindowBackground()
            }
        }
    }

    private fun applyWindowBackgroundColor() {
        ViewCompat.setBackgroundTintList(window.decorView, null)
        window.decorView.setBackgroundColor(ThemeConfig.getFallbackBackgroundColor(this))
    }

    private fun applyInitialWindowBackground() {
        applyWindowBackgroundColor()
    }

    private fun applyRootBackgroundPolicy() {
        if (imageBg && !AppConfig.isEInkMode && ThemeConfig.hasUsableBgImage(this)) {
            ViewCompat.setBackgroundTintList(binding.root, null)
            binding.root.background = null
        }
    }

    open fun upBackgroundImage() {
        if (!imageBg || AppConfig.isEInkMode) {
            applyWindowBackgroundColor()
            return
        }
        val hasBgImage = ThemeConfig.hasUsableBgImage(this)
        try {
            val drawable = ThemeConfig.getBgImage(this, windowManager.windowSize)
            if (drawable != null) {
                ViewCompat.setBackgroundTintList(window.decorView, null)
                window.decorView.background = drawable
            } else if (hasBgImage) {
                applyWindowBackgroundColor()
            } else {
                applyWindowBackgroundColor()
            }
        } catch (_: OutOfMemoryError) {
            if (hasBgImage) {
                applyWindowBackgroundColor()
            } else {
                applyWindowBackgroundColor()
            }
            toastOnUi(R.string.background_image_too_large)
        } catch (e: Exception) {
            if (hasBgImage) {
                applyWindowBackgroundColor()
            } else {
                applyWindowBackgroundColor()
            }
            AppLog.put(getString(R.string.background_image_load_error, e.localizedMessage), e)
        }
    }

    open fun setupSystemBar() {
        if (fullScreen && !isInMultiWindow) {
            fullScreen()
        }
        val isTransparentStatusBar = AppConfig.isTransparentStatusBar
        val statusBarColor = ThemeStore.statusBarColor(this, isTransparentStatusBar)
        setStatusBarColorAuto(statusBarColor, isTransparentStatusBar, fullScreen)
        if (toolBarTheme == Theme.Dark) {
            setLightStatusBar(false)
        } else if (toolBarTheme == Theme.Light) {
            setLightStatusBar(true)
        }
        upNavigationBarColor()
    }

    @SuppressLint("ObsoleteSdkInt")
    open fun applyPreferredRefreshRate() {
        val layoutParams = window.attributes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val display = currentDisplay()
            val targetMode = resolvePreferredDisplayMode(display)
            layoutParams.preferredDisplayModeId = targetMode?.modeId ?: 0
            layoutParams.preferredRefreshRate = when {
                targetMode != null -> targetMode.refreshRate
                AppConfig.useHighRefreshRate -> 0f
                else -> 60f
            }
        } else {
            layoutParams.preferredRefreshRate = if (AppConfig.useHighRefreshRate) 0f else 60f
        }
        window.attributes = layoutParams
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun currentDisplay(): Display? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            windowManager.defaultDisplay
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun resolvePreferredDisplayMode(display: Display?): Display.Mode? {
        display ?: return null
        val currentMode = display.mode
        val sameResolutionModes = display.supportedModes.filter {
            it.physicalWidth == currentMode.physicalWidth &&
                it.physicalHeight == currentMode.physicalHeight
        }
        if (sameResolutionModes.isEmpty()) return null
        return if (AppConfig.useHighRefreshRate) {
            sameResolutionModes.maxByOrNull { it.refreshRate }
        } else {
            sameResolutionModes
                .filter { it.refreshRate <= 61f }
                .maxByOrNull { it.refreshRate }
                ?: sameResolutionModes.minByOrNull { it.refreshRate }
        }
    }

    open fun upNavigationBarColor() {
        if (AppConfig.immNavigationBar) {
            setNavigationBarColorAuto(ThemeStore.navigationBarColor(this))
        } else {
            val nbColor = ColorUtils.darkenColor(ThemeStore.navigationBarColor(this))
            setNavigationBarColorAuto(nbColor)
        }
    }

    open fun observeLiveBus() {
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.dispatchTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            false
        }
    }

    override fun finish() {
        currentFocus?.hideSoftInput()
        super.finish()
    }

    private companion object {
        /**
         * 弹出菜单容器上「本次表面是否已铺过」的标记键（配合配置签名，避免同一次弹出的
         * 每个菜单项都重建一份 drawable）。取值口径与玻璃管线的 keyed tag
         * （`GLASS_POSITION_TRACKING_TAG_KEY`）一致：应用私有 key，`key >>> 24 >= 2`。
         */
        const val POPUP_MENU_SURFACE_TAG = 0x5F_4753_52
    }
}
