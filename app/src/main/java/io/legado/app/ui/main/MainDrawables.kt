package io.legado.app.ui.main

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.ColorUtils as AppColorUtils
import io.legado.app.utils.dpToPx

/**
 * 主界面底部栏 / 侧栏 Drawable 工厂（自 MainActivity 拆出，纯 Context 依赖）。
 */
internal fun Context.mainBottomBarOpacityLevel(value: Int): Float {
    return (value.coerceIn(0, 100) / 200f).coerceIn(0f, 0.5f)
}

internal fun Context.mainStandardBottomBarOpacityLevel(value: Int): Float {
    return (value.coerceIn(0, 100) / 100f).coerceIn(0f, 1f)
}

internal fun Context.mainBottomBarBorderColor(
    config: NavigationBarIconConfig.Config = NavigationBarIconConfig.currentEntry(AppConfig.isNightTheme).config
): Int? {
    val color = config.borderColor ?: return null
    return AppColorUtils.withAlpha(color, config.borderAlpha.coerceIn(0, 100) / 100f)
}

internal fun Context.createSolidBottomShellDrawable(cornerRadius: Float, oval: Boolean): GradientDrawable {
    val config = NavigationBarIconConfig.currentEntry(AppConfig.isNightTheme).config
    val baseColor = bottomBackground
    val alpha = mainStandardBottomBarOpacityLevel(config.opacity)
    return GradientDrawable().apply {
        shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        if (!oval) {
            this.cornerRadius = cornerRadius
        }
        setColor(AppColorUtils.withAlpha(baseColor, alpha))
        mainBottomBarBorderColor(config)?.let { setStroke(1.dpToPx(), it) }
    }
}

internal fun Context.createStandardBottomShellDrawable(): Drawable {
    val config = NavigationBarIconConfig.currentEntry(AppConfig.isNightTheme).config
    val baseColor = bottomBackground
    val alpha = mainStandardBottomBarOpacityLevel(config.opacity)
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 0f
        setColor(AppColorUtils.withAlpha(baseColor, alpha))
        mainBottomBarBorderColor(config)?.let { setStroke(1.dpToPx(), it) }
    }
}

internal fun Context.createEInkBottomShellDrawable(cornerRadius: Float, oval: Boolean): GradientDrawable {
    val baseColor = bottomBackground
    return GradientDrawable().apply {
        shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        if (!oval) {
            this.cornerRadius = cornerRadius
        }
        setColor(baseColor)
        setStroke(1.dpToPx(), AppColorUtils.withAlpha(Color.BLACK, 0.42f))
    }
}

internal fun Context.createSolidBottomIndicatorDrawable(cornerRadius: Float): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        this.cornerRadius = cornerRadius
        setColor(primaryColor)
    }
}

internal fun Context.createSideNavigationScrimDrawable(): GradientDrawable {
    return GradientDrawable().apply {
        setColor(AppColorUtils.withAlpha(Color.BLACK, 0.42f))
    }
}

internal fun Context.createSideNavigationHeaderDrawable(hasWallpaper: Boolean): GradientDrawable {
    val baseColor = bottomBackground
    val isLight = AppColorUtils.isColorLight(baseColor)
    val surface = if (hasWallpaper) {
        AppColorUtils.withAlpha(
            if (AppConfig.isNightTheme) Color.BLACK else Color.WHITE,
            if (AppConfig.isNightTheme) 0.20f else 0.42f
        )
    } else {
        AppColorUtils.blendColors(
            baseColor,
            if (isLight) Color.WHITE else Color.BLACK,
            if (isLight) 0.34f else 0.16f
        )
    }
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = UiCorner.panelRadius(this@createSideNavigationHeaderDrawable)
        setColor(surface)
        setStroke(
            1.dpToPx(),
            AppColorUtils.withAlpha(
                if (isLight) Color.BLACK else Color.WHITE,
                if (hasWallpaper) 0.06f else 0.10f
            )
        )
    }
}

internal fun Context.createSideNavigationSearchDrawable(): GradientDrawable {
    val searchSurfaceColor = if (AppConfig.isNightTheme) {
        AppColorUtils.withAlpha(Color.rgb(52, 52, 56), 0.42f)
    } else {
        AppColorUtils.withAlpha(Color.rgb(120, 120, 128), 0.22f)
    }
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = UiCorner.searchRadius(18f)
        setColor(searchSurfaceColor)
        setStroke(0, Color.TRANSPARENT)
    }
}

internal fun Context.createSideNavigationPanelDrawable(hasWallpaper: Boolean): GradientDrawable {
    val baseColor = bottomBackground
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 0f
        setColor(if (hasWallpaper) Color.TRANSPARENT else baseColor)
        if (hasWallpaper) {
            setStroke(0, Color.TRANSPARENT)
        } else {
            setStroke(
                1.dpToPx(),
                AppColorUtils.withAlpha(
                    if (AppColorUtils.isColorLight(baseColor)) Color.BLACK else Color.WHITE,
                    0.12f
                )
            )
        }
    }
}

internal fun Context.createSideNavigationRowDrawable(selected: Boolean, hasWallpaper: Boolean): Drawable {
    val baseColor = bottomBackground
    val isLight = AppColorUtils.isColorLight(baseColor)
    val fill = if (selected) {
        if (hasWallpaper) {
            AppColorUtils.withAlpha(
                if (AppConfig.isNightTheme) Color.BLACK else Color.WHITE,
                if (AppConfig.isNightTheme) 0.18f else 0.34f
            )
        } else {
            if (AppConfig.isNightTheme) {
                AppColorUtils.withAlpha(Color.rgb(52, 52, 56), 0.46f)
            } else {
                AppColorUtils.withAlpha(Color.rgb(120, 120, 128), 0.20f)
            }
        }
    } else {
        Color.TRANSPARENT
    }
    return InsetDrawable(
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiCorner.actionRadius(this@createSideNavigationRowDrawable)
            setColor(fill)
            setStroke(0, Color.TRANSPARENT)
        },
        4.dpToPx(),
        5.dpToPx(),
        4.dpToPx(),
        5.dpToPx()
    )
}

internal fun Context.createSideNavigationGroupDrawable(selected: Boolean): Drawable {
    val fill = if (selected) {
        if (AppConfig.isNightTheme) {
            AppColorUtils.withAlpha(Color.rgb(52, 52, 56), 0.42f)
        } else {
            AppColorUtils.withAlpha(Color.rgb(120, 120, 128), 0.18f)
        }
    } else {
        Color.TRANSPARENT
    }
    return InsetDrawable(
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiCorner.actionRadius(this@createSideNavigationGroupDrawable)
            setColor(fill)
            setStroke(0, Color.TRANSPARENT)
        },
        12.dpToPx(),
        0,
        12.dpToPx(),
        0
    )
}

internal fun Context.createLiquidGlassShellDrawable(
    glassLevel: Float,
    cornerRadius: Float,
    oval: Boolean,
    selected: Boolean
): GradientDrawable {
    val baseColor = bottomBackground
    val isLight = AppColorUtils.isColorLight(baseColor)
    val surfaceColor = if (isLight) Color.WHITE else Color.rgb(22, 24, 28)
    val fallbackBoost = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) 0.08f else 0f
    val startAlpha = (0.18f + glassLevel * 0.16f + fallbackBoost).coerceIn(0f, 0.44f)
    val centerAlpha = (0.10f + glassLevel * 0.12f + fallbackBoost * 0.65f).coerceIn(0f, 0.32f)
    val endAlpha = (0.08f + glassLevel * 0.10f + fallbackBoost * 0.45f).coerceIn(0f, 0.26f)
    val selectedBoost = if (selected) 0.05f else 0f
    val strokeAlpha = (0.18f + glassLevel * 0.16f + selectedBoost).coerceIn(0f, 0.42f)
    return GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            AppColorUtils.withAlpha(surfaceColor, startAlpha + selectedBoost),
            AppColorUtils.withAlpha(surfaceColor, centerAlpha + selectedBoost),
            AppColorUtils.withAlpha(surfaceColor, endAlpha + selectedBoost)
        )
    ).apply {
        shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        if (!oval) {
            setCornerRadius(cornerRadius)
        }
        setStroke(
            1.dpToPx(),
            mainBottomBarBorderColor() ?: AppColorUtils.withAlpha(surfaceColor, strokeAlpha)
        )
    }
}
