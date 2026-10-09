package io.legado.app.lib.theme

import android.content.Context
import io.legado.app.utils.ColorUtils
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig

/**
 * P2-d: reader-side semantic colors derived from the active theme, used when
 * ReadBookConfig "follow theme" is enabled. Values follow day/night/E-Ink.
 */
object ThemeReaderBridge {

    fun textColor(context: Context): Int = with(context) {
        getPrimaryTextColor(AppConfig.isNightTheme)
    }

    fun accentColor(context: Context): Int = with(context) {
        accentColor
    }

    /** Solid background (never transparent even with a main background image). */
    fun backgroundColor(context: Context): Int = with(context) {
        ThemeConfig.getFallbackBackgroundColor(this)
    }

    fun darkStatusIcon(context: Context): Boolean =
        ColorUtils.isColorLight(backgroundColor(context))
}
