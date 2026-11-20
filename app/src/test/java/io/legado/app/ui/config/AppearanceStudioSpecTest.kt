package io.legado.app.ui.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceStudioSpecTest {

    @Test
    fun `resolveStudioPreviewRadii scales panel action and inner radii cleanly`() {
        val defaultRadii = resolveStudioPreviewRadii(1.0f)
        assertEquals(10f, defaultRadii.panelRadiusDp, 0.001f)
        assertEquals(7f, defaultRadii.actionRadiusDp, 0.001f)
        assertEquals(5f, defaultRadii.innerRadiusDp, 0.001f)
        assertFalse(defaultRadii.isSquare)

        val clampedMax = resolveStudioPreviewRadii(5.0f)
        assertEquals(30f, clampedMax.panelRadiusDp, 0.001f)
        assertEquals(21f, clampedMax.actionRadiusDp, 0.001f)
        assertEquals(19f, clampedMax.innerRadiusDp, 0.001f)
        assertFalse(clampedMax.isSquare)

        val square = resolveStudioPreviewRadii(0f)
        assertEquals(0f, square.panelRadiusDp, 0.001f)
        assertEquals(0f, square.actionRadiusDp, 0.001f)
        assertEquals(0f, square.innerRadiusDp, 0.001f)
        assertTrue(square.isSquare)
    }

    @Test
    fun `corner scale slider step round trips and clamps to 0 to 3x`() {
        assertEquals(15, cornerScaleToSliderStep(1.5f))
        assertEquals(1.5f, sliderStepToCornerScale(15), 0.001f)
        assertEquals(0, cornerScaleToSliderStep(-1f))
        assertEquals(30, cornerScaleToSliderStep(4.2f))
        assertEquals(3.0f, sliderStepToCornerScale(99), 0.001f)
    }

    @Test
    fun `preview alpha clamps 0 to 100 percent`() {
        assertEquals(0f, resolveStudioPreviewAlphaFactor(-10), 0.001f)
        assertEquals(0.85f, resolveStudioPreviewAlphaFactor(85), 0.001f)
        assertEquals(1f, resolveStudioPreviewAlphaFactor(150), 0.001f)
    }

    @Test
    fun `glass slider disabled in eInk and solid or clear modes`() {
        assertFalse(isStudioGlassSliderEnabled(isEInkMode = true, glassMode = "glass"))
        assertFalse(isStudioGlassSliderEnabled(isEInkMode = true, glassMode = "frosted"))
        assertFalse(isStudioGlassSliderEnabled(isEInkMode = false, glassMode = "solid"))
        assertFalse(isStudioGlassSliderEnabled(isEInkMode = false, glassMode = "clear"))
        assertTrue(isStudioGlassSliderEnabled(isEInkMode = false, glassMode = "glass"))
        assertTrue(isStudioGlassSliderEnabled(isEInkMode = false, glassMode = "frosted"))
    }
}
