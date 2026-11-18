package io.legado.app.lib.theme

import io.legado.app.help.config.TopBarConfig
import kotlin.math.roundToInt

/**
 * 顶栏圆角几何的单一真相源（纯函数层，不绘制、不碰 View/Compose/Drawable）。
 * 半径只来自顶栏包 config.cornerScale（经 TopBarConfig.resolveCornerRadiusPx），
 * 悬浮态只来自 config.floatingChrome，二者互不反推（R4）。
 */
object TopBarChromeSpec {
    data class Chrome(
        val radiusPx: Float,
        val floating: Boolean,
        /** 悬浮态左右内边距像素；非悬浮恒为 0（带默认值，不破坏既有调用）。 */
        val hInsetPx: Int = 0,
        /** 悬浮态顶部内边距像素；非悬浮恒为 0。 */
        val topInsetPx: Int = 0,
        /** 悬浮态底部内边距像素；非悬浮恒为 0。 */
        val bottomInsetPx: Int = 0,
    )

    /**
     * 从圆角半径纯推导悬浮卡片的三个内边距：左右/底部 0.6×半径，顶部 0.5×半径。
     * 不新增 dimens（避开 R58 资源门禁）；radius=0 或非悬浮时三者全 0。
     */
    private fun resolveInsets(radiusPx: Float, floating: Boolean): Triple<Int, Int, Int> {
        if (!floating) return Triple(0, 0, 0)
        return Triple(
            (radiusPx * 0.6f).roundToInt(),
            (radiusPx * 0.5f).roundToInt(),
            (radiusPx * 0.6f).roundToInt()
        )
    }

    /**
     * @param baseRadiusPx 基础半径像素（= getDimension(ui_panel_radius)），由调用方传入以保持本函数纯。
     * @param config 顶栏包配置（半径/悬浮态的唯一来源）。
     * @param topBarHeightPx 顶栏高度像素，用于把半径钳制到半高，避免 cornerScale=3 变胶囊；<=0 时不钳制。
     */
    fun resolve(baseRadiusPx: Float, config: TopBarConfig.Config, topBarHeightPx: Float): Chrome {
        val raw = TopBarConfig.resolveCornerRadiusPx(baseRadiusPx, config)
        val radius = if (topBarHeightPx > 0f) minOf(raw, topBarHeightPx / 2f) else raw
        val floating = config.floatingChrome
        val (hInset, topInset, bottomInset) = resolveInsets(radius, floating)
        return Chrome(
            radiusPx = radius,
            floating = floating,
            hInsetPx = hInset,
            topInsetPx = topInset,
            bottomInsetPx = bottomInset
        )
    }
}
