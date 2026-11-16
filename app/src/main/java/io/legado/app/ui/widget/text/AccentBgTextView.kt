package io.legado.app.ui.widget.text

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import io.legado.app.R
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor

class AccentBgTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    private var radius = 0

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.AccentBgTextView)
        radius = (typedArray.getDimensionPixelOffset(R.styleable.AccentBgTextView_radius, radius) *
            UiCorner.scale()).toInt()
        typedArray.recycle()
        if (!isInEditMode) {
            typeface = context.uiTypeface()
        }
        upBackground()
    }

    fun setRadius(radius: Int) {
        this.radius = radius.dpToPx()
        upBackground()
    }

    /**
     * 按当前主题 accent 重建自绘背景与文字色。
     *
     * 还原入口：宿主页面（书籍详情页的「阅读」按钮）在玻璃档会把本控件的自绘背景换成
     * 半透明 accent 玻璃面并重算文字色，判否回退时必须把**背景与文字色一并**还原——
     * 只换回背景会留下玻璃档的文字色。与 [setRadius] 一样只是 `upBackground()` 的公开
     * 包装，不改变任何既有观感。
     */
    fun restoreAccentBackground() {
        upBackground()
    }

    private fun upBackground() {
        val accentColor = if (isInEditMode) {
            context.getCompatColor(R.color.accent)
        } else {
            ThemeStore.accentColor(context)
        }
        background = Selector.shapeBuild()
            .setCornerRadius(radius)
            .setDefaultBgColor(accentColor)
            .setPressedBgColor(ColorUtils.darkenColor(accentColor))
            .create()
        setTextColor(
            if (ColorUtils.isColorLight(accentColor)) {
                Color.BLACK
            } else {
                Color.WHITE
            }
        )
    }
}
