package io.legado.app.ui.glass

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.filletControlBackground
import io.legado.app.ui.main.explore.ExploreGlassBackdrop
import io.legado.app.ui.main.explore.resolvedExploreGlassMode

/**
 * 跨包（非发现页）宿主的玻璃入口。
 *
 * 与发现页行、主底栏是**同一份** drawable 与材质：四档降级（液态 AGSL / 硬件高斯 /
 * 软件预模糊 / 实底壳）、设备判定与圆角全部由底层管线决定，这里只做三件事：
 *
 * 1. **要求显式声明采样域**（无默认值）。非壁纸域一律返回 null，调用方保留原有背景。
 *    这是从结构上阻止「在不透明面板里采样壁纸」的唯一可靠方式，见 [GlassSamplingDomain]。
 * 2. 收敛「玻璃 ?: 主题纯色控件背景」的回退链到 [filletControlBackground] 一个实现。
 * 3. 给 Compose 桥（[GlassPanel]）提供组合期判定与配置签名两个只读入口，
 *    判定规则仍由本包纯函数表与底层管线决定。
 *
 * 实现位置暂留在发现页包（那条管线刚在 1.25.11 稳定，不做大范围搬迁）；本包是
 * 跨包调用方唯一应该 import 的入口。
 */
object GlassSurface {

    /**
     * 底栏 / 面板语义的玻璃背景（与主底栏同圆角 `ui_panel_radius`）。
     * 非壁纸域返回 null。
     */
    fun panelBackground(host: View, domain: GlassSamplingDomain): Drawable? =
        wallpaperDrawable(host, domain) { ExploreGlassBackdrop.panelBackground(host) }

    /**
     * 同一份面板玻璃，圆角由调用方给（Compose 卡片的圆角带 `uiCornerScale` 缩放，
     * 不是 `ui_panel_radius` 原值）。除几何外没有任何参数分支。
     */
    fun panelBackground(
        host: View,
        domain: GlassSamplingDomain,
        cornerRadiusPx: Float
    ): Drawable? = wallpaperDrawable(host, domain) {
        ExploreGlassBackdrop.panelBackground(host, cornerRadiusPx)
    }

    /** 列表行背景。非壁纸域返回 null（调用方保留默认透明背景）。 */
    fun rowBackground(host: View, domain: GlassSamplingDomain): Drawable? =
        wallpaperDrawable(host, domain) { ExploreGlassBackdrop.rowBackground(host) }

    /** 行内小控件背景（带按压态）。非壁纸域返回 null。 */
    fun controlBackground(host: View, domain: GlassSamplingDomain): Drawable? =
        wallpaperDrawable(host, domain) { ExploreGlassBackdrop.controlBackground(host) }

    /**
     * 行内小控件的玻璃化回退链：玻璃可用则用玻璃，否则回退主题纯色控件背景。
     *
     * 面板域调用它也只会拿到纯色背景——与直接调用 `Context.filletControlBackground()`
     * 观感一致，但把「这里为什么不能玻璃」写进了调用点。
     */
    fun filletControlBackground(host: View, domain: GlassSamplingDomain): Drawable =
        controlBackground(host, domain) ?: host.context.filletControlBackground()

    /**
     * 配置签名：任何影响玻璃观感的设置项变化都会改变它。Compose 桥用它做重绑键，
     * 不必逐个监听偏好键。内容与发现页行重绑用的是同一个签名（单一口径）。
     */
    fun configSignature(context: Context): String =
        ExploreGlassBackdrop.configSignature(context)

    /**
     * 组合期可用性判定：与 drawable 入口的早退分支同源（纯函数
     * [glassSurfaceAvailable]），因此判「可用」后拿到的背景一定非空。
     */
    fun isAvailable(domain: GlassSamplingDomain, context: Context): Boolean =
        glassSurfaceAvailable(
            domain = domain,
            mode = resolvedExploreGlassMode(),
            isEInk = AppConfig.isEInkMode,
            isLowRam = ExploreGlassBackdrop.isLowRamDevice(context)
        )

    private inline fun wallpaperDrawable(
        host: View,
        domain: GlassSamplingDomain,
        produce: () -> Drawable?
    ): Drawable? = if (glassSurfaceDrawableAllowed(domain)) produce() else null
}
