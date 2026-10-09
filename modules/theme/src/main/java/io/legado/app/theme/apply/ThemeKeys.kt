package io.legado.app.theme.apply

/**
 * Single source of truth for theme preference key names and the day/night
 * key split (moved from the app's ThemeRuntimeKeys in P0a-4).
 *
 * The literal values MUST stay identical to the keys written by H.1.7.3
 * (app's PreferKey constants) — they are the storage contract that makes
 * direct upgrade from H.1.7.3 keep every theme setting (plan rule 10.0).
 */
object ThemeKeys {

    // ---- font scale & typography ----
    const val FONT_SCALE = "fontScale"
    const val FONT_SCALE_NIGHT = "fontScaleNight"
    const val UI_FONT_PATH = "ui_font_path"
    const val UI_FONT_PATH_NIGHT = "ui_font_path_night"
    const val TITLE_FONT_PATH = "title_font_path"
    const val TITLE_FONT_PATH_NIGHT = "title_font_path_night"
    const val UI_FONT_COLOR = "ui_font_color"
    const val UI_FONT_COLOR_NIGHT = "ui_font_color_night"
    const val TITLE_FONT_COLOR = "title_font_color"
    const val TITLE_FONT_COLOR_NIGHT = "title_font_color_night"

    // ---- shape & translucency ----
    const val UI_CORNER_SCALE = "uiCornerScale"
    const val UI_CORNER_SCALE_NIGHT = "uiCornerScaleNight"
    const val UI_LAYOUT_ALPHA = "uiLayoutAlpha"
    const val UI_LAYOUT_ALPHA_NIGHT = "uiLayoutAlphaNight"
    const val DIALOG_ALPHA = "dialogAlpha"
    const val DIALOG_ALPHA_NIGHT = "dialogAlphaNight"
    const val UI_CORNER_SEARCH_FOLLOW = "uiCornerSearchFollow"
    const val UI_CORNER_SEARCH_FOLLOW_NIGHT = "uiCornerSearchFollowNight"
    const val UI_CORNER_REPLY_FOLLOW = "uiCornerReplyFollow"
    const val UI_CORNER_REPLY_FOLLOW_NIGHT = "uiCornerReplyFollowNight"

    // ---- extended surface colors ----
    const val THEME_CARD_COLOR = "themeCardColor"
    const val THEME_CARD_COLOR_NIGHT = "themeCardColorNight"
    const val THEME_MUTED_COLOR = "themeMutedColor"
    const val THEME_MUTED_COLOR_NIGHT = "themeMutedColorNight"
    const val THEME_SEARCH_FIELD_BACKGROUND_COLOR = "themeSearchFieldBackgroundColor"
    const val THEME_SEARCH_FIELD_BACKGROUND_COLOR_NIGHT = "themeSearchFieldBackgroundColorNight"
    const val THEME_TAB_BACKGROUND_COLOR = "themeTabBackgroundColor"
    const val THEME_TAB_BACKGROUND_COLOR_NIGHT = "themeTabBackgroundColorNight"
    const val THEME_SHELF_COLOR = "themeShelfColor"
    const val THEME_SHELF_COLOR_NIGHT = "themeShelfColorNight"
    const val THEME_CARD_SHADOW = "themeCardShadow"
    const val THEME_CARD_SHADOW_NIGHT = "themeCardShadowNight"
    const val THEME_CARD_BACKGROUND_BLUR = "themeCardBackgroundBlur"
    const val THEME_CARD_BACKGROUND_BLUR_NIGHT = "themeCardBackgroundBlurNight"
    const val THEME_EXPLORE_GLASS_BLUR = "themeExploreGlassBlur"
    const val THEME_EXPLORE_GLASS_BLUR_NIGHT = "themeExploreGlassBlurNight"

    // ---- main palette, theme name, backgrounds ----
    const val THEME_NAME = "durThemeName"
    const val THEME_NAME_NIGHT = "durThemeNameNight"
    const val COLOR_PRIMARY = "colorPrimary"
    const val COLOR_PRIMARY_NIGHT = "colorPrimaryNight"
    const val COLOR_ACCENT = "colorAccent"
    const val COLOR_ACCENT_NIGHT = "colorAccentNight"
    const val COLOR_BACKGROUND = "colorBackground"
    const val COLOR_BACKGROUND_NIGHT = "colorBackgroundNight"
    const val COLOR_BOTTOM_BACKGROUND = "colorBottomBackground"
    const val COLOR_BOTTOM_BACKGROUND_NIGHT = "colorBottomBackgroundNight"
    const val TRANSPARENT_NAV_BAR = "transparentNavBar"
    const val TRANSPARENT_NAV_BAR_NIGHT = "transparentNavBarNight"
    const val BG_IMAGE = "backgroundImage"
    const val BG_IMAGE_NIGHT = "backgroundImageNight"
    const val BG_IMAGE_BLURRING = "backgroundImageBlurring"
    const val BG_IMAGE_NIGHT_BLURRING = "backgroundImageNightBlurring"
    const val BG_IMAGE_CROP = "backgroundImageCrop"
    const val BG_IMAGE_NIGHT_CROP = "backgroundImageNightCrop"
    const val BOOK_INFO_BG_IMAGE = "bookInfoBackgroundImage"
    const val BOOK_INFO_BG_IMAGE_NIGHT = "bookInfoBackgroundImageNight"
    const val PANEL_BG_IMAGE = "panelBackgroundImage"
    const val PANEL_BG_IMAGE_NIGHT = "panelBackgroundImageNight"
    const val PANEL_BG_SCALE_TYPE = "panelBackgroundScaleType"
    const val PANEL_BG_SCALE_TYPE_NIGHT = "panelBackgroundScaleTypeNight"
    const val PANEL_BORDER_COLOR = "panelBorderColor"
    const val PANEL_BORDER_COLOR_NIGHT = "panelBorderColorNight"
    const val PANEL_BORDER_ALPHA = "panelBorderAlpha"
    const val PANEL_BORDER_ALPHA_NIGHT = "panelBorderAlphaNight"

    fun fontScale(night: Boolean): String = if (night) FONT_SCALE_NIGHT else FONT_SCALE

    fun uiFontPath(night: Boolean): String = if (night) UI_FONT_PATH_NIGHT else UI_FONT_PATH

    fun titleFontPath(night: Boolean): String = if (night) TITLE_FONT_PATH_NIGHT else TITLE_FONT_PATH

    fun uiFontColor(night: Boolean): String = if (night) UI_FONT_COLOR_NIGHT else UI_FONT_COLOR

    fun titleFontColor(night: Boolean): String = if (night) TITLE_FONT_COLOR_NIGHT else TITLE_FONT_COLOR

    fun uiCornerScale(night: Boolean): String = if (night) UI_CORNER_SCALE_NIGHT else UI_CORNER_SCALE

    fun uiLayoutAlpha(night: Boolean): String = if (night) UI_LAYOUT_ALPHA_NIGHT else UI_LAYOUT_ALPHA

    fun dialogAlpha(night: Boolean): String = if (night) DIALOG_ALPHA_NIGHT else DIALOG_ALPHA

    fun uiCornerSearchFollow(night: Boolean): String =
        if (night) UI_CORNER_SEARCH_FOLLOW_NIGHT else UI_CORNER_SEARCH_FOLLOW

    fun uiCornerReplyFollow(night: Boolean): String =
        if (night) UI_CORNER_REPLY_FOLLOW_NIGHT else UI_CORNER_REPLY_FOLLOW

    fun themeCardColor(night: Boolean): String =
        if (night) THEME_CARD_COLOR_NIGHT else THEME_CARD_COLOR

    fun themeMutedColor(night: Boolean): String =
        if (night) THEME_MUTED_COLOR_NIGHT else THEME_MUTED_COLOR

    fun themeSearchFieldBackgroundColor(night: Boolean): String =
        if (night) THEME_SEARCH_FIELD_BACKGROUND_COLOR_NIGHT else THEME_SEARCH_FIELD_BACKGROUND_COLOR

    fun themeTabBackgroundColor(night: Boolean): String =
        if (night) THEME_TAB_BACKGROUND_COLOR_NIGHT else THEME_TAB_BACKGROUND_COLOR

    fun themeShelfColor(night: Boolean): String =
        if (night) THEME_SHELF_COLOR_NIGHT else THEME_SHELF_COLOR

    fun themeCardShadow(night: Boolean): String =
        if (night) THEME_CARD_SHADOW_NIGHT else THEME_CARD_SHADOW

    fun themeCardBackgroundBlur(night: Boolean): String =
        if (night) THEME_CARD_BACKGROUND_BLUR_NIGHT else THEME_CARD_BACKGROUND_BLUR

    fun themeExploreGlassBlur(night: Boolean): String =
        if (night) THEME_EXPLORE_GLASS_BLUR_NIGHT else THEME_EXPLORE_GLASS_BLUR

    /** Day/night pairs that shared one key before the split. */
    val legacyNightPairs: List<Pair<String, String>> = listOf(
        FONT_SCALE to FONT_SCALE_NIGHT,
        UI_FONT_PATH to UI_FONT_PATH_NIGHT,
        TITLE_FONT_PATH to TITLE_FONT_PATH_NIGHT,
        UI_FONT_COLOR to UI_FONT_COLOR_NIGHT,
        TITLE_FONT_COLOR to TITLE_FONT_COLOR_NIGHT,
        UI_CORNER_SCALE to UI_CORNER_SCALE_NIGHT,
        UI_LAYOUT_ALPHA to UI_LAYOUT_ALPHA_NIGHT,
        DIALOG_ALPHA to DIALOG_ALPHA_NIGHT,
        UI_CORNER_SEARCH_FOLLOW to UI_CORNER_SEARCH_FOLLOW_NIGHT,
        UI_CORNER_REPLY_FOLLOW to UI_CORNER_REPLY_FOLLOW_NIGHT,
        THEME_CARD_COLOR to THEME_CARD_COLOR_NIGHT,
        THEME_MUTED_COLOR to THEME_MUTED_COLOR_NIGHT,
        THEME_SEARCH_FIELD_BACKGROUND_COLOR to THEME_SEARCH_FIELD_BACKGROUND_COLOR_NIGHT,
        THEME_TAB_BACKGROUND_COLOR to THEME_TAB_BACKGROUND_COLOR_NIGHT,
        THEME_SHELF_COLOR to THEME_SHELF_COLOR_NIGHT,
        THEME_CARD_SHADOW to THEME_CARD_SHADOW_NIGHT,
        THEME_CARD_BACKGROUND_BLUR to THEME_CARD_BACKGROUND_BLUR_NIGHT,
        THEME_EXPLORE_GLASS_BLUR to THEME_EXPLORE_GLASS_BLUR_NIGHT,
    )
}
