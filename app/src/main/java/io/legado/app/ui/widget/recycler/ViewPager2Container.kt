package io.legado.app.ui.widget.recycler

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.RelativeLayout
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

class ViewPager2Container @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RelativeLayout(context, attrs, defStyleAttr) {

    private var mViewPager2: ViewPager2? = null
    private var disallowParentInterceptDownEvent = true
    private var startX = 0
    private var startY = 0

    override fun onFinishInflate() {
        super.onFinishInflate()
        mViewPager2 = findViewPager2()
    }

    override fun onViewAdded(child: android.view.View?) {
        super.onViewAdded(child)
        if (mViewPager2 == null && child is ViewPager2) {
            mViewPager2 = child
        }
    }

    override fun onViewRemoved(child: android.view.View?) {
        super.onViewRemoved(child)
        if (child === mViewPager2) {
            mViewPager2 = findViewPager2()
        }
    }

    private fun findViewPager2(): ViewPager2? {
        for (i in 0 until childCount) {
            val childView = getChildAt(i)
            if (childView is ViewPager2) {
                return childView
            }
        }
        return null
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val pager = mViewPager2 ?: findViewPager2()?.also { mViewPager2 = it }
            ?: return super.onInterceptTouchEvent(ev)
        val itemCount = pager.adapter?.itemCount ?: 0
        if (!pager.isUserInputEnabled || itemCount <= 1) {
            return super.onInterceptTouchEvent(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x.toInt()
                startY = ev.y.toInt()
                parent?.requestDisallowInterceptTouchEvent(!disallowParentInterceptDownEvent)
            }
            MotionEvent.ACTION_MOVE -> {
                val endX = ev.x.toInt()
                val endY = ev.y.toInt()
                val disX = abs(endX - startX)
                val disY = abs(endY - startY)
                if (pager.orientation == ViewPager2.ORIENTATION_VERTICAL) {
                    onVerticalActionMove(pager, endY, disX, disY)
                } else if (pager.orientation == ViewPager2.ORIENTATION_HORIZONTAL) {
                    onHorizontalActionMove(pager, endX, disX, disY)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(
                false
            )
        }
        return super.onInterceptTouchEvent(ev)
    }

    private fun onHorizontalActionMove(pager: ViewPager2, endX: Int, disX: Int, disY: Int) {
        val itemCount = pager.adapter?.itemCount ?: return
        if (disX > disY) {
            val currentItem = pager.currentItem
            if (currentItem == 0 && endX - startX > 0) {
                parent?.requestDisallowInterceptTouchEvent(false)
            } else {
                parent?.requestDisallowInterceptTouchEvent(
                    currentItem != itemCount - 1
                            || endX - startX >= 0
                )
            }
        } else if (disY > disX) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
    }

    private fun onVerticalActionMove(pager: ViewPager2, endY: Int, disX: Int, disY: Int) {
        val itemCount = pager.adapter?.itemCount ?: return
        val currentItem = pager.currentItem
        if (disY > disX) {
            if (currentItem == 0 && endY - startY > 0) {
                parent?.requestDisallowInterceptTouchEvent(false)
            } else {
                parent?.requestDisallowInterceptTouchEvent(
                    currentItem != itemCount - 1
                            || endY - startY >= 0
                )
            }
        } else if (disX > disY) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
    }

    /**
     * 设置是否允许在当前View的{@link MotionEvent#ACTION_DOWN}事件中禁止父View对事件的拦截，该方法
     * 用于解决CoordinatorLayout+CollapsingToolbarLayout在嵌套ViewPager2Container时引起的滑动冲突问题。
     *
     * 设置是否允许在ViewPager2Container的{@link MotionEvent#ACTION_DOWN}事件中禁止父View对事件的拦截，该方法
     * 用于解决CoordinatorLayout+CollapsingToolbarLayout在嵌套ViewPager2Container时引起的滑动冲突问题。
     *
     * @param disallowParentInterceptDownEvent 是否允许ViewPager2Container在{@link MotionEvent#ACTION_DOWN}事件中禁止父View拦截事件，默认值为false
     *   true 不允许ViewPager2Container在{@link MotionEvent#ACTION_DOWN}时间中禁止父View的时间拦截，
     *   设置disallowIntercept为true可以解决CoordinatorLayout+CollapsingToolbarLayout的滑动冲突
     *   false 允许ViewPager2Container在{@link MotionEvent#ACTION_DOWN}时间中禁止父View的时间拦截，
     */
    fun disallowParentInterceptDownEvent(disallowParentInterceptDownEvent: Boolean) {
        this.disallowParentInterceptDownEvent = disallowParentInterceptDownEvent
    }
}