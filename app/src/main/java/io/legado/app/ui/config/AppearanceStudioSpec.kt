package io.legado.app.ui.config

import kotlin.math.roundToInt

/**
 * Pure JVM helpers backing the Appearance Studio workbench sliders and live preview.
 */
internal data class StudioPreviewRadii(
    val panelRadiusDp: Float,
    val actionRadiusDp: Float,
    val innerRadiusDp: Float,
    val isSquare: Boolean
)

internal fun resolveStudioPreviewRadii(cornerScale: Float): StudioPreviewRadii {
    val safeScale = cornerScale.coerceIn(0f, 3f)
    val panelDp = 10f * safeScale
    val actionDp = 7f * safeScale
    val innerDp = if (actionDp > 0f) (actionDp - 2f).coerceAtLeast(3f) else 0f
    return StudioPreviewRadii(
        panelRadiusDp = panelDp,
        actionRadiusDp = actionDp,
        innerRadiusDp = innerDp,
        isSquare = panelDp <= 0f
    )
}

internal fun cornerScaleToSliderStep(cornerScale: Float): Int =
    (cornerScale.coerceIn(0f, 3f) * 10f).roundToInt().coerceIn(0, 30)

internal fun sliderStepToCornerScale(step: Int): Float =
    (step.coerceIn(0, 30) / 10f).coerceIn(0f, 3f)

internal fun resolveStudioPreviewAlphaFactor(layoutAlphaPercent: Int): Float =
    (layoutAlphaPercent.coerceIn(0, 100) / 100f)

internal fun isStudioGlassSliderEnabled(isEInkMode: Boolean, glassMode: String): Boolean {
    if (isEInkMode) return false
    return glassMode != "solid" && glassMode != "clear"
}
