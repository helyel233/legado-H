package io.legado.app.ui.book.info

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.graphics.ColorUtils
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.surface.SurfaceCorners
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.themeMutedColorOrDefault
import io.legado.app.ui.glass.GlassLiquidBackdropDrawable
import io.legado.app.ui.glass.GlassSurface
import io.legado.app.ui.glass.glassFrostSurfaceRgb
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.explore.ExploreGlassBackdrop
import io.legado.app.ui.main.explore.resolvedExploreGlassMode
import io.legado.app.utils.ColorUtils as AppColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefString
import io.legado.app.utils.stackBlurSoftware

/**
 * 书籍详情页（`BookInfoActivity`）的卡片 / 操作按钮表面。
 *
 * ## 为什么这里**不走** [GlassSurface] 的 drawable 入口
 *
 * 本页背后是 `@id/bg_book`（用户配置的详情背景图，已由 `BookCover.loadBlur` 模糊）
 * 叠 `@id/vw_bg` 的 `#50000000` 遮罩，**不是主题壁纸**——`BookInfoActivity` 因此覆盖了
 * `showsWallpaperBehindContent = false`。壁纸采样管线在本页会画出用户根本看不见的壁纸
 * （[io.legado.app.ui.glass.GlassSamplingDomain] 明确禁止的错误管线）；内容域采样
 * （`StableLiquidGlassView` + 兄弟子树）在这里也不成立：`bg_book` 是 `ImageView` 而不是
 * `ViewGroup`，唯一的同级容器 `vw_bg` 是卡片的祖先（自录制，会被守卫拒绝）。
 *
 * 第五轮修正：上面「零采样就等价于磨砂」的结论只在**背后真有一张图**时成立。用户
 * 实际用主题纯色 / 系统壁纸，`bg_book` 只是一块纯色——模糊纯色仍是纯色，把卡片
 * 现在统一走**一颗** AGSL 玻璃 drawable [GlassLiquidBackdropDrawable]（与底栏 /
 * 目录页同一颗 shader），材质参数只有 [GlassMaterialPreset] 一份：
 *
 * 1. **真实模糊**：主线程一次全幅截图降采样（[captureBackdropSnapshot]）→
 *    后台线程按卡片区域裁剪 + `stackBlurSoftware`（[cropBackdropRegion]），作为卡片的
 *    backdrop，有底图时给 drawable 做真折射 + 色散的采样源；
 * 2. **壳层 + 折射边缘**：无底图 / 纯色底 / 位图被回收时，同一颗 drawable 仍画三色渐变
 *    壳层、边缘与描边（见 [GlassLiquidBackdropDrawable.draw] 的安全降级）。
 *
 * 关键纪律：底图有无只是**同一档内的细节差异**，绝不再据此切换 drawable 管线——
 * 这正是反复进入详情页「纯色 / 玻璃 / 磨砂」跳变的根除点。
 *
 * ## 降级
 *
 * [bookInfoTranslucentSurfaceAllowed] 为 false 时本类所有入口返回 null，调用方回退到
 * 接入前的 [UiCorner.panelRounded] / `book_info_frost` 按钮表面，逐值不变。
 *
 * ## 与全 App 其它玻璃面的已知偏差（有意为之，不是漏项）
 *
 * 玻璃档下本类**不再叠用户配置的「面板背景图」与「面板描边色」**（旧
 * [UiCorner.panelRounded] 会叠 `panelImageDrawable` + `panelBorderColor`），描边统一换成
 * `@color/glass_stroke`。原因：用户那张面板背景图是**整块铺满**的，叠上来会把背后的详情
 * 背景图完全盖住——那正是本类要让出来的东西，两者互斥。这与全 App 其它玻璃面在玻璃档下
 * 不显示 `panelImage` 是同一条纪律，不是本页特例。
 */

/**
 * 详情页卡片能否让出 alpha（纯函数，便于 JVM 单测锁表）。
 *
 * 只认 [glassEnabled]——即 [GlassSurface.wallpaperGlassEnabled]（solid 档 / 墨水屏 /
 * 低内存 三件事），与全 App 玻璃判定的唯一口径一致。
 *
 * ## 为什么不再叠加「有背景图」这道门（第四轮根因）
 *
 * 前几轮在此叠了 `hasBackdropImage`，要求 `bookInfoBgImage` / `bgImage` 偏好字符串非空才上
 * 玻璃。但用户实际用的是**系统壁纸 / 主题纯色**，压根没有把图片写进这些偏好键；加上
 * 本页 `showsWallpaperBehindContent = false`，`bg_book` 也只显示纯色。于是无论玻璃档
 * 怎么开，`hasBackdropImage` 恒 false → 卡片 / 顶栏 / 按钮全部判否回退实底 → 用户看到
 * 「详情页玻璃完全无变化」。这道门就是前几轮修了却一律不生效的直接原因。
 *
 * 而唯一生效的搜书页走的是 `GlassPanel(requireWallpaperHost = false)`——它**不看**任何
 * 背景图偏好。本函数据此统一口径：玻璃档开就上玻璃。无背景图时 `bg_book` 是纯色，
 * 半透明卡面透出该纯色并由 [GlassLiquidBackdropDrawable] 的壳层 / 折射边缘建立玻璃观感，
 * 观感不比实底差（对比度推导见 [PANEL_ALPHA]），
 * 绝不会出现「卡片透明却露出错误内容」——本页背后只有纯色 / 用户自己的模糊背景图。
 *
 * [glassEnabled] 为 false（solid / 墨水屏 / 低内存）时仍逐值回退实底，保持既有降级矩阵。
 */
internal fun bookInfoTranslucentSurfaceAllowed(glassEnabled: Boolean): Boolean = glassEnabled

/**
 * 页面自管顶栏面的圆角（纯函数，便于 JVM 单测锁表）。
 *
 * 悬浮 chrome 开启 → 顶栏已被 margins 内缩成卡片，面必须与卡片同圆角
 * （[cornerRadiusPx] 来自 [io.legado.app.lib.theme.TopBarChromeSpec]）；
 * 关闭 → 传 0，贴边通栏直角，与 1.31.1 及以前的存量观感逐字节一致。
 *
 * 「是否圆角」只由悬浮布尔决定、半径只由 chrome 传入，禁止由 radius>0 反推悬浮（R4）。
 */
internal fun pageOwnedTitleBarRadius(floating: Boolean, cornerRadiusPx: Float): Float =
    if (floating) cornerRadiusPx else 0f

/**
 * 详情页背景图的取用顺序（纯函数，便于 JVM 单测锁表）。
 *
 * 1. 用户配置的「详情背景图」（`bookInfoBackgroundImage` / `...Night`）优先；
 * 2. 为空时回退 **App 级背景图**（`backgroundImage` / `backgroundImageNight`）。
 *
 * ## 为什么需要第 2 级回退（第二轮真机取证结论）
 *
 * 绝大多数用户（包括本缺陷的报告者）从未配置过详情页专用背景图：主题包的
 * `theme.json` 只有 `backgroundImgPath`、没有 `bookInfoBackgroundImgPath`，
 * `ThemeConfig.applyTheme` 写入 `bookInfoBgImage` 时值为 null 会**移除**该键。
 * 于是「玻璃档 + 全 App 都坐在背景图上」的用户，唯独详情页整页判否回退实底——
 * 真机表现正是「详情页完全无变化」。回退到 App 级背景图后：
 *
 * - 详情页与书架 / 发现 / 我的等页**同源同底**（都是那张背景图），观感一致；
 * - 玻璃判定与 `bg_book` 的取图共用本函数这一个口径，不会出现「卡片让了 alpha
 *   而背后没有图」的两套判定漂移；
 * - 不新增任何偏好键——用户想给详情页换独立底图时，原有的详情背景图偏好仍然
 *   优先生效，行为不变。
 *
 * 两级都只判「偏好字符串非空」，不判文件存在：`bg_book` 的图经 Glide 加载，路径可能是
 * `content://` URI 而不是文件路径，用 `File.exists()` 判定会得到假阴性。
 *
 * 注意：本函数**只服务于背景图加载**（`bg_book` 到底加载哪张图），不再参与玻璃判定——
 * 玻璃判定已按第四轮根因修复统一到 [bookInfoTranslucentSurfaceAllowed]，不再要求偏好非空。
 */
internal fun resolveBookInfoBackdropImagePath(
    detailPath: String?,
    appBackgroundPath: String?
): String? {
    if (!detailPath.isNullOrBlank()) return detailPath
    return appBackgroundPath?.takeIf { it.isNotBlank() }
}

/**
 * 详情页顶栏玻璃面的压暗填充色（纯函数，便于 JVM 单测锁对比度）。
 *
 * 黑色 + [BookInfoBackdropSurface.TITLE_BAR_SCRIM_ALPHA] 下限，对比度推导见该常量的
 * KDoc：白字对最坏底图 ≥ 4.5:1。
 */
internal fun titleBarScrimColor(): Int =
    ColorUtils.setAlphaComponent(
        android.graphics.Color.BLACK,
        (BookInfoBackdropSurface.TITLE_BAR_SCRIM_ALPHA * 255).toInt()
    )

/** 日间最坏底图：模糊详情图纯黑（`vw_bg` 的 31% 黑遮罩叠加后仍是纯黑）。 */
internal const val BOOK_INFO_DARK_BACKDROP_GRAY = 0

/** 夜间最坏底图：纯白底图经 `vw_bg` 31% 压暗后的灰度（255 × 0.69 ≈ 176）。 */
internal const val BOOK_INFO_LIGHT_BACKDROP_GRAY = 176

/**
 * 「阅读」主按钮在玻璃档下的文字色（纯函数，JVM 单测锁表）。
 *
 * 半透明 accent 面背后是模糊详情图 + `vw_bg` 遮罩，文字对比度要按**最坏底图**算：
 * 黑字的最坏面出现在日间（底图纯黑把面压暗），白字的最坏面出现在夜间（底图 176 灰
 * 把面提亮）。两个方向各取最坏值，选最坏对比度更高的那个；原 `AccentBgTextView` 的
 * 「按 accent 亮度二选一」是为不透明面设计的，半透明面被底图拉偏后会失准，这里按
 * **实际面色**重算。
 *
 * [fillAlpha] 必须与绘制用的 [BookInfoBackdropSurface.readButtonFillAlpha]
 * 同源，否则文字色与面色失配。
 */
internal fun accentButtonTextColor(accentColor: Int, fillAlpha: Float): Int {
    val onDark = blendOverBackdrop(accentColor, fillAlpha, BOOK_INFO_DARK_BACKDROP_GRAY)
    val onLight = blendOverBackdrop(accentColor, fillAlpha, BOOK_INFO_LIGHT_BACKDROP_GRAY)
    val blackWorst = contrastRatio(BLACK_OPAQUE, onDark)
    val whiteWorst = contrastRatio(WHITE_OPAQUE, onLight)
    return if (blackWorst >= whiteWorst) BLACK_OPAQUE else WHITE_OPAQUE
}

private const val BLACK_OPAQUE = 0xFF000000.toInt()
private const val WHITE_OPAQUE = 0xFFFFFFFF.toInt()

/** 半透明填充叠在单色底图上的合成色（逐通道线性插值，纯算术，不依赖 Android 运行时）。 */
private fun blendOverBackdrop(fill: Int, alpha: Float, backdropGray: Int): Int {
    val a = alpha.coerceIn(0f, 1f).toDouble()
    fun channel(shift: Int): Int {
        val value = ((fill shr shift) and 0xFF).toDouble()
        // Double 运算 + round，与单测的独立复算逐位同口径（Float 会差出 1/255 抖动）
        return Math.round(value * a + backdropGray * (1 - a)).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/** WCAG 相对亮度（sRGB 通道线性化后按 0.2126 / 0.7152 / 0.0722 加权）。 */
private fun relativeLuminance(color: Int): Double {
    fun channelToLinear(shift: Int): Double {
        val c = ((color shr shift) and 0xFF) / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channelToLinear(16) +
        0.7152 * channelToLinear(8) +
        0.0722 * channelToLinear(0)
}

/** WCAG 对比度（亮的在分子，恒 ≥ 1）。 */
private fun contrastRatio(a: Int, b: Int): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** 区域裁剪参数的合法性守卫（纯函数，JVM 单测锁「0 半径不产出位图」）。 */
internal fun backdropCropParamsValid(outWidth: Int, outHeight: Int, blurRadius: Int): Boolean =
    blurRadius > 0 && outWidth > 0 && outHeight > 0

/**
 * 将 ViewPager2 次页卡片的屏幕 X 坐标按页宽归一化到当前屏内区间（纯函数，JVM 单测锁表）。
 *
 * 次页卡片在水平方向处于屏幕外，直接与全屏 `bg_book` 求交会得到空矩形并退化为无底图壳层；
 * 当 `pagerWidth <= 0` 时原值返回。
 */
internal fun normalizePagerHorizontalOffset(hostX: Int, pagerX: Int, pagerWidth: Int): Int {
    if (pagerWidth <= 0) return hostX
    val relX = ((hostX - pagerX) % pagerWidth + pagerWidth) % pagerWidth
    return pagerX + relX
}

object BookInfoBackdropSurface {

    /**
     * 卡片填充的 alpha 上限。
     *
     * 这个上限由**正文对比度**倒推，不是观感偏好。本页正文与次要文字都是半透明文字色
     * （日间 `#de000000` / `#8a000000`，夜间 `#ffffffff` / `#b3ffffff`），它们会跟着
     * 卡面一起变暗 / 变亮，所以卡面越透、对比度越低。按 WCAG 相对亮度、最坏底图
     * （日间纯黑模糊底图 / 夜间纯白模糊底图，且都已过 `vw_bg` 的 31% 压暗）实算：
     *
     * | 卡面 alpha | 日间 主文字 / 次要文字 | 夜间 主文字 / 次要文字 |
     * |---|---|---|
     * | 1.00（接入前） | 12.9 : 1 / 4.6 : 1 | 17.0 : 1 / 8.9 : 1 |
     * | **0.80（本值）** | **11.3 : 1 / 4.1 : 1** | **11.4 : 1 / 6.6 : 1** |
     * | 0.62（更透） | 7.0 : 1 / 3.4 : 1 | 7.5 : 1 / 4.7 : 1 |
     *
     * 0.62 会把日间次要文字（作者 / 最近阅读 / 阅读时长）压到 3.4:1，属于「为了玻璃糊字」；
     * 取 0.80 后仍能透出 20% 的模糊底图，主文字全部 ≥11:1，次要文字与接入前同档
     * （日间 4.1 vs 4.6，夜间 6.6 vs 8.9）。想更透只能同时改本页文字色，不是调这一个数。
     *
     * 仍然受用户既有「界面不透明度」约束（取两者较小值），不新增偏好键。
     */
    private const val PANEL_ALPHA = 0.80f

    /** 与 `UiCorner.surfaceColor(pressed = true)` 相同的按压增量。 */
    private const val PRESSED_ALPHA_STEP = 0.08f

    /**
     * 顶栏玻璃面的压暗 alpha。
     *
     * 与 [PANEL_ALPHA] 方向相反：顶栏 `app:themeMode="dark"`，标题 / 图标**恒为白色**，
     * 玻璃面越透白字对比度越低，所以这里倒推出来的是**下限**而不是上限。按 WCAG、
     * 最坏底图（`vw_bg` 的 31% 黑遮罩之后的底图：日间纯黑模糊图 → 0，夜间纯白 →
     * 255 × 0.69 ≈ 176 灰）实算：黑面 alpha 0.35 时最坏面色 ≈ 114 灰，白字 4.8:1 ≥ 4.5，
     * 且比接入前（透明 + 纯遮罩，最坏 ≈ 2.2:1）是净提升。
     *
     * **不叠「界面不透明度」**：该偏好的语义是「面越透」（卡面取 min(layoutAlpha, 上限)），
     * 而本面 alpha 越大对比度越高，照搬 min 口径在默认 layoutAlpha=1.0 时会退化成
     * 「不透明黑条」——因此钉死为对比度倒推值。
     */
    internal const val TITLE_BAR_SCRIM_ALPHA = 0.35f

    /** 卡片 / 面板背景；null = 保持接入前的主题实底。 */
    fun panelBackground(context: Context, radiusPx: Float): Drawable? =
        panelBackground(context, radiusPx, backdrop = null)

    /**
     * 卡片 / 面板背景（带真实模糊底图）。
     *
     * [backdrop] 是 `bg_book` 在**本卡片背后的那一块区域**的区域截图 + 软件模糊结果，
     * 由 `BookInfoActivity` 在视图完成布局后取一次；纯色背景 / 截图失败时为 null，
     * 此时仍然铺壳层 + 折射边缘（[GlassLiquidBackdropDrawable] 的安全降级），
     * 不会退回死板实底。
     *
     * 填充 alpha 仍是 [PANEL_ALPHA]（WCAG 倒推的下限），本函数只新增可见的玻璃边缘，
     * 不动任何一档的对比度。
     */
    fun panelBackground(context: Context, radiusPx: Float, backdrop: Bitmap?): Drawable? {
        clearDrawableIfApplicable(context, radiusPx)?.let { return it }
        if (!isAllowed(context)) return null
        return glassFrost(
            context = context,
            backdrop = backdrop,
            tintColor = fillColor(context, panelAlpha()),
            radiusPx = radiusPx,
            corners = SurfaceCorners.ALL
        )
    }

    /** 操作排按钮背景（带按压态）；null = 保持接入前的 `book_info_frost` 表面。 */
    fun actionBackground(context: Context, radiusPx: Float): Drawable? =
        actionBackground(context, radiusPx, backdrop = null)

    /** 操作排按钮背景（带模糊底图）；纯色 / 截图失败时 [backdrop] 传 null。 */
    fun actionBackground(context: Context, radiusPx: Float, backdrop: Bitmap?): Drawable? {
        clearDrawableIfApplicable(context, radiusPx)?.let { return it }
        if (!isAllowed(context)) return null
        val alpha = panelAlpha()
        // 按钮只有一行的高度，壳层按「按下 / 默认」两份分别构造，保留既有按压反馈。
        val default = glassFrost(
            context, backdrop, frostFillColor(context, alpha), radiusPx, SurfaceCorners.ALL
        )
        val pressed = glassFrost(
            context,
            backdrop,
            frostFillColor(context, (alpha + PRESSED_ALPHA_STEP).coerceAtMost(1f)),
            radiusPx,
            SurfaceCorners.ALL
        )
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), default)
        }
    }

    /**
     * 顶栏玻璃面（零采样；悬浮卡半径由顶栏包 chrome 决定，非悬浮保持贴边通栏直角）。
     *
     * 本页顶栏在 `onActivityCreated` 里已被固定为透明背景
     * （`titleBar.setBackgroundResource(R.color.transparent)`），背后是 `bg_book`
     * （详情背景图或纯色）+ `vw_bg` 遮罩，因此与卡片共用同一份零采样判定
     * （[bookInfoTranslucentSurfaceAllowed]，只认玻璃档，不再要求背景图偏好非空）。
     * 判否返回 null，调用方保持 / 还原页面的透明背景，逐值不变。
     *
     * [cornerRadiusPx] 是页面自管表面（`TitleBar.applyPageOwnedSurface`）传进来的
     * chrome 半径：悬浮 chrome 开启时顶栏已被 margins 内缩成卡片，面必须同圆角，
     * 否则就是真机上「悬浮了却不是圆角」的方角卡；非悬浮（floatingChrome 关）传 0，
     * 逐值保持 1.31.1 及以前的贴边通栏直角。半径只消费不解析——解析唯一真相源是
     * [io.legado.app.lib.theme.TopBarChromeSpec]，由 TitleBar 统一算好传入。
     *
     * 填充是黑色压暗而不是卡面色：白字在日间浅色卡面上叠 0.80 alpha 反而会跌破
     * 接入前的对比度（推导见 [TITLE_BAR_SCRIM_ALPHA]），黑面与 `vw_bg` 同极性，
     * 两种主题下都只提升白字对比度。描边与卡面同一份 `glass_stroke`。
     */
    fun titleBarBackground(
        context: Context,
        cornerRadiusPx: Float = 0f,
        backdrop: Bitmap? = null
    ): Drawable? {
        // CLEAR 档：卡片 / 按钮走 clearDrawableIfApplicable 铺了半透明壳，顶栏若沿
        // isAllowed 判否回全透明，就成了同档里唯一没有壳的表面。这里按同一份
        // clearSurfaceColor 口径铺壳；底色仍取黑（顶栏白字恒定的对比度前提），
        // alpha 由 CLEAR_SURFACE_ALPHA 统一，描边与卡片同一份 glass_stroke。
        if (resolvedExploreGlassMode() == GlassMaterialPreset.CLEAR) {
            return UiCorner.opaqueRoundedStroke(
                GlassMaterialPreset.clearSurfaceColor(android.graphics.Color.BLACK),
                cornerRadiusPx,
                1.dpToPx(),
                glassStrokeColor(context)
            )
        }
        if (!isAllowed(context)) return null
        return glassFrost(
            context = context,
            backdrop = backdrop,
            tintColor = titleBarScrimColor(),
            radiusPx = cornerRadiusPx,
            corners = SurfaceCorners.ALL,
            lightBase = false
        )
    }

    /**
     * 「阅读」主按钮（`tv_read`）的玻璃面：保留 accent 色相，只把填充让出 alpha。
     *
     * 不与 tvShelf / tvTocJump 共用 [actionBackground]（`book_info_frost`）：那是
     * 次级按钮的既有底色，换上去主按钮就失去了「accent 主操作」的语义。按压态沿用
     * `AccentBgTextView` 的 darken 增量、判定与描边同卡面，唯一变化是材质透明度。
     * 判否返回 null，调用方还原 `AccentBgTextView` 的自绘背景与文字色。
     */
    fun readButtonBackground(context: Context, radiusPx: Float): Drawable? {
        val accent = ThemeStore.accentColor(context)
        // CLEAR 档：与卡片 CLEAR 壳同一份口径（accent 色相 × CLEAR alpha + 描边），
        // 只把填充换成半透明壳；判否回实底的路径不动。按压态沿用 darken 增量，
        // 同样过 clearSurfaceColor，两态只差色深不差 alpha。
        if (resolvedExploreGlassMode() == GlassMaterialPreset.CLEAR) {
            return Selector.shapeBuild()
                .setCornerRadius(radiusPx.toInt())
                .setDefaultBgColor(GlassMaterialPreset.clearSurfaceColor(accent))
                .setPressedBgColor(
                    GlassMaterialPreset.clearSurfaceColor(
                        io.legado.app.utils.ColorUtils.darkenColor(accent)
                    )
                )
                .create()
        }
        if (!isAllowed(context)) return null
        val alpha = readButtonFillAlpha()
        return Selector.shapeBuild()
            .setCornerRadius(radiusPx.toInt())
            .setDefaultBgColor(withAlpha(accent, alpha))
            .setPressedBgColor(
                withAlpha(io.legado.app.utils.ColorUtils.darkenColor(accent), alpha)
            )
            .create()
    }

    /**
     * 「阅读」按钮玻璃档的填充 alpha，与卡面同口径（界面不透明度与 0.80 上限取小）。
     * CLEAR 档的填充走 [GlassMaterialPreset.clearSurfaceColor]（固定
     * [GlassMaterialPreset.CLEAR_SURFACE_ALPHA]），文字色必须与实际面色同 alpha
     * 才不失配，这里按档位返回对应值。
     * 单独暴露是因为文字色（[accentButtonTextColor]）必须与绘制用同一个 alpha 算，
     * 两处各写一份迟早失配。
     */
    internal fun readButtonFillAlpha(): Float =
        if (resolvedExploreGlassMode() == GlassMaterialPreset.CLEAR) {
            GlassMaterialPreset.CLEAR_SURFACE_ALPHA
        } else {
            panelAlpha()
        }

    /**
     * 卡片内部的「卷」分组行（目录页的 `chapter.isVolume` 行）。
     *
     * 它本身也是一块实底（`themeMutedColorOrDefault()`，见 `bindCatalogRow`）。不跟着
     * 让 alpha 的话，半透明的目录卡里会留一条不透明带 —— 正是「一块玻璃、一块实底」。
     * 色相仍取 muted，与「章」行靠颜色而不是靠不透明度区分。
     */
    fun sectionBackground(context: Context, radiusPx: Float): Drawable? =
        sectionBackground(context, radiusPx, backdrop = null)

    /** 「卷」分组行（带模糊底图）；纯色 / 截图失败时 [backdrop] 传 null。 */
    fun sectionBackground(context: Context, radiusPx: Float, backdrop: Bitmap?): Drawable? {
        clearDrawableIfApplicable(context, radiusPx)?.let { return it }
        if (!isAllowed(context)) return null
        return glassFrost(
            context = context,
            backdrop = backdrop,
            tintColor = ColorUtils.setAlphaComponent(
                context.themeMutedColorOrDefault(),
                (panelAlpha() * 255).toInt()
            ),
            radiusPx = radiusPx,
            corners = SurfaceCorners.ALL
        )
    }

    /**
     * 本页 `bg_book` 应当加载的背景图路径（详情图优先，回退 App 级背景图）。
     *
     * 暴露给 `BookInfoActivity.applyBookInfoBackground` 的原因：取图与玻璃判定必须
     * **同一个口径**——各自读一遍偏好迟早分叉（一边判了有图、一边却加载了 null），
     * 分叉的两种结果都直接写在真机脸上：卡片半透明而背后纯色，或整页回退实底。
     */
    fun bookInfoBackdropImagePath(context: Context): String? {
        val isNight = AppConfig.isNightTheme
        val detailPath = context.getPrefString(
            if (isNight) PreferKey.bookInfoBgImageN else PreferKey.bookInfoBgImage
        )
        val appBackgroundPath = context.getPrefString(
            if (isNight) PreferKey.bgImageN else PreferKey.bgImage
        )
        return resolveBookInfoBackdropImagePath(detailPath, appBackgroundPath)
    }

    /**
     * 透明无玻璃档：卡片铺主题包表面色的半透明层，透出背后封面/背景但不模糊、不折射。
     * 命中返回 drawable，其余档返回 null 交给原玻璃判定链路。
     */
    private fun clearDrawableIfApplicable(context: Context, radiusPx: Float): Drawable? {
        if (resolvedExploreGlassMode() != GlassMaterialPreset.CLEAR) return null
        return GlassMaterialPreset.createSolidShellDrawable(
            fillColor = GlassMaterialPreset.clearSurfaceColor(context.themeCardColorOrDefault()),
            borderColor = glassStrokeColor(context),
            cornerRadiusPx = radiusPx,
            oval = false,
            strokeWidthPx = 1.dpToPx()
        )
    }

    /**
     * 详情页唯一的玻璃 drawable 构造点（卡片 / 按钮 / 分组行共用）。
     *
     * 恒走 [GlassLiquidBackdropDrawable]——与底栏 / 目录页同一颗 AGSL 着色器
     * （真折射 + 色散 + GPU 模糊），材质参数只从 [GlassMaterialPreset.current] 读。
     * 这是反复进入详情页观感跳变的根除点：
     *
     * - **观感只由档位决定**。参数只有一份 [GlassMaterialPreset.current]，不再有第二套
     *   手绘管线（旧 `GlassFrostDrawable`）在不同时机给出不同观感；
     * - [backdrop] 退化为纯采样输入：有图 = 折射 + 色散细节更丰富，无图 / 被回收 =
     *   同档的纯壳层玻璃（见 [GlassLiquidBackdropDrawable.draw] 的安全降级）。
     *   两者是同一档的「细节差异」，不是「玻璃 / 磨砂 / 纯色」的类别跳变。
     *
     * 于是 Glide 缓存命中与否、视图是否完成布局、后台裁剪协程是否被取消这些时序变量，
     * 只影响玻璃细节的多寡，再也不能改变用户看到的材质档位。
     */
    private fun glassFrost(
        context: Context,
        backdrop: Bitmap?,
        tintColor: Int,
        radiusPx: Float,
        corners: SurfaceCorners,
        lightBase: Boolean = AppColorUtils.isColorLight(context.themeCardColorOrDefault())
    ): Drawable {
        val density = context.resources.displayMetrics.density
        val params = GlassMaterialPreset.current(resolvedExploreGlassMode())
        return GlassLiquidBackdropDrawable(
            context = context,
            backdrop = backdrop,
            material = params,
            density = density,
            cornerRadiusPx = radiusPx,
            corners = corners,
            strokeColor = glassStrokeColor(context),
            strokeWidthPx = 1.dpToPx().toFloat(),
            tintColor = tintColor,
            surfaceRgb = glassFrostSurfaceRgb(lightBase = lightBase),
            shell = params.shell
        )
    }

    /**
     * 是否需要真实截图底图（玻璃档可用且非 CLEAR）。
     *
     * false 时调用方可**同步**铺完所有表面：CLEAR 走壳层、判否走实底回退，全程无位图
     * 工作；true 时调用方走「主线程一次快照 + 后台裁剪模糊」的异步管线。
     */
    fun backdropGlassCapable(context: Context): Boolean = isAllowed(context)

    /** 底图快照 / 区域裁剪共用的降采样比例（与模糊半径的折算口径一致）。 */
    const val BACKDROP_DOWNSCALE = 3f

    /**
     * 把 [source]（`bg_book`）整幅画到一张降采样位图上（**主线程、一次 draw、无模糊**）。
     *
     * 7 张卡片 / 按钮各截各的旧方案会在主线程连做 7 次 `View.draw` + `stackBlurSoftware`；
     * 改为这里只画一次全幅降采样快照（1/3 尺寸，纯 ImageView 绘制，开销可控），
     * 各卡片的区域裁剪与软件模糊交给 [cropBackdropRegion] 在后台线程做。
     *
     * 视图未布局 / 尺寸为 0 / 建位图失败返回 null，调用方同步铺「纯壳层玻璃」。
     */
    fun captureBackdropSnapshot(source: View, downscale: Float): Bitmap? {
        if (source.width <= 0 || source.height <= 0) return null
        val outWidth = (source.width / downscale).toInt().coerceAtLeast(1)
        val outHeight = (source.height / downscale).toInt().coerceAtLeast(1)
        val raw = runCatching {
            Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        val scaleX = outWidth.toFloat() / source.width
        val scaleY = outHeight.toFloat() / source.height
        val drawn = runCatching {
            val canvas = Canvas(raw)
            canvas.scale(scaleX, scaleY)
            source.draw(canvas)
        }.isSuccess
        if (!drawn) {
            raw.recycle()
            return null
        }
        return raw
    }

    /**
     * 从 [captureBackdropSnapshot] 的快照里裁出 [regionInSnapshot]（**快照坐标系**）那块
     * 区域并做软件模糊（**纯位图运算，可在任意线程执行**）。
     *
     * 任一步失败都返回 null，调用方退回「纯壳层玻璃」，绝不交回未模糊的清晰快照。
     * 模糊半径走 [ExploreGlassBackdrop.exploreSoftwareBlurRadius] 同一口径；背景图
     * 已经由 `BookCover.loadBlur(12)` 预模糊一次，这里再糊只是把卡片区域里的残留
     * 细节压成雾面，不会出现双重模糊导致的「糊成一整片同色」（底图本身没有文字）。
     */
    fun cropBackdropRegion(
        snapshot: Bitmap,
        regionInSnapshot: Rect,
        outWidth: Int,
        outHeight: Int,
        blurRadius: Int
    ): Bitmap? {
        if (!backdropCropParamsValid(outWidth, outHeight, blurRadius)) return null
        val clipped = Rect(regionInSnapshot).apply {
            intersect(0, 0, snapshot.width, snapshot.height)
        }
        if (clipped.isEmpty || clipped.width() <= 0 || clipped.height() <= 0) return null
        val raw = runCatching {
            Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        val scaleX = outWidth.toFloat() / clipped.width()
        val scaleY = outHeight.toFloat() / clipped.height()
        val drawn = runCatching {
            val canvas = Canvas(raw)
            canvas.scale(scaleX, scaleY)
            canvas.translate(-clipped.left.toFloat(), -clipped.top.toFloat())
            canvas.drawBitmap(snapshot, 0f, 0f, null)
        }.isSuccess
        if (!drawn) {
            raw.recycle()
            return null
        }
        val blurred = runCatching { raw.stackBlurSoftware(blurRadius) }.getOrNull()
        if (blurred == null || blurred === raw) {
            raw.recycle()
            return null
        }
        raw.recycle()
        return blurred
    }

    private fun isAllowed(context: Context): Boolean = bookInfoTranslucentSurfaceAllowed(
        glassEnabled = GlassSurface.wallpaperGlassEnabled(context)
    )

    /**
     * 让位后的卡面填充色。
     *
     * 用「主题卡片色 + 上限 alpha」而不是固定的 `book_info_frost`：本页卡面原本就是
     * `themeCardColorOrDefault()`（见 `BookInfoActivity.applyUiCorners`），换色会让夜间 /
     * 自定义主题下的卡片偏色，这里只改 alpha 不改色相。
     */
    private fun panelAlpha(): Float = UiCorner.layoutAlpha().coerceAtMost(PANEL_ALPHA)

    private fun fillColor(context: Context, alpha: Float): Int =
        ColorUtils.setAlphaComponent(
            context.themeCardColorOrDefault(),
            (alpha.coerceIn(0f, 1f) * 255).toInt()
        )

    /**
     * 操作排按钮的填充色：保留 `@color/book_info_frost` 的色相（它是本页次级按钮的既有底色），
     * 只把 alpha 换成上限值。换成卡面色会让按钮与卡片同色，"次级按钮"的语义消失。
     */
    private fun frostFillColor(context: Context, alpha: Float): Int =
        ColorUtils.setAlphaComponent(
            androidx.core.content.ContextCompat.getColor(context, R.color.book_info_frost),
            (alpha.coerceIn(0f, 1f) * 255).toInt()
        )

    /** 1dp 描边：统一取主题包「边框线色」，与底栏 / 卡片 / 弹窗边界同一份取色口径。 */
    private fun glassStrokeColor(context: Context): Int = UiCorner.borderLineColor(context)

    /** 只改 alpha 不改色相的填充色工具（accent 面与按压态共用）。 */
    private fun withAlpha(color: Int, alpha: Float): Int =
        ColorUtils.setAlphaComponent(color, (alpha.coerceIn(0f, 1f) * 255).toInt())
}
