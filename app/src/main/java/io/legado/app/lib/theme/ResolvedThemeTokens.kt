package io.legado.app.lib.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.help.config.pkg.UiScope
import io.legado.app.ui.main.GlassMaterialPreset

/**
 * 材质与毛玻璃轻量引用（遵守 R2：唯一参数表在 GlassMaterialPreset）。
 *
 * 这里只记录当前生效档位、强度与描边色，渲染层需要模糊半径 / 着色 / 折射 / 壳层 alpha 时
 * 必须回查 `GlassMaterialPreset`，严禁在此处或调用方复制第二份参数表。
 */
@Immutable
data class GlassTokenRef(
    val mode: String,
    val level: Int,
    val strokeColorArgb: Int?,
    val signature: String
)

@Immutable
data class ThemeTokenColors(
    val primaryArgb: Int,
    val onPrimaryArgb: Int,
    val accentArgb: Int,
    val onAccentArgb: Int,
    val backgroundArgb: Int,
    val bottomBarArgb: Int,
    val bottomBarTextArgb: Int,
    val cardArgb: Int,
    val mutedArgb: Int,
    val dividerArgb: Int,
    val borderArgb: Int?,
    val primaryTextArgb: Int,
    val secondaryTextArgb: Int,
    val titleTextArgb: Int,
    val dangerArgb: Int
)

@Immutable
data class ThemeTokenCorners(
    val panelRadiusDp: Float,
    val actionRadiusDp: Float,
    val panelRadiusPx: Float
)

@Immutable
data class ResolvedScopeTokens(
    val scope: UiScope,
    val materialMode: String,
    val panelRadiusDp: Float,
    val actionRadiusDp: Float,
    val panelRadiusPx: Float,
    val alphaPercent: Int,
    val shadowElevationDp: Int,
    val blurRadiusDp: Float,
    val borderAlphaPercent: Int,
    val isCustomOverride: Boolean
) {
    val alphaFraction: Float
        get() = alphaPercent.coerceIn(0, 100) / 100f

    val borderAlphaFraction: Float
        get() = borderAlphaPercent.coerceIn(0, 100) / 100f
}

@Immutable
data class ResolvedLayoutTokens(
    val densityScale: Float,
    val pageHorizontalDp: Float,
    val detailHorizontalDp: Float,
    val sectionTitleOutsideHorizontalDp: Float,
    val sectionGapDp: Float,
    val cardGapDp: Float,
    val gridGapDp: Float
) {
    companion object {
        fun fromDensityScale(scale: Float): ResolvedLayoutTokens {
            val clamped = scale.coerceIn(0.8f, 1.3f)
            return ResolvedLayoutTokens(
                densityScale = clamped,
                pageHorizontalDp = 12f * clamped,
                detailHorizontalDp = 16f * clamped,
                sectionTitleOutsideHorizontalDp = 4f * clamped,
                sectionGapDp = 12f * clamped,
                cardGapDp = 8f * clamped,
                gridGapDp = 10f * clamped
            )
        }
    }
}

/**
 * 全 App Compose 与 View 桥接层共享的不可变视觉 Token 快照。
 */
@Immutable
data class ResolvedThemeTokens(
    val env: AppearanceEnv,
    val colors: ThemeTokenColors,
    val corners: ThemeTokenCorners,
    val wallpaperVisible: Boolean,
    val glassRef: GlassTokenRef,
    val paletteSignature: String,
    val signature: String,
    val layout: ResolvedLayoutTokens = ResolvedLayoutTokens.fromDensityScale(1f),
    val scopeOverrides: Map<UiScope, ResolvedScopeTokens> = emptyMap(),
    val globalAlphaPercent: Int = 100,
    val dialogAlphaPercent: Int = 100,
    val globalShadowDp: Int = 0,
    val globalBlurRadiusDp: Float = 0f,
    val borderAlphaPercent: Int = 100,
    val legacyPipelineActive: Boolean = false
) {
    val topBar: ResolvedScopeTokens
        get() = resolveScope(UiScope.TOP_BAR)

    val bottomBar: ResolvedScopeTokens
        get() = resolveScope(UiScope.BOTTOM_BAR)

    val bookshelfCard: ResolvedScopeTokens
        get() = resolveScope(UiScope.BOOKSHELF_CARD)

    val dialog: ResolvedScopeTokens
        get() = resolveScope(UiScope.DIALOG)

    val animationDurationScale: Float
        get() = if (env.isEInk) 0f else 1f

    fun transitionDurationMs(normalMs: Int): Int =
        if (env.isEInk) 0 else normalMs.coerceAtLeast(0)

    fun resolveScope(scope: UiScope): ResolvedScopeTokens {
        if (env.isEInk) {
            return ResolvedScopeTokens(
                scope = scope,
                materialMode = GlassMaterialPreset.SOLID,
                panelRadiusDp = corners.panelRadiusDp.coerceAtLeast(0f),
                actionRadiusDp = corners.actionRadiusDp.coerceAtLeast(0f),
                panelRadiusPx = corners.panelRadiusPx.coerceAtLeast(0f),
                alphaPercent = 100,
                shadowElevationDp = 0,
                blurRadiusDp = 0f,
                borderAlphaPercent = 100,
                isCustomOverride = false
            )
        }
        if (!legacyPipelineActive) {
            scopeOverrides[scope]?.let { return it }
        }
        val baseAlpha = if (scope == UiScope.DIALOG) {
            dialogAlphaPercent.coerceIn(0, 100)
        } else {
            globalAlphaPercent.coerceIn(0, 100)
        }
        return ResolvedScopeTokens(
            scope = scope,
            materialMode = glassRef.mode,
            panelRadiusDp = corners.panelRadiusDp.coerceAtLeast(0f),
            actionRadiusDp = corners.actionRadiusDp.coerceAtLeast(0f),
            panelRadiusPx = corners.panelRadiusPx.coerceAtLeast(0f),
            alphaPercent = baseAlpha,
            shadowElevationDp = globalShadowDp.coerceIn(0, 24),
            blurRadiusDp = globalBlurRadiusDp.coerceIn(0f, 30f),
            borderAlphaPercent = borderAlphaPercent.coerceIn(0, 100),
            isCustomOverride = false
        )
    }

    fun toMaterialColorScheme(): ColorScheme {
        val primary = Color(colors.primaryArgb)
        val onPrimary = Color(colors.onPrimaryArgb)
        val accent = Color(colors.accentArgb)
        val onAccent = Color(colors.onAccentArgb)
        val background = Color(colors.backgroundArgb)
        val surface = Color(colors.cardArgb)
        val surfaceVariant = Color(colors.mutedArgb)
        val onBackground = Color(colors.primaryTextArgb)
        val onSurface = Color(colors.primaryTextArgb)
        val onSurfaceVariant = Color(colors.secondaryTextArgb)
        val outline = Color(colors.borderArgb ?: colors.dividerArgb)
        val outlineVariant = Color(colors.dividerArgb)
        val error = Color(colors.dangerArgb)

        return if (env.isNight) {
            darkColorScheme(
                primary = accent,
                onPrimary = onAccent,
                primaryContainer = primary,
                onPrimaryContainer = onPrimary,
                secondary = accent,
                onSecondary = onAccent,
                secondaryContainer = surfaceVariant,
                onSecondaryContainer = onSurface,
                tertiary = accent,
                onTertiary = onAccent,
                background = background,
                onBackground = onBackground,
                surface = surface,
                onSurface = onSurface,
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = onSurfaceVariant,
                outline = outline,
                outlineVariant = outlineVariant,
                error = error,
                onError = Color.White
            )
        } else {
            lightColorScheme(
                primary = accent,
                onPrimary = onAccent,
                primaryContainer = primary,
                onPrimaryContainer = onPrimary,
                secondary = accent,
                onSecondary = onAccent,
                secondaryContainer = surfaceVariant,
                onSecondaryContainer = onSurface,
                tertiary = accent,
                onTertiary = onAccent,
                background = background,
                onBackground = onBackground,
                surface = surface,
                onSurface = onSurface,
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = onSurfaceVariant,
                outline = outline,
                outlineVariant = outlineVariant,
                error = error,
                onError = Color.White
            )
        }
    }

    fun toMaterialShapes(): Shapes {
        val actionDp: Dp = corners.actionRadiusDp.coerceAtLeast(0f).dp
        val panelDp: Dp = corners.panelRadiusDp.coerceAtLeast(0f).dp
        return Shapes(
            extraSmall = RoundedCornerShape((actionDp * 0.5f).coerceAtLeast(2.dp)),
            small = RoundedCornerShape(actionDp),
            medium = RoundedCornerShape(panelDp),
            large = RoundedCornerShape(panelDp),
            extraLarge = RoundedCornerShape(panelDp)
        )
    }
}
