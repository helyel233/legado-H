package io.legado.app.uikit.token

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

/**
 * Motion tokens (docs/ui-rewrite-plan.md 6.1 / 6.5.5).
 * Duration: 120/200/320ms only. Curve: FastOutSlowIn.
 */
object AppMotion {

    const val FAST: Int = 120
    const val NORMAL: Int = 200
    const val SLOW: Int = 320

    val Easing = FastOutSlowInEasing

    fun <T> fast(): TweenSpec<T> = tween(FAST, easing = Easing)
    fun <T> normal(): TweenSpec<T> = tween(NORMAL, easing = Easing)
    fun <T> slow(): TweenSpec<T> = tween(SLOW, easing = Easing)
}
