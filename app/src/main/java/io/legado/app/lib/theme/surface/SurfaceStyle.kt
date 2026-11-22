package io.legado.app.lib.theme.surface

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.ThemeTokenCompiler
import io.legado.app.lib.theme.UiCorner
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefString
import kotlin.math.roundToInt

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

    /**
     * 对话框 / 阅读浮层 / 浮层的描边色：统一取主题包「边框线色」（跟随强调色或选色）。
     * 之前恒返回 null 导致走 SurfaceDrawable 的弹窗一律无边框，与其他界面不一致。
     */
    fun themePanelBorderColor(context: Context): Int? = UiCorner.borderLineColor(context)

    fun themePanelImagePath(context: Context): String? {
        if (AppConfig.isEInkMode) return null
        val key = if (AppConfig.isNightTheme) PreferKey.panelBgImageN else PreferKey.panelBgImage
        return context.getPrefString(key)?.takeIf { it.isNotBlank() }
    }

    fun themePanelImageFitInside(context: Context): Boolean {
        val key = if (AppConfig.isNightTheme) PreferKey.panelBgScaleTypeN else PreferKey.panelBgScaleType
        return context.getPrefString(key) == ThemeConfig.PANEL_BG_FIT
    }

    fun dialog(context: Context, corners: SurfaceCorners = SurfaceCorners.ALL): SurfaceStyle {
        val tokens = ThemeTokenCompiler.compileFromContext(context)
        val dialogScope = tokens.dialog
        val baseTint = dialogSurfaceColor(
            ContextCompat.getColor(context, R.color.dialog_surface)
        )
        val tintColor = dialogScope.resolveSurfaceColorIfActive(baseTint, tokens.env.isEInk)
        val radiusPx = if (!tokens.env.isEInk && dialogScope.isCustomOverride) {
            dialogScope.panelRadiusPx
        } else {
            compactSurfaceRadius(context)
        }
        val rawStroke = themePanelBorderColor(context)
        val strokeColor = if (rawStroke != null && !tokens.env.isEInk && dialogScope.isCustomOverride) {
            ColorUtils.withAlpha(rawStroke, dialogScope.borderAlphaFraction)
        } else {
            rawStroke ?: android.graphics.Color.TRANSPARENT
        }
        val blurPx = if (tokens.env.isEInk) {
            0
        } else {
            dialogScope.blurRadiusDp.roundToInt().coerceAtLeast(dialogBlurRadius())
        }
        return SurfaceStyle(
            tintColor = tintColor,
            cornerRadiusPx = radiusPx,
            corners = corners,
            strokeColor = strokeColor,
            strokeWidthPx = if (rawStroke != null) {
                PANEL_STROKE_WIDTH_DP.dpToPx()
            } else {
                0f
            },
            blurRadiusPx = blurPx,
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
