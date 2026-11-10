package io.legado.app.ui.main

import android.graphics.drawable.GradientDrawable
import android.os.Build
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ColorUtils

/**
 * 底栏与经典发现页共用的「三档材质预设」唯一来源。
 *
 * 三档取值与全局材质档位完全一致（resolveExploreGlassMode：solid / glass / frosted）：
 * [SOLID]（常规 / 无玻璃）、[GLASS]（玻璃 / 透明玻璃）、[FROSTED]（磨砂玻璃）。
 * 每档不是「在某种默认观感上乘系数」，而是下面这一整组独立参数：
 * 强度键、模糊半径、白色着色、色散、折射高度/偏移、壳层三色 alpha、描边 alpha。
 *
 * 之前分散在 `MainActivity.setupLiquidGlass()` 与
 * `MainActivity.createLiquidGlassShellDrawable()` 里的公式集中到这里，
 * 底栏与发现页都调用同一份 [params] / [shellAlphas]，避免两处各自硬编码后逐渐漂移。
 * [createSolidShellDrawable] 同理，保证「无玻璃」档的底色与描边也和底栏一致。
 *
 * 壳层 / 描边 / 实底公式全部保持 1.25.5 及以前底栏的逐值口径；
 * 模糊 / 白色着色 / 色散 / 折射几何在 1.26.1 起按四条可见维度拉开透明玻璃与磨砂
 * 的差距（旧取值下两档切换在真机上差异不足）。
 * 纯函数（[params] / [shellAlphas] / [opacityLevel] / [solidOpacity]）不依赖
 * Android 运行时，可直接用本地 JVM 单测锁住口径。
 */
object GlassMaterialPreset {

    const val SOLID = "solid"
    const val GLASS = "glass"
    const val FROSTED = "frosted"

    val modes = setOf(SOLID, GLASS, FROSTED)

    /** 玻璃档强度：0-100 → 0-0.5，与底栏 bottomBarOpacityLevel 同口径。 */
    fun opacityLevel(value: Int): Float =
        (value.coerceIn(0, 100) / 200f).coerceIn(0f, 0.5f)

    /** 实底档不透明度：0-100 → 0-1，与底栏 standardBottomBarOpacityLevel 同口径。 */
    fun solidOpacity(value: Int): Float =
        (value.coerceIn(0, 100) / 100f).coerceIn(0f, 1f)

    /**
     * 实底壳层最终使用的 alpha（0-1）。
     *
     * 标准布局底栏（含底栏壁纸层）走 [solidOpacity]（默认 `forceOpaque=false`），
     * 逐值保持 1.25.5 观感；浮动底栏、无玻璃档表面与发现页「无玻璃」档要求
     * 「完全不透明的纯色块」，而图标包 `config.opacity` 默认只有 76
     * （内置 default 为 76、Config 默认 72），直接复用会得到 0.72~0.76 的
     * 半透明壳层、仍透出壁纸，故这些表面传 `forceOpaque=true` 把 alpha 钉死为 1。
     * 纯函数，便于 JVM 单测锁住「实底档 alpha=255」与「旧半透明公式不变」两件事。
     */
    fun solidAlpha(opacity: Int, forceOpaque: Boolean = false): Float =
        if (forceOpaque) 1f else solidOpacity(opacity)

    /** 实底壳层最终填充色（纯函数，便于断言 alpha 分量为 255）。 */
    fun solidColor(baseColor: Int, opacity: Int, forceOpaque: Boolean = false): Int =
        ColorUtils.withAlpha(baseColor, solidAlpha(opacity, forceOpaque))

    /**
     * 无玻璃档「底栏 / 搜索按钮 / 悬浮球」等表面层的填充色：alpha 固定 255，
     * RGB 即主题包预设的「底栏色」（`bottomBackground`）。
     *
     * 图标包 `opacity` 是旧半透明底栏时代的口径；无玻璃档若沿用会让壁纸透出
     * 24%~28%，真机表现为「切无玻璃后底栏失去背景色」。这里把「完全不透明」
     * 钉成命名纯函数，调用点不再各自传 `forceOpaque`（少传一个实参就会退化）。
     */
    fun solidSurfaceFillColor(baseColor: Int): Int = ColorUtils.withAlpha(baseColor, 1f)

    /**
     * 实底壳层在未设置全局描边色时的兜边 alpha。
     *
     * 实底档没有着色器/模糊，底色又与发现页行背景逐值相同（都取 bottomBackground），
     * 于是底栏浮在同样颜色的行上时边界完全消失（真机反馈「切无玻璃后底栏背景色
     * 完全消失」）。1dp 对比色描边是唯一能在同色背景下画出轮廓的层，
     * 强度取 0.22：深色底上是可见但不刺眼的亮边，浅色底上是可见的暗边。
     */
    const val SOLID_SHELL_STROKE_ALPHA = 0.22f

    /**
     * 实底壳层的兜边色（纯函数）：深色底用白色亮边、浅色底用黑色暗边，
     * 保证描边与填充色之间始终有色差，不依赖 Android 的 `Color` 运行时。
     *
     * [lightBase] 由调用方用 `AppColorUtils.isColorLight(baseColor)` 传入，
     * 与底栏壳层选择 surfaceColor 的判据保持同一口径。
     */
    fun solidShellStroke(
        lightBase: Boolean,
        alpha: Float = SOLID_SHELL_STROKE_ALPHA
    ): Int = ColorUtils.withAlpha(if (lightBase) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), alpha)

    /** API < 33 的壳层补偿，底栏既有逻辑；抽出来让发现页也走同一判定。 */
    fun fallbackBoost(): Float =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) 0.08f else 0f

    /** 壳层渐变三色与描边的 alpha。selectedBoost 仅用于底栏选中态指示器。 */
    data class ShellAlphas(
        val start: Float,
        val center: Float,
        val end: Float,
        val stroke: Float
    )

    fun shellAlphas(
        glassLevel: Float,
        fallbackBoost: Float = 0f,
        selectedBoost: Float = 0f
    ): ShellAlphas = ShellAlphas(
        start = (0.18f + glassLevel * 0.16f + fallbackBoost)
            .coerceIn(0f, 0.44f) + selectedBoost,
        center = (0.10f + glassLevel * 0.12f + fallbackBoost * 0.65f)
            .coerceIn(0f, 0.32f) + selectedBoost,
        end = (0.08f + glassLevel * 0.10f + fallbackBoost * 0.45f)
            .coerceIn(0f, 0.26f) + selectedBoost,
        // 描边不含 fallbackBoost，但含 selectedBoost 且整体限幅 0.42（底栏既有口径）
        stroke = (0.18f + glassLevel * 0.16f + selectedBoost).coerceIn(0f, 0.42f)
    )

    /**
     * 一档材质的完整参数。dp 值由调用方按各自 density 换算为像素；
     * [dispersion] / [refractionHeightDp] / [refractionOffsetDp] 是 LiquidGlass
     * 着色器专用量，底栏、顶栏与发现页行渲染器读同一份（行内按行高做几何标定）。
     */
    data class Params(
        val mode: String,
        val glassLevel: Float,
        val blurDp: Float,
        val tintAlpha: Float,
        val dispersion: Float,
        val refractionHeightDp: Float,
        val refractionOffsetDp: Float,
        val shell: ShellAlphas
    )

    fun params(
        mode: String,
        glassLevel: Float,
        fallbackBoost: Float = 0f
    ): Params {
        val frosted = mode == FROSTED
        return Params(
            mode = mode,
            glassLevel = glassLevel,
            // 两档差异必须同时落在四条可见维度上，而不是同一观感的深浅版本：
            // · 模糊：磨砂 20–32dp，透明玻璃 3–6dp——磨砂把背后内容糊成色块，
            //   透明玻璃仍能辨认内容轮廓（硬件管线在高密度屏上按 50px 封顶，
            //   两档仍保持约 3 倍半径差）；
            // · 白色着色：磨砂 0.18–0.28，透明玻璃 0.004–0.014——磨砂是明显奶白雾面，
            //   透明玻璃几乎无色；默认强度（glass 68 → 0.34，frosted 70 → 0.35）下
            //   两档差 ≈ 0.24，几乎是上一版 0.13 的两倍；
            // · 色散：透明玻璃 0.24–0.36（边缘彩色折射更锐），磨砂 0.04–0.08（收敛成均匀雾面）；
            // · 折射几何：透明玻璃更高更深（24–36dp / 46–72dp），磨砂浅而短（12–15dp / 20–32dp）——
            //   磨砂不强调边缘变形，避免「既糊又扭曲」。
            blurDp = if (frosted) 20f + glassLevel * 24f else 3f + glassLevel * 6f,
            tintAlpha = if (frosted) 0.18f + glassLevel * 0.20f else 0.004f + glassLevel * 0.02f,
            dispersion = if (frosted) {
                (0.04f + glassLevel * 0.08f).coerceAtMost(0.18f)
            } else {
                (0.24f + glassLevel * 0.24f).coerceAtMost(0.50f)
            },
            refractionHeightDp = if (frosted) {
                12f + glassLevel * 6f
            } else {
                24f + glassLevel * 12f
            },
            refractionOffsetDp = if (frosted) {
                20f + glassLevel * 12f
            } else {
                46f + glassLevel * 26f
            },
            shell = shellAlphas(glassLevel, fallbackBoost)
        )
    }

    /**
     * 倍率滑杆缩放（纯函数）：100 = 原值，0 = 关闭该维度，上限 200。
     *
     * 只缩放「用户可调」的模糊 / 着色 / 壳层 / 描边四个维度；[Params.dispersion] 与
     * 折射几何不参与——它们由档位预设唯一决定，否则同一份材质在不同宿主上会出现
     * 两套色散，正是「同参数观感不一致」的来源之一。
     *
     * 底栏 / 顶栏 / 发现页行全部经过这里（1.26.1 起四个倍率是全局口径），
     * 因此任何宿主只要滑杆为 100 就与预设逐值一致，不为某一处单开缩放路径。
     */
    fun scalePercent(base: Float, percent: Int): Float =
        base * (percent.coerceIn(0, 200) / 100f)

    /**
     * 按四个倍率滑杆缩放整档参数（纯函数）。
     * 与 [params] 返回同一类型，调用方无需区分「预设值 / 滑杆后的值」。
     */
    fun scaled(
        params: Params,
        blurPercent: Int = 100,
        tintPercent: Int = 100,
        shellPercent: Int = 100,
        strokePercent: Int = 100
    ): Params = Params(
        mode = params.mode,
        glassLevel = params.glassLevel,
        blurDp = scalePercent(params.blurDp, blurPercent),
        tintAlpha = scalePercent(params.tintAlpha, tintPercent)
            .coerceIn(0f, MAX_TINT_ALPHA),
        dispersion = params.dispersion,
        refractionHeightDp = params.refractionHeightDp,
        refractionOffsetDp = params.refractionOffsetDp,
        shell = ShellAlphas(
            start = scalePercent(params.shell.start, shellPercent)
                .coerceIn(0f, MAX_SHELL_ALPHA),
            center = scalePercent(params.shell.center, shellPercent)
                .coerceIn(0f, MAX_SHELL_ALPHA),
            end = scalePercent(params.shell.end, shellPercent)
                .coerceIn(0f, MAX_SHELL_ALPHA),
            stroke = scalePercent(params.shell.stroke, strokePercent)
                .coerceIn(0f, MAX_STROKE_ALPHA)
        )
    )

    /*
     * 倍率滑杆的上限（与 1.25.11 之前发现页行内各层上限逐值相同）。
     * 上限必须放在 [scaled] 里而不是各宿主各写一份：1.26.1 起底栏 / 顶栏 / 行
     * 共用同一份缩放结果，上限一旦分叉，同一档参数又会给出不同的描边与壳层。
     */
    const val MAX_TINT_ALPHA = 0.45f
    const val MAX_SHELL_ALPHA = 0.94f
    const val MAX_STROKE_ALPHA = 0.42f

    /** 四个全局倍率滑杆的快照（100 = 预设原值，0 = 关闭该维度，200 = 加倍）。 */
    data class Scales(
        val blur: Int = 100,
        val tint: Int = 100,
        val shell: Int = 100,
        val stroke: Int = 100
    )

    /** 磨砂档的默认强度（与 `AppConfig.frostedGlassLevel` 默认值同源）。 */
    const val DEFAULT_FROSTED_LEVEL = 70

    /** 玻璃 / 无玻璃档的默认强度（与 `AppConfig.liquidGlassLevel` 默认值同源）。 */
    const val DEFAULT_LIQUID_LEVEL = 68

    /**
     * 一档预设对应的**固定滑杆取值**：强度 + 四个倍率。
     *
     * 这是「预设」在滑杆空间里的唯一坐标：选中一档 = 把整组数写回偏好，
     * 反过来只要当前滑杆与这组数不一致，设置页就把材质显示为「自定义」。
     * 数值取该档的全局默认值（磨砂 70 / 其余 68，四个倍率 100），与
     * `AppConfig` 的默认值及「恢复默认」的目标状态逐值相同。
     */
    data class PresetPoint(
        val level: Int,
        val scales: Scales = Scales()
    )

    /**
     * 某档预设的固定滑杆取值。
     *
     * 强度按 [levelFor] 的读取口径取值（磨砂档读磨砂强度键，其余读液态强度键），
     * 于是「预设坐标」与「渲染层实际生效的强度」是同一个数，不会出现设置页显示
     * 70 而渲染读 68 的错位。
     */
    fun presetPoint(mode: String): PresetPoint = PresetPoint(
        level = if (mode == FROSTED) DEFAULT_FROSTED_LEVEL else DEFAULT_LIQUID_LEVEL
    )

    /**
     * 当前滑杆是否仍落在 [mode] 档的预设坐标上（纯函数）。
     *
     * 只比较该档实际读取的强度（[levelFor]）与四个倍率：另一个强度键在当前档
     * 不参与渲染，把它算进来会让「切档残留值」被误判为自定义。任一滑杆被拖动即
     * 返回 false，设置页据此显示「自定义」而不是继续显示原预设名。
     */
    fun matchesPreset(
        mode: String,
        frostedLevel: Int,
        liquidLevel: Int,
        scales: Scales
    ): Boolean {
        val point = presetPoint(mode)
        return levelFor(mode, frostedLevel, liquidLevel) == point.level && scales == point.scales
    }

    /**
     * 「档位 + 强度 + 四个倍率」→ 某一宿主的最终材质（纯函数）。
     *
     * 这是底栏 / 顶栏 / 发现页行**唯一**的材质入口：三处调用同一个函数、读同一组键，
     * 于是「同一档参数」在构造上就必然一致，而不是靠三处各自记着乘同一个系数。
     * 1.26.1 之前四个倍率只乘在发现页行的静态参数上，底栏与顶栏始终按 100 取预设——
     * 用户把「模糊 / 着色 / 遮盖 / 描边」调离 100 后，行与底栏必然分叉。
     *
     * 磨砂档读 `frostedLevel`，其余档读 `liquidLevel`（与底栏历史口径一致）。
     */
    fun resolved(
        mode: String,
        frostedLevel: Int,
        liquidLevel: Int,
        scales: Scales = Scales(),
        fallbackBoost: Float = fallbackBoost()
    ): Params {
        val levelValue = levelFor(mode, frostedLevel, liquidLevel)
        return scaled(
            params = params(mode, opacityLevel(levelValue), fallbackBoost),
            blurPercent = scales.blur,
            tintPercent = scales.tint,
            shellPercent = scales.shell,
            strokePercent = scales.stroke
        )
    }

    /**
     * 该档实际读取的强度键值（磨砂档读 frosted，其余读 liquid）。
     * 设置页的强度滑杆也用它取当前值，保证滑杆显示的就是渲染层生效的强度。
     */
    fun levelFor(mode: String, frostedLevel: Int, liquidLevel: Int): Int =
        if (mode == FROSTED) frostedLevel else liquidLevel

    /**
     * 运行期当前材质：从偏好读档位强度与四个倍率后交给 [resolved]。
     * 底栏、顶栏、发现页行都从这里取，任何一处新增宿主也必须走这里。
     */
    fun current(mode: String): Params = resolved(
        mode = mode,
        frostedLevel = AppConfig.frostedGlassLevel,
        liquidLevel = AppConfig.liquidGlassLevel,
        scales = Scales(
            blur = AppConfig.exploreGlassBlurScale,
            tint = AppConfig.exploreGlassTintScale,
            shell = AppConfig.exploreGlassShellScale,
            stroke = AppConfig.exploreGlassStrokeScale
        ),
        fallbackBoost = fallbackBoost()
    )

    /**
     * 材质的稳定签名（纯函数）：任一可调维度变化都会改变它。
     *
     * 底栏 / 顶栏 / 发现页把签名拼进各自的缓存键或重建判据，
     * 于是「强度 / 四个倍率 / 档位」变化时三处会同步重建，不会出现
     * 「某个宿主的缓存键漏了一个倍率，改了滑杆它不动」这类分叉。
     */
    fun signature(params: Params): String = listOf(
        params.mode,
        params.glassLevel,
        params.blurDp,
        params.tintAlpha,
        params.dispersion,
        params.refractionHeightDp,
        params.refractionOffsetDp,
        params.shell.start,
        params.shell.center,
        params.shell.end,
        params.shell.stroke
    ).joinToString("|")

    /**
     * 实底壳层唯一的构造实现：接收**已算好**的填充色，只做 Android Drawable 薄映射。
     *
     * 填充色由纯函数 [solidColor] 产出（底栏走 `forceOpaque=false`，发现页无玻璃档
     * 走 `forceOpaque=true`），这样「是否强制不透明」的决策留在纯 JVM 可测的一侧，
     * 不会因为调用点少传一个实参就悄悄退化成半透明。
     */
    fun createSolidShellDrawable(
        fillColor: Int,
        borderColor: Int?,
        cornerRadiusPx: Float,
        oval: Boolean,
        strokeWidthPx: Int
    ): GradientDrawable = GradientDrawable().apply {
        shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        if (!oval) this.cornerRadius = cornerRadiusPx
        setColor(fillColor)
        borderColor?.let { setStroke(strokeWidthPx, it) }
    }

    /**
     * 实底壳层的统一构造入口：底色取 bottomBackground，不透明度取底栏图标包
     * config.opacity，描边取全局 glassStrokeColor（未配置则不描边）。
     * 标准布局底栏的底栏壁纸层传 `forceOpaque=false`，公式与 1.25.5 逐值一致；
     * 无玻璃档的浮动底栏 / 搜索按钮 / 悬浮球改用上面的填充色重载 + [solidSurfaceFillColor]
     * （alpha 钉死 255），发现页无玻璃档同样走填充色重载。
     */
    fun createSolidShellDrawable(
        baseColor: Int,
        opacity: Int,
        borderColor: Int?,
        cornerRadiusPx: Float,
        oval: Boolean,
        strokeWidthPx: Int,
        forceOpaque: Boolean = false
    ): GradientDrawable = createSolidShellDrawable(
        fillColor = solidColor(baseColor, opacity, forceOpaque),
        borderColor = borderColor,
        cornerRadiusPx = cornerRadiusPx,
        oval = oval,
        strokeWidthPx = strokeWidthPx
    )
}
