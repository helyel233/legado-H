package io.legado.app.lib.theme

import android.content.Context
import io.legado.app.help.config.AppConfig
import io.legado.app.theme.apply.ThemeApplier
import io.legado.app.theme.apply.ThemeKeys

/**
 * P0a-4: the key-name truth and the day/night split moved to the theme
 * module ([ThemeKeys]); this object stays as a thin delegate so existing
 * callers keep compiling unchanged. New code should use ThemeKeys directly.
 */
object ThemeRuntimeKeys {

    fun migrateLegacyNightValues(context: Context) {
        ThemeApplier(SpThemePersistence(context)).migrateLegacyNightValues()
    }

    fun fontScale(isNight: Boolean = AppConfig.isNightTheme): String =
        ThemeKeys.fontScale(isNight)

    fun uiFontPath(isNight: Boolean = AppConfig.isNightTheme): String =
        ThemeKeys.uiFontPath(isNight)

    fun titleFontPath(isNight: Boolean = AppConfig.isNightTheme): String =
        ThemeKeys.titleFontPath(isNight)

    fun uiFontColor(isNight: Boolean = AppConfig.isNightTheme): String =
        ThemeKeys.uiFontColor(isNight)

    fun titleFontColor(isNight: Boolean = AppConfig.isNightTheme): String =
        ThemeKeys.titleFontColor(isNight)

    fun uiCornerScale(isNight: Boolean = AppConfig.isNightTheme): String =
        ThemeKeys.uiCornerScale(isNight)

    fun uiLayoutAlpha(isNight: Boolean = AppConfig.isNightTheme): String =
        ThemeKeys.uiLayoutAlpha(isNight)

    fun dialogAlpha(isNight: Boolean = AppConfig.isNightTheme): String =
        ThemeKeys.dialogAlpha(isNight)

    fun uiCornerSearchFollow(isNight: Boolean = AppConfig.isNightTheme): String =
        ThemeKeys.uiCornerSearchFollow(isNight)

    fun uiCornerReplyFollow(isNight: Boolean = AppConfig.isNightTheme): String =
        ThemeKeys.uiCornerReplyFollow(isNight)

    fun themeCardColor(isNight: Boolean = AppConfig.isNightTheme): String =
        ThemeKeys.themeCardColor(isNight)

    fun themeMutedColor(isNight: Boolean = AppConfig.isNightTheme): String =
        ThemeKeys.themeMutedColor(isNight)

    fun themeSearchFieldBackgroundColor(isNight: Boolean = AppConfig.isNightTheme): String =
        ThemeKeys.themeSearchFieldBackgroundColor(isNight)

    fun themeTabBackgroundColor(isNight: Boolean = AppConfig.isNightTheme): String =
        ThemeKeys.themeTabBackgroundColor(isNight)

    fun themeShelfColor(isNight: Boolean = AppConfig.isNightTheme): String =
        ThemeKeys.themeShelfColor(isNight)

    fun themeCardShadow(isNight: Boolean = AppConfig.isNightTheme): String =
        ThemeKeys.themeCardShadow(isNight)

    fun themeCardBackgroundBlur(isNight: Boolean = AppConfig.isNightTheme): String =
        ThemeKeys.themeCardBackgroundBlur(isNight)

    fun themeExploreGlassBlur(isNight: Boolean = AppConfig.isNightTheme): String =
        ThemeKeys.themeExploreGlassBlur(isNight)

    fun activeColorKey(key: String, isNight: Boolean = AppConfig.isNightTheme): String {
        return when (key) {
            ThemeKeys.THEME_CARD_COLOR, ThemeKeys.THEME_CARD_COLOR_NIGHT -> themeCardColor(isNight)
            ThemeKeys.THEME_MUTED_COLOR, ThemeKeys.THEME_MUTED_COLOR_NIGHT -> themeMutedColor(isNight)
            ThemeKeys.THEME_SEARCH_FIELD_BACKGROUND_COLOR,
            ThemeKeys.THEME_SEARCH_FIELD_BACKGROUND_COLOR_NIGHT -> themeSearchFieldBackgroundColor(isNight)
            ThemeKeys.THEME_TAB_BACKGROUND_COLOR, ThemeKeys.THEME_TAB_BACKGROUND_COLOR_NIGHT ->
                themeTabBackgroundColor(isNight)
            ThemeKeys.THEME_SHELF_COLOR, ThemeKeys.THEME_SHELF_COLOR_NIGHT -> themeShelfColor(isNight)
            else -> key
        }
    }

    fun allKeys(): Set<String> = setOf(
        ThemeKeys.FONT_SCALE, ThemeKeys.FONT_SCALE_NIGHT,
        ThemeKeys.UI_FONT_PATH, ThemeKeys.UI_FONT_PATH_NIGHT,
        ThemeKeys.TITLE_FONT_PATH, ThemeKeys.TITLE_FONT_PATH_NIGHT,
        ThemeKeys.UI_FONT_COLOR, ThemeKeys.UI_FONT_COLOR_NIGHT,
        ThemeKeys.TITLE_FONT_COLOR, ThemeKeys.TITLE_FONT_COLOR_NIGHT,
        ThemeKeys.UI_CORNER_SCALE, ThemeKeys.UI_CORNER_SCALE_NIGHT,
        ThemeKeys.UI_LAYOUT_ALPHA, ThemeKeys.UI_LAYOUT_ALPHA_NIGHT,
        ThemeKeys.DIALOG_ALPHA, ThemeKeys.DIALOG_ALPHA_NIGHT,
        ThemeKeys.UI_CORNER_SEARCH_FOLLOW, ThemeKeys.UI_CORNER_SEARCH_FOLLOW_NIGHT,
        ThemeKeys.UI_CORNER_REPLY_FOLLOW, ThemeKeys.UI_CORNER_REPLY_FOLLOW_NIGHT,
        ThemeKeys.THEME_CARD_COLOR, ThemeKeys.THEME_CARD_COLOR_NIGHT,
        ThemeKeys.THEME_MUTED_COLOR, ThemeKeys.THEME_MUTED_COLOR_NIGHT,
        ThemeKeys.THEME_SEARCH_FIELD_BACKGROUND_COLOR,
        ThemeKeys.THEME_SEARCH_FIELD_BACKGROUND_COLOR_NIGHT,
        ThemeKeys.THEME_TAB_BACKGROUND_COLOR, ThemeKeys.THEME_TAB_BACKGROUND_COLOR_NIGHT,
        ThemeKeys.THEME_SHELF_COLOR, ThemeKeys.THEME_SHELF_COLOR_NIGHT,
        ThemeKeys.THEME_CARD_SHADOW, ThemeKeys.THEME_CARD_SHADOW_NIGHT,
        ThemeKeys.THEME_CARD_BACKGROUND_BLUR, ThemeKeys.THEME_CARD_BACKGROUND_BLUR_NIGHT,
        ThemeKeys.THEME_EXPLORE_GLASS_BLUR, ThemeKeys.THEME_EXPLORE_GLASS_BLUR_NIGHT,
    )
}
