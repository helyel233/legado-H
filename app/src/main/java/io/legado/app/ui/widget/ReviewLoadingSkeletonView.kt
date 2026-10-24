package io.legado.app.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import io.legado.app.help.config.AppConfig

/**
 * 段评弹窗内容加载期的占位骨架。
 *
 * 弹窗从「点下」到「WebView 首帧」之间有一段时间窗口，此时 WebView 是透明的，
 * 用户看到的是一个空壳：既没有内容，也没有任何「正在加载」的反馈，主观上像是
 * 卡住了。这里用几条与评论列表同构的灰条占据这段时间，内容出现后淡出。
 *
 * 之所以用自绘而不是往布局里塞子 View：骨架只在几百毫秒内可见，
 * 子 View 树会参与测量/布局并在弹窗打开动画期间产生额外开销，
 * 自绘只有一次 `onDraw`。
 */
class ReviewLoadingSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val pulseAnimator = ValueAnimator.ofFloat(MIN_PULSE, MAX_PULSE).apply {
        duration = 850L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }

    private var pulse = MAX_PULSE

    private val density by lazy { resources.displayMetrics.density }

    private fun dp(value: Float): Float = value * density

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
        // 纯装饰：不要让读屏软件把它当成一个空的内容区域
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 立即显示并开始呼吸；重复调用无副作用。 */
    fun show() {
        if (visibility == VISIBLE && pulseAnimator.isStarted) return
        animate().cancel()
        alpha = 1f
        visibility = VISIBLE
        if (!pulseAnimator.isStarted) pulseAnimator.start()
    }

    /** 内容已可见：淡出后彻底隐藏，避免挡住 WebView 的触摸。 */
    fun hide() {
        if (visibility != VISIBLE) return
        pulseAnimator.cancel()
        animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                visibility = GONE
                alpha = 1f
            }
            .start()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        animate().cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val widthPx = width.toFloat()
        val heightPx = height.toFloat()
        if (widthPx <= 0f || heightPx <= 0f) return
        paint.color = if (AppConfig.isNightTheme) {
            ColorUtils.setAlphaComponent(Color.WHITE, (BASE_ALPHA_NIGHT * pulse).toInt())
        } else {
            ColorUtils.setAlphaComponent(Color.BLACK, (BASE_ALPHA_DAY * pulse).toInt())
        }
        val pad = dp(16f)
        val avatar = dp(36f)
        val lineHeight = dp(11f)
        val lineGap = dp(8f)
        val avatarTextGap = dp(12f)
        val cardGap = dp(20f)
        val radius = lineHeight / 2f
        val bar = RectF()
        val textLeft = pad + avatar + avatarTextGap
        val textWidth = widthPx - textLeft - pad
        if (textWidth <= 0f) return
        var top = pad + dp(6f)
        var index = 0
        while (top + avatar <= heightPx - pad) {
            canvas.drawOval(
                RectF(pad, top, pad + avatar, top + avatar),
                paint
            )
            // 名字行与内容行宽度交替变化，避免整屏灰条完全对齐显得僵硬
            val nameRatio = if (index % 2 == 0) 0.44f else 0.56f
            val contentRatio = if (index % 2 == 0) 0.86f else 0.72f
            bar.set(textLeft, top + dp(4f), textLeft + textWidth * nameRatio, top + dp(4f) + lineHeight)
            canvas.drawRoundRect(bar, radius, radius, paint)
            bar.set(
                textLeft,
                top + dp(4f) + lineHeight + lineGap,
                textLeft + textWidth * contentRatio,
                top + dp(4f) + lineHeight * 2 + lineGap
            )
            canvas.drawRoundRect(bar, radius, radius, paint)
            top += avatar + cardGap
            index++
        }
        // 底部渐隐，暗示内容还会继续
        paint.color = if (AppConfig.isNightTheme) {
            ColorUtils.setAlphaComponent(Color.WHITE, (FADE_ALPHA_NIGHT * pulse).toInt())
        } else {
            ColorUtils.setAlphaComponent(Color.BLACK, (FADE_ALPHA_DAY * pulse).toInt())
        }
        canvas.drawRoundRect(
            RectF(pad, top, widthPx - pad, top + lineHeight),
            radius,
            radius,
            paint
        )
    }

    private companion object {
        const val MIN_PULSE = 0.42f
        const val MAX_PULSE = 1f
        const val BASE_ALPHA_DAY = 24
        const val BASE_ALPHA_NIGHT = 30
        const val FADE_ALPHA_DAY = 10
        const val FADE_ALPHA_NIGHT = 14
    }
}
