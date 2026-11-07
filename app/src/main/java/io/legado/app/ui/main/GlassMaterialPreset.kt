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
 * 公式全部保持 1.25.5 及以前底栏的逐值口径，未调整任何观感参数；
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
     * 底栏走 [solidOpacity]（默认 `forceOpaque=false`），逐值保持 1.25.5 观感；
     * 发现页「无玻璃」档要求「完全不透明的纯色块」，而图标包 `config.opacity`
     * 默认只有 76（内置 default 为 76、Config 默认 72），直接复用会得到 0.72~0.76
     * 的半透明壳层、仍透出壁纸，故发现页传 `forceOpaque=true` 把 alpha 钉死为 1。
     * 纯函数，便于 JVM 单测锁住「实底档 alpha=255」与「底栏口径不变」两件事。
     */
    fun solidAlpha(opacity: Int, forceOpaque: Boolean = false): Float =
        if (forceOpaque) 1f else solidOpacity(opacity)

    /** 实底壳层最终填充色（纯函数，便于断言 alpha 分量为 255）。 */
    fun solidColor(baseColor: Int, opacity: Int, forceOpaque: Boolean = false): Int =
        ColorUtils.withAlpha(baseColor, solidAlpha(opacity, forceOpaque))

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
     * [dispersion] / [refractionHeightDp] / [refractionOffsetDp] 是底栏
     * LiquidGlass 着色器专用量，发现页行渲染器没有折射 shader，仅用它们生成
     * 同预设来源的边缘高光（见 ExploreGlassBackdrop.FrostedGlassDrawable）。
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
            blurDp = if (frosted) 12f + glassLevel * 18f else 8f + glassLevel * 10f,
            tintAlpha = if (frosted) 0.05f + glassLevel * 0.10f else 0.02f + glassLevel * 0.05f,
            dispersion = if (frosted) {
                (0.10f + glassLevel * 0.12f).coerceAtMost(0.28f)
            } else {
                (0.20f + glassLevel * 0.24f).coerceAtMost(0.48f)
            },
            refractionHeightDp = if (frosted) {
                14f + glassLevel * 8f
            } else {
                22f + glassLevel * 12f
            },
            refractionOffsetDp = if (frosted) {
                26f + glassLevel * 16f
            } else {
                42f + glassLevel * 24f
            },
            shell = shellAlphas(glassLevel, fallbackBoost)
        )
    }

    /**
     * 底栏当前档位对应的完整预设：磨砂档读 frostedGlassLevel，其余读 liquidGlassLevel。
     * 发现页液态行管线（API 33+）与顶栏也复用它，做到档位与强度都逐值一致。
     */
    fun current(mode: String): Params {
        val levelValue = if (mode == FROSTED) {
            AppConfig.frostedGlassLevel
        } else {
            AppConfig.liquidGlassLevel
        }
        return params(mode, opacityLevel(levelValue), fallbackBoost())
    }

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
     * 无玻璃档（底栏 solid）的统一实底壳层：底色取 bottomBackground，不透明度取
     * 底栏图标包 config.opacity，描边取全局 glassStrokeColor（未配置则不描边）。
     * 底栏的常规/实底壳层与发现页的无玻璃档共用同一个构造实现。
     * [forceOpaque] 默认 false，底栏所有调用点都不传，公式与 1.25.5 逐值一致、观感不变；
     * 发现页改用上面的填充色重载，由纯函数算出强制不透明的颜色后传入。
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
