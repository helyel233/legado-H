package io.legado.app.uikit.facade

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import io.legado.app.uikit.theme.AppUiColors
import io.legado.app.uikit.token.AppRadius

/**
 * Single toast entry point (docs/ui-rewrite-plan.md 6.4).
 * Replaces ToastUtils.toastOnUi / toastOnUiLegacy and all Snackbars usage.
 * Renders a themed rounded card on the current surface color.
 *
 * Note: custom-view toasts are blocked only while the app is in background
 * on API 30+; foreground usage (the only supported case here) is fine.
 */
object AppToast {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    /** Cancel the previous toast so consecutive toasts do not queue up. */
    @Volatile
    private var current: Toast? = null

    /** Must be called once from Application.onCreate. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    @JvmOverloads
    fun show(message: CharSequence, long: Boolean = false) {
        val context = appContext ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showInternal(context, message, long)
        } else {
            mainHandler.post { showInternal(context, message, long) }
        }
    }

    @JvmStatic
    fun showLong(message: CharSequence) = show(message, long = true)

    private fun showInternal(context: Context, message: CharSequence, long: Boolean) {
        val density = context.resources.displayMetrics.density
        val radius = AppRadius.md.value * AppUiColors.radiusScale * density

        val card = FrameLayout(context)
        card.background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(AppUiColors.surface)
            setStroke(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, context.resources.displayMetrics).toInt(),
                AppUiColors.outline,
            )
        }
        val text = TextView(context).apply {
            this.text = message
            textSize = 14f
            setTextColor(AppUiColors.onSurface)
            setTypeface(typeface, Typeface.NORMAL)
            val pad = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                12f,
                context.resources.displayMetrics,
            ).toInt()
            setPadding(pad, pad, pad, pad)
        }
        card.addView(text)

        val toast = Toast(context)
        toast.duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        toast.view = card
        toast.setGravity(Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM, 0, (56 * density).toInt())
        current?.cancel()
        current = toast
        try {
            toast.show()
        } catch (ignored: Throwable) {
            // Custom view toast rejected (e.g. background restrictions): fall back silently.
            current = null
        }
    }
}
