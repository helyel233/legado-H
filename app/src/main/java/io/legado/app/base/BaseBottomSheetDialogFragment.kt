package io.legado.app.base

import android.graphics.Outline
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.LayoutRes
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.surface.SurfaceCorners
import io.legado.app.lib.theme.surface.SurfaceDrawable
import io.legado.app.lib.theme.surface.SurfaceStyles
import io.legado.app.ui.glass.GlassSurface

/** Base contract for dialogs whose owned surface is attached to the bottom edge. */
abstract class BaseBottomSheetDialogFragment(
    @LayoutRes layoutId: Int,
    adaptationSoftKeyboard: Boolean = false
) : BaseDialogFragment(layoutId, adaptationSoftKeyboard) {

    /**
     * 面板表面是否铺壁纸域玻璃，**默认关闭**。
     *
     * 本类被目录 / 分类选择 / 书架合集选择这类「密集内容面板」继承，那里铺玻璃会同时
     * 牺牲可读性与滚动性能；因此不做结构默认，只由确实需要的一个面板覆写为 true
     * （`HomepageModuleManageSheet`）。判定与回退仍全部由 [GlassSurface.dialogBackground]
     * 决定，这里不新增任何采样或材质参数。
     */
    protected open val sheetSurfaceGlass: Boolean get() = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (AppConfig.isEInkMode) return
        val surface: View = view.findViewById<View>(R.id.vw_bg) ?: view
        // 与实底分支的 SurfaceStyles.dialog(…, TOP) 读同一个圆角来源
        // （SurfaceStyles.compactSurfaceRadius = UiCorner.panelRadius），玻璃与实底不可能
        // 在圆角上错开。
        val radius = UiCorner.panelRadius(requireContext())
        val glass = if (sheetSurfaceGlass) {
            GlassSurface.dialogBackground(surface, radius)
        } else {
            null
        }
        if (glass != null) {
            // 玻璃 drawable 只支持「四角同半径」，而本类今天的表面是 SurfaceCorners.TOP
            // （下两角直角）：直接把半径交给玻璃会让下两角也变圆，贴着屏幕底边时还会
            // 在底角漏出两条壁纸缝。这里把轮廓的**底边推到 View 之外**——View 的裁剪区
            // 仍是自身 bounds，于是只有上两角保留圆角、下两角被裁成直角，几何与
            // SurfaceCorners.TOP 逐值一致。轮廓与绘制共用同一个 radius，
            // 上两角的弧度不会与玻璃层错位。
            surface.background = glass
            surface.outlineProvider = TopRoundedOutlineProvider(radius)
            surface.clipToOutline = true
        } else {
            // 底部弹出面板：圆角只留在顶部，覆盖父类默认的四角弹窗表面
            // （这也是「玻璃不可用」时逐值回到改动前的观感：同一份 SurfaceDrawable）
            val style = SurfaceStyles.dialog(requireContext(), SurfaceCorners.TOP)
            surface.background = SurfaceDrawable(null, style)
            surface.clipToOutline = false
        }
    }

    /**
     * 只圆上两角的轮廓：`setRoundRect` 的底边取 `height + radius`，超出 View bounds 的
     * 那部分被裁剪区吃掉，等效于下两角直角。圆角半径为 0 时退化成矩形轮廓。
     */
    private class TopRoundedOutlineProvider(
        private val radiusPx: Float
    ) : ViewOutlineProvider() {

        override fun getOutline(view: View, outline: Outline) {
            val width = view.width
            val height = view.height
            if (width <= 0 || height <= 0) return
            val radius = radiusPx.coerceAtLeast(0f)
            outline.setRoundRect(0, 0, width, height + radius.toInt(), radius)
        }
    }
}
