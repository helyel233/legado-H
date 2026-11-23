package io.legado.app.ui.book.read.page.delegate

/**
 * Pure-JVM gate controlling when idle page-turn snapshot prewarming may run.
 * Only RenderNode/Picture-backed horizontal delegates in non-E-Ink, idle state qualify.
 */
object PageTurnPrewarmPolicy {

    fun canPrewarm(
        optimizeRender: Boolean,
        prewarmEnabled: Boolean,
        isEInkMode: Boolean,
        isScroll: Boolean,
        supportsSafePrewarm: Boolean,
        isAnimating: Boolean,
    ): Boolean {
        return optimizeRender &&
            prewarmEnabled &&
            !isEInkMode &&
            !isScroll &&
            supportsSafePrewarm &&
            !isAnimating
    }
}
