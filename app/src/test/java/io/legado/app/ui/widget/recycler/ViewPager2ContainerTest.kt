package io.legado.app.ui.widget.recycler

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ViewPager2ContainerTest {

    private lateinit var context: Context

    private class RecordingParentLayout(context: Context) : FrameLayout(context) {
        var lastDisallowIntercept: Boolean? = null

        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
            lastDisallowIntercept = disallowIntercept
        }
    }

    private fun invokeOnFinishInflate(container: ViewPager2Container) {
        ViewPager2Container::class.java.getDeclaredMethod("onFinishInflate").apply {
            isAccessible = true
        }.invoke(container)
    }

    private class DummyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    private class DummyPagerAdapter(private val count: Int) :
        RecyclerView.Adapter<DummyViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DummyViewHolder {
            val view = View(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return DummyViewHolder(view)
        }

        override fun onBindViewHolder(holder: DummyViewHolder, position: Int) = Unit

        override fun getItemCount(): Int = count
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun createContainerWithPager(
        pageCount: Int,
        currentPage: Int
    ): Pair<RecordingParentLayout, ViewPager2Container> {
        val parent = RecordingParentLayout(context)
        val container = ViewPager2Container(context)
        val pager = ViewPager2(context).apply {
            adapter = DummyPagerAdapter(pageCount)
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
        }
        container.addView(
            pager,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        invokeOnFinishInflate(container)
        container.disallowParentInterceptDownEvent(false)
        parent.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        parent.measure(
            MeasureSpec.makeMeasureSpec(400, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(800, MeasureSpec.EXACTLY)
        )
        parent.layout(0, 0, 400, 800)
        pager.setCurrentItem(currentPage, false)
        parent.lastDisallowIntercept = null
        return parent to container
    }

    private fun interceptEvent(
        container: ViewPager2Container,
        action: Int,
        x: Float,
        y: Float
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        return try {
            container.onInterceptTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    @Test
    fun `missing ViewPager2 child degrades gracefully without throwing`() {
        val parent = RecordingParentLayout(context)
        val container = ViewPager2Container(context)
        container.addView(View(context))
        invokeOnFinishInflate(container)
        parent.addView(container)

        interceptEvent(container, MotionEvent.ACTION_DOWN, 100f, 100f)
        interceptEvent(container, MotionEvent.ACTION_MOVE, 160f, 105f)
        interceptEvent(container, MotionEvent.ACTION_UP, 160f, 105f)

        assertNull(parent.lastDisallowIntercept)
    }

    @Test
    fun `middle page horizontal drag keeps disallowIntercept true`() {
        val (parent, container) = createContainerWithPager(pageCount = 3, currentPage = 1)

        interceptEvent(container, MotionEvent.ACTION_DOWN, 200f, 300f)
        assertEquals(true, parent.lastDisallowIntercept)

        interceptEvent(container, MotionEvent.ACTION_MOVE, 140f, 305f)
        assertEquals(true, parent.lastDisallowIntercept)

        interceptEvent(container, MotionEvent.ACTION_MOVE, 260f, 305f)
        assertEquals(true, parent.lastDisallowIntercept)
    }

    @Test
    fun `first page right swipe and last page left swipe release disallowIntercept to parent`() {
        val (firstParent, firstContainer) = createContainerWithPager(pageCount = 3, currentPage = 0)
        interceptEvent(firstContainer, MotionEvent.ACTION_DOWN, 100f, 300f)
        assertEquals(true, firstParent.lastDisallowIntercept)

        // Swiping right at page 0 hands control back to parent
        interceptEvent(firstContainer, MotionEvent.ACTION_MOVE, 180f, 305f)
        assertEquals(false, firstParent.lastDisallowIntercept)

        // Swiping left at page 0 stays with inner ViewPager2
        interceptEvent(firstContainer, MotionEvent.ACTION_MOVE, 40f, 305f)
        assertEquals(true, firstParent.lastDisallowIntercept)

        val (lastParent, lastContainer) = createContainerWithPager(pageCount = 3, currentPage = 2)
        interceptEvent(lastContainer, MotionEvent.ACTION_DOWN, 200f, 300f)
        assertEquals(true, lastParent.lastDisallowIntercept)

        // Swiping left at last page hands control back to parent
        interceptEvent(lastContainer, MotionEvent.ACTION_MOVE, 120f, 305f)
        assertEquals(false, lastParent.lastDisallowIntercept)

        // Swiping right at last page stays with inner ViewPager2
        interceptEvent(lastContainer, MotionEvent.ACTION_MOVE, 260f, 305f)
        assertEquals(true, lastParent.lastDisallowIntercept)
    }

    @Test
    fun `vertical scroll releases disallowIntercept to parent`() {
        val (parent, container) = createContainerWithPager(pageCount = 3, currentPage = 1)

        interceptEvent(container, MotionEvent.ACTION_DOWN, 200f, 300f)
        assertEquals(true, parent.lastDisallowIntercept)

        interceptEvent(container, MotionEvent.ACTION_MOVE, 205f, 390f)
        assertEquals(false, parent.lastDisallowIntercept)
    }
}
