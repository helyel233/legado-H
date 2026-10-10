package io.legado.app.ui.main

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.ui.main.ai.AiChatActivity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import kotlin.math.abs

/**
 * AI 悬浮球控制器（自 MainActivity 拆出）。
 * 主界面通过闭包注入样式/可见性/图标等依赖，控制器自身不感知宿主 Activity 类型。
 */
internal class MainAiFloatingBallController(
    private val context: Context,
    private val container: ViewGroup,
    private val touchSlopPx: Float,
    private val pulseInterpolator: Interpolator,
    private val shellDrawable: (oval: Boolean) -> GradientDrawable,
    private val iconSync: (ball: View) -> Unit,
    private val visibleProbe: () -> Boolean,
    private val bottomInset: () -> Int,
    private val openAiChat: () -> Unit
) {

    private var ball: FrameLayout? = null
    private var ballDragged = false
    private val attachRunnable = Runnable {
        ball?.let { place(it, animate = true, attached = true) }
    }

    fun update() {
        if (!visibleProbe()) {
            cancel()
            ball?.isVisible = false
            return
        }
        val target = ball ?: create().also {
            ball = it
            it.visibility = View.INVISIBLE
            container.addView(it)
        }
        iconSync(target)
        showWhenReady(target)
    }

    fun cancel() {
        ball?.removeCallbacks(attachRunnable)
    }

    fun resyncIcon() {
        ball?.let(iconSync)
    }

    private fun create(): FrameLayout {
        val size = context.resources.getDimensionPixelSize(R.dimen.main_ai_floating_ball_size)
        val iconPadding = context.resources.getDimensionPixelSize(R.dimen.main_ai_floating_ball_icon_padding)
        return FrameLayout(context).apply {
            elevation = context.resources.getDimension(R.dimen.main_search_button_elevation)
            background = shellDrawable(true)
            layoutParams = ConstraintLayout.LayoutParams(size, size)
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            })
            iconSync(this)
            setOnClickListener {
                if (!ballDragged) {
                    openAiChat()
                }
            }
            setOnTouchListener(TouchListener(this))
        }
    }

    private fun scheduleAttach() {
        ball?.removeCallbacks(attachRunnable)
        ball?.postDelayed(attachRunnable, 3000L)
    }

    private fun showWhenReady(target: View) {
        target.bringToFront()
        if (place(target, animate = false, attached = true)) {
            target.isVisible = true
            scheduleAttach()
            return
        }
        target.visibility = View.INVISIBLE
        container.doOnLayout {
            target.doOnLayout {
                if (ball === target && visibleProbe()) {
                    showWhenReady(target)
                }
            }
        }
    }

    private fun place(target: View, animate: Boolean, attached: Boolean): Boolean {
        val parentWidth = container.width
        val parentHeight = container.height
        if (parentWidth <= 0 || parentHeight <= 0 || target.width <= 0 || target.height <= 0) return false
        val side = context.getPrefInt(PreferKey.aiFloatingBallSide, 1).coerceIn(0, 1)
        val yPercent = context.getPrefInt(PreferKey.aiFloatingBallYPercent, 50).coerceIn(8, 92)
        val hiddenOffset = 0f
        val targetX = if (side == 0) -hiddenOffset else parentWidth - target.width + hiddenOffset
        val safeMargin = context.resources.getDimensionPixelSize(R.dimen.main_ai_floating_ball_safe_margin)
        val availableHeight = (parentHeight - bottomInset() - target.height).coerceAtLeast(1)
        val maxY = (parentHeight - bottomInset() - target.height - safeMargin)
            .coerceAtLeast(safeMargin)
        val targetY = (availableHeight * yPercent / 100f)
            .coerceIn(safeMargin.toFloat(), maxY.toFloat())
        if (animate) {
            target.animate()
                .x(targetX)
                .y(targetY)
                .setDuration(180L)
                .setInterpolator(pulseInterpolator)
                .start()
        } else {
            target.x = targetX
            target.y = targetY
        }
        return true
    }

    private inner class TouchListener(private val target: View) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var downX = 0f
        private var downY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.removeCallbacks(attachRunnable)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = target.x
                    downY = target.y
                    ballDragged = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!ballDragged && (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)) {
                        ballDragged = true
                    }
                    if (ballDragged) {
                        target.x = (downX + dx).coerceIn(0f, (container.width - target.width).toFloat())
                        val topLimit = 12.dpToPx().toFloat()
                        val bottomLimit = (container.height - bottomInset() - target.height - 12.dpToPx())
                            .coerceAtLeast(12.dpToPx())
                            .toFloat()
                        target.y = (downY + dy).coerceIn(topLimit, bottomLimit)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (ballDragged) {
                        val side = if (target.x + target.width / 2f < container.width / 2f) 0 else 1
                        val availableHeight = (container.height - bottomInset() - target.height).coerceAtLeast(1)
                        val yPercent = ((target.y / availableHeight) * 100).toInt()
                            .coerceIn(8, 92)
                        context.putPrefInt(PreferKey.aiFloatingBallSide, side)
                        context.putPrefInt(PreferKey.aiFloatingBallYPercent, yPercent)
                        place(target, animate = true, attached = false)
                        scheduleAttach()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        target.performClick()
                    }
                    return true
                }
            }
            return false
        }
    }
}
