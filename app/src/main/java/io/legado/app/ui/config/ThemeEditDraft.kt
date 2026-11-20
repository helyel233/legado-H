package io.legado.app.ui.config

import com.google.gson.Gson
import io.legado.app.help.config.ThemeConfig
import java.util.Locale

enum class ThemeEditFontTarget {
    UI,
    TITLE
}

/**
 * 主题编辑弹窗的纯状态草稿机（覆盖原 ThemeManageActivity 全部 27 个 pending/editing 字段 + 基本色板与 UI 折叠状态），
 * 支持进程重建 / 旋转经 `onSaveInstanceState` JSON 往返恢复，且零 Android UI 依赖，可在纯 JVM 上单测。
 */
data class ThemeEditDraft(
    val themeName: String = "",
    val isNightTheme: Boolean = false,
    val editingConfigNight: Boolean = false,
    val editingEntryDirName: String? = null,
    val editingEntryNight: Boolean? = null,
    val nameEditable: Boolean = true,
    val canDelete: Boolean = false,
    val activeGroupIndex: Int = 0,
    val advancedColorsExpanded: Boolean = false,
    val primaryColorHex: String = "#FFFFFF",
    val accentColorHex: String = "#0A84FF",
    val backgroundColorHex: String = "#F6F7F9",
    val bottomBackgroundHex: String = "#FFFFFF",
    val blur: Int = 0,
    val mainBackgroundPath: String? = null,
    val mainBackgroundCrop: String? = null,
    val bookInfoBackgroundPath: String? = null,
    val panelBackgroundPath: String? = null,
    val panelBackgroundScaleType: String = "crop",
    val panelBorderColor: String? = null,
    val panelBorderAlpha: Int = 100,
    val uiCornerScale: Float = 1f,
    val uiLayoutAlpha: Int = 100,
    val dialogAlpha: Int = 100,
    val cardColor: String? = null,
    val mutedColor: String? = null,
    val searchFieldBackgroundColor: String? = null,
    val tabBackgroundColor: String? = null,
    val shelfColor: String? = null,
    val cardShadow: Int? = null,
    val cardBackgroundBlur: Float? = null,
    val fontScale: Int = 0,
    val uiCornerSearchFollow: Boolean = false,
    val uiCornerReplyFollow: Boolean = false,
    val uiFontPath: String? = null,
    val titleFontPath: String? = null,
    val uiFontColor: String? = null,
    val titleFontColor: String? = null,
    val fontTarget: ThemeEditFontTarget = ThemeEditFontTarget.UI
) {
    fun overriddenAdvancedColorCount(): Int {
        return listOf(
            cardColor,
            mutedColor,
            searchFieldBackgroundColor,
            tabBackgroundColor,
            shelfColor
        ).count { !it.isNullOrBlank() }
    }

    fun sanitized(): ThemeEditDraft {
        return copy(
            activeGroupIndex = activeGroupIndex.coerceIn(0, 3),
            blur = blur.coerceIn(0, 25),
            panelBorderAlpha = panelBorderAlpha.coerceIn(0, 100),
            uiCornerScale = uiCornerScale.coerceIn(0f, 3f),
            uiLayoutAlpha = uiLayoutAlpha.coerceIn(0, 100),
            dialogAlpha = dialogAlpha.coerceIn(0, 100),
            cardShadow = cardShadow?.coerceIn(0, 24),
            cardBackgroundBlur = cardBackgroundBlur?.coerceIn(0f, 25f),
            fontScale = if (fontScale == 0) 0 else fontScale.coerceIn(8, 16)
        )
    }

    fun toThemeConfig(
        baseConfig: ThemeConfig.Config,
        fallbackName: String,
        fallbackPrimaryHex: String,
        fallbackAccentHex: String,
        fallbackBackgroundHex: String,
        fallbackBottomHex: String
    ): ThemeConfig.Config {
        val safe = sanitized()
        val resolvedName = safe.themeName.trim().ifBlank { fallbackName }
        return baseConfig.copy(
            themeName = resolvedName,
            isNightTheme = safe.isNightTheme,
            primaryColor = normalizeHex6(safe.primaryColorHex) ?: fallbackPrimaryHex,
            accentColor = normalizeHex6(safe.accentColorHex) ?: fallbackAccentHex,
            backgroundColor = normalizeHex6(safe.backgroundColorHex) ?: fallbackBackgroundHex,
            bottomBackground = normalizeHex6(safe.bottomBackgroundHex) ?: fallbackBottomHex,
            transparentNavBar = true,
            backgroundImgPath = safe.mainBackgroundPath,
            backgroundImgBlur = safe.blur,
            backgroundImgCrop = normalizeCropSpec(safe.mainBackgroundCrop),
            bookInfoBackgroundImgPath = safe.bookInfoBackgroundPath,
            panelBackgroundImgPath = safe.panelBackgroundPath,
            panelBackgroundScaleType = safe.panelBackgroundScaleType,
            panelBorderColor = normalizeHex6(safe.panelBorderColor),
            panelBorderAlpha = safe.panelBorderAlpha,
            uiCornerScale = safe.uiCornerScale,
            uiLayoutAlpha = safe.uiLayoutAlpha,
            dialogAlpha = safe.dialogAlpha,
            cardColor = normalizeHex6(safe.cardColor),
            mutedColor = normalizeHex6(safe.mutedColor),
            searchFieldBackgroundColor = normalizeHex6(safe.searchFieldBackgroundColor),
            tabBackgroundColor = normalizeHex6(safe.tabBackgroundColor),
            shelfColor = normalizeHex6(safe.shelfColor),
            cardShadow = safe.cardShadow,
            cardBackgroundBlur = safe.cardBackgroundBlur,
            uiCornerSearchFollow = safe.uiCornerSearchFollow,
            uiCornerReplyFollow = safe.uiCornerReplyFollow,
            fontScale = safe.fontScale,
            uiFontPath = safe.uiFontPath,
            titleFontPath = safe.titleFontPath,
            uiFontColor = normalizeHex6(safe.uiFontColor),
            titleFontColor = normalizeHex6(safe.titleFontColor)
        )
    }

    fun toJson(): String = gson.toJson(this)

    companion object {
        private val gson by lazy { Gson() }

        fun fromJson(json: String?): ThemeEditDraft? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                gson.fromJson(json, ThemeEditDraft::class.java)?.sanitized()
            }.getOrNull()
        }

        fun formatHex6(argb: Int): String {
            return String.format(Locale.US, "#%06X", 0xFFFFFF and argb)
        }

        fun normalizeHex6(value: String?): String? {
            val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val clean = raw
                .removePrefix("#")
                .removePrefix("0x")
                .removePrefix("0X")
            if (clean.isEmpty() || !clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                return null
            }
            val rgbHex = when (clean.length) {
                3 -> clean.map { "$it$it" }.joinToString("")
                4 -> clean.substring(1).map { "$it$it" }.joinToString("")
                6 -> clean
                8 -> clean.substring(2)
                else -> return null
            }
            return "#${rgbHex.uppercase(Locale.ROOT)}"
        }

        fun normalizeCropSpec(value: String?): String? {
            val parts = value
                ?.split(',', '|', ';')
                ?.mapNotNull { it.trim().toFloatOrNull()?.coerceIn(0f, 1f) }
                ?: return null
            if (parts.size != 4) return null
            val (left, top, right, bottom) = parts
            if (right <= left || bottom <= top) return null
            return parts.joinToString(",") { crop ->
                String.format(Locale.US, "%.6f", crop).trimEnd('0').trimEnd('.')
            }
        }
    }
}
