package io.legado.app.theme.palette

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

/**
 * A1-1 seed palette engine (docs/ui-rewrite-plan-v4.md 2.1).
 *
 * Input: one seed color + a generation strategy + day/night.
 * Output: the full semantic color role set (~40 roles, day & night),
 * derived with the official Material 3 tonal palette algorithm via
 * MaterialKolor (pure Kotlin port of material-color-utilities).
 *
 * Pure functions only: no Android framework, no persistence, unit-testable.
 * Contrast is checked with WCAG relative luminance (docs/ui-rewrite-plan-v4.md 2.2).
 */
enum class PaletteStrategy {
    /** Material 3 default (TonalSpot). */
    TONAL,

    /** Tonal + pure black background/surface for OLED screens. */
    AMOLED,

    /** Reduced-chroma scheme (low saturation). */
    MUTED,

    /** Chroma 0, grayscale — used by the built-in e-ink theme. */
    MONO,
}

/**
 * Semantic color roles. The single source of truth the whole App reads from;
 * skin overrides (theme.json `override`) and local tweaks (delta) key on these
 * names (lowercase snake, e.g. `color.on_background`).
 */
enum class PaletteRole {
    PRIMARY, ON_PRIMARY, PRIMARY_CONTAINER, ON_PRIMARY_CONTAINER,
    SECONDARY, ON_SECONDARY, SECONDARY_CONTAINER, ON_SECONDARY_CONTAINER,
    TERTIARY, ON_TERTIARY, TERTIARY_CONTAINER, ON_TERTIARY_CONTAINER,
    ACCENT, ON_ACCENT,
    BACKGROUND, ON_BACKGROUND,
    SURFACE, ON_SURFACE, SURFACE_VARIANT, ON_SURFACE_VARIANT,
    SURFACE_DIM, SURFACE_BRIGHT,
    SURFACE_CONTAINER, SURFACE_CONTAINER_LOW, SURFACE_CONTAINER_LOWEST,
    SURFACE_CONTAINER_HIGH, SURFACE_CONTAINER_HIGHEST,
    OUTLINE, OUTLINE_VARIANT,
    INVERSE_SURFACE, INVERSE_ON_SURFACE, INVERSE_PRIMARY,
    ERROR, ON_ERROR, ERROR_CONTAINER, ON_ERROR_CONTAINER,
    SCRIM, SHADOW,
}

object SeedPalette {

    /**
     * Derive the full role set from one seed.
     *
     * @param seed ARGB int, e.g. 0xFF5B6ABF
     */
    fun generate(
        seed: Int,
        strategy: PaletteStrategy = PaletteStrategy.TONAL,
        dark: Boolean = false,
    ): Map<PaletteRole, Int> {
        val scheme = dynamicColorScheme(
            seedColor = Color(seed),
            isDark = dark,
            isAmoled = strategy == PaletteStrategy.AMOLED,
            style = when (strategy) {
                PaletteStrategy.TONAL, PaletteStrategy.AMOLED -> PaletteStyle.TonalSpot
                PaletteStrategy.MUTED -> PaletteStyle.Neutral
                PaletteStrategy.MONO -> PaletteStyle.Monochrome
            },
        )
        return buildMap {
            put(PaletteRole.PRIMARY, scheme.primary.toArgb())
            put(PaletteRole.ON_PRIMARY, scheme.onPrimary.toArgb())
            put(PaletteRole.PRIMARY_CONTAINER, scheme.primaryContainer.toArgb())
            put(PaletteRole.ON_PRIMARY_CONTAINER, scheme.onPrimaryContainer.toArgb())
            put(PaletteRole.SECONDARY, scheme.secondary.toArgb())
            put(PaletteRole.ON_SECONDARY, scheme.onSecondary.toArgb())
            put(PaletteRole.SECONDARY_CONTAINER, scheme.secondaryContainer.toArgb())
            put(PaletteRole.ON_SECONDARY_CONTAINER, scheme.onSecondaryContainer.toArgb())
            put(PaletteRole.TERTIARY, scheme.tertiary.toArgb())
            put(PaletteRole.ON_TERTIARY, scheme.onTertiary.toArgb())
            put(PaletteRole.TERTIARY_CONTAINER, scheme.tertiaryContainer.toArgb())
            put(PaletteRole.ON_TERTIARY_CONTAINER, scheme.onTertiaryContainer.toArgb())
            // Accent is the highlight role used for badges/progress; derived from tertiary.
            put(PaletteRole.ACCENT, scheme.tertiary.toArgb())
            put(PaletteRole.ON_ACCENT, scheme.onTertiary.toArgb())
            put(PaletteRole.BACKGROUND, scheme.background.toArgb())
            put(PaletteRole.ON_BACKGROUND, scheme.onBackground.toArgb())
            put(PaletteRole.SURFACE, scheme.surface.toArgb())
            put(PaletteRole.ON_SURFACE, scheme.onSurface.toArgb())
            put(PaletteRole.SURFACE_VARIANT, scheme.surfaceVariant.toArgb())
            put(PaletteRole.ON_SURFACE_VARIANT, scheme.onSurfaceVariant.toArgb())
            put(PaletteRole.SURFACE_DIM, scheme.surfaceDim.toArgb())
            put(PaletteRole.SURFACE_BRIGHT, scheme.surfaceBright.toArgb())
            put(PaletteRole.SURFACE_CONTAINER, scheme.surfaceContainer.toArgb())
            put(PaletteRole.SURFACE_CONTAINER_LOW, scheme.surfaceContainerLow.toArgb())
            put(PaletteRole.SURFACE_CONTAINER_LOWEST, scheme.surfaceContainerLowest.toArgb())
            put(PaletteRole.SURFACE_CONTAINER_HIGH, scheme.surfaceContainerHigh.toArgb())
            put(PaletteRole.SURFACE_CONTAINER_HIGHEST, scheme.surfaceContainerHighest.toArgb())
            put(PaletteRole.OUTLINE, scheme.outline.toArgb())
            put(PaletteRole.OUTLINE_VARIANT, scheme.outlineVariant.toArgb())
            put(PaletteRole.INVERSE_SURFACE, scheme.inverseSurface.toArgb())
            put(PaletteRole.INVERSE_ON_SURFACE, scheme.inverseOnSurface.toArgb())
            put(PaletteRole.INVERSE_PRIMARY, scheme.inversePrimary.toArgb())
            put(PaletteRole.ERROR, scheme.error.toArgb())
            put(PaletteRole.ON_ERROR, scheme.onError.toArgb())
            put(PaletteRole.ERROR_CONTAINER, scheme.errorContainer.toArgb())
            put(PaletteRole.ON_ERROR_CONTAINER, scheme.onErrorContainer.toArgb())
            put(PaletteRole.SCRIM, scheme.scrim.toArgb())
            put(PaletteRole.SHADOW, scheme.scrim.toArgb())
        }
    }

    /** WCAG relative-contrast ratio between two ARGB colors (1.0 .. 21.0). */
    fun contrastRatio(fg: Int, bg: Int): Double {
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(bg)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = (argb shr shift) and 0xFF
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
