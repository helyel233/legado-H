package io.legado.app.ui.main.homepage

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.ui.glass.GLASS_SIGNATURE_TAG
import io.legado.app.ui.glass.NO_WALLPAPER_SIGNATURE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

/**
 * 第一轮 C 项修复里**只在 onResume 才走**的那条重铺链路的回归锁。
 *
 * `HomepageGlassResurfaceTest` 锁的是 `onViewAttachedToWindow` 形态；而真机上「设置页改档 →
 * 返回首页」不会产生 attach 事件（Activity 没重建、页也没重新挂载），走的是
 * `HomepageFragment.onResume → adapter.refreshVisibleCardSurfaces(rv)` /
 * `sourcePagerAdapter.refreshCardSurfaces()`。这条链路有三个只能靠代码保证的风险，
 * 本类逐个锁住：
 *
 * 1. **不能空指针**：RecyclerView 一个子项都没有（首帧未布局 / 页未附着）时必须静默跳过；
 * 2. **不能崩在 ViewPager2 内层 RecyclerView 的获取上**：`vp.getChildAt(0)` 在未布局时为
 *    null，必须 `as? RecyclerView` 跳过而不是强转；
 * 3. **必须真的重铺**：已布局、已显示卡面的 holder，签名过期后要换掉旧背景并写回签名
 *    （否则后续判定会一直以为「已经铺过」）。
 *
 * 环境说明：Robolectric 下 `ThemeConfig.hasUsableBgImage` 恒为 false，重铺落在
 * 「无壁纸 → 原实底」这一支；玻璃材质观感属真机验证项。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class HomepageResurfaceWiringTest {

    private lateinit var context: Context
    private lateinit var parent: FrameLayout

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
        parent = FrameLayout(context)
    }

    /** onResume 路径：已布局的模块列表里，错误卡必须按签名重铺。 */
    @Test
    fun `refresh on an laid out module list re-surfaces visible cards`() {
        val adapter = HomepageAdapter(context, noopCallBack())
        val recyclerView = layOut(adapter, listOf(errorModule()))
        assertTrue("前置：RecyclerView 必须真的布局出子项", recyclerView.childCount > 0)

        val errorCard = recyclerView.getChildAt(0).findViewById<View>(R.id.ll_error)
        assertEquals(NO_WALLPAPER_SIGNATURE, errorCard.getTag(GLASS_SIGNATURE_TAG))
        val staleBackground = ColorDrawable(Color.RED)
        errorCard.setTag(GLASS_SIGNATURE_TAG, null)
        errorCard.background = staleBackground

        adapter.refreshVisibleCardSurfaces(recyclerView)

        assertNotSame("onResume 重铺必须换掉过期卡面", staleBackground, errorCard.background)
        assertEquals(
            "必须写回签名，否则下一次判定会以为已经铺过而跳过",
            NO_WALLPAPER_SIGNATURE,
            errorCard.getTag(GLASS_SIGNATURE_TAG)
        )
    }

    /** 空 RecyclerView（首帧未布局 / 页未附着）：重铺必须静默跳过，不得抛异常。 */
    @Test
    fun `refresh on an empty list is a silent no-op`() {
        val adapter = HomepageAdapter(context, noopCallBack())
        val recyclerView = RecyclerView(context)

        adapter.refreshVisibleCardSurfaces(recyclerView)

        assertEquals(0, recyclerView.childCount)
    }

    /**
     * 网格排行榜模块：ViewPager2 尚未布局（`getChildAt(0)` 为 null）时重铺不得崩，
     * 也不得把榜单容器当成卡面铺一层。
     */
    @Test
    fun `grid ranking module without a laid out pager refresh does not crash`() {
        val adapter = HomepageAdapter(context, noopCallBack())
        val holder = adapter.createViewHolder(parent, 0)
        holder.bind(gridRankingModule())

        adapter.onViewAttachedToWindow(holder)

        val rankCard = holder.itemView.findViewById<View>(R.id.ll_rank_card)
        assertEquals("网格形态下榜单容器必须无背景", null, rankCard.background)
    }

    /** 页卡适配器的按需重铺：对已加入的页视图按签名重铺，且只写自己的签名 tag。 */
    @Test
    fun `pager refresh re-surfaces every attached page card`() {
        val adapter = HomepageGridRankingPagerAdapter(context, {}, {})
        // ViewPager2 的内层 RecyclerView 一定带 LayoutManager；宿主代码是通过
        // `getChildAt(0) as? RecyclerView` 拿到它再遍历子项的，这里如实构造同形态容器
        val pagerRecycler = RecyclerView(context)
        pagerRecycler.layoutManager = LinearLayoutManager(context)
        val page = adapter.createViewHolder(parent, 0).itemView
        pagerRecycler.addView(page)
        assertEquals("前置：页视图必须已挂进内层容器", 1, pagerRecycler.childCount)
        val staleBackground = ColorDrawable(Color.RED)
        page.setTag(GLASS_SIGNATURE_TAG, null)
        page.background = staleBackground

        adapter.refreshVisibleCardSurfaces(pagerRecycler)

        assertNotSame("页卡必须重铺", staleBackground, page.background)
        assertEquals(NO_WALLPAPER_SIGNATURE, page.getTag(GLASS_SIGNATURE_TAG))
    }

    /** 重铺不得重绑内容：子项数量与 holder 实例都应保持不变。 */
    @Test
    fun `refresh does not rebind or recreate the item views`() {
        val adapter = HomepageAdapter(context, noopCallBack())
        val recyclerView = layOut(adapter, listOf(rankingModule()))
        val before = recyclerView.getChildAt(0)
        val countBefore = recyclerView.childCount

        adapter.refreshVisibleCardSurfaces(recyclerView)

        assertEquals(countBefore, recyclerView.childCount)
        assertSame("重铺只换背景，不得替换子视图", before, recyclerView.getChildAt(0))
    }

    // region 辅助

    private fun layOut(
        adapter: HomepageAdapter,
        modules: List<HomepageModuleUi>
    ): RecyclerView {
        val recyclerView = RecyclerView(context)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter
        adapter.submitModules(modules)
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT_PX, View.MeasureSpec.EXACTLY)
        )
        recyclerView.layout(0, 0, WIDTH_PX, HEIGHT_PX)
        return recyclerView
    }

    private fun noopCallBack(): HomepageAdapter.CallBack = object : HomepageAdapter.CallBack {
        override fun onBookClick(book: SearchBook) = Unit
        override fun onBookLongClick(book: SearchBook) = Unit
        override fun onModuleHeaderClick(module: HomepageModuleUi, exploreUrl: String?) = Unit
        override fun onKindClick(module: HomepageModuleUi, kind: ExploreKind) = Unit
        override fun onRetry(module: HomepageModuleUi) = Unit
        override fun onLoadMore(module: HomepageModuleUi) = Unit
        override fun onLoadMoreRankingTab(module: HomepageModuleUi, tabIndex: Int) = Unit
        override fun onSelectRankingTab(module: HomepageModuleUi, index: Int) = Unit
    }

    private fun errorModule() = module(
        type = HomepageModuleType.Waterfall,
        state = ModuleLoadState.Error("boom")
    )

    private fun gridRankingModule() = module(
        type = HomepageModuleType.GridRanking,
        state = ModuleLoadState.Loaded(books = emptyList())
    )

    private fun rankingModule() = module(
        type = HomepageModuleType.Ranking,
        state = ModuleLoadState.Loaded(books = emptyList())
    )

    private fun module(type: HomepageModuleType, state: ModuleLoadState) = HomepageModuleUi(
        sourceUrl = "https://example.test",
        setName = "set",
        globalId = "module-wiring-${type.name.lowercase()}",
        type = type,
        title = "title",
        state = state
    )

    private companion object {
        const val WIDTH_PX = 1080
        const val HEIGHT_PX = 1920
    }

    // endregion
}
