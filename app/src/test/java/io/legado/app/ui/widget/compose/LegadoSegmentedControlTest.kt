package io.legado.app.ui.widget.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class LegadoSegmentedControlTest {

    private val palette = LegadoMiuixPalette(
        accent = Color(0xFF0A84FF),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFE9ECF0),
        primaryText = Color(0xFF1C1C1E),
        secondaryText = Color(0xFF6E6E73),
        danger = Color(0xFFFF3B30),
        onAccent = Color(0xFFFFFFFF),
        panelRadius = 16.dp,
        actionRadius = 12.dp
    )

    @Test
    fun resolveSegmentedControlColors_surfaceThumb_usesL2TrackAndL1Thumb() {
        val colors = resolveSegmentedControlColors(palette, SegmentedControlTone.SurfaceThumb)
        assertEquals(palette.surfaceVariant, colors.trackColor)
        assertEquals(palette.surface, colors.selectedThumbColor)
        assertEquals(palette.accent, colors.selectedTextColor)
        assertEquals(palette.secondaryText, colors.unselectedTextColor)
    }

    @Test
    fun resolveSegmentedControlColors_accentThumb_usesL2TrackAndAccentThumb() {
        val colors = resolveSegmentedControlColors(palette, SegmentedControlTone.AccentThumb)
        assertEquals(palette.surfaceVariant, colors.trackColor)
        assertEquals(palette.accent, colors.selectedThumbColor)
        assertEquals(palette.onAccent, colors.selectedTextColor)
        assertEquals(palette.secondaryText, colors.unselectedTextColor)
    }

    @Test
    fun resolveSegmentedInnerRadiusDp_clampsSmallAndNegativeValues() {
        assertEquals(9f, resolveSegmentedInnerRadiusDp(12f), 0.001f)
        assertEquals(4f, resolveSegmentedInnerRadiusDp(5f), 0.001f)
        assertEquals(4f, resolveSegmentedInnerRadiusDp(0f), 0.001f)
        assertEquals(4f, resolveSegmentedInnerRadiusDp(-6f), 0.001f)
    }

    @Test
    fun resolveSegmentedSelectedIndex_findsMatchingOptionOrMinusOne() {
        val options = listOf(
            SegmentedOption("0", "Follow System"),
            SegmentedOption("1", "Light"),
            SegmentedOption("2", "Dark")
        )
        assertEquals(1, resolveSegmentedSelectedIndex(options, "1"))
        assertEquals(-1, resolveSegmentedSelectedIndex(options, "unknown"))
    }
}
