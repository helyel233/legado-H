package io.legado.app.ui.book.read.page.delegate

import android.os.SystemClock
import android.view.animation.Interpolator

/**
 * Sub-pixel floating-point scroller for 120Hz+ page-turn animations.
 * Keeps pure-JVM curve injection so unit tests run without Android stubs.
 */
class FloatPageScroller(
    private var progressCurve: (Float) -> Float = { it },
    private val timeSourceMs: () -> Long = { SystemClock.uptimeMillis() }
) {

    constructor(
        interpolator: Interpolator,
        timeSourceMs: () -> Long = { SystemClock.uptimeMillis() }
    ) : this({ interpolator.getInterpolation(it) }, timeSourceMs)

    var startX: Float = 0f
        private set
    var startY: Float = 0f
        private set
    var finalX: Float = 0f
        private set
    var finalY: Float = 0f
        private set
    var currX: Float = 0f
        private set
    var currY: Float = 0f
        private set
    var duration: Int = 0
        private set
    var isFinished: Boolean = true
        private set

    private var dx: Float = 0f
    private var dy: Float = 0f
    private var startTimeMs: Long = 0L

    fun startScroll(
        startX: Float,
        startY: Float,
        dx: Float,
        dy: Float,
        duration: Int
    ) {
        this.startX = startX
        this.startY = startY
        this.dx = dx
        this.dy = dy
        this.finalX = startX + dx
        this.finalY = startY + dy
        this.duration = duration.coerceAtLeast(0)
        if ((dx == 0f && dy == 0f) || this.duration == 0) {
            this.currX = finalX
            this.currY = finalY
            this.isFinished = true
            return
        }
        this.currX = startX
        this.currY = startY
        this.startTimeMs = timeSourceMs()
        this.isFinished = false
    }

    fun computeScrollOffset(): Boolean {
        if (isFinished) return false
        val elapsed = (timeSourceMs() - startTimeMs).coerceAtLeast(0L)
        if (elapsed < duration) {
            val progress = progressCurve(elapsed.toFloat() / duration)
            currX = startX + dx * progress
            currY = startY + dy * progress
        } else {
            currX = finalX
            currY = finalY
            isFinished = true
        }
        return true
    }

    fun abortAnimation() {
        currX = finalX
        currY = finalY
        isFinished = true
    }

    fun setInterpolator(interpolator: Interpolator) {
        progressCurve = { interpolator.getInterpolation(it) }
    }

    fun setProgressCurve(curve: (Float) -> Float) {
        progressCurve = curve
    }
}
