package io.legado.app.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.alpha
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import com.google.android.material.appbar.AppBarLayout
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.theme.ThemeTokenCompiler
import io.legado.app.lib.theme.TopBarChromeSpec
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.titleTextColor
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.ui.glass.GlassSamplingDomain
import io.legado.app.ui.glass.GlassSurface
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.activity
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import splitties.views.bottomPadding
import splitties.views.topPadding

/** 顶栏铺玻璃后的 elevation：极低但非 0，避免横屏下文字 / 图标颜色异常。 */
private const val GLASS_ELEVATION = 0.1f

/**
 * 顶栏是否该接管壁纸域玻璃（纯 JVM 判定，便于单测）。
 *
 * 只在顶栏本身就是「透明档」时成立：`opaque` 页面（web_view / code_edit 等）与
 * 关闭透明导航栏的偏好下，顶栏背后不是壁纸，接了会采到被自己盖住的壁纸。
 */
internal fun titleBarWantsWallpaperGlass(
    opaque: Boolean,
    transparentNavBar: Boolean,
    isEInk: Boolean,
    hasUsableBgImage: Boolean
): Boolean = !opaque && transparentNavBar && !isEInk && hasUsableBgImage

/**
 * `BaseActivity` / `BaseFragment` 是否该对 `R.id.title_bar` **自动** opt-in 玻璃
 * （纯 JVM 判定，便于单测）。
 *
 * 比 [titleBarWantsWallpaperGlass] 多两条前置条件，两条都对应一个已确证的误套形态：
 *
 * - [layoutEnables] = false（布局里写了 `app:wallpaperGlass="false"`）：相机预览、
 *   WebView 预览等内容域页面，顶栏背后是**内容**而不是壁纸，采样壁纸等于画出用户
 *   看不见的背景；
 * - [pageShowsWallpaper] = false（宿主 Activity 覆盖了
 *   `showsWallpaperBehindContent`）：书籍详情 / 视频 / 音频等页面用自己的不透明内容
 *   铺满整页，壁纸被自己盖住。
 *
 * 前两条是「这个页面有没有壁纸」，后两条（[transparentNavBar] / [hasUsableBgImage]）
 * 是「顶栏自己是不是透明的、有没有底图可采」——四者同时成立才真的会采样到壁纸。
 */
internal fun titleBarAutoWallpaperGlassEnabled(
    layoutEnables: Boolean,
    pageShowsWallpaper: Boolean,
    opaque: Boolean,
    transparentNavBar: Boolean,
    isEInk: Boolean,
    hasUsableBgImage: Boolean
): Boolean = layoutEnables && pageShowsWallpaper && titleBarWantsWallpaperGlass(
    opaque = opaque,
    transparentNavBar = transparentNavBar,
    isEInk = isEInk,
    hasUsableBgImage = hasUsableBgImage
)

/**
 * 本次重判该对顶栏背景做什么（纯 JVM 判定，便于单测）。
 *
 * 五个动作把「幂等」与「回退」拆开锁住，两者此前只有前者：
 *
 * - [TitleBarGlassAction.APPLY] / [TitleBarGlassAction.REBUILD]：该铺玻璃，签名与已铺的不同
 *   （从没铺过 / 档位强度描边改了），必须重建 drawable。注意签名不含壁纸文件身份：
 *   只换背景图内容不改档位时不会走到 REBUILD，旧采样会保留到 View 重建；
 * - [TitleBarGlassAction.KEEP]：签名一致，**不得**重建（重复调用零开销）；
 * - [TitleBarGlassAction.RELEASE]：判定翻成「不该铺」（关背景图 / 切 solid / 开墨水屏 /
 *   `app:wallpaperGlass="false"` / 页面改成不露壁纸），此时**必须撤掉**已铺玻璃；
 * - [TitleBarGlassAction.SKIP]：不该铺且从没铺过，原背景原样不动
 *   （顶栏本就透明，不能因为「回退」被重铺一次背景）。
 *
 * 另有一条让位规则：页面已用 [TitleBar.applyControlledWallpaperGlass] 接管本顶栏时
 * （`opaque` 的 WebView 阅读页），自动 opt-in 返回 SKIP——既不铺也不撤，把决定权留给页面。
 *
 * 只有 RELEASE 与 SKIP 的区别在这里：两者都是「不铺」，但前者要恢复 init 时的原背景，
 * 后者什么都不做。上一版把两者合成一个 `return false`，于是玻璃永远撤不掉。
 */
internal enum class TitleBarGlassAction {
    APPLY,
    KEEP,
    REBUILD,
    RELEASE,
    SKIP
}

internal fun titleBarGlassAction(
    enabled: Boolean,
    signature: String,
    appliedSignature: String?,
    yieldToPage: Boolean = false
): TitleBarGlassAction = when {
    // 页面已用 applyControlledWallpaperGlass / applyPageOwnedSurface 接管本顶栏：自动 opt-in 整条让位。
    // 注：生产路径已在 applyWallpaperGlassDecision 开头整体早退（内部固定传 false），
    // 此参数保留供纯函数决策表与既有单测（TitleBarGlassYieldPrecedenceTest 等）显式消费。
    yieldToPage -> TitleBarGlassAction.SKIP
    !enabled -> if (appliedSignature == null) {
        TitleBarGlassAction.SKIP
    } else {
        TitleBarGlassAction.RELEASE
    }

    appliedSignature == null -> TitleBarGlassAction.APPLY
    appliedSignature == signature -> TitleBarGlassAction.KEEP
    else -> TitleBarGlassAction.REBUILD
}

/**
 * B系 [TitleBar] 悬浮 chrome 的表面决策（纯 JVM 判定，便于单测）。
 *
 * 与 [titleBarGlassAction] 同层：只回答「顶栏表面用哪种材质」，不碰 View：
 *
 * - [TitleBarChromeSurface.GLASS]：悬浮 + 玻璃判定通过 → 圆角玻璃卡片；
 * - [TitleBarChromeSurface.PACKAGE]：悬浮 + 玻璃判定不过（无背景图 / solid 档 /
 *   `opaque` / 布局退出 / 低内存降级）→ 顶栏包实底圆角卡片；
 * - [TitleBarChromeSurface.LEGACY]：非悬浮 → 一切回归历史行为（逐字节 dormant）。
 *
 * 「是否悬浮」只来自顶栏包 `floatingChrome`（独立布尔，禁止由 radius>0 反推，R4）。
 */
internal enum class TitleBarChromeSurface {
    GLASS,
    PACKAGE,
    LEGACY
}

internal fun titleBarChromeSurface(glassEnabled: Boolean, floating: Boolean): TitleBarChromeSurface =
    when {
        floating && glassEnabled -> TitleBarChromeSurface.GLASS
        floating -> TitleBarChromeSurface.PACKAGE
        else -> TitleBarChromeSurface.LEGACY
    }

/**
 * 玻璃账本与包背景卡片共用的复合签名。
 *
 * 必须同时含：顶栏包身份（编辑包文件后 lastModified 变化 → 即时重铺）、玻璃档位签名、
 * 有效半径与悬浮态。半径进签名是红队既定项——View 侧 ledger 此前不含 radius，
 * 改 cornerScale 会命中 KEEP 而保留旧半径的玻璃 drawable。
 */
internal fun titleBarChromeSurfaceSignature(
    packageSignature: String,
    glassSignature: String,
    radiusPx: Float,
    floating: Boolean
): String = "$packageSignature|$glassSignature|r=$radiusPx|f=$floating"

/**
 * 顶栏的「原样快照 + 已铺标记」账本。
 *
 * [originalBackground] / [originalElevation] 是**首次铺玻璃之前**的外观，由
 * [TitleBar.init] 在设定完背景之后立刻记下（早于任何自动 / 手动 apply，包括页面在
 * `onActivityCreated` 里的手写调用）。[appliedSignature] 记住当前铺上去的是哪一档材质。
 *
 * 抽成独立类是因为它是「回退」唯一的状态来源：判否时先问 [consumeApplied]，只有真的
 * 铺过才去动背景——从没铺过的顶栏保持原样。装配这颗状态机属于可单测的纯逻辑
 * （[TitleBar] 本身需要 Material 宿主与真实壁纸位图，无法在纯 JVM / Robolectric 下
 * 走通「开局铺玻璃」那条路）。
 */
internal class TitleBarGlassLedger {

    var originalBackground: Drawable? = null
        private set

    var originalElevation: Float = 0f
        private set

    var appliedSignature: String? = null
        private set

    /** 原始外观是否已捕获。重复捕获保持首次快照（init 只调一次，防御性幂等）。 */
    var originalSurfaceCaptured: Boolean = false
        private set

    fun captureOriginalSurface(background: Drawable?, elevationPx: Float) {
        if (originalSurfaceCaptured) return
        originalBackground = background
        originalElevation = elevationPx
        originalSurfaceCaptured = true
    }

    fun markApplied(signature: String) {
        appliedSignature = signature
    }

    /**
     * 清掉「已铺」标记，并回答「这次是否真的需要回退」。
     *
     * 返回 true 只发生一次：之后 [appliedSignature] 为 null，重复调用返回 false，
     * 所以「判否 → 回退」路径天然幂等（`onResume` 每次都会走一遍判否分支）。
     */
    fun consumeApplied(): Boolean {
        if (appliedSignature == null) return false
        appliedSignature = null
        return true
    }
}

/**
 * 把 [view] 恢复成首次铺玻璃之前的外观：先解绑位置跟踪，再铺回原背景与原 elevation。
 *
 * 三条都必要：
 * 1. **解绑跟踪**：玻璃 drawable 在 attach 时挂上了 layout / attach 回调与滚动监听，
 *    只把背景换掉的话，这些回调仍会驱动已换下的 drawable（白算采样矩阵、白留位图）；
 * 2. **铺回原背景实例**：透明档顶栏的原背景是透明 ColorDrawable，不透明档是主色实底，
 *    两种都要逐值还原，不能统一改成透明（否则不透明档顶栏会露壁纸）；
 * 3. **还原 elevation**：[TitleBar.setBackground] 对半透明背景会强制 `0.1f`，
 *    不还原就会让本来有阴影的顶栏永久失去阴影。
 *
 * 返回 false 表示本来就没铺过玻璃，什么都没改。
 */
internal fun restoreTitleBarSurface(view: View, ledger: TitleBarGlassLedger): Boolean {
    if (!ledger.consumeApplied()) return false
    GlassSurface.releaseBackgroundTracking(view)
    view.background = ledger.originalBackground
    view.elevation = ledger.originalElevation
    return true
}

@Suppress("unused", "MemberVisibilityCanBePrivate")
class TitleBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppBarLayout(context, attrs) {

    val toolbar: Toolbar
    val menu: Menu
        get() = toolbar.menu

    var title: CharSequence?
        get() = toolbar.title
        set(title) {
            if (toolbar.title != title) {
                toolbar.title = title
                post { applyTitleStyle() }
            }
        }

    var subtitle: CharSequence?
        get() = toolbar.subtitle
        set(subtitle) {
            if (toolbar.subtitle != subtitle) {
                toolbar.subtitle = subtitle
                post { applyTitleStyle() }
            }
        }

    private val displayHomeAsUp: Boolean
    private val navigationIconTint: ColorStateList?
    private val navigationIconTintMode: Int
    private val fitStatusBar: Boolean
    private val fitNavigationBar: Boolean
    private val attachToActivity: Boolean
    private val opaque: Boolean
    private val forceDarkText: Boolean

    /**
     * 布局是否允许宿主页面**自动**为本顶栏接入壁纸域玻璃（`app:wallpaperGlass`）。
     *
     * 默认 true：二级页顶栏绝大多数本来就浮在壁纸上，逐个页面手写
     * `applyWallpaperGlass()` 既容易漏（本次反馈的「约 20 个二级页顶栏还是实底」
     * 就是这么来的），也无法在布局层面表达「这一页不该套玻璃」。
     * 与 `opaque` 同一个「布局显式声明」的先例，语义相反：`opaque` 说「顶栏不透明」，
     * `wallpaperGlass=false` 说「别给我套玻璃」。
     */
    val wallpaperGlassOptIn: Boolean

    /** 「原样快照 + 已铺标记」，降级回退的唯一状态来源（见 [TitleBarGlassLedger]）。 */
    private val glassLedger = TitleBarGlassLedger()

    // ===== 悬浮圆角 chrome 状态（B系接线，dormant by default）=====

    /** 系统栏顶部 inset，来自 insets listener；悬浮卡片的 top margin 依赖它。 */
    private var systemTopInset = 0

    /** 最近一次解析的 chrome 几何；inset 变化 / 非悬浮回滚都要拿它重算。 */
    private var lastResolvedChrome: TopBarChromeSpec.Chrome? = null

    /** 首次悬浮前的原始 margins 快照；从没悬浮过则不回滚（null = 无事发生）。 */
    private var chromeOriginalMargins: IntArray? = null

    /** 当前包背景卡片签名；非 null 表示顶栏表面被 chrome 接管（玻璃之外的状态）。 */
    private var chromeBgSignature: String? = null

    /**
     * 本顶栏是否已被页面用 [applyControlledWallpaperGlass] 接管。
     *
     * 一旦接管，[applyAutoWallpaperGlass] 整条让位（见 [titleBarGlassAction] 的
     * `yieldToPage`）：自动判定是为「顶栏浮在壁纸上的普通二级页」设计的，
     * `opaque` 页面（WebView 阅读页顶栏背后是壁纸、但页面声明不透明）会让它判否，
     * 从而把页面刚铺上的玻璃撤掉。
     */
    private var pageOwnsWallpaperGlass = false

    init {
        val a = context.obtainStyledAttributes(
            attrs, R.styleable.TitleBar,
            R.attr.titleBarStyle, 0
        )
        navigationIconTint = a.getColorStateList(R.styleable.TitleBar_navigationIconTint)
        navigationIconTintMode = a.getInt(R.styleable.TitleBar_navigationIconTintMode, 9)
        attachToActivity = a.getBoolean(R.styleable.TitleBar_attachToActivity, true)
        displayHomeAsUp = a.getBoolean(R.styleable.TitleBar_displayHomeAsUp, true)
        fitStatusBar = a.getBoolean(R.styleable.TitleBar_fitStatusBar, true)
        fitNavigationBar = a.getBoolean(R.styleable.TitleBar_fitNavigationBar, false)
        opaque = a.getBoolean(R.styleable.TitleBar_opaque, false)
        wallpaperGlassOptIn = a.getBoolean(R.styleable.TitleBar_wallpaperGlass, true)
        val themeMode = a.getInt(R.styleable.TitleBar_themeMode, 0)
        forceDarkText = themeMode == 1

        val navigationIcon = a.getDrawable(R.styleable.TitleBar_navigationIcon)
        val navigationContentDescription =
            a.getText(R.styleable.TitleBar_navigationContentDescription)
        val titleText = a.getString(R.styleable.TitleBar_title)
        val subtitleText = a.getString(R.styleable.TitleBar_subtitle)

        when (themeMode) {
            1 -> inflate(context, R.layout.view_title_bar_dark, this)
            else -> inflate(context, R.layout.view_title_bar, this)
        }
        toolbar = findViewById(R.id.toolbar)

        toolbar.apply {
            navigationIcon?.let {
                this.navigationIcon = it
                this.navigationContentDescription = navigationContentDescription
            }

            if (a.hasValue(R.styleable.TitleBar_titleTextAppearance)) {
                this.setTitleTextAppearance(
                    context,
                    a.getResourceId(R.styleable.TitleBar_titleTextAppearance, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_titleTextColor)) {
                this.setTitleTextColor(a.getColor(R.styleable.TitleBar_titleTextColor, -0x1))
            }

            if (a.hasValue(R.styleable.TitleBar_subtitleTextAppearance)) {
                this.setSubtitleTextAppearance(
                    context,
                    a.getResourceId(R.styleable.TitleBar_subtitleTextAppearance, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_subtitleTextColor)) {
                this.setSubtitleTextColor(a.getColor(R.styleable.TitleBar_subtitleTextColor, -0x1))
            }


            if (a.hasValue(R.styleable.TitleBar_contentInsetLeft)
                || a.hasValue(R.styleable.TitleBar_contentInsetRight)
            ) {
                this.setContentInsetsAbsolute(
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetLeft, 0),
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetRight, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetStart)
                || a.hasValue(R.styleable.TitleBar_contentInsetEnd)
            ) {
                this.setContentInsetsRelative(
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetStart, 0),
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetEnd, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetStartWithNavigation)) {
                this.contentInsetStartWithNavigation = a.getDimensionPixelOffset(
                    R.styleable.TitleBar_contentInsetStartWithNavigation, 0
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetEndWithActions)) {
                this.contentInsetEndWithActions = a.getDimensionPixelOffset(
                    R.styleable.TitleBar_contentInsetEndWithActions, 0
                )
            }

            if (!titleText.isNullOrBlank()) {
                this.title = titleText
            }

            if (!subtitleText.isNullOrBlank()) {
                this.subtitle = subtitleText
            }

            if (a.hasValue(R.styleable.TitleBar_contentLayout)) {
                inflate(context, a.getResourceId(R.styleable.TitleBar_contentLayout, 0), this)
            }
        }
        applyTitleStyle()

        if (!isInEditMode) {
//            if (fitStatusBar) {
//                setPadding(paddingLeft, context.statusBarHeight, paddingRight, paddingBottom)
//            }
//
//            if (fitNavigationBar) {
//                setPadding(paddingLeft, paddingTop, paddingRight, context.navigationBarHeight)
//            }

            // listener 恒装：悬浮 chrome 的 top margin 依赖 systemTopInset，
            // 与 fitStatusBar 无关（写 padding 才受开关控制）。
            setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                systemTopInset = insets.top
                val floating = lastResolvedChrome?.floating == true
                if (fitStatusBar) {
                    // 悬浮态：状态栏让位改由卡片 top margin 承担，padding 归零，
                    // 否则卡片表面会一直延伸进状态栏。
                    topPadding = if (floating) 0 else insets.top
                }
                if (fitNavigationBar) {
                    bottomPadding = insets.bottom
                }
                if (floating) {
                    // 首次 inset 派发可能在 chrome 应用之后（margin 里 top 缺状态栏高度），
                    // inset 到位即重算一次几何。
                    lastResolvedChrome?.let { applyChromeGeometry(it) }
                }
                windowInsets
            }

            if (AppConfig.isEInkMode) {
                setBackgroundResource(R.drawable.bg_eink_border_bottom)
            } else if (!opaque && context.transparentNavBar) {
                setBackgroundColor(Color.TRANSPARENT)
            } else {
                setBackgroundColor(context.primaryColor)
                elevation = context.elevation
            }

            stateListAnimator = null

            // 记录「首次铺玻璃之前」的原始外观：降级回退要用它逐值还原（透明档 → 透明底，
            // 不透明档 → 主色实底 + 阴影）。位置有意放在 init 的最末尾——此刻背景与
            // elevation 都已按主题设定完毕，且早于任何自动 opt-in
            // （BaseActivity / BaseFragment）与页面在 onActivityCreated 里的手写
            // applyWallpaperGlass()，因此记下的必然是「原背景」而不是某一档玻璃。
            glassLedger.captureOriginalSurface(background, elevation)
        }
        a.recycle()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachToActivity()
        post { applyTitleStyle() }
    }

    fun setNavigationOnClickListener(clickListener: ((View) -> Unit)) {
        toolbar.setNavigationOnClickListener(clickListener)
    }

    fun setTitle(titleId: Int) {
        toolbar.setTitle(titleId)
        post { applyTitleStyle() }
    }

    fun setSubTitle(subtitleId: Int) {
        toolbar.setSubtitle(subtitleId)
        post { applyTitleStyle() }
    }

    private fun applyTitleStyle() {
        if (isInEditMode) return
        val typeface = context.titleTypeface()
        val textColor = if (forceDarkText) Color.WHITE else context.titleTextColor
        toolbar.setTitleTextColor(textColor)
        toolbar.setSubtitleTextColor(textColor)
        setColorFilter(textColor)
        toolbar.children.filterIsInstance<TextView>().forEach {
            it.typeface = typeface
            it.setTextColor(textColor)
        }
    }

    fun setTitleTextColor(@ColorInt color: Int) {
        toolbar.setTitleTextColor(color)
    }

    fun setTitleTextAppearance(@StyleRes resId: Int) {
        toolbar.setTitleTextAppearance(context, resId)
    }

    fun setSubTitleTextColor(@ColorInt color: Int) {
        toolbar.setSubtitleTextColor(color)
    }

    fun setSubTitleTextAppearance(@StyleRes resId: Int) {
        toolbar.setSubtitleTextAppearance(context, resId)
    }

    fun setTextColor(@ColorInt color: Int) {
        setTitleTextColor(color)
        setSubTitleTextColor(color)
    }

    /**
     * 自动 opt-in 入口：布局允许、且本页真的露出壁纸时铺玻璃；否则**撤掉**已铺的玻璃。
     *
     * 由 [io.legado.app.base.BaseActivity] / [io.legado.app.base.BaseFragment] 对
     * `R.id.title_bar` 统一调用（`onCreate` / `onViewCreated` / 每次 `onResume` /
     * 主题变化回调），把「二级页顶栏忘了接玻璃」从人工清单变成结构默认；
     * 页面仍可用 `app:wallpaperGlass="false"` 显式退出（内容域页面）。
     *
     * [pageShowsWallpaper] 取宿主的 `showsWallpaperBehindContent`：书籍详情 / 视频 /
     * 音频等用不透明内容铺满整页的页面必须传 false，否则会采到用户看不见的壁纸。
     *
     * **回退**：判定翻成否时（关背景图 / 切 solid / 开墨水屏 / 布局退出 / 页面改成
     * 不露壁纸）调 [restoreTitleBarSurface] 恢复原背景与 elevation，不能只返回 false——
     * 真机链路是「开背景图 + 玻璃档 → 进设置切 solid 或关背景图 → 返回页面」，
     * 顶栏 View 是同一个实例，旧 drawable 会一直贴在身上。
     */
    fun applyAutoWallpaperGlass(pageShowsWallpaper: Boolean): Boolean {
        if (isInEditMode) return false
        return applyWallpaperGlassDecision(
            enabled = titleBarAutoWallpaperGlassEnabled(
                layoutEnables = wallpaperGlassOptIn,
                pageShowsWallpaper = pageShowsWallpaper,
                opaque = opaque,
                transparentNavBar = context.transparentNavBar,
                isEInk = AppConfig.isEInkMode,
                hasUsableBgImage = ThemeConfig.hasUsableBgImage(context)
            ) && GlassSurface.wallpaperGlassEnabled(context),
            yieldToPage = pageOwnsWallpaperGlass
        )
    }

    /**
     * 受控入口：由「自己知道该不该铺、但自动判定会否掉」的页面驱动（当前只有
     * `ReadRssActivity` 的 WebView 阅读页——`opaque = true`，顶栏背后却是壁纸）。
     *
     * 与 [applyWallpaperGlass] 的区别只是判定来源：这里不猜 `transparentNavBar` /
     * 底图可用性，要求调用方给出 [enabled]；但**幂等、签名去重、降级回退三者完全共用**
     * 同一份账本与决策表，所以「开玻璃 → 切 solid → 返回」会走 RELEASE 恢复 init 原背景，
     * 而不是像上一版那样在 `if (SOLID) return` 里把旧玻璃留在顶栏上。
     *
     * 调用一次即视为页面接管：此后 [applyAutoWallpaperGlass] 让位（不铺也不撤），
     * 页面须在每次回到前台时重新调本方法重判。
     *
     * [cornerRadiusPx] 供非贴边形态（有外边距的顶栏卡）使用；贴边通栏取默认 0。
     */
    fun applyControlledWallpaperGlass(enabled: Boolean, cornerRadiusPx: Float = 0f): Boolean {
        if (isInEditMode) return false
        pageOwnsWallpaperGlass = true
        return applyWallpaperGlassDecision(enabled = enabled, cornerRadiusPx = cornerRadiusPx)
    }

    /**
     * 把顶栏背景换成壁纸域玻璃（贴边矩形，圆角 0）。
     *
     * 只在顶栏正处于透明档、页面确有可用背景图且非墨水屏时生效；返回 false 表示顶栏
     * **保持 / 已恢复** init 时设定的原背景。调用方须是「页面根背景会被 BaseActivity
     * 置空、壁纸确实透到顶栏」的普通二级页——本方法不猜测祖先容器是否不透明。
     *
     * **幂等**：签名（玻璃档位 / 强度 / 倍率 / 描边色 / 夜间模式）未变时直接返回 true，
     * 不重建 drawable。这是自动 opt-in 的前提——`BaseActivity.onCreate` 与页面自己的
     * `onActivityCreated` 都会调到这里，切主题 / 回到前台也会重调，重复建 drawable 会
     * 白付一次壁纸采样与位置监听（并让逐个页面手写的旧调用点变成隐性泄漏）。
     *
     * **可回退**：本方法判否（含 `GlassSurface.wallpaperGlassEnabled` 判否，
     * 即 solid 档 / 墨水屏 / 低内存）时同样会撤掉已铺玻璃。
     *
     * **已知的结构性陷阱（有意保留现状）**：本方法不检查 `app.wallpaperGlass` 布局退出属性
     * 与宿主页面的 `showsWallpaperBehindContent`——它表达的是「调用方明确要求铺玻璃」。
     * 已接线的 17 个页面全部位于露出壁纸的普通二级页，加上这两条判定会改变它们的既有
     * 语义（其中阅读记录等页面是 `opaque` 之外的显式接管形态）；而 `app:wallpaperGlass="false"`
     * 的语义是「别让**自动**路径给我套玻璃」，手写调用属于页面自己的决定。新页面请优先
     * 依赖自动 opt-in，只有「自动判定会否掉但页面确实浮在壁纸上」时才用
     * [applyControlledWallpaperGlass]，它会话式接管并接管回退。
     */
    fun applyWallpaperGlass(): Boolean {
        if (isInEditMode) return false
        return applyWallpaperGlassDecision(
            enabled = titleBarWantsWallpaperGlass(
                opaque = opaque,
                transparentNavBar = context.transparentNavBar,
                isEInk = AppConfig.isEInkMode,
                hasUsableBgImage = ThemeConfig.hasUsableBgImage(context)
            ) && GlassSurface.wallpaperGlassEnabled(context)
        )
    }

    /**
     * 页面自管顶栏表面的唯一入口（R2 单一写者）。
     *
     * 详情页这类「背后是 bg_book 而不是壁纸」的页面，表面材质只能由页面自己画
     * （壁纸域采样在本页不成立），但**几何必须仍然来自 chrome**。此前页面绕过
     * chrome 直接写 `background`：悬浮 margins 由自动路径施加、方角面由页面铺，
     * 同一块表面两个写者——真机上就是「顶栏悬浮内缩了却不是圆角」。
     *
     * 本方法把 chrome（几何 + 半径 + 悬浮态）解析好交给 [face]（悬浮卡半径取
     * `chrome.radiusPx`，非悬浮传 0 保持贴边通栏），施加几何后铺上返回的 drawable。
     * 调用后 `pageOwnsWallpaperGlass = true`：自动 opt-in 与包卡分支对本顶栏
     * **零触碰**（几何照常施加，表面动作整体让位），页面是表面的唯一写者。
     *
     * [face] 返回 null 表示页面判否（非玻璃档 / CLEAR 判否），本方法不动背景，
     * 由页面按自身账本还原透明档。
     */
    fun applyPageOwnedSurface(face: (chrome: TopBarChromeSpec.Chrome) -> Drawable?): Boolean {
        if (isInEditMode) return false
        pageOwnsWallpaperGlass = true
        val (chrome, _) = resolveChrome()
        applyChromeGeometry(chrome)
        val drawable = face(chrome) ?: return false
        setBackground(drawable)
        return true
    }

    /**
     * 解析当前顶栏包的悬浮 chrome 几何（半径 / 悬浮态 / 三向 inset）。
     *
     * 唯一真相源是 [TopBarChromeSpec]（与 A系 [MainTopBarView] 共用）：
     * 半径只来自 `cornerScale`，悬浮只来自 `floatingChrome`，互不反推（R4）。
     */
    private fun resolveChrome(): Pair<TopBarChromeSpec.Chrome, TopBarConfig.Config> {
        val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
        val topScope = ThemeTokenCompiler.compileFromContext(context).topBar
        val base = if (topScope.isCustomOverride) {
            topScope.panelRadiusPx
        } else {
            resources.getDimension(R.dimen.ui_panel_radius)
        }
        val height = measuredHeight.takeIf { it > 0 }?.toFloat() ?: 0f
        return TopBarChromeSpec.resolve(base, config, height) to config
    }

    /**
     * 把悬浮卡片的几何施加到 [TitleBar] 自身：左右 / 底部 inset 走 margins，
     * 状态栏让位从 topPadding 迁移到 top margin（卡片从状态栏下方起卡）。
     * 非悬浮时恢复首次悬浮前快照；从没悬浮过则什么都不动（dormant 安全底线）。
     */
    private fun applyChromeGeometry(chrome: TopBarChromeSpec.Chrome) {
        lastResolvedChrome = chrome
        val lp = layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (chromeOriginalMargins == null) {
            chromeOriginalMargins = intArrayOf(lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin)
        }
        if (chrome.floating) {
            val top = if (fitStatusBar) systemTopInset + chrome.topInsetPx else chrome.topInsetPx
            if (lp.leftMargin != chrome.hInsetPx || lp.rightMargin != chrome.hInsetPx ||
                lp.topMargin != top || lp.bottomMargin != chrome.bottomInsetPx
            ) {
                lp.leftMargin = chrome.hInsetPx
                lp.topMargin = top
                lp.rightMargin = chrome.hInsetPx
                lp.bottomMargin = chrome.bottomInsetPx
                layoutParams = lp
            }
            if (fitStatusBar && paddingTop != 0) {
                setPadding(paddingLeft, 0, paddingRight, paddingBottom)
            }
        } else {
            val original = chromeOriginalMargins
            if (original != null &&
                (lp.leftMargin != original[0] || lp.topMargin != original[1] ||
                    lp.rightMargin != original[2] || lp.bottomMargin != original[3])
            ) {
                lp.leftMargin = original[0]
                lp.topMargin = original[1]
                lp.rightMargin = original[2]
                lp.bottomMargin = original[3]
                layoutParams = lp
            }
            if (fitStatusBar && systemTopInset > 0 && paddingTop != systemTopInset) {
                setPadding(paddingLeft, systemTopInset, paddingRight, paddingBottom)
            }
        }
    }

    /**
     * 悬浮 + 玻璃判定不过时的降级表面：顶栏包实底圆角卡片（[TopBarConfig.resolveBackgroundColor]）。
     * 按 [signature] 幂等：包文件 / 半径 / 悬浮态任一变化才重建 drawable。
     */
    private fun applyPackageChromeSurface(chrome: TopBarChromeSpec.Chrome, signature: String, config: TopBarConfig.Config) {
        if (chromeBgSignature == signature) return
        chromeBgSignature = signature
        val topScope = ThemeTokenCompiler.compileFromContext(context).topBar
        val baseColor = TopBarConfig.resolveBackgroundColor(config)
        val color = if (!AppConfig.isEInkMode && topScope.isCustomOverride) {
            ColorUtils.withAlpha(baseColor, topScope.alphaFraction)
        } else {
            baseColor
        }
        // 与 setBackground(ColorDrawable) 的存量语义一致：半透明底保留 0.1f，
        // 避免横屏下文字 / 图标颜色异常。
        elevation = if (Color.alpha(color) < 255) GLASS_ELEVATION else 0f
        setBackground(
            GradientDrawable().apply {
                cornerRadius = chrome.radiusPx
                setColor(color)
            }
        )
    }

    /** 撤掉包背景卡片，恢复 init 时设定的原背景与 elevation（从没铺过则无事发生）。 */
    private fun releasePackageChromeSurface() {
        if (chromeBgSignature == null) return
        chromeBgSignature = null
        background = glassLedger.originalBackground
        elevation = glassLedger.originalElevation
    }

    /**
     * 一条路径执行 chrome 几何 + [titleBarGlassAction] 给出的表面动作。
     *
     * chrome 与玻璃共用这个漏斗（自动 / 手动 / 受控三入口都汇进来），所以
     * BaseActivity / BaseFragment 的每次 onResume 重判同时也是 chrome 重判：
     * 设计页改包（半径 / 悬浮开关）后返回页面即生效，签名未变时全部幂等空转。
     *
     * 表面决策见 [titleBarChromeSurface]：悬浮+玻璃 → 圆角玻璃卡（半径取 chrome，
     * 调用方 [cornerRadiusPx] 只在非悬浮受控卡生效）；悬浮+非玻璃 → 包实底卡；
     * 非悬浮 → 历史行为逐字节不变（radius 参数也回归调用方原值）。
     *
     * [yieldToPage] 只由自动 opt-in 传 true（页面已接管时不插手玻璃判定）。
     * 让位语义是「几何照常、表面零触碰」：chrome 几何始终施加（悬浮是顶栏包的
     * 全局决定），但玻璃 / 包卡两个表面分支对已接管顶栏整体无写权——
     * 否则页面自管材质面（不进玻璃账本）会在每次 resume 被自动入口撕掉重铺。
     */
    private fun applyWallpaperGlassDecision(
        enabled: Boolean,
        cornerRadiusPx: Float = 0f,
        yieldToPage: Boolean = false
    ): Boolean {
        val (chrome, config) = resolveChrome()
        applyChromeGeometry(chrome)
        // 让位即零触碰（表面动作层面）：页面自管表面（applyPageOwnedSurface /
        // applyControlledWallpaperGlass）时，自动入口的几何照常施加，但玻璃 /
        // 包卡两个表面分支都不再有写权。此前 PACKAGE 分支只在不等式
        // 「yieldToPage && appliedSignature != null」下让位——页面自管材质面
        // （如详情页的压暗玻璃面）不进玻璃账本、appliedSignature 恒 null，
        // 于是每次 resume 自动入口都会把包卡铺到页面上，与页面自己的面
        // 形成「你铺我撕」的双写循环。
        if (yieldToPage) {
            return false
        }
        val topScope = ThemeTokenCompiler.compileFromContext(context).topBar
        val effectiveEnabled = enabled && topScope.materialMode != GlassMaterialPreset.SOLID
        val glassRadius = if (chrome.floating) chrome.radiusPx else cornerRadiusPx
        val signature = titleBarChromeSurfaceSignature(
            packageSignature = TopBarConfig.currentSignature(AppConfig.isNightTheme),
            glassSignature = "${GlassSurface.configSignature(context)}|top=${topScope.materialMode}:${topScope.alphaPercent}:${topScope.panelRadiusDp}",
            radiusPx = glassRadius,
            floating = chrome.floating
        )
        return when (titleBarChromeSurface(effectiveEnabled, chrome.floating)) {
            TitleBarChromeSurface.PACKAGE -> {
                // 悬浮但玻璃判定不过：撤掉已铺玻璃（若有）再铺包背景卡片。
                restoreTitleBarSurface(this, glassLedger)
                applyPackageChromeSurface(chrome, signature, config)
                true
            }

            TitleBarChromeSurface.GLASS, TitleBarChromeSurface.LEGACY -> {
                // yieldToPage 已在上面整体让位早退，这里恒 false（SKIP 分支只为
                // 穷尽性保留，titleBarGlassAction 的让位语义由单测锁定）。
                val action = titleBarGlassAction(
                    enabled = effectiveEnabled,
                    signature = signature,
                    appliedSignature = glassLedger.appliedSignature,
                    yieldToPage = false
                )
                releasePackageChromeSurface()
                when (action) {
                    TitleBarGlassAction.SKIP -> false
                    TitleBarGlassAction.KEEP -> true
                    TitleBarGlassAction.RELEASE -> {
                        restoreTitleBarSurface(this, glassLedger)
                        false
                    }

                    TitleBarGlassAction.APPLY, TitleBarGlassAction.REBUILD -> {
                        val glass = GlassSurface.panelBackground(
                            this,
                            GlassSamplingDomain.WALLPAPER,
                            glassRadius
                        )
                        if (glass == null) {
                            // 管线本次给不出玻璃（如低内存降级）：上一次铺的必须撤掉，
                            // 不能把旧材质留在顶栏上。
                            restoreTitleBarSurface(this, glassLedger)
                            if (chrome.floating) {
                                // 悬浮态玻璃降级失败：退到包实底卡片，悬浮卡不能裸奔成透明。
                                applyPackageChromeSurface(chrome, signature, config)
                                true
                            } else {
                                false
                            }
                        } else {
                            glassLedger.markApplied(signature)
                            elevation = GLASS_ELEVATION
                            setBackground(glass)
                            true
                        }
                    }
                }
            }
        }
    }

    fun setColorFilter(@ColorInt color: Int) {
        val colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        toolbar.children.firstOrNull { it is ImageView }?.background?.colorFilter = colorFilter
        toolbar.navigationIcon?.colorFilter = colorFilter
        toolbar.overflowIcon?.colorFilter = colorFilter
        toolbar.menu.children.forEach {
            it.icon?.colorFilter = colorFilter
        }
    }

    override fun setBackgroundColor(color: Int) {
        if (color.alpha < 255) {
            //这里不能改为0f,改为0f在横屏模式下文字和图标颜色会变
            elevation = 0.1f
        }
        super.setBackgroundColor(color)
    }

    override fun setBackground(background: Drawable?) {
        if (background is ColorDrawable) {
            if (background.alpha < 255) {
                //这里不能改为0f,改为0f在横屏模式下文字和图标颜色会变
                elevation = 0.1f
            }
        }
        super.setBackground(background)
    }

    fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, fullScreen: Boolean) {
//        if (fitStatusBar) {
//            val topPadding = if (!isInMultiWindowMode && fullScreen) context.statusBarHeight else 0
//            setPadding(paddingLeft, topPadding, paddingRight, paddingBottom)
//        }
    }

    private fun attachToActivity() {
        if (attachToActivity) {
            activity?.let {
                it.setSupportActionBar(toolbar)
                it.supportActionBar?.setDisplayHomeAsUpEnabled(displayHomeAsUp)
            }
        }
    }

}
