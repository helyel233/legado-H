package io.legado.app.uikit.state

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * XML-side state container (docs/ui-rewrite-plan.md 6.4).
 * Wraps content and renders unified loading/empty/error views on demand.
 * Views are built in code so this module stays resource-free.
 */
class StateLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var stateView: View? = null

    fun showLoading(message: CharSequence? = null) {
        show(StateViewBindings.loadingView(context, message))
    }

    fun showEmpty(
        title: CharSequence,
        desc: CharSequence? = null,
        actionLabel: CharSequence? = null,
        onAction: (() -> Unit)? = null,
    ) {
        val bindings = StateViewBindings.create(context)
        bindings.title.text = title
        if (!desc.isNullOrEmpty()) {
            bindings.desc.text = desc
            bindings.desc.visibility = View.VISIBLE
        }
        bindings.setAction(actionLabel, filled = false, onAction = onAction)
        show(bindings.root)
    }

    fun showError(
        message: CharSequence,
        retryLabel: CharSequence? = null,
        onRetry: (() -> Unit)? = null,
    ) {
        val bindings = StateViewBindings.create(context)
        bindings.title.text = message
        bindings.setAction(retryLabel, filled = true, onAction = onRetry)
        show(bindings.root)
    }

    fun showContent() {
        stateView?.let { removeView(it) }
        stateView = null
    }

    private fun show(view: View) {
        showContent()
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        stateView = view
    }
}
