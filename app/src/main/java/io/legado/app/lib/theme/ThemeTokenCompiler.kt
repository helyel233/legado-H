package io.legado.app.lib.theme

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.glass.rememberGlassConfigSignature
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.explore.ExploreGlassBackdrop
import io.legado.app.ui.main.explore.resolveExploreGlassMode
import io.legado.app.ui.main.explore.resolveGlassStrokeColor
import io.legado.app.ui.widget.compose.rememberHasUsableBgImage
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString

/**
 * 运行时主题 Token 编译器。
 *
 * 缓存策略（R2 单一来源）：
 * - 直接复用 [themeUiPaletteCached] / [rememberThemeUiPalette] 背后的 [ThemeUiSignatureCache]，
 *   不并列第二套 SP 监听器或独立缓存。
 * - 纯函数 [compileRaw] 可脱离 Android 运行时执行，供 JVM 单测锁住日/夜/墨水屏/壁纸编译口径。
 */
object ThemeTokenCompiler {

    private const val ARGB_WHITE = 0xFFFFFFFF.toInt()
    private const val ARGB_BLACK = 0xFF000000.toInt()
    private const val ARGB_EINK_MUTED = 0xFFF2F2F2.toInt()
    private const val ARGB_EINK_SECONDARY = 0xFF444444.toInt()

    fun compileRaw(
        env: AppearanceEnv,
        primaryArgb: Int,
        accentArgb: Int,
        backgroundArgb: Int,
        bottomBarArgb: Int,
        cardArgb: Int,
        mutedArgb: Int,
        dividerArgb: Int,
        borderArgb: Int?,
        primaryTextArgb: Int,
        secondaryTextArgb: Int,
        titleTextArgb: Int,
        dangerArgb: Int,
        panelRadiusDp: Float,
        actionRadiusDp: Float,
        panelRadiusPx: Float,
        glassMode: String,
        glassLevel: Int,
        glassStrokeColorArgb: Int?,
        glassSignature: String,
        paletteSignature: String
    ): ResolvedThemeTokens {
        val resolvedGlassMode = if (env.isEInk) GlassMaterialPreset.SOLID else glassMode
        val resolvedGlassLevel = if (env.isEInk) 0 else glassLevel.coerceIn(0, 100)
        val wallpaperVisible = env.hasUsableWallpaper && !env.isEInk

        val resolvedPrimary = if (env.isEInk) ARGB_WHITE else primaryArgb
        val resolvedAccent = if (env.isEInk) ARGB_BLACK else accentArgb
        val resolvedBg = if (env.isEInk) ARGB_WHITE else backgroundArgb
        val resolvedCard = if (env.isEInk) ARGB_WHITE else cardArgb
        val resolvedMuted = if (env.isEInk) ARGB_EINK_MUTED else mutedArgb
        val resolvedPrimaryText = if (env.isEInk) ARGB_BLACK else primaryTextArgb
        val resolvedTitleText = if (env.isEInk) ARGB_BLACK else titleTextArgb
        val resolvedSecondaryText = if (env.isEInk) ARGB_EINK_SECONDARY else secondaryTextArgb

        // 明度判定复用 SurfaceHierarchyDerivation.isLightArgb（R2 单一真相源，同包无需 import）
        val onPrimary = if (SurfaceHierarchyDerivation.isLightArgb(resolvedPrimary)) ARGB_BLACK else ARGB_WHITE
        val onAccent = if (SurfaceHierarchyDerivation.isLightArgb(resolvedAccent)) ARGB_BLACK else ARGB_WHITE

        val colors = ThemeTokenColors(
            primaryArgb = resolvedPrimary,
            onPrimaryArgb = onPrimary,
            accentArgb = resolvedAccent,
            onAccentArgb = onAccent,
            backgroundArgb = resolvedBg,
            bottomBarArgb = if (env.isEInk) ARGB_WHITE else bottomBarArgb,
            bottomBarTextArgb = resolvedPrimaryText,
            cardArgb = resolvedCard,
            mutedArgb = resolvedMuted,
            dividerArgb = dividerArgb,
            borderArgb = if (env.isEInk) ARGB_BLACK else borderArgb,
            primaryTextArgb = resolvedPrimaryText,
            secondaryTextArgb = resolvedSecondaryText,
            titleTextArgb = resolvedTitleText,
            dangerArgb = dangerArgb
        )
        val corners = ThemeTokenCorners(
            panelRadiusDp = panelRadiusDp,
            actionRadiusDp = actionRadiusDp,
            panelRadiusPx = panelRadiusPx
        )
        val glassRef = GlassTokenRef(
            mode = resolvedGlassMode,
            level = resolvedGlassLevel,
            strokeColorArgb = if (env.isEInk) null else glassStrokeColorArgb,
            signature = glassSignature
        )
        val fullSignature = buildString {
            append(paletteSignature)
            append("|env=").append(env.isNight).append(',').append(env.isEInk)
            append(',').append(env.hasUsableWallpaper).append(',').append(env.hardwareBlurSupported)
            append("|glass=").append(glassRef.mode).append(':').append(glassRef.level)
            append(':').append(glassRef.strokeColorArgb ?: "null").append(':').append(glassSignature)
        }
        return ResolvedThemeTokens(
            env = env,
            colors = colors,
            corners = corners,
            wallpaperVisible = wallpaperVisible,
            glassRef = glassRef,
            paletteSignature = paletteSignature,
            signature = fullSignature
        )
    }

    fun compileFromContext(
        context: Context,
        palette: ThemeUiPalette = context.themeUiPaletteCached(),
        hasUsableWallpaper: Boolean = ThemeConfig.hasUsableBgImage(context),
        glassSignature: String = ExploreGlassBackdrop.configSignature(context)
    ): ResolvedThemeTokens {
        val env = AppearanceEnv(
            isNight = AppConfig.isNightTheme,
            isEInk = AppConfig.isEInkMode,
            hasUsableWallpaper = hasUsableWallpaper,
            hardwareBlurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        )
        val storedMode = context.getPrefString(PreferKey.exploreGlassEffectMode, "").orEmpty()
        val legacyEnabled = context.getPrefBoolean(PreferKey.exploreGlassEnabled, true)
        val mode = resolveExploreGlassMode(storedMode, legacyEnabled)
        val frostedLevel = context.getPrefInt(PreferKey.frostedGlassLevel, AppConfig.frostedGlassLevel)
        val liquidLevel = context.getPrefInt(PreferKey.liquidGlassLevel, AppConfig.liquidGlassLevel)
        val level = GlassMaterialPreset.levelFor(mode, frostedLevel, liquidLevel).coerceIn(0, 100)
        val strokeRaw = context.getPrefString(PreferKey.glassStrokeColor, "").orEmpty()
        val strokeColor = resolveGlassStrokeColor(strokeRaw)

        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val panelRadiusPx = UiCorner.panelRadius(context)
        val actionRadiusPx = UiCorner.actionRadius(context)

        val bottomBar = if (palette.hasCustomCardColor || palette.hasCustomMutedColor) {
            palette.mutedColor
        } else {
            context.bottomBackground
        }

        return compileRaw(
            env = env,
            primaryArgb = context.primaryColor,
            accentArgb = context.accentColor,
            backgroundArgb = context.backgroundColor,
            bottomBarArgb = bottomBar,
            cardArgb = palette.cardColor,
            mutedArgb = palette.mutedColor,
            dividerArgb = palette.dividerColor,
            borderArgb = UiCorner.panelBorderColor(context),
            primaryTextArgb = context.primaryTextColor,
            secondaryTextArgb = context.secondaryTextColor,
            titleTextArgb = context.titleTextColor,
            dangerArgb = ContextCompat.getColor(context, R.color.md_red_500),
            panelRadiusDp = panelRadiusPx / density,
            actionRadiusDp = actionRadiusPx / density,
            panelRadiusPx = panelRadiusPx,
            glassMode = mode,
            glassLevel = level,
            glassStrokeColorArgb = strokeColor,
            glassSignature = glassSignature,
            paletteSignature = palette.signature
        )
    }
}

@Composable
fun rememberResolvedThemeTokens(): ResolvedThemeTokens {
    val context = LocalContext.current
    val palette = rememberThemeUiPalette()
    val hasUsableWallpaper = rememberHasUsableBgImage()
    val glassSignature = rememberGlassConfigSignature()
    return remember(context, palette, hasUsableWallpaper, glassSignature) {
        ThemeTokenCompiler.compileFromContext(
            context = context,
            palette = palette,
            hasUsableWallpaper = hasUsableWallpaper,
            glassSignature = glassSignature
        )
    }
}
