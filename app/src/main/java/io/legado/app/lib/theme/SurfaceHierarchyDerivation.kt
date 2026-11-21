package io.legado.app.lib.theme

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 纯函数色彩层级与同心圆角派生引擎（零 Android 运行时依赖，供 View/Compose 上游及 JVM 单测共用）。
 *
 * 约定层级：
 * - L0 (Canvas): 页面画布底色 (backgroundArgb)
 * - L1 (Card):   一级容器卡片 / 弹窗底板 (由 L0 同色相明度阶梯派生)
 * - L2 (Slot):   卡片内嵌凹槽 / 输入框 / 开关与滑杆轨道 / 次级按钮 (由 L1 派生)
 */
object SurfaceHierarchyDerivation {

    const val ARGB_WHITE = 0xFFFFFFFF.toInt()
    const val ARGB_BLACK = 0xFF000000.toInt()
    const val ARGB_EINK_MUTED = 0xFFF2F2F2.toInt()

    /**
     * 未显式配置 `themeCardColor` 时，由画布底色 L0 派生一级卡片色 L1。
     *
     * - 墨水屏：强制旁路返回纯白 `#FFFFFF`。
     * - 日间模式：若 L0 已接近纯白（RGB 均 >= 245），返回 `#FFFFFF`；否则保留 L0 冷暖色相并向纯白混合 72%，拉开浮起感。
     * - 夜间模式：保留 L0 色相并向纯白混合 7%，在纯黑 `#000000` 下升至 `#121212`。
     */
    fun deriveCardColor(
        backgroundArgb: Int,
        isNight: Boolean,
        isEInk: Boolean
    ): Int {
        if (isEInk) return ARGB_WHITE
        // 防呆：Context.backgroundColor 在开启壁纸时可能传入 Color.TRANSPARENT (alpha == 0)，
        // 必须先回落到实色基准画布再派生，防止算出 #B8B8B8B8 / #12121212 灰块。
        val opaqueBg = if ((backgroundArgb ushr 24) == 0) {
            if (isNight) 0xFF121212.toInt() else ARGB_WHITE
        } else {
            backgroundArgb
        }
        if (!isNight) {
            val r = (opaqueBg ushr 16) and 0xFF
            val g = (opaqueBg ushr 8) and 0xFF
            val b = opaqueBg and 0xFF
            return if (r >= 245 && g >= 245 && b >= 245) {
                ARGB_WHITE
            } else {
                blendArgb(opaqueBg, ARGB_WHITE, 0.72f)
            }
        }
        return blendArgb(opaqueBg, ARGB_WHITE, 0.07f)
    }

    /**
     * 未显式配置 `themeMutedColor` / `themeSearchFieldBackgroundColor` / `themeTabBackgroundColor` 时，
     * 由一级卡片色 L1 派生二级内嵌凹槽色 L2。
     *
     * - 墨水屏：强制旁路返回 `#F2F2F2`。
     * - 日间模式：向「黑混 12% 强调色」混合 5.5%，形成卡片内部自然凹陷的同色相浅槽。
     * - 夜间模式：向「白混 12% 强调色」混合 6.5%，形成深色卡片内部可辨的次级槽。
     */
    fun deriveSlotColor(
        cardArgb: Int,
        accentArgb: Int,
        isNight: Boolean,
        isEInk: Boolean
    ): Int {
        if (isEInk) return ARGB_EINK_MUTED
        return if (!isNight) {
            val tintDark = blendArgb(ARGB_BLACK, accentArgb, 0.12f)
            blendArgb(cardArgb, tintDark, 0.055f)
        } else {
            val tintLight = blendArgb(ARGB_WHITE, accentArgb, 0.12f)
            blendArgb(cardArgb, tintLight, 0.065f)
        }
    }

    /**
     * 未显式配置 `panelBorderColor` / `glassStrokeColor` 时的默认柔和描边。
     *
     * 废除旧版默认回落 100% 不透明 `accentColor` 的刺眼实线框：
     * - 墨水屏：强制 `#FF000000` 100% 纯黑实线。
     * - 日/夜模式：按表面明暗取黑/白基色并微染 20% 强调色，透明度收敛至 ~8.6% (alpha=22) / ~12.5% (alpha=32)。
     */
    fun deriveDefaultBorderLineColor(
        surfaceArgb: Int,
        accentArgb: Int,
        isNight: Boolean,
        isEInk: Boolean
    ): Int {
        if (isEInk) return ARGB_BLACK
        val onSurface = if (isLightArgb(surfaceArgb)) ARGB_BLACK else ARGB_WHITE
        val tinted = blendArgb(onSurface, accentArgb, 0.20f)
        val alpha = if (isNight) 32 else 22
        return withAlphaArgb(tinted, alpha)
    }

    /**
     * 同心圆角退让公式：
     * - scale <= 0f -> 0f（直角档）
     * - scale > 0f  -> max(panelBasePx * scale - 3dp * density, 4dp * density * scale)
     *
     * 在默认 ui_panel_radius = 10dp、scale = 1.0 下，panel = 10dp、action = 7dp；
     * 在 scale = 2.0 下，panel = 20dp、action = 17dp，始终保持 3dp 同心曲率差，不随放大失真。
     */
    fun computeActionRadiusPx(
        panelBasePx: Float,
        scale: Float,
        density: Float
    ): Float {
        val clampedScale = scale.coerceIn(0f, 3f)
        if (clampedScale <= 0f) return 0f
        val safeDensity = density.coerceAtLeast(1f)
        val panelActual = panelBasePx * clampedScale
        val inset = 3f * safeDensity
        val minRadius = 4f * safeDensity * clampedScale
        return max(panelActual - inset, minRadius)
    }

    fun isLightArgb(argb: Int): Boolean {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val darkness = 1.0 - (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return darkness < 0.5
    }

    fun blendArgb(color1: Int, color2: Int, ratio: Float): Int {
        val t = ratio.coerceIn(0f, 1f)
        val inv = 1f - t
        val a = (((color1 ushr 24) and 0xFF) * inv + ((color2 ushr 24) and 0xFF) * t).roundToInt().coerceIn(0, 255)
        val r = (((color1 ushr 16) and 0xFF) * inv + ((color2 ushr 16) and 0xFF) * t).roundToInt().coerceIn(0, 255)
        val g = (((color1 ushr 8) and 0xFF) * inv + ((color2 ushr 8) and 0xFF) * t).roundToInt().coerceIn(0, 255)
        val b = ((color1 and 0xFF) * inv + (color2 and 0xFF) * t).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun withAlphaArgb(argb: Int, alpha: Int): Int {
        return (argb and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    }

    /**
     * L2 槽内嵌 L3 控件底色派生（仅在控件与宿主底色相等时触发，禁止靠叠加第二条平行描边拉层次）。
     *
     * - 墨水屏：强制旁路返回纯白 `#FFFFFFFF`（与 L2 `#F2F2F2` 拉开明度阶梯，无半透明灰块）。
     * - 日间浅色槽（`isLightArgb` 为真）：向纯白回提 `55%` 形成浮起控件感；若原槽底已接近纯白（最大通道差 < 6），改向深色微压 `5.5%` 防退化同色。
     * - 夜间模式或日间自定义深色槽（`isNight || !isLightArgb`）：向「白混 10% 强调色」微提 `7%`，避免日间深色卡片内嵌控件被 55% 向白炸成浅灰板。
     * - 玻璃半透明态（alpha < 255）：保留透明通道并补足 `+20` alpha（上限 `255`），避免高不透明度档位下倒挂更透或跳变。
     */
    fun deriveL3ControlColor(
        slotArgb: Int,
        accentArgb: Int,
        isNight: Boolean,
        isEInk: Boolean
    ): Int {
        if (isEInk) return ARGB_WHITE
        val origAlpha = (slotArgb ushr 24) and 0xFF
        val opaqueSlot = (slotArgb and 0x00FFFFFF) or (0xFF shl 24)
        val shiftedRgb = if (!isNight && isLightArgb(opaqueSlot)) {
            val lifted = blendArgb(opaqueSlot, ARGB_WHITE, 0.55f)
            if (maxRgbChannelDiff(opaqueSlot, lifted) >= 6) {
                lifted
            } else {
                val tintDark = blendArgb(ARGB_BLACK, accentArgb, 0.12f)
                blendArgb(opaqueSlot, tintDark, 0.055f)
            }
        } else {
            val tintLight = blendArgb(ARGB_WHITE, accentArgb, 0.10f)
            blendArgb(opaqueSlot, tintLight, 0.07f)
        }
        val finalAlpha = if (origAlpha >= 255) 255 else (origAlpha + 20).coerceIn(0, 255)
        return withAlphaArgb(shiftedRgb, finalAlpha)
    }

    /**
     * 大圆角下的同心内边距防穿模公式：
     * - `panelRadiusDp <= 14f`（默认 `0x~1.4x`）维持 `basePaddingDp` 不变；
     * - 超过 `14f` 时按超额的 `0.25` 倍补偿内边距（上限 `maxBonusDp`，默认 `+4dp`），
     *   避免超圆角档下内部子控件四角顶穿外卡圆弧。
     */
    fun resolveConcentricCardPaddingDp(
        panelRadiusDp: Float,
        basePaddingDp: Float = 14f,
        maxBonusDp: Float = 4f
    ): Float {
        val safeBase = basePaddingDp.coerceAtLeast(0f)
        val safeRadius = panelRadiusDp.coerceAtLeast(0f)
        val safeBonus = maxBonusDp.coerceAtLeast(0f)
        if (safeRadius <= 14f) return safeBase
        val extra = ((safeRadius - 14f) * 0.25f).coerceIn(0f, safeBonus)
        return safeBase + extra
    }

    private fun maxRgbChannelDiff(a: Int, b: Int): Int {
        val dr = kotlin.math.abs(((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF))
        val dg = kotlin.math.abs(((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF))
        val db = kotlin.math.abs((a and 0xFF) - (b and 0xFF))
        return max(dr, max(dg, db))
    }
}
