package io.legado.app.ui.main

import android.view.MotionEvent

/**
 * A2-2 step 1: sidebar swipe gesture state machine, extracted verbatim from
 * MainActivity as a pure-logic delegate (no View references). The activity
 * remains responsible for open/close/cancel side effects.
 */
class SideNavigationGestureDelegate(private val touchSlopPx: Int) {

    enum class Gesture { NONE, BLOCKED, OPEN_START, OPEN_END, CLOSE }

    var downX = 0f
        private set
    var downY = 0f
        private set
    var handled = false
        private set
    var allowed = false
        private set

    fun onDown(rawX: Float, rawY: Float, rootWidth: Int, edgeGuardPx: Int) {
        downX = rawX
        downY = rawY
        handled = false
        allowed = rawX > edgeGuardPx && rawX < rootWidth - edgeGuardPx
    }

    fun isCloseGesture(dx: Float, gravity: String): Boolean =
        if (gravity == "end") dx > 0f else dx < 0f

    fun recognizeMove(rawX: Float, rawY: Float, gravity: String, isOpen: Boolean): Gesture {
        if (!allowed) return Gesture.NONE
        if (handled) return Gesture.BLOCKED
        val dx = rawX - downX
        val dy = rawY - downY
        val absDx = abs(dx)
        val absDy = abs(dy)
        if (absDx < touchSlopPx * 3f || absDx < absDy * 1.35f) return Gesture.NONE
        return when {
            isOpen -> if (isCloseGesture(dx, gravity)) Gesture.CLOSE else Gesture.NONE
            dx != 0f -> if (dx < 0f) Gesture.OPEN_END else Gesture.OPEN_START
            else -> Gesture.NONE
        }
    }

    fun markHandled() {
        handled = true
    }

    fun onUpCancel(): Boolean {
        allowed = false
        if (handled) {
            handled = false
            return true
        }
        return false
    }

    private fun abs(v: Float): Float = if (v < 0f) -v else v
}
