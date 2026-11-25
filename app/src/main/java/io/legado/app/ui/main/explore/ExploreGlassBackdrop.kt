package io.legado.app.ui.main.explore

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.MainThemeBackgroundState
import io.legado.app.ui.widget.compose.ComposeThemeImageCrop
import io.legado.app.utils.ColorUtils as AppColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.stackBlurSoftware
import java.io.File
import kotlin.math.max

/**
 * 发现页书源名行的毛玻璃背景。
 *
 * 取主界面背景图层（与主题背景同一文件/裁剪/模糊设置）生成一张按屏幕对齐的降采样底图，
 * 行背景按行在屏幕上的实时位置采样这张底图，滚动时随位置重新采样，
 * 得到「透出背后壁纸并磨砂」的玻璃观感，避免书源名与壁纸内容直接重合。
 *
 * 1.25.10 起再增加**第三条、优先级最高**的管线（方案 A2，仅 API 33+）：
 * [RowGlassLiquidRenderer] 用底栏那颗同一份 AGSL（MIT，已复制到
 * `res/raw/glass_row_liquid.agsl`）在**未预模糊的 raw 位图**上做真折射 + 色散，
 * 并由 GPU 高斯完成模糊（`createChainEffect`，与底栏库同一顺序）。
 * 它**不复用底层库的 Java 宿主**、不 `bind` 任何视图、不对任何 `View` 调
 * `beginRecording` + `View.draw()`；录制内容永远只有我们自己画的一条 `drawRect`，
 * 因此 1.25.8 的 RenderNode 重入录制崩溃（native abort）在本路径上结构性不存在。
 * 材质、壳层、描边、色散与折射**全部**直接消费 [GlassMaterialPreset.current] 的同一个
 * [GlassMaterialPreset.Params]：底栏、顶栏、行三处读同一组键、走同一个纯函数，
 * 因此同档参数在构造上必然一致。行内不再有 tint / 壳层 / 高光增益，也没有只在行上
 * 叠加的拟液态层——那些层会让同一档参数下行比底栏更白、更厚，正是要修的偏差。
 * 该层失败或被降级时，下面两条既有管线**原样接管**，观感连续、不留空白帧。
 *
 * 模糊有两条管线，按设备能力二选一（②③ 的软件管线始终保留，是硬件管线的降级基线）：
 * - **硬件管线（API 31+）**：底图不做软件模糊，改由 [HardwareGlassLayer] 把「玻璃层」
 *   录进一个独立 [RenderNode]，对其设置 [RenderEffect.createBlurEffect] 交给 GPU 做真高斯。
 *   RenderEffect 只作用于该 RenderNode 自身的绘制，不影响 tint/shell/描边，更不会影响
 *   同层的书源名文字，因此不需要把整行 View 一起模糊。
 * - **软件管线（API 21-30 / 降级）**：底图在 IO 线程经 [stackBlurSoftware] 预模糊一次，
 *   行绘制用 `BitmapShader` 采样，即 ②③ 已实测验证的既有实现。软件模糊只由该档
 *   `blurDp × 模糊倍率` 决定，不再叠加主题背景的模糊偏好——生产背景层对壁纸根本
 *   不做模糊（该偏好只关闭动图），把 `state.blur` 计进半径等于凭空多糊一层。
 *
 * 两条管线共用同一份底图构建（decode + crop 对齐 + fallbackColor）、同一套位置缓存
 * （[GlassPositionTracker]）与同一套 shell/tint/描边参数，保证降级时观感连续。
 *
 * 材质三档（自动预设，与底栏液态玻璃完全一致，见 [GlassMaterialPreset]）：
 * - **无玻璃 [EXPLORE_GLASS_MODE_SOLID]**：由纯函数 [exploreSolidShellColor] 把
 *   [GlassMaterialPreset.createSolidShellDrawable] 填充色重载的入参钉成 alpha=255，
 *   得到贴主题底色、完全不透明的纯色块；不受图标包 opacity、玻璃强度、墨水屏/低内存
 *   降级影响，也不再透出壁纸。实底描边仍只读全局设置，行不额外兜边
 *   （兜边只用于浮在行之上的底栏，见 `GlassMaterialPreset.solidShellStroke`）；
 * - **透明玻璃 [EXPLORE_GLASS_MODE_GLASS]**：取 glass 档整组参数（`3 + l*6`dp 轻模糊、
 *   `0.004 + l*0.02` 近乎无色着色、更强色散、更深折射），行上表现为通透可见内容的玻璃；
 * - **磨砂玻璃 [EXPLORE_GLASS_MODE_FROSTED]**：取 frosted 档整组参数（`20 + l*24`dp 强模糊、
 *   `0.18 + l*0.20` 奶白着色、色散收敛、折射更浅），行上表现为糊成色块的奶白雾面。
 * 两档差异同时落在模糊 / 着色 / 色散 / 折射几何四条可见维度上，而不是同一观感的深浅版本；
 * 三档取值只有 [GlassMaterialPreset] 一份来源，行内不做任何再标定。
 * 1.25.x 的「跟随底栏材质」开关已移除（启动时一次性迁移固化档位与强度）。
 *
 * 降级矩阵（任一条命中都不留空白帧，也不退回未模糊的 raw）：
 * - 无玻璃档：完全不透明纯色块（不受玻璃强度 / 墨水屏 / 低内存影响）；
 * - 墨水屏 / 低内存：返回 null，用原透明背景；
 * - 动图壁纸且「使用纯色底」开启：`backdrop()` 返回 null，玻璃层不采样，
 *   只留 tint/shell/描边纯实底；关闭该开关才允许采样静止首帧；
 * - API 21-30：不创建 [HardwareGlassLayer]，直接走软件管线；
 * - API 31+ 但 RenderEffect 构造失败：init 的 `runCatching` 得到 null，走软件管线；
 * - 非硬件加速 Canvas：当帧走软件管线，不置持久故障位（截图/软件 layer 是临时状态）；
 * - `drawRenderNode` 在硬件 Canvas 上抛异常：置 `hardwareFailed`，该行永久走软件管线；
 * - raw/blurred 任一缺失或已回收：raw 缺失直接让位软件管线（避免双重模糊），
 *   blurred 缺失则只画 shell，绝不退回未模糊的 raw；
 * - `stackBlurSoftware` 构建异常：blurred 留 null（不再兜底为 raw），软件管线只画 shell，
 *   硬件管线仍可用 raw 由 GPU 出图。
 * 由于 [GlassBackdrop] 同时缓存两份底图，以上降级都能「当帧」完成，无需重新异步构建。
 *
 * 参数来源：档位、两个强度键与四个倍率全部由 [GlassMaterialPreset.current] 读取；
 * 本文件只读档位、描边色、硬件模糊与动图开关这四个非材质键。[EXPLORE_GLASS_PREF_KEYS]
 * 汇总所有影响观感的键，供 ExploreFragment 注册即时重绑监听，与 [configSignature]
 * 的 onResume 兜底一起保证「改完返回发现页即生效」。
 */
object ExploreGlassBackdrop {

    /**
     * 软件管线 `stackBlurSoftware` 的位图像素半径（纯函数，便于单测）。
     * 输入是 [GlassMaterialPreset.Params.blurDp]（已含档位与模糊倍率），与硬件 / 液态
     * 管线读同一个值。主题背景的模糊偏好**不参与**：生产背景层对壁纸不做模糊（该偏好
     * 只关闭动图播放），把它计进半径会让软件管线凭空多糊一层，与底栏观感不一致。
     * blurDp = 0（模糊倍率 0）时返回 0 = 不模糊，与硬件/液态管线传 0 的语义一致。
     */
    fun exploreSoftwareBlurRadius(
        blurDp: Float,
        density: Float
    ): Int {
        if (blurDp <= 0f) return 0
        return (blurDp * density * STACK_BLUR_DENSITY_FACTOR / DOWNSCALE)
            .toInt().coerceIn(1, 25)
    }

    /**
     * 软件模糊位图半径的换算系数：GPU 高斯半径 → stackBlurSoftware 等效半径的 1.4 倍，
     * 再除以 [DOWNSCALE]。blurDp 本身来自 [GlassMaterialPreset.Params.blurDp]，
     * 发现页不再自己定义 `12 + l*18` 这类公式。
     */
    private const val STACK_BLUR_DENSITY_FACTOR = 1.4f

    /**
     * 背景位图相对屏幕的降采样比例。
     * 值越小采样越细、越接近原图，能有效减少降采样导致的摩尔纹/色块，
     * 代价是构建位图变大、软件模糊耗时上升（构建已放 IO 线程且仅首次 miss 时执行一次）。
     * 屏幕等效模糊半径由 [backdrop] 的 radius 公式按 DOWNSCALE 反比例换算，故改此值不改变观感强度。
     */
    private const val DOWNSCALE = 3f

    private data class BackdropCache(
        val key: String,
        val backdrop: GlassBackdrop?
    )

    /**
     * 未模糊底图缓存（键只含壁纸/裁剪/夜间/目标尺寸/fallbackColor）。
     * 与 [cache] 分开：调整模糊倍率时只需复用 raw 重新做一次软件模糊，
     * 不必重新 decode + 裁剪；设置页预览拖动模糊滑杆时差别最明显。
     */
    private data class RawCache(
        val key: String,
        val raw: Bitmap
    )

    @Volatile
    private var cache: BackdropCache? = null

    @Volatile
    private var rawCache: RawCache? = null

    //按 key 去重的在途构建任务与等待回填的 drawable，避免多行同时 miss 重复构建
    private val inFlightKeys = HashSet<String>()
    private val pendingCallbacks = HashMap<String, ArrayList<(GlassBackdrop?) -> Unit>>()

    /**
     * 配置签名：任何影响观感的设置项变化都会改变它。
     * ExploreFragment 在 onResume 时用它做兜底重绑（另有 [EXPLORE_GLASS_PREF_KEYS]
     * 的即时偏好监听），因此新增细项后不必再逐个加监听——只要参数进快照，
     * 返回发现页即生效。材质签名覆盖档位、两个强度与四个倍率（[GlassMaterialPreset]
     * 的唯一入口），非材质键单独拼上；描边色 raw 值纳入签名（颜色变化需重绑行背景）。
     */
    fun configSignature(context: Context): String {
        val config = readExploreGlassConfig(context)
        return listOf(
            config.mode,
            GlassMaterialPreset.signature(GlassMaterialPreset.current(config.mode)),
            config.strokeColorRaw.orEmpty(),
            config.hardwareBlur,
            config.animatedSolid,
            AppConfig.isNightTheme
        ).joinToString("|")
    }

    /** 屏幕对齐的降采样壁纸（未模糊）；设置页预览用它铺底，与行玻璃采样同一份底图。 */
    fun previewWallpaper(context: Context, onReady: (Bitmap?) -> Unit): Bitmap? {
        val config = readExploreGlassConfig(context)
        val material = GlassMaterialPreset.current(config.mode)
        return backdrop(context, config, material) { onReady(it?.raw) }?.raw
    }

    /**
     * 低内存设备判定（设备级事实，非偏好）。
     *
     * 公开给 [io.legado.app.ui.glass.GlassSurface.isAvailable]：Compose 桥需要在组合期
     * 提前决定「画玻璃层还是回退原背景」，必须与 [glassDrawable] 的降级判定同源，
     * 不能各写一份阈值。
     */
    fun isLowRamDevice(context: Context): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

    /**
     * 无玻璃档：完全不透明的纯色块（贴主题底色），不再是半透明壳层。
     *
     * 1.25.5 把图标包 `config.opacity`（内置默认 76、Config 默认 72）直接传给
     * [GlassMaterialPreset.createSolidShellDrawable]，经 `solidOpacity` 得到
     * 0.72~0.76 的 alpha，行背景仍然透出壁纸——这正是真机反馈的「无玻璃没变成
     * 不透明纯色块」。这里只收集 Android 侧参数，颜色与全部构造规格统一由
     * [withExploreSolidShellSpec] 产出（填充色 alpha 钉死为 255），函数体只做
     * 字段展开交给填充色重载；底栏仍走原 `solidOpacity`，观感逐值不变。
     * 底色、圆角与描边依旧复用同一实现，不另写一份 drawable。
     * 描边色自 1.25.8 起统一读全局 [PreferKey.glassStrokeColor]（未设置 = 不描边）。
     * 行不参与底栏的兜边：行直接贴在壁纸上，同色底不会让轮廓消失；
     * 只有浮在行之上的底栏才需要 `GlassMaterialPreset.solidShellStroke` 兜边。
     */
    private fun solidShellDrawable(
        context: Context,
        cornerRadiusPx: Float = context.resources.getDimension(R.dimen.ui_panel_radius)
    ): GradientDrawable {
        val navConfig = NavigationBarIconConfig.currentEntry(AppConfig.isNightTheme).config
        return withExploreSolidShellSpec(
            baseColor = ThemeStore.bottomBackground(context),
            iconOpacity = navConfig.opacity,
            borderColor = glassStrokeColor(context),
            cornerRadiusPx = cornerRadiusPx,
            strokeWidthPx = 1.dpToPx()
        ) { spec ->
            GlassMaterialPreset.createSolidShellDrawable(
                fillColor = spec.fillColor,
                borderColor = spec.borderColor,
                cornerRadiusPx = spec.cornerRadiusPx,
                oval = spec.oval,
                strokeWidthPx = spec.strokeWidthPx
            )
        }
    }

    /**
     * 透明无玻璃档的表面壳：主题包底色 + [GlassMaterialPreset.CLEAR_SURFACE_ALPHA] 半透明。
     * 只有圆角填充与可选描边，没有模糊 / 折射 / 色散 / 白色着色——「只透明」的落点。
     * 与 solid 壳同为 GradientDrawable 薄映射，差别只在填充色的 alpha。
     */
    private fun clearShellDrawable(
        context: Context,
        cornerRadiusPx: Float = context.resources.getDimension(R.dimen.ui_panel_radius)
    ): GradientDrawable = GlassMaterialPreset.createSolidShellDrawable(
        fillColor = GlassMaterialPreset.clearSurfaceColor(ThemeStore.bottomBackground(context)),
        borderColor = glassStrokeColor(context),
        cornerRadiusPx = cornerRadiusPx,
        oval = false,
        strokeWidthPx = 1.dpToPx()
    )

    /**
     * 描边色：统一取主题包「边框线色」（[UiCorner.borderLineColor]，跟随强调色或选色）。
     * 这样玻璃行 / 壳层 / 底栏描边与其他卡片 / 弹窗 / 按钮共用同一份取色口径。
     */
    fun glassStrokeColor(context: Context): Int? = UiCorner.borderLineColor(context)

    /**
     * 底栏 / 面板玻璃背景（选择模式底栏等与主底栏同语义的表面）。
     *
     * 与书源名行是**同一份** drawable 与材质：圆角同为 `ui_panel_radius`（主底栏
     * `bg_liquid_glass_shell_overlay` 的圆角逐值一致），四档降级与设备判定全部由
     * [glassDrawable] 决定，这里只负责把用途命名清楚。
     * 返回 null 表示设备降级（墨水屏 / 低内存 / 动图壁纸纯色底），调用方回退实色。
     *
     * 跨包（非发现页）宿主请改走 [io.legado.app.ui.glass.GlassSurface.panelBackground]：
     * 那里要求显式声明采样域，面板域 / 内容域直接返回 null，从结构上阻止
     * 「在不透明面板里采样壁纸」的错误复用。
     */
    fun panelBackground(host: View): Drawable? =
        panelBackground(host, host.resources.getDimension(R.dimen.ui_panel_radius))

    /**
     * 与 [panelBackground] 同一份产物，只允许自定义圆角。
     *
     * Compose 桥（[io.legado.app.ui.glass.GlassPanel]）的宿主是一个独立 View，圆角由调用方
     * 传入（如设置页卡片的 `UiCorner.panelRadius` 缩放值），不能读死 `ui_panel_radius`。
     * 除圆角外没有任何参数分支：材质、降级、采样域仍由 [glassDrawable] 单点决定。
     *
     * [screenAligned] 供独立窗口（对话框）使用：本管线按「壁纸位图对齐到宿主根视图」
     * 建立采样矩阵，而对话框窗口的根视图只有面板那么大，照常换算会把整张壁纸
     * 缩进面板里。置 true 时改用屏幕尺寸与屏幕坐标，与壁纸位图的构建口径一致。
     */
    fun panelBackground(
        host: View,
        cornerRadiusPx: Float,
        screenAligned: Boolean = false,
        modeOverride: String? = null
    ): Drawable? = glassDrawable(host, cornerRadiusPx, screenAligned, modeOverride)

    /** 返回毛玻璃行背景；设备降级时返回 null，调用方保留默认透明背景。 */
    fun rowBackground(host: View): Drawable? = panelBackground(host)

    /**
     * 设置页预览专用：同一份行玻璃 drawable，只允许自定义圆角。
     * 底栏药丸预览传 `height / 2`，与书源名行并排即可用肉眼核对「同参数是否同观感」。
     */
    fun previewBackground(host: View, cornerRadiusPx: Float): Drawable? =
        glassDrawable(host, cornerRadiusPx)

    /**
     * 设置页预览专用：Compose 的 LazyColumn 滚动不会触发子 View 的布局回调，
     * 预览容器在自身屏幕坐标变化后主动调这里，让玻璃层按新位置重采样。
     * 未挂接玻璃（无玻璃档 / 设备降级）时是 no-op。
     */
    fun refreshPreviewPosition(host: View) {
        GlassPositionTracker.refresh(host)
    }

    /**
     * 显式解绑 [host] 上的玻璃位置跟踪。
     *
     * 宿主把玻璃换回**原背景**时必须调这里（顶栏降级回退、卡片切回实底）：旧
     * [TrackerBinding] 仍挂在 host 的 layout / attach 回调与最近可滚动祖先的滚动监听上，
     * 并持有已换下的 drawable 与壁纸位图。只是 `setBackground(原背景)` 不足以停掉它——
     * 滚动时回调照样触发，等于给一个不再绘制的 drawable 白算矩阵、白留一张位图。
     *
     * 复用切档时已有的那条 View-detach 路径（[GlassPositionTracker.detach]），不新增
     * 监听；重复调用是 no-op，切回玻璃档后 [glassDrawable] 的 attach 会重建 binding。
     */
    fun detachPositionTracking(host: View) {
        GlassPositionTracker.detach(host)
    }

    /**
     * 展开行内部小控件（线路 / 类型 / 平台 / 搜索按钮等）的玻璃背景。
     *
     * 与行标题、底栏走**同一条**链路：档位、强度、四个倍率、tint / shell / 描边全部来自
     * [GlassMaterialPreset.current]，四级降级（液态 AGSL / 硬件高斯 / 软件预模糊 / 实底）
     * 也由 [glassDrawable] 按同一套判定决定。上一版这里刻意只走静态管线以换取
     * 展开性能，真机对比下控件与行标题的观感差异是肉眼可见的（暗磨砂 vs 折射亮边），
     * 因此按「观感一致优先」改回同一条管线：每个控件尺寸远小于整行，单帧高斯面积很小，
     * 且液态层惰性创建（首次绘制才编译 AGSL），不会在绑定阶段集中付出开销。
     * 圆角取主题 `actionRadius`，与 [filletControlBackground] 的控件圆角一致。
     *
     * 玻璃 drawable 本身没有按下态，这里用一层低透明度反色叠在玻璃上方保留按压反馈；
     * 设备降级（墨水屏 / 低内存）返回 null，调用方回退 [filletControlBackground]。
     */
    fun controlBackground(host: View): Drawable? {
        val context = host.context
        val radius = UiCorner.actionRadius(context)
        val glass = glassDrawable(host, radius) ?: return null
        val pressScrim = AppColorUtils.withAlpha(
            if (AppColorUtils.isColorLight(ThemeStore.bottomBackground(context))) {
                Color.BLACK
            } else {
                Color.WHITE
            },
            CONTROL_PRESS_SCRIM_ALPHA
        )
        val pressed = LayerDrawable(
            arrayOf(
                glass,
                GradientDrawable().apply {
                    setColor(pressScrim)
                    cornerRadius = radius
                }
            )
        )
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), glass)
        }
    }

    private fun glassDrawable(
        host: View,
        cornerRadiusPx: Float,
        screenAligned: Boolean = false,
        modeOverride: String? = null
    ): Drawable? {
        val context = host.context
        val baseConfig = readExploreGlassConfig(context)
        val config = if (!modeOverride.isNullOrBlank() && modeOverride in GlassMaterialPreset.modes) {
            baseConfig.copy(mode = modeOverride)
        } else {
            baseConfig
        }
        // 无玻璃档是纯实底，没有模糊/位图采样开销，墨水屏与低内存设备也必须
        // 保持「不透明纯色块」（放在设备降级判定之前）。
        // 所有非玻璃分支都主动解绑上一份 tracker，避免切档后监听与旧 drawable 滞留。
        if (config.mode == EXPLORE_GLASS_MODE_SOLID) {
            GlassPositionTracker.detach(host)
            return solidShellDrawable(context, cornerRadiusPx)
        }
        // 透明无玻璃：只把表面变成半透明主题色，透出背后但不模糊、不折射、无色散。
        // 与 solid 同在设备降级判定之前——它同样不采样壁纸，墨水屏 / 低内存也应一致。
        if (config.mode == EXPLORE_GLASS_MODE_CLEAR) {
            GlassPositionTracker.detach(host)
            return clearShellDrawable(context, cornerRadiusPx)
        }
        // 墨水屏 / 低内存设备自动降级为关闭，牺牲少量视觉换取滚动流畅（架构 T03 验收项）
        if (AppConfig.isEInkMode || isLowRamDevice(context)) {
            GlassPositionTracker.detach(host)
            return null
        }
        // 材质的唯一入口：档位、两个强度键、四个倍率、API<33 壳层补偿都在这里读，
        // 底栏 / 顶栏 / 行拿到的是同一个函数产物，行内不再做任何二次缩放。
        val material = GlassMaterialPreset.current(config.mode)
        val useHardware = supportsHardwareBlur() && config.hardwareBlur
        val density = context.resources.displayMetrics.density
        val blurPx = exploreHardwareBlurPx(material.blurDp, density)
        val baseColor = ThemeStore.bottomBackground(context)
        val strokeColor = glassStrokeColor(context)
        val effectiveBlurPx = if (useHardware) blurPx else 0f
        val useLiquid = rowLiquidCapability(
            sdkInt = Build.VERSION.SDK_INT,
            rowLiquidEnabled = ROW_LIQUID_ENABLED,
            hardwareBlurPref = config.hardwareBlur,
            isEInk = false,
            isLowRam = false,
            canvasHardwareAccelerated = true,
            backdropAvailable = true,
            liquidFailed = RowLiquidRuntimeState.shaderFailed
        ) == RowLiquidCapability.LIQUID
        val reusable = (host.background as? FrostedGlassDrawable)?.takeIf {
            it.matchesConfig(
                radiusPx = cornerRadiusPx,
                baseColor = baseColor,
                material = material,
                strokeColor = strokeColor,
                useHardware = useHardware,
                blurPx = effectiveBlurPx,
                useLiquid = useLiquid,
                screenAligned = screenAligned
            )
        }
        val drawable = reusable ?: FrostedGlassDrawable(
            host = host,
            radiusPx = cornerRadiusPx,
            baseColor = baseColor,
            material = material,
            strokeColor = strokeColor,
            useHardware = useHardware,
            blurPx = effectiveBlurPx,
            useLiquid = useLiquid,
            screenAligned = screenAligned
        )
        //主线程只查缓存；miss 时异步构建（decode + 软件模糊较重，避免主线程卡顿/ANR），
        //完成后回填刷新，期间先用上一张缓存位图（若有）过渡
        drawable.updateBackdrop(
            backdrop(context, config, material, drawable::updateBackdrop)
        )
        GlassPositionTracker.attach(host, drawable)
        return drawable
    }

    private fun backdrop(
        context: Context,
        config: ExploreGlassConfig,
        material: GlassMaterialPreset.Params,
        onReady: (GlassBackdrop?) -> Unit
    ): GlassBackdrop? {
        val state = MainThemeBackgroundState.from(context)
        // 动图壁纸（gif/webp）由背景层 ComposeThemeImage 逐帧播放，而解码只能取首帧：
        // 若照常采样会产生「壁纸在动、玻璃静止」的错乱。默认返回 null 走纯 shell 实底；
        // 设置页关掉「动图壁纸使用纯色底」后，用户可接受静止首帧玻璃，此时照常构建。
        if (state.file != null && state.animated && config.animatedSolid) return null
        val metrics = context.resources.displayMetrics
        val targetW = max(64, (metrics.widthPixels / DOWNSCALE).toInt())
        val targetH = max(64, (metrics.heightPixels / DOWNSCALE).toInt())
        val file = state.file
        // rawKey 只含「底图内容」：壁纸文件 / 裁剪 / 夜间 / 目标尺寸 / 兜底色。
        // 模糊半径单独拼进 blurKey——调模糊倍率时复用 raw 只重做一次软件模糊，
        // 不重新 decode + 裁剪（设置页预览拖动模糊滑杆的流畅度取决于此）。
        val rawKey = listOf(
            file?.absolutePath.orEmpty(),
            file?.length() ?: 0L,
            file?.lastModified() ?: 0L,
            state.crop?.let { "${it.left},${it.top},${it.right},${it.bottom}" }.orEmpty(),
            AppConfig.isNightTheme,
            targetW,
            targetH,
            state.fallbackColor
        ).joinToString("|")
        val radius = exploreSoftwareBlurRadius(material.blurDp, metrics.density)
        val blurKey = "$rawKey|$radius"
        synchronized(this) {
            cache?.takeIf { it.key == blurKey }?.let { return it.backdrop }
        }
        synchronized(this) {
            val stale = cache?.backdrop
            pendingCallbacks.getOrPut(blurKey) { ArrayList() }.add(onReady)
            if (inFlightKeys.add(blurKey)) {
                // 构建放 IO；旧缓存位图不显式 recycle（仍可能被各行 drawable 引用，
                // 显式回收会在重绘时抛 "trying to use a recycled bitmap"），交给 GC 回收
                val reusableRaw = rawCache?.takeIf { it.key == rawKey }?.raw
                Coroutine.async(Coroutine.defaultScope) {
                    buildBackdrop(
                        file, state.fallbackColor, state.crop, targetW, targetH,
                        rawKey, radius, reusableRaw
                    )
                }.onSuccess { backdrop ->
                    finishBackdropBuild(blurKey, backdrop)
                }.onError {
                    finishBackdropBuild(blurKey, null)
                }
            }
            return stale
        }
    }

    /** 构建完成：更新缓存并回填所有等待的 drawable（回调在主线程执行）。 */
    private fun finishBackdropBuild(key: String, backdrop: GlassBackdrop?) {
        val callbacks = synchronized(this) {
            inFlightKeys.remove(key)
            if (backdrop != null) {
                cache = BackdropCache(key, backdrop)
            }
            pendingCallbacks.remove(key)
        } ?: return
        callbacks.forEach { it(backdrop) }
    }

    private fun buildBackdrop(
        file: File?,
        fallbackColor: Int,
        crop: ComposeThemeImageCrop?,
        targetW: Int,
        targetH: Int,
        rawKey: String,
        radius: Int,
        reusableRaw: Bitmap?
    ): GlassBackdrop? {
        val raw = reusableRaw?.takeIf { !it.isRecycled } ?: decodeRaw(
            file, fallbackColor, crop, targetW, targetH
        )
        if (raw !== reusableRaw) {
            rawCache = RawCache(rawKey, raw)
        }
        // raw 供硬件管线与液态管线，模糊由 GPU 完成，绝不能预先软件模糊（否则双重叠加）；
        // blurred 供软件管线与硬件失败时的即时降级。radius = 0 表示模糊倍率为 0：
        // 直接复用 raw（同一实例），此时「不模糊」是用户明确的选择，不是降级兜底。
        val blurred = if (radius <= 0) raw else runCatching {
            raw.stackBlurSoftware(radius)
        }.getOrNull()
        return GlassBackdrop(raw = raw, blurred = blurred)
    }

    /** 解码并按屏幕对齐构建未模糊底图（纯 IO，无 Android 主线程状态）。 */
    private fun decodeRaw(
        file: File?,
        fallbackColor: Int,
        crop: ComposeThemeImageCrop?,
        targetW: Int,
        targetH: Int
    ): Bitmap {
        var source: Bitmap? = null
        if (file != null && file.isFile && file.canRead()) {
            source = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetW * 2 &&
                    bounds.outHeight / (sample * 2) >= targetH * 2
                ) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }.getOrNull()
        }
        val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(fallbackColor)
        source?.let { src ->
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val cropRect = if (crop != null) {
                RectF(
                    src.width * crop.left.coerceIn(0f, 1f),
                    src.height * crop.top.coerceIn(0f, 1f),
                    src.width * crop.right.coerceIn(0f, 1f),
                    src.height * crop.bottom.coerceIn(0f, 1f)
                )
            } else {
                RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
            }
            if (cropRect.width() >= 1f && cropRect.height() >= 1f) {
                // 与主题背景图层一致：居中裁剪铺满屏幕
                val scale = max(targetW / cropRect.width(), targetH / cropRect.height())
                val dstW = cropRect.width() * scale
                val dstH = cropRect.height() * scale
                val dx = (targetW - dstW) / 2f
                val dy = (targetH - dstH) / 2f
                canvas.drawBitmap(
                    src,
                    Rect(
                        cropRect.left.toInt(),
                        cropRect.top.toInt(),
                        cropRect.right.toInt(),
                        cropRect.bottom.toInt()
                    ),
                    RectF(dx, dy, dx + dstW, dy + dstH),
                    paint
                )
            }
            if (src !== result) {
                src.recycle()
            }
        }
        return result
    }
}
