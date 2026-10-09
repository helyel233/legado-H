package io.legado.app.uikit.state

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.legado.app.uikit.theme.AppUiColors
import io.legado.app.uikit.token.AppRadius
import io.legado.app.uikit.token.AppSize
import io.legado.app.uikit.token.AppSpacing

/**
 * Code-built empty/error state view with mutable icon/title/action,
 * shared by [StateLayout] and legacy state containers.
 * Single source of the unified empty/loading/error visual (6.5.6).
 */
class StateViewBindings private constructor(
    val root: LinearLayout,
    val icon: ImageView,
    val title: TextView,
    val desc: TextView,
    private val actionContainer: LinearLayout,
) {

    /** Set the action button; hides it when label or callback is null. */
    fun setAction(label: CharSequence?, filled: Boolean, onAction: (() -> Unit)?) {
        actionContainer.removeAllViews()
        if (label.isNullOrEmpty() || onAction == null) return
        val context = actionContainer.context
        val density = context.resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            cornerRadius = AppRadius.sm.value * AppUiColors.radiusScale * density
            if (filled) {
                setColor(AppUiColors.primary)
            } else {
                setStroke(dp(context, AppSize.divider.value.toInt()), AppUiColors.outline)
                setColor(Color.TRANSPARENT)
            }
        }
        val button = TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(if (filled) AppUiColors.onPrimary else AppUiColors.primary)
            background = bg
            gravity = Gravity.CENTER
            minWidth = dp(context, 88)
            minHeight = dp(context, AppSize.buttonCompact.value.toInt())
            setPadding(dp(context, 16), 0, dp(context, 16), 0)
            setOnClickListener { onAction() }
        }
        actionContainer.addView(button)
    }

    companion object {

        fun create(context: Context): StateViewBindings {
            val iconView = ImageView(context).apply {
                visibility = View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val titleView = TextView(context).apply {
                textSize = 18f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(AppUiColors.onSurface)
                gravity = Gravity.CENTER
            }
            val descView = TextView(context).apply {
                textSize = 14f
                setTextColor(AppUiColors.muted)
                gravity = Gravity.CENTER
                visibility = View.GONE
            }
            val actionBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(context, AppSpacing.s24.value.toInt()), 0, dp(context, AppSpacing.s24.value.toInt()), 0)
                addView(iconView)
                addView(titleView)
                addView(descView)
                addView(actionBox)
            }
            (iconView.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(context, AppSpacing.s8.value.toInt())
            (titleView.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(context, AppSpacing.s8.value.toInt())
            (descView.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(context, AppSpacing.s16.value.toInt())
            (actionBox.layoutParams as LinearLayout.LayoutParams).topMargin = dp(context, AppSpacing.s16.value.toInt())
            return StateViewBindings(root, iconView, titleView, descView, actionBox)
        }

        fun loadingView(context: Context, message: CharSequence? = null): View {
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            val progress = ProgressBar(context).apply {
                isIndeterminate = true
            }
            box.addView(progress)
            if (!message.isNullOrEmpty()) {
                val text = TextView(context).apply {
                    textSize = 14f
                    setTextColor(AppUiColors.muted)
                    gravity = Gravity.CENTER
                    text = message
                }
                (text.layoutParams as LinearLayout.LayoutParams).topMargin = dp(context, AppSpacing.s12.value.toInt())
                box.addView(text)
            }
            return box
        }

        private fun dp(context: Context, value: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()
    }
}
