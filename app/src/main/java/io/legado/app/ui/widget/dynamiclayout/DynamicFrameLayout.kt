package io.legado.app.ui.widget.dynamiclayout

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import io.legado.app.R
import io.legado.app.uikit.state.StateViewBindings

/**
 * Legacy state container kept for its [ViewSwitcher] API (used by search and
 * explore pages). Rendering is delegated to the unified uikit state views
 * (docs/ui-rewrite-plan.md 6.5.6); the old ViewStub layouts were removed.
 */
@Suppress("unused")
class DynamicFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), ViewSwitcher {

    private var stateBindings: StateViewBindings? = null
    private var progressView: View? = null

    private var contentView: View? = null

    private var errorIcon: Drawable? = null
    private var emptyIcon: Drawable? = null

    private var errorActionDescription: CharSequence? = null
    private var emptyActionDescription: CharSequence? = null
    private var emptyDescription: CharSequence? = null

    private var errorAction: Action? = null
    private var emptyAction: Action? = null

    private var changeListener: OnVisibilityChangeListener? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.DynamicFrameLayout)
        errorIcon = a.getDrawable(R.styleable.DynamicFrameLayout_errorSrc)
        emptyIcon = a.getDrawable(R.styleable.DynamicFrameLayout_emptySrc)

        emptyActionDescription = a.getText(R.styleable.DynamicFrameLayout_emptyActionDescription)
        emptyDescription = a.getText(R.styleable.DynamicFrameLayout_emptyDescription)

        errorActionDescription = a.getText(R.styleable.DynamicFrameLayout_errorActionDescription)
        if (errorActionDescription == null) {
            errorActionDescription = context.getString(R.string.dynamic_click_retry)
        }
        a.recycle()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        contentView = getChildAt(0)
    }

    override fun showErrorView(message: CharSequence) {
        ensureErrorView()

        stateBindings?.let {
            it.root.visibility = View.VISIBLE
            it.icon.setImageDrawable(errorIcon)
            it.icon.visibility = if (errorIcon == null) View.GONE else View.VISIBLE
            it.title.text = message
            it.setAction(
                errorActionDescription,
                filled = true,
            ) { errorAction?.onAction(this@DynamicFrameLayout) }
        }
        setViewVisible(contentView, false)
        setViewVisible(progressView, false)

        dispatchVisibilityChanged(ViewSwitcher.SHOW_ERROR_VIEW)
    }

    override fun showErrorView(messageId: Int) {
        showErrorView(resources.getText(messageId))
    }

    override fun showEmptyView() {
        ensureErrorView()

        stateBindings?.let {
            it.root.visibility = View.VISIBLE
            it.icon.setImageDrawable(emptyIcon)
            it.icon.visibility = if (emptyIcon == null) View.GONE else View.VISIBLE
            it.title.text = emptyDescription
            it.setAction(
                errorActionDescription,
                filled = true,
            ) { emptyAction?.onAction(this@DynamicFrameLayout) }
        }
        setViewVisible(contentView, false)
        setViewVisible(progressView, false)

        dispatchVisibilityChanged(ViewSwitcher.SHOW_EMPTY_VIEW)
    }

    override fun showProgressView() {
        ensureProgressView()

        setViewVisible(stateBindings?.root, false)
        setViewVisible(contentView, false)
        setViewVisible(progressView, true)

        dispatchVisibilityChanged(ViewSwitcher.SHOW_PROGRESS_VIEW)
    }

    override fun showContentView() {
        setViewVisible(stateBindings?.root, false)
        setViewVisible(contentView, true)
        setViewVisible(progressView, false)

        dispatchVisibilityChanged(ViewSwitcher.SHOW_CONTENT_VIEW)
    }

    fun setOnVisibilityChangeListener(listener: OnVisibilityChangeListener) {
        changeListener = listener
    }

    fun setErrorAction(action: Action) {
        errorAction = action
    }

    fun setEmptyAction(action: Action) {
        emptyAction = action
    }

    private fun setViewVisible(view: View?, visible: Boolean) {
        view?.let {
            it.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun ensureErrorView() {
        if (stateBindings == null) {
            stateBindings = StateViewBindings.create(context)
            addView(
                stateBindings!!.root,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
        }
    }

    private fun ensureProgressView() {
        if (progressView == null) {
            progressView = StateViewBindings.loadingView(
                context,
                context.getString(R.string.dynamic_loading)
            )
            addView(
                progressView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
        }
    }

    private fun dispatchVisibilityChanged(@ViewSwitcher.Visibility visibility: Int) {
        changeListener?.onVisibilityChanged(visibility)
    }

    interface Action {
        fun onAction(switcher: ViewSwitcher)
    }


    interface OnVisibilityChangeListener {

        fun onVisibilityChanged(@ViewSwitcher.Visibility visibility: Int)
    }
}
