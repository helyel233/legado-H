package io.legado.app.ui.glass

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Looper
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.constant.PreferKey
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.explore.GLASS_POSITION_TRACKING_TAG_KEY
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.putPrefString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

/**
 * T1c `GlassPositionTracker` 宿主协调器与幂等绑定回归测试：
 *
 * 1. 同一个 [RecyclerView] 内的 N 个玻璃子项只注册 **1** 个共享 `OnScrollListener`
 *    （而不是 N 个重复监听），滚动一帧会同步驱动全部 N 个子项 drawable 刷新偏移；
 * 2. 对同一子项重复调用 `GlassSurface.rowBackground`（模拟 `onBindViewHolder` / `convert`），
 *    直接复用既有 drawable 与 tracker 绑定，不累积、不拆装监听；
 * 3. 嵌套 [RecyclerView]（如首页横向列表嵌在纵向列表里）两侧各只注册 1 个共享监听，
 *    外层或内层滚动都能驱动最内层子项重采样；
 * 4. 全部子项解绑后，共享协调器从 [RecyclerView] 彻底卸载 `OnScrollListener`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class GlassPositionTrackerCoordinatorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
        context.putPrefString(PreferKey.exploreGlassEffectMode, GlassMaterialPreset.FROSTED)
    }

    @After
    fun tearDown() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        context.defaultSharedPreferences.edit()
            .remove(PreferKey.exploreGlassEffectMode)
            .remove(PreferKey.exploreGlassEnabled)
            .apply()
    }

    @Test
    fun `attaching N items in a RecyclerView shares one scroll listener and updates all drawables on scroll`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val recyclerView = SpyRecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
        }
        val rows = mutableListOf<SpyRowView>()
        val itemCount = 5
        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                return object : RecyclerView.ViewHolder(SpyRowView(parent.context)) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val row = holder.itemView as SpyRowView
                row.layoutParams = RecyclerView.LayoutParams(400, 120)
                val glass = GlassSurface.rowBackground(row, GlassSamplingDomain.WALLPAPER)
                assumeNotNull("Robolectric 未能构建玻璃 drawable，本用例不适用", glass)
                row.background = glass
                if (row !in rows) rows += row
            }

            override fun getItemCount(): Int = itemCount
        }

        activity.setContentView(recyclerView)
        layoutInWindow(activity, width = 400, height = 400)

        assertTrue("应至少布局出多个可见行", rows.size >= 3)
        assertEquals(
            "N 个玻璃行在同一个 RecyclerView 内只能注册 1 个共享 OnScrollListener",
            1,
            recyclerView.addedScrollListeners.size
        )
        assertEquals(1, recyclerView.activeScrollListeners.size)
        rows.forEach { row ->
            assertNotNull(row.getTag(GLASS_POSITION_TRACKING_TAG_KEY))
            row.invalidatedDrawables.clear()
        }

        // 真实向下滚动 40px：所有可见子项屏幕坐标变化，共享协调器应一次性更新全部 N 个 drawable
        recyclerView.scrollBy(0, 40)

        rows.forEachIndexed { index, row ->
            assertTrue(
                "第 $index 行在 RecyclerView 滚动后必须触发 drawable invalidateSelf()",
                row.invalidatedDrawables.contains(row.background)
            )
        }
    }

    @Test
    fun `re-binding existing rows reuses drawable and binding without adding or churning listeners`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val recyclerView = SpyRecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
        }
        val rows = mutableListOf<SpyRowView>()
        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                return object : RecyclerView.ViewHolder(SpyRowView(parent.context)) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val row = holder.itemView as SpyRowView
                row.layoutParams = RecyclerView.LayoutParams(400, 120)
                val glass = GlassSurface.rowBackground(row, GlassSamplingDomain.WALLPAPER)
                assumeNotNull("Robolectric 未能构建玻璃 drawable，本用例不适用", glass)
                row.background = glass
                if (row !in rows) rows += row
            }

            override fun getItemCount(): Int = 5
        }

        activity.setContentView(recyclerView)
        layoutInWindow(activity, width = 400, height = 300)

        val initialDrawables = rows.map { it.background }
        val initialBindings = rows.map { it.getTag(GLASS_POSITION_TRACKING_TAG_KEY) }
        val initialAdds = recyclerView.addedScrollListeners.size
        val initialRemoves = recyclerView.removedScrollListeners.size

        // 模拟多次滚动绑定 / convert() 重绑同配置行
        rows.forEach { row ->
            val rebound = GlassSurface.rowBackground(row, GlassSamplingDomain.WALLPAPER)
            assertNotNull(rebound)
            row.background = rebound
        }

        assertEquals("同配置复用不得新增 OnScrollListener", initialAdds, recyclerView.addedScrollListeners.size)
        assertEquals("同配置复用不得拆装 OnScrollListener", initialRemoves, recyclerView.removedScrollListeners.size)
        rows.forEachIndexed { i, row ->
            assertSame("同配置复用应保留同一份 FrostedGlassDrawable", initialDrawables[i], row.background)
            assertSame("同配置复用应保留同一份 TrackerBinding", initialBindings[i], row.getTag(GLASS_POSITION_TRACKING_TAG_KEY))
        }

        // 切档后重新绑定：允许生成新 drawable，但绑定与共享滚动监听仍不重复注册/拆装
        context.putPrefString(PreferKey.exploreGlassEffectMode, GlassMaterialPreset.GLASS)
        rows.forEach { row ->
            val updated = GlassSurface.rowBackground(row, GlassSamplingDomain.WALLPAPER)
            assertNotNull(updated)
            row.background = updated
        }
        assertEquals("换肤重绑也不得新增 OnScrollListener", initialAdds, recyclerView.addedScrollListeners.size)
        assertEquals("换肤重绑也不得拆装 OnScrollListener", initialRemoves, recyclerView.removedScrollListeners.size)

        // 滚动仍能驱动新的 drawable 更新位置
        rows.forEach { it.invalidatedDrawables.clear() }
        recyclerView.scrollBy(0, 30)
        rows.forEach { row ->
            assertTrue(row.invalidatedDrawables.contains(row.background))
        }
    }

    @Test
    fun `detaching all tracked views uninstalls the shared scroll listener cleanly`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val recyclerView = SpyRecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
        }
        val rows = mutableListOf<SpyRowView>()
        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                return object : RecyclerView.ViewHolder(SpyRowView(parent.context)) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val row = holder.itemView as SpyRowView
                row.layoutParams = RecyclerView.LayoutParams(400, 120)
                val glass = GlassSurface.rowBackground(row, GlassSamplingDomain.WALLPAPER)
                assumeNotNull("Robolectric 未能构建玻璃 drawable，本用例不适用", glass)
                row.background = glass
                if (row !in rows) rows += row
            }

            override fun getItemCount(): Int = 5
        }

        activity.setContentView(recyclerView)
        layoutInWindow(activity, width = 400, height = 300)

        assertEquals(1, recyclerView.activeScrollListeners.size)

        // 逐个释放：只剩最后一个前，共享监听仍保持活跃；全部释放后彻底卸载
        for (i in 0 until rows.size - 1) {
            GlassSurface.releaseBackgroundTracking(rows[i])
            assertNull(rows[i].getTag(GLASS_POSITION_TRACKING_TAG_KEY))
            assertEquals(1, recyclerView.activeScrollListeners.size)
        }
        GlassSurface.releaseBackgroundTracking(rows.last())
        assertNull(rows.last().getTag(GLASS_POSITION_TRACKING_TAG_KEY))
        assertEquals(0, recyclerView.activeScrollListeners.size)
    }

    @Test
    fun `nested RecyclerViews register one coordinator each and outer scroll updates inner cards`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val outerRv = SpyRecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
        }
        val innerRv = SpyRecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
            layoutParams = RecyclerView.LayoutParams(400, 200)
        }
        val cards = mutableListOf<SpyRowView>()
        innerRv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                return object : RecyclerView.ViewHolder(SpyRowView(parent.context)) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val card = holder.itemView as SpyRowView
                card.layoutParams = RecyclerView.LayoutParams(150, 180)
                val glass = GlassSurface.rowBackground(card, GlassSamplingDomain.WALLPAPER)
                assumeNotNull("Robolectric 未能构建玻璃 drawable，本用例不适用", glass)
                card.background = glass
                if (card !in cards) cards += card
            }

            override fun getItemCount(): Int = 4
        }
        outerRv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                return if (viewType == 0) {
                    object : RecyclerView.ViewHolder(innerRv) {}
                } else {
                    val filler = View(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(400, 500)
                    }
                    object : RecyclerView.ViewHolder(filler) {}
                }
            }

            override fun getItemViewType(position: Int): Int = position
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
            override fun getItemCount(): Int = 2
        }

        activity.setContentView(outerRv)
        layoutInWindow(activity, width = 400, height = 400)

        assertTrue(cards.size >= 2)
        assertEquals("外层 RecyclerView 只应注册 1 个共享监听", 1, outerRv.activeScrollListeners.size)
        assertEquals("内层 RecyclerView 只应注册 1 个共享监听", 1, innerRv.activeScrollListeners.size)

        cards.forEach { it.invalidatedDrawables.clear() }
        outerRv.scrollBy(0, 25)
        cards.forEach { card ->
            assertTrue(
                "外层垂直滚动必须驱动内层玻璃卡刷新坐标",
                card.invalidatedDrawables.contains(card.background)
            )
        }

        cards.forEach { it.invalidatedDrawables.clear() }
        innerRv.scrollBy(20, 0)
        cards.forEach { card ->
            assertTrue(
                "内层水平滚动必须驱动内层玻璃卡刷新坐标",
                card.invalidatedDrawables.contains(card.background)
            )
        }
    }

    private fun layoutInWindow(activity: Activity, width: Int, height: Int) {
        val decor = activity.window.decorView
        decor.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, width, height)
    }

    private class SpyRecyclerView(context: Context) : RecyclerView(context) {
        val addedScrollListeners = mutableListOf<OnScrollListener>()
        val removedScrollListeners = mutableListOf<OnScrollListener>()
        val activeScrollListeners = mutableListOf<OnScrollListener>()

        override fun addOnScrollListener(listener: OnScrollListener) {
            addedScrollListeners += listener
            activeScrollListeners += listener
            super.addOnScrollListener(listener)
        }

        override fun removeOnScrollListener(listener: OnScrollListener) {
            removedScrollListeners += listener
            activeScrollListeners -= listener
            super.removeOnScrollListener(listener)
        }
    }

    private class SpyRowView(context: Context) : FrameLayout(context) {
        val invalidatedDrawables = mutableListOf<Drawable>()

        override fun invalidateDrawable(drawable: Drawable) {
            invalidatedDrawables += drawable
            super.invalidateDrawable(drawable)
        }
    }
}
