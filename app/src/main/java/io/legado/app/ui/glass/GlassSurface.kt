package io.legado.app.ui.glass

import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.view.View
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.filletControlBackground
import io.legado.app.ui.main.GlassMaterialPreset
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
        cornerRadiusPx: Float,
        screenAligned: Boolean = false
    ): Drawable? = wallpaperDrawable(host, domain) {
        ExploreGlassBackdrop.panelBackground(host, cornerRadiusPx, screenAligned)
    }

    /** 列表行背景。非壁纸域返回 null（调用方保留默认透明背景）。 */
    fun rowBackground(host: View, domain: GlassSamplingDomain): Drawable? =
        wallpaperDrawable(host, domain) { ExploreGlassBackdrop.rowBackground(host) }

    /** 行内小控件背景（带按压态）。非壁纸域返回 null。 */
    fun controlBackground(host: View, domain: GlassSamplingDomain): Drawable? =
        wallpaperDrawable(host, domain) { ExploreGlassBackdrop.controlBackground(host) }

    /**
     * 对话框面板背景（壁纸域，圆角默认 `ui_panel_radius`）。
     *
     * 对话框是独立窗口，采样必须按屏幕而不是按宿主根视图对齐
     * （见 [ExploreGlassBackdrop.panelBackground] 的 screenAligned）。
     * 返回 null 表示本对话框应保持主题实底表面：无壁纸可见 / 非玻璃档 /
     * 墨水屏 / 低内存，调用方回退 [io.legado.app.lib.theme.dialogSurfaceBackground]。
     */
    fun dialogBackground(host: View): Drawable? = dialogBackground(
        host,
        host.resources.getDimension(R.dimen.ui_panel_radius)
    )

    fun dialogBackground(host: View, cornerRadiusPx: Float): Drawable? {
        if (!dialogGlassAllowed(host.context)) return null
        return wallpaperDrawable(host, GlassSamplingDomain.WALLPAPER) {
            ExploreGlassBackdrop.panelBackground(host, cornerRadiusPx, screenAligned = true)
        }
    }

    /**
     * 对话框能否铺壁纸域玻璃（组合期与绘制期共用同一口径）。
     *
     * 三条否决条件：
     * - 宿主页面内容背后不是壁纸（阅读页、书籍详情封面、视频、音频等
     *   `showsWallpaperBehindContent = false` 的页面）：采样到用户看不见的壁纸；
     * - 无玻璃档：此时对话框保持主题实底表面，而不是换成底栏色的实底壳；
     * - 墨水屏 / 低内存：由 [isAvailable] 的设备判定统一否决。
     */
    fun dialogGlassAllowed(context: Context): Boolean = wallpaperHostAllowed(context)

    /**
     * 页面内卡片能否铺壁纸域玻璃（与对话框同一份壁纸可见性口径）。
     *
     * 与 [dialogGlassAllowed] 的区别只在采样方式：页面卡片的宿主根视图就是屏幕
     * （多窗口下是窗口），按根视图对齐即可；对话框是独立小窗口，必须屏幕对齐。
     */
    fun pageGlassAllowed(context: Context): Boolean = wallpaperHostAllowed(context)

    /**
     * 壁纸域玻璃**当前是否还给**（不看宿主页面是否露出壁纸）。
     *
     * 给「View 本身就贴在壁纸上方」的宿主用（顶栏贴边矩形、底栏这类）：调用方已经知道
     * 自己背后是壁纸（页面根背景置空），这里只回答「设备与设置此刻还允不允许玻璃」。
     * 与 [pageGlassAllowed] 的差别只在第一条：这里不做 `BaseActivity` 宿主回溯，
     * 因此顶栏这种在 `setContentView` 之前 / 之后都有调用点的 View 也能判。
     *
     * 无玻璃档必须在这里判否：[panelBackground] 在 solid 档返回的是**底栏色不透明壳**，
     * 与顶栏原背景（透明档 → 透明）不同观感。顶栏降级回退要回到「init 时的原背景」，
     * 就必须在取 drawable 之前先问这里，而不是拿回一个实底壳贴上去。
     */
    fun wallpaperGlassEnabled(context: Context): Boolean =
        resolvedExploreGlassMode() != GlassMaterialPreset.SOLID &&
            isAvailable(GlassSamplingDomain.WALLPAPER, context)

    /**
     * 解绑 [host] 上的玻璃位置跟踪（换回原背景时调）。
     *
     * 与 [pagePanelBackground] 配对：铺玻璃时管线会调
     * [ExploreGlassBackdrop.detachPositionTracking] 的反面（attach），换回实底时
     * 必须调这里，否则滚动 / 布局回调仍会驱动已换下的 drawable。
     */
    fun releaseBackgroundTracking(host: View) {
        ExploreGlassBackdrop.detachPositionTracking(host)
    }

    /**
     * 页面内卡片 / 列表行的背景：壁纸域玻璃优先，不可用时回退调用方给的实底 drawable。
     *
     * 抽成单一入口的原因是「先判 [pageGlassAllowed] 再取 drawable」这两步**不能拆**：
     * [panelBackground] 在无玻璃档返回的是底栏色的不透明实底壳，直接拿来铺页面卡片会
     * 变成「主题卡片 → 底栏色」的观感跳变；必须先让 [pageGlassAllowed] 判否、走调用方
     * 的主题实底回退。solid 档 / 墨水屏 / 低内存 / 无背景图的页面因此逐值保持原样。
     *
     * **回退即解绑**：判否时先 [releaseBackgroundTracking]。这条不能留给调用方各写一遍
     * ——「关于页卡 / 阅读记录面板 / 首页榜单卡」这些宿主是常驻 View，切 solid 或关背景图
     * 后签名变化会重走本方法，但旧 `TrackerBinding` 仍挂在 layout / attach 回调与最近
     * 可滚动祖先的滚动监听上，继续逐帧给一份不再绘制的 drawable 重采样。
     *
     * [cornerRadiusPx] 只影响玻璃层几何（页面卡片带 `uiCornerScale` 缩放、连续列表行取 0）；
     * 观感参数仍由底层管线单点决定，这里没有任何第二份参数表。
     */
    fun pagePanelBackground(
        host: View,
        cornerRadiusPx: Float,
        fallback: () -> Drawable
    ): Drawable {
        if (pageGlassAllowed(host.context)) {
            // 允许但管线给不出 drawable（低内存等）：wallpaperDrawable 已解绑跟踪
            panelBackground(host, GlassSamplingDomain.WALLPAPER, cornerRadiusPx)?.let { return it }
            return fallback()
        }
        releaseBackgroundTracking(host)
        return fallback()
    }

    private fun wallpaperHostAllowed(context: Context): Boolean {
        if (resolvedExploreGlassMode() == GlassMaterialPreset.SOLID) return false
        val host = context.hostBaseActivity() ?: return false
        if (!host.showsWallpaperBehindContent) return false
        return isAvailable(GlassSamplingDomain.WALLPAPER, context)
    }

    private fun Context.hostBaseActivity(): BaseActivity<*>? {
        var current: Context = this
        while (true) {
            if (current is BaseActivity<*>) return current
            val base = (current as? ContextWrapper)?.baseContext ?: return null
            if (base === current) return null
            current = base
        }
    }

    /**
     * 行内小控件的玻璃化回退链：玻璃可用则用玻璃，否则回退主题纯色控件背景。
     *
     * 面板域调用它也只会拿到纯色背景——与直接调用 `Context.filletControlBackground()`
     * 观感一致，但把「这里为什么不能玻璃」写进了调用点。
     */
    fun filletControlBackground(host: View, domain: GlassSamplingDomain): Drawable =
        controlBackground(host, domain) ?: host.context.filletControlBackground()

    /**
     * 配置签名：任何影响玻璃**观感**的设置项变化都会改变它。Compose 桥用它做重绑键，
     * 不必逐个监听偏好键。内容与发现页行重绑用的是同一个签名（单一口径）。
     *
     * **不要用它做「要不要重铺」的判定**：它不含背景图开关。凡是要比较「这次的表面与上次
     * 是否同一档」的调用点，一律用
     * [io.legado.app.ui.glass.wallpaperGlassSignature]（= 本签名 + 壁纸此刻是否可用），
     * 否则「关/开背景图后返回」会命中相同签名而不重铺。
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

    /**
     * 取玻璃 drawable 的统一前置判定 + 回退解绑。
     *
     * 本次没拿到玻璃（非壁纸域 / 设备降级 / 动图壁纸纯色底 / 管线构建失败）时先
     * [releaseBackgroundTracking]：宿主接下来会铺回原表面，旧的位置跟踪必须同时下线，
     * 否则它会继续给一份已经不在绘制的 drawable 重采样（真实路径：切 solid、关背景图、
     * 换壁纸后页面签名变化重走这里）。
     *
     * 拿到玻璃时不解绑——[ExploreGlassBackdrop.panelBackground] 内部已经
     * attach 了一份新的 tracker。
     */
    private inline fun wallpaperDrawable(
        host: View,
        domain: GlassSamplingDomain,
        produce: () -> Drawable?
    ): Drawable? {
        val drawable = if (glassSurfaceDrawableAllowed(domain)) produce() else null
        if (drawable == null) releaseBackgroundTracking(host)
        return drawable
    }
}
