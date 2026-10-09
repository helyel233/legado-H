package io.legado.app.theme.apply

import io.legado.app.theme.persistence.ThemePersistence

/**
 * Pure key-value write logic for applying a theme config (moved from the
 * app's ThemeConfig.applyConfig in P0a-4). Orchestration (background file
 * download, contrast computation, ThemeStore colors, day/night switch)
 * stays in the app; this class only owns WHICH keys receive WHICH values.
 */
class ThemeApplier(private val persistence: ThemePersistence) {

    /**
     * One-time migration: before the day/night key split these fields shared
     * the day key. On first launch copy day values to night keys so night
     * mode does not fall back to defaults.
     */
    fun migrateLegacyNightValues(markerKey: String = LEGACY_NIGHT_MIGRATED_KEY): Boolean {
        if (persistence.getBoolean(markerKey, false)) return false
        val all = persistence.all()
        ThemeKeys.legacyNightPairs.forEach { (dayKey, nightKey) ->
            if (!persistence.contains(nightKey)) {
                when (val value = all[dayKey]) {
                    is Int -> persistence.putInt(nightKey, value)
                    is Boolean -> persistence.putBoolean(nightKey, value)
                    is String -> persistence.putString(nightKey, value)
                    is Float -> persistence.putFloat(nightKey, value)
                    is Long -> persistence.putLong(nightKey, value)
                }
            }
        }
        persistence.putBoolean(markerKey, true)
        return true
    }

    fun writeMetrics(night: Boolean, cornerScale: Float?, layoutAlpha: Int?, dialogAlpha: Int?) {
        cornerScale?.let {
            persistence.putString(ThemeKeys.uiCornerScale(night), scaleToString(it.coerceIn(0f, 3f)))
        }
        layoutAlpha?.let {
            persistence.putInt(ThemeKeys.uiLayoutAlpha(night), it.coerceIn(0, 100))
        }
        dialogAlpha?.let {
            persistence.putInt(ThemeKeys.dialogAlpha(night), it.coerceIn(0, 100))
        }
    }

    fun writeCornerFollow(night: Boolean, searchFollow: Boolean?, replyFollow: Boolean?) {
        searchFollow?.let { persistence.putBoolean(ThemeKeys.uiCornerSearchFollow(night), it) }
        replyFollow?.let { persistence.putBoolean(ThemeKeys.uiCornerReplyFollow(night), it) }
    }

    fun writeFontScale(night: Boolean, fontScale: Int?) {
        fontScale?.let { persistence.putInt(ThemeKeys.fontScale(night), it.coerceIn(0, 16)) }
    }

    fun writeFontPaths(night: Boolean, uiFontPath: String?, titleFontPath: String?) {
        persistence.putString(ThemeKeys.uiFontPath(night), uiFontPath.orEmpty())
        persistence.putString(ThemeKeys.titleFontPath(night), titleFontPath.orEmpty())
    }

    /** Writes the final (contrast-sanitized) colors; computation stays in app. */
    fun writeFontColors(night: Boolean, uiFontColor: String, titleFontColor: String) {
        persistence.putString(ThemeKeys.uiFontColor(night), uiFontColor)
        persistence.putString(ThemeKeys.titleFontColor(night), titleFontColor)
    }

    /**
     * Extended surface colors; null value removes the key (falls back to defaults).
     */
    fun writeExtendedColors(
        night: Boolean,
        cardColor: String?,
        mutedColor: String?,
        searchFieldBackgroundColor: String?,
        tabBackgroundColor: String?,
        shelfColor: String?,
        cardShadow: Int?,
        cardBackgroundBlur: Float?,
        exploreGlassBlur: Int?,
    ) {
        putOrClear(ThemeKeys.themeCardColor(night), cardColor)
        putOrClear(ThemeKeys.themeMutedColor(night), mutedColor)
        putOrClear(ThemeKeys.themeSearchFieldBackgroundColor(night), searchFieldBackgroundColor)
        putOrClear(ThemeKeys.themeTabBackgroundColor(night), tabBackgroundColor)
        putOrClear(ThemeKeys.themeShelfColor(night), shelfColor)
        if (cardShadow != null) {
            persistence.putInt(ThemeKeys.themeCardShadow(night), cardShadow.coerceIn(0, 24))
        } else {
            persistence.remove(ThemeKeys.themeCardShadow(night))
        }
        if (cardBackgroundBlur != null) {
            persistence.putInt(
                ThemeKeys.themeCardBackgroundBlur(night),
                (cardBackgroundBlur * 10f).toInt().coerceIn(0, 250)
            )
        } else {
            persistence.remove(ThemeKeys.themeCardBackgroundBlur(night))
        }
        if (exploreGlassBlur != null) {
            persistence.putInt(ThemeKeys.themeExploreGlassBlur(night), exploreGlassBlur.coerceIn(0, 100))
        } else {
            persistence.remove(ThemeKeys.themeExploreGlassBlur(night))
        }
    }

    fun writeMainColors(
        night: Boolean,
        themeName: String,
        primary: Int,
        accent: Int,
        background: Int,
        bottomBackground: Int,
    ) {
        if (night) {
            persistence.putString(ThemeKeys.THEME_NAME_NIGHT, themeName)
            persistence.putInt(ThemeKeys.COLOR_PRIMARY_NIGHT, primary)
            persistence.putInt(ThemeKeys.COLOR_ACCENT_NIGHT, accent)
            persistence.putInt(ThemeKeys.COLOR_BACKGROUND_NIGHT, background)
            persistence.putInt(ThemeKeys.COLOR_BOTTOM_BACKGROUND_NIGHT, bottomBackground)
            persistence.putBoolean(ThemeKeys.TRANSPARENT_NAV_BAR_NIGHT, true)
        } else {
            persistence.putString(ThemeKeys.THEME_NAME, themeName)
            persistence.putInt(ThemeKeys.COLOR_PRIMARY, primary)
            persistence.putInt(ThemeKeys.COLOR_ACCENT, accent)
            persistence.putInt(ThemeKeys.COLOR_BACKGROUND, background)
            persistence.putInt(ThemeKeys.COLOR_BOTTOM_BACKGROUND, bottomBackground)
            persistence.putBoolean(ThemeKeys.TRANSPARENT_NAV_BAR, true)
        }
    }

    fun writeBackgrounds(
        night: Boolean,
        mainPath: String?,
        mainBlur: Int?,
        mainCrop: String?,
        bookInfoPath: String?,
        panelPath: String?,
        panelScaleType: String,
        panelBorderColor: String?,
        panelBorderAlpha: Int,
    ) {
        if (night) {
            persistence.putString(ThemeKeys.BG_IMAGE_NIGHT, mainPath)
            persistence.putInt(ThemeKeys.BG_IMAGE_NIGHT_BLURRING, mainBlur ?: 0)
            persistence.putString(ThemeKeys.BG_IMAGE_NIGHT_CROP, mainCrop.orEmpty())
            persistence.putString(ThemeKeys.BOOK_INFO_BG_IMAGE_NIGHT, bookInfoPath)
            persistence.putString(ThemeKeys.PANEL_BG_IMAGE_NIGHT, panelPath)
            persistence.putString(ThemeKeys.PANEL_BG_SCALE_TYPE_NIGHT, panelScaleType)
            persistence.putString(ThemeKeys.PANEL_BORDER_COLOR_NIGHT, panelBorderColor.orEmpty())
            persistence.putInt(ThemeKeys.PANEL_BORDER_ALPHA_NIGHT, panelBorderAlpha)
        } else {
            persistence.putString(ThemeKeys.BG_IMAGE, mainPath)
            persistence.putInt(ThemeKeys.BG_IMAGE_BLURRING, mainBlur ?: 0)
            persistence.putString(ThemeKeys.BG_IMAGE_CROP, mainCrop.orEmpty())
            persistence.putString(ThemeKeys.BOOK_INFO_BG_IMAGE, bookInfoPath)
            persistence.putString(ThemeKeys.PANEL_BG_IMAGE, panelPath)
            persistence.putString(ThemeKeys.PANEL_BG_SCALE_TYPE, panelScaleType)
            persistence.putString(ThemeKeys.PANEL_BORDER_COLOR, panelBorderColor.orEmpty())
            persistence.putInt(ThemeKeys.PANEL_BORDER_ALPHA, panelBorderAlpha)
        }
    }

    private fun putOrClear(key: String, value: String?) {
        val normalized = value?.takeIf { it.isNotBlank() }
        if (normalized == null) {
            persistence.remove(key)
        } else {
            persistence.putString(key, normalized)
        }
    }

    private fun scaleToString(value: Float): String =
        if (value == value.toInt().toFloat()) {
            value.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }

    companion object {
        const val LEGACY_NIGHT_MIGRATED_KEY = "themeNightExtMigrated"
    }
}
