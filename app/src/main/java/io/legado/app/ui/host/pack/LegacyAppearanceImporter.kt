package io.legado.app.ui.host.pack

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.theme.apply.ThemeKeys
import io.legado.app.help.config.MainLayoutPresetConfig
import io.legado.app.theme.pack.ThemeFonts
import io.legado.app.theme.pack.ThemeImages
import io.legado.app.theme.pack.ThemePackageSpec
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import java.io.File

/**
 * P5-2 旧版本设置导入器（**翻译**，不是兼容）。
 *
 * 改版期不读旧存档、不做双写；到 P5 才提供这一条一次性通道：
 * 把旧体系的外观设置（颜色 / 背景图 / 圆角 / 透明度 / 底栏形态 / 字体）
 * 翻译成一套 V5 主题包 + 界面包，用户预览后决定是否落库。
 *
 * 落库后旧格式即不再存在；不导入 = 全新开始。
 */
object LegacyAppearanceImporter {

    data class Proposal(
        val theme: ThemePackageSpec,
        val layout: io.legado.app.uikit.v5.pack.LayoutPackageSpecV2,
        /** 预览清单：项目 → 翻译后的值 */
        val summary: List<Pair<String, String>>,
    )

    private const val IMPORTED_THEME_ID = "legacy_theme"
    private const val IMPORTED_LAYOUT_ID = "legacy_layout"

    /** 读取旧外观设置并翻译（不落库）。 */
    fun read(context: Context): Proposal {
        val summary = mutableListOf<Pair<String, String>>()

        // ---------- 颜色：日间 primary 作种子色 ----------
        val dayPrimary = context.getPrefInt(PreferKey.cPrimary)
        val nightBackground = context.getPrefInt(PreferKey.cNBackground, 0)
        val seed = hex(dayPrimary)
        val darkStrategy = if (isNearBlack(nightBackground)) "amoled" else "tonal"
        summary += "主题色（白天）" to "#$seed"
        summary += "夜间策略" to if (darkStrategy == "amoled") "纯黑（AMOLED）" else "色调协调"

        // ---------- 背景图 ----------
        val bg = existingPath(context.getPrefString(PreferKey.bgImage))
        val bgN = existingPath(context.getPrefString(PreferKey.bgImageN))
        val bookBg = existingPath(context.getPrefString(PreferKey.bookInfoBgImage))
        val panelBg = existingPath(context.getPrefString(PreferKey.panelBgImage))
        val images = ThemeImages(
            background = bg, backgroundNight = bgN,
            bookInfo = bookBg, bookInfoNight = null,
            panel = panelBg, panelNight = null,
        )
        summary += "背景图" to if (bg != null) "已携带" else "无"
        if (bookBg != null) summary += "详情页背景" to "已携带"
        if (panelBg != null) summary += "面板背景" to "已携带"

        // ---------- 字体 ----------
        val uiFont = existingPath(context.getPrefString(ThemeKeys.UI_FONT_PATH))
        val titleFont = existingPath(context.getPrefString(ThemeKeys.TITLE_FONT_PATH))
        val fonts = ThemeFonts(ui = uiFont, title = titleFont)
        if (uiFont != null) summary += "界面字体" to "已携带"
        if (titleFont != null) summary += "标题字体" to "已携带"

        val theme = ThemePackageSpec(
            id = IMPORTED_THEME_ID,
            name = "我的主题包（来自旧版）",
            author = "",
            seed = seed.ifBlank { "#5B6ABF" },
            darkStrategy = darkStrategy,
            fonts = fonts,
            images = images,
        )

        // ---------- 界面：圆角 / 透明度 / 底栏形态 ----------
        val cornerScale = context.getPrefString(ThemeKeys.UI_CORNER_SCALE)?.toFloatOrNull() ?: 1f
        val layoutAlpha = context.getPrefString(ThemeKeys.UI_LAYOUT_ALPHA)?.toFloatOrNull()
        val dialogAlpha = context.getPrefString(ThemeKeys.DIALOG_ALPHA)?.toFloatOrNull()

        val preset = MainLayoutPresetConfig.currentPreset()
        val bottomMode = context.getPrefInt(PreferKey.bottomBarLayoutMode, 0)
        val navPosition = when {
            preset == MainLayoutPresetConfig.PRESET_SIDEBAR -> "side"
            bottomMode == 1 -> "float" // 旧值 1 = 悬浮胶囊
            else -> "bottom"
        }
        val glassEnabled = (layoutAlpha ?: 1f) < 0.99f || (dialogAlpha ?: 1f) < 0.99f
        val glassAlpha = (dialogAlpha ?: layoutAlpha ?: 0.42f).coerceIn(0.05f, 1f)

        summary += "形状缩放" to "%.2f×".format(cornerScale)
        summary += "玻璃" to if (glassEnabled) "开 · ${(glassAlpha * 100).toInt()}%" else "关（实色卡）"
        summary += "导航形态" to when (navPosition) {
            "float" -> "悬浮胶囊"; "side" -> "侧栏"; else -> "底栏"
        }

        val layout = io.legado.app.uikit.v5.pack.LayoutPackageSpecV2(
            id = IMPORTED_LAYOUT_ID,
            name = "我的界面包（来自旧版）",
            author = "",
            navPosition = navPosition,
            shapeFamily = "soft", // 旧体系只有圆角大小一个维度，取最耐看的柔和族
            shapeScale = cornerScale.coerceIn(0f, 2f),
            glassEnabled = glassEnabled,
            glassAlpha = glassAlpha,
            glassBlur = io.legado.app.uikit.v5.token.GlassSpec.DEFAULT_BLUR,
            glassStroke = true,
            glassNoise = io.legado.app.uikit.v5.token.GlassSpec.DEFAULT_NOISE,
            glassVibrancy = io.legado.app.uikit.v5.token.GlassSpec.DEFAULT_VIBRANCY,
            densityFactor = 1f,
            motionLevel = io.legado.app.uikit.v5.token.MotionLevel.STANDARD.id,
        )

        return Proposal(theme, layout, summary)
    }

    /** 落库并应用（用户确认后调用）。 */
    fun commit(context: Context, proposal: Proposal) {
        V5PackageStore.init(context)
        V5PackageStore.updateTheme(proposal.theme)
        V5PackageStore.updateLayout(proposal.layout)
        V5PackageStore.applyTheme(proposal.theme.id)
        V5PackageStore.applyLayout(proposal.layout.id)
    }

    /** 是否有值得导入的旧外观设置（用于入口显隐）。 */
    fun hasLegacyAppearance(context: Context): Boolean {
        return context.getPrefString(PreferKey.bgImage)?.isNotBlank() == true ||
            context.getPrefString(ThemeKeys.UI_CORNER_SCALE)?.isNotBlank() == true ||
            context.getPrefString(ThemeKeys.UI_LAYOUT_ALPHA)?.isNotBlank() == true ||
            context.getPrefString(ThemeKeys.UI_FONT_PATH)?.isNotBlank() == true ||
            MainLayoutPresetConfig.currentPreset() != MainLayoutPresetConfig.PRESET_DEFAULT
    }

    // ---------------------------------------------------------------- 工具

    private fun hex(color: Int): String {
        if (color == 0) return ""
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return "%02X%02X%02X".format(r, g, b)
    }

    private fun isNearBlack(color: Int): Boolean {
        if (color == 0) return false
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return r + g + b < 24
    }

    /** 仅在文件确实存在时才搬运路径（旧设置可能留着已删文件的路径）。 */
    private fun existingPath(path: String?): String? =
        path?.takeIf { it.isNotBlank() && File(it).exists() }
}
