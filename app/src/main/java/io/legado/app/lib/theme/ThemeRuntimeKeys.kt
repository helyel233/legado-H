package io.legado.app.lib.theme

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.defaultSharedPreferences

object ThemeRuntimeKeys {

    private const val legacyNightMigratedKey = "themeNightExtMigrated"

    // 日夜键拆分前，这些字段日夜共用旧键。首次升级时把旧值复制到夜间键，
    // 否则夜间模式的字号/字体/颜色/透明度等会全部回落默认值。
    private val legacyNightPairs = NightKeyPairs.themeRuntimePairs

    fun migrateLegacyNightValues(context: Context) {
        val prefs = context.defaultSharedPreferences
        if (prefs.getBoolean(legacyNightMigratedKey, false)) return
        val all = prefs.all
        val editor = prefs.edit()
        legacyNightPairs.forEach { (dayKey, nightKey) ->
            if (!all.containsKey(nightKey)) {
                when (val value = all[dayKey]) {
                    is Int -> editor.putInt(nightKey, value)
                    is Boolean -> editor.putBoolean(nightKey, value)
                    is String -> editor.putString(nightKey, value)
                    is Float -> editor.putFloat(nightKey, value)
                    is Long -> editor.putLong(nightKey, value)
                }
            }
        }
        editor.putBoolean(legacyNightMigratedKey, true)
        editor.commit()
    }

    fun fontScale(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.fontScaleN else PreferKey.fontScale

    fun uiFontPath(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.uiFontPathN else PreferKey.uiFontPath

    fun titleFontPath(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.titleFontPathN else PreferKey.titleFontPath

    fun uiFontColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.uiFontColorN else PreferKey.uiFontColor

    fun titleFontColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.titleFontColorN else PreferKey.titleFontColor

    fun uiCornerScale(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.uiCornerScaleN else PreferKey.uiCornerScale

    fun uiLayoutAlpha(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.uiLayoutAlphaN else PreferKey.uiLayoutAlpha

    fun dialogAlpha(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.dialogAlphaN else PreferKey.dialogAlpha

    fun uiCornerSearchFollow(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.uiCornerSearchFollowN else PreferKey.uiCornerSearchFollow

    fun uiCornerReplyFollow(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.uiCornerReplyFollowN else PreferKey.uiCornerReplyFollow

    fun themeCardColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeCardColorN else PreferKey.themeCardColor

    fun themeMutedColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeMutedColorN else PreferKey.themeMutedColor

    fun themeSearchFieldBackgroundColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeSearchFieldBackgroundColorN else PreferKey.themeSearchFieldBackgroundColor

    fun themeTabBackgroundColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeTabBackgroundColorN else PreferKey.themeTabBackgroundColor

    fun themeShelfColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeShelfColorN else PreferKey.themeShelfColor

    fun themeCardShadow(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeCardShadowN else PreferKey.themeCardShadow

    fun themeCardBackgroundBlur(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeCardBackgroundBlurN else PreferKey.themeCardBackgroundBlur

    fun themeExploreGlassBlur(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeExploreGlassBlurN else PreferKey.themeExploreGlassBlur

    fun activeColorKey(key: String, isNight: Boolean = AppConfig.isNightTheme): String {
        return when (key) {
            PreferKey.themeCardColor, PreferKey.themeCardColorN -> themeCardColor(isNight)
            PreferKey.themeMutedColor, PreferKey.themeMutedColorN -> themeMutedColor(isNight)
            PreferKey.themeSearchFieldBackgroundColor,
            PreferKey.themeSearchFieldBackgroundColorN -> themeSearchFieldBackgroundColor(isNight)
            PreferKey.themeTabBackgroundColor, PreferKey.themeTabBackgroundColorN -> themeTabBackgroundColor(isNight)
            PreferKey.themeShelfColor, PreferKey.themeShelfColorN -> themeShelfColor(isNight)
            else -> key
        }
    }

    fun allKeys(): Set<String> = NightKeyPairs.themeRuntimeKeys
}
