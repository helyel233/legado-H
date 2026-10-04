package io.legado.app.lib.theme.surface

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.lib.theme.UiCorner
import io.legado.app.utils.dpToPx

/**
 * 可调表面的唯一视觉描述。窗口类型、截图时机和内容布局不应进入这里。
 * （legado-H 简化版：不含主题包底图与模糊采集，仅保留圆角/着色/描边。）
 */
data class SurfaceStyle(
    @param:ColorInt val tintColor: Int,
    val cornerRadiusPx: Float,
    val corners: SurfaceCorners = SurfaceCorners.ALL,
    @param:ColorInt val strokeColor: Int = android.graphics.Color.TRANSPARENT,
    val strokeWidthPx: Float = 0f,
    val blurRadiusPx: Int = 0,
    val backdropImagePath: String? = null,
    val backdropImageFitInside: Boolean = false
)

enum class SurfaceCorners {
    NONE,
    ALL,
    TOP
}

/**
 * 弹窗、阅读浮层和普通 UI 块从这里取得样式，避免各页面重复计算透明度与圆角。
 */
object SurfaceStyles {

    private const val PANEL_STROKE_WIDTH_DP = 1f

    fun compactSurfaceRadius(context: Context): Float {
        return UiCorner.panelRadius(context)
    }

    fun dialogSurfaceColor(@ColorInt color: Int): Int = color

    fun dialogBlurRadius(): Int = 0

    fun themePanelBorderColor(@Suppress("UNUSED_PARAMETER") context: Context): Int? = null

    fun themePanelImagePath(@Suppress("UNUSED_PARAMETER") context: Context): String? = null

    fun themePanelImageFitInside(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false

    fun dialog(context: Context, corners: SurfaceCorners = SurfaceCorners.ALL): SurfaceStyle {
        val themeStroke = themePanelBorderColor(context)
        return SurfaceStyle(
            tintColor = dialogSurfaceColor(
                ContextCompat.getColor(context, R.color.dialog_surface)
            ),
            cornerRadiusPx = compactSurfaceRadius(context),
            corners = corners,
            strokeColor = themeStroke ?: android.graphics.Color.TRANSPARENT,
            strokeWidthPx = if (themeStroke != null) {
                PANEL_STROKE_WIDTH_DP.dpToPx()
            } else {
                0f
            },
            blurRadiusPx = dialogBlurRadius(),
            backdropImagePath = themePanelImagePath(context),
            backdropImageFitInside = themePanelImageFitInside(context)
        )
    }

    fun popup(context: Context): SurfaceStyle = dialog(context)

    fun reading(
        @ColorInt tintColor: Int,
        cornerRadiusPx: Float,
        corners: SurfaceCorners = SurfaceCorners.ALL,
        @ColorInt strokeColor: Int = android.graphics.Color.TRANSPARENT,
        strokeWidthPx: Float = 0f,
        blurRadiusPx: Int = 0
    ): SurfaceStyle {
        return SurfaceStyle(
            tintColor = tintColor,
            cornerRadiusPx = cornerRadiusPx,
            corners = corners,
            strokeColor = strokeColor,
            strokeWidthPx = strokeWidthPx,
            blurRadiusPx = blurRadiusPx
        )
    }

    fun ui(
        context: Context,
        @ColorInt color: Int,
        cornerRadiusPx: Float = UiCorner.panelRadius(context),
        corners: SurfaceCorners = SurfaceCorners.ALL,
        @ColorInt strokeColor: Int = android.graphics.Color.TRANSPARENT,
        strokeWidthPx: Float = 0f
    ): SurfaceStyle {
        return SurfaceStyle(
            tintColor = UiCorner.surfaceColor(color),
            cornerRadiusPx = cornerRadiusPx,
            corners = corners,
            strokeColor = strokeColor,
            strokeWidthPx = strokeWidthPx
        )
    }
}
