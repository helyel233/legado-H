@file:Suppress("unused")

package io.legado.app.lib.theme

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
@ColorInt
fun Context.getPrimaryTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_primary_text)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_primary_text)
    }
}

@ColorInt
fun Context.getSecondaryTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_secondary)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_primary_text)
    }
}

@ColorInt
fun Context.getPrimaryDisabledTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_disabled)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_disabled)
    }
}

@ColorInt
fun Context.getSecondaryDisabledTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(
            this,
            androidx.appcompat.R.color.secondary_text_disabled_material_light
        )
    } else {
        ContextCompat.getColor(
            this,
            androidx.appcompat.R.color.secondary_text_disabled_material_dark
        )
    }
}

val Context.primaryColor: Int
    get() = ThemeStore.primaryColor(this)

val Context.primaryColorDark: Int
    get() = ThemeStore.primaryColorDark(this)

val Context.accentColor: Int
    get() = ThemeStore.accentColor(this)

val Context.backgroundColor: Int
    get() = if (!AppConfig.isEInkMode && ThemeConfig.hasUsableBgImage(this)) {
        Color.TRANSPARENT
    } else {
        ThemeStore.backgroundColor(this)
    }

val Context.bottomBackground: Int
    get() = ThemeStore.bottomBackground(this)

val Context.primaryTextColor: Int
    get() = AppConfig.uiFontColor.toThemeTextColorOrNull()
        ?: defaultThemeTextColor(AppConfig.isNightTheme)

val Context.titleTextColor: Int
    get() = AppConfig.titleFontColor.toThemeTextColorOrNull()
        ?: defaultThemeTextColor(AppConfig.isNightTheme)

val Context.transparentNavBar: Boolean
    get() = ThemeStore.transparentNavBar(this)

val Context.secondaryTextColor: Int
    get() = AppConfig.uiFontColor.toThemeTextColorOrNull()
        ?.let { ColorUtils.withAlpha(it, 0.72f) }
        ?: ColorUtils.withAlpha(defaultThemeTextColor(AppConfig.isNightTheme), 0.72f)

val Context.primaryDisabledTextColor: Int
    get() = getPrimaryDisabledTextColor(!AppConfig.isNightTheme)

val Context.secondaryDisabledTextColor: Int
    get() = getSecondaryDisabledTextColor(!AppConfig.isNightTheme)

val Fragment.primaryColor: Int
    get() = ThemeStore.primaryColor(requireContext())

val Fragment.primaryColorDark: Int
    get() = ThemeStore.primaryColorDark(requireContext())

val Fragment.accentColor: Int
    get() = ThemeStore.accentColor(requireContext())

val Fragment.backgroundColor: Int
    get() = requireContext().backgroundColor

val Fragment.bottomBackground: Int
    get() = ThemeStore.bottomBackground(requireContext())

val Fragment.primaryTextColor: Int
    get() = AppConfig.uiFontColor.toThemeTextColorOrNull()
        ?: defaultThemeTextColor(AppConfig.isNightTheme)

val Fragment.secondaryTextColor: Int
    get() = AppConfig.uiFontColor.toThemeTextColorOrNull()
        ?.let { ColorUtils.withAlpha(it, 0.72f) }
        ?: ColorUtils.withAlpha(defaultThemeTextColor(AppConfig.isNightTheme), 0.72f)

val Fragment.primaryDisabledTextColor: Int
    get() = requireContext().getPrimaryDisabledTextColor(!AppConfig.isNightTheme)

val Fragment.secondaryDisabledTextColor: Int
    get() = requireContext().getSecondaryDisabledTextColor(!AppConfig.isNightTheme)

@ColorInt
fun String?.toThemeTextColorOrNull(): Int? {
    val raw = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val withoutPrefix = raw
        .removePrefix("#")
        .removePrefix("0x")
        .removePrefix("0X")
    val candidate = if (
        withoutPrefix.length in setOf(6, 8) &&
        withoutPrefix.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    ) {
        "#$withoutPrefix"
    } else {
        raw
    }
    return kotlin.runCatching { candidate.toColorInt() }.getOrNull()
}

@ColorInt
fun defaultThemeTextColor(isNightTheme: Boolean): Int {
    return if (isNightTheme) Color.WHITE else Color.BLACK
}

fun defaultThemeTextColorHex(isNightTheme: Boolean): String {
    return if (isNightTheme) "#FFFFFF" else "#000000"
}

val Context.buttonDisabledColor: Int
    get() = if (AppConfig.isNightTheme) {
        ContextCompat.getColor(this, R.color.md_dark_disabled)
    } else {
        ContextCompat.getColor(this, R.color.md_light_disabled)
    }

val Context.isDarkTheme: Boolean
    get() = AppConfig.isNightTheme

val Fragment.isDarkTheme: Boolean
    get() = requireContext().isDarkTheme

val Context.elevation: Float
    @SuppressLint("PrivateResource")
    get() {
        return if (AppConfig.elevation < 0) {
            ThemeUtils.resolveFloat(
                this,
                android.R.attr.elevation,
                resources.getDimension(com.google.android.material.R.dimen.design_appbar_elevation)
            )
        } else {
            AppConfig.elevation.toFloat().dpToPx()
        }
    }

val Context.filletBackground: Drawable
    get() {
        return UiCorner.panelRounded(this, backgroundColor, UiCorner.panelRadius(this))
    }

/**
 * 圆角小控件（发现页、书源登录、书源调试、视频控制条等）的主题化背景：
 * 底色与主题控件面板同源（主题柔和色），不透明度直接跟随主题设置的
 * 界面不透明度（UiCorner.surfaceColor，按下加深 0.08），与弹窗、
 * Compose 列表表面同一语义，替代上游硬编码蓝色的 selector_fillet_btn_bg。
 */
fun Context.filletControlBackground(): StateListDrawable {
    val base = themeMutedColorOrDefault()
    fun state(pressed: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiCorner.actionRadius(this@filletControlBackground)
            setColor(UiCorner.surfaceColor(base, pressed))
        }
    }
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), state(true))
        addState(intArrayOf(), state(false))
    }
}

/**
 * 递归把布局里静态引用 selector_fillet_btn_bg 的控件替换为主题化背景，
 * 供布局直接引用该 drawable 的页面（调试页、视频控制条等）调用。
 */
fun View.applyThemedFilletControlBackground() {
    val target = ContextCompat.getDrawable(context, R.drawable.selector_fillet_btn_bg)
        ?.constantState ?: return
    fun walk(view: View) {
        if (view.background?.constantState == target) {
            view.background = context.filletControlBackground()
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                walk(view.getChildAt(index))
            }
        }
    }
    walk(this)
}

val Context.dialogSurfaceBackground: GradientDrawable
    get() {
        val surfaceColor = themeColorOrNull(PreferKey.themeCardColor)
            ?: ContextCompat.getColor(this, R.color.dialog_surface)
        return UiCorner.opaqueRounded(surfaceColor, UiCorner.panelRadius(this))
    }

fun Context.filletTopBackground(@ColorInt color: Int): GradientDrawable {
    val radius = UiCorner.panelRadius(this)
    return GradientDrawable().apply {
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        setColor(color)
    }
}
