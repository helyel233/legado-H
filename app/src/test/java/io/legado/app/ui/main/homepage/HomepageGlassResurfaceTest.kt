package io.legado.app.ui.main.homepage

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.help.config.ThemeConfig
import io.legado.app.R
import io.legado.app.ui.glass.GLASS_SIGNATURE_TAG
import io.legado.app.ui.glass.NO_WALLPAPER_SIGNATURE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
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
 * 首页卡面「改档后重铺」的回归锁（QA 第一轮 C 项）。
 *
 * 缺陷形态：`HomepageGridRankingPagerAdapter` 只在 `onCreateViewHolder` 铺一次页卡，
 * `HomepageAdapter` 的榜单卡 / 错误卡只在 bind 时铺一次，而 `boundKey` 会拦掉同模块重绑。
 * 于是「设置里改玻璃档 / 关背景图 → 返回首页」时 holder 常驻、不重建，卡面停在旧材质上
 * （solid 档仍显示玻璃）。
 *
 * 这里锁的是重铺**确实发生**：模拟「旧签名与旧背景都已过期」的 holder，再走一次挂载、
 * 回到前台的重铺入口，断言签名字段被写回、旧背景被换掉；以及反方向——`rankStyle = false`
 * 的形态不能被重铺成卡面（否则已隐藏的卡面会重新显出来）。
 *
 * 环境说明：Robolectric 下 `ThemeConfig.hasUsableBgImage` 恒为 false，重铺的是
 * 「无壁纸 → 原实底 drawable」这一支；玻璃材质本身的观感属真机验证项。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class HomepageGlassResurfaceTest {

    private lateinit var context: Context
    private lateinit var parent: FrameLayout

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
        parent = FrameLayout(context)
    }

    @Test
    fun `precondition without background image the resurface branch is the original surface`() {
        assertFalse(ThemeConfig.hasUsableBgImage(context))
    }

    @Test
    fun `pager page card is re-surfaced when re-attached`() {
        val adapter = HomepageGridRankingPagerAdapter(context, {}, {})
        val holder = adapter.createViewHolder(parent, 0)
        assertEquals(
            "创建时就应铺好（无壁纸档 = 原卡面实底 + 无壁纸签名）",
            NO_WALLPAPER_SIGNATURE,
            holder.itemView.getTag(GLASS_SIGNATURE_TAG)
        )
        // 模拟：页面存活期间改档 / 关背景图，旧签名与旧背景都过期了
        val staleBackground = ColorDrawable(Color.RED)
        holder.itemView.setTag(GLASS_SIGNATURE_TAG, null)
        holder.itemView.background = staleBackground

        adapter.onViewAttachedToWindow(holder)

        assertNotSame("重新挂载必须换掉旧背景", staleBackground, holder.itemView.background)
        assertEquals(
            "重新挂载必须写回签名，否则后续判定会一直以为「已经铺过」",
            NO_WALLPAPER_SIGNATURE,
            holder.itemView.getTag(GLASS_SIGNATURE_TAG)
        )
    }

    @Test
    fun `error module card is re-surfaced when the holder re-attaches`() {
        val adapter = HomepageAdapter(context, noopCallBack())
        val holder = adapter.createViewHolder(parent, 0)
        holder.bind(errorModule())
        val errorCard = holder.itemView.findViewById<View>(R.id.ll_error)
        assertEquals(NO_WALLPAPER_SIGNATURE, errorCard.getTag(GLASS_SIGNATURE_TAG))

        val staleBackground = ColorDrawable(Color.RED)
        errorCard.setTag(GLASS_SIGNATURE_TAG, null)
        errorCard.background = staleBackground

        adapter.onViewAttachedToWindow(holder)

        assertNotSame("错误卡在 holder 重新挂载时必须重铺", staleBackground, errorCard.background)
        assertEquals(NO_WALLPAPER_SIGNATURE, errorCard.getTag(GLASS_SIGNATURE_TAG))
    }

    @Test
    fun `rank card without card surface is not painted back when re-attaching`() {
        val adapter = HomepageAdapter(context, noopCallBack())
        val holder = adapter.createViewHolder(parent, 0)
        // Waterfall（非排行榜）形态复用 llRankCard 容器但不带卡面
        holder.bind(waterfallModule())
        val rankCard = holder.itemView.findViewById<View>(R.id.ll_rank_card)

        adapter.onViewAttachedToWindow(holder)

        assertNull("rankStyle=false 的形态不能被重铺出卡面", rankCard.background)
        assertNull(
            "rankStyle=false 时签名 tag 必须保持清空，否则切回榜单会被判成已铺过",
            rankCard.getTag(GLASS_SIGNATURE_TAG)
        )
    }

    @Test
    fun `ranking module card is re-surfaced when the holder re-attaches`() {
        val adapter = HomepageAdapter(context, noopCallBack())
        val holder = adapter.createViewHolder(parent, 0)
        holder.bind(rankingModule())
        val rankCard = holder.itemView.findViewById<View>(R.id.ll_rank_card)
        val freshBackground = rankCard.background
        val staleBackground = ColorDrawable(Color.BLUE)
        rankCard.setTag(GLASS_SIGNATURE_TAG, null)
        rankCard.background = staleBackground

        adapter.onViewAttachedToWindow(holder)

        assertNotSame("榜单卡重新挂载时必须重铺", staleBackground, rankCard.background)
        assertEquals(NO_WALLPAPER_SIGNATURE, rankCard.getTag(GLASS_SIGNATURE_TAG))
        assertTrue("重铺后应回到原卡面实底（无壁纸档）", freshBackground != staleBackground)
    }

    @Test
    fun `resurfacing twice keeps the same drawable`() {
        val adapter = HomepageAdapter(context, noopCallBack())
        val holder = adapter.createViewHolder(parent, 0)
        holder.bind(rankingModule())
        val rankCard = holder.itemView.findViewById<View>(R.id.ll_rank_card)

        adapter.onViewAttachedToWindow(holder)
        val first = rankCard.background
        adapter.onViewAttachedToWindow(holder)

        assertSame("签名未变时不得重建卡面（每次 attach 都会走到这里）", first, rankCard.background)
    }

    // region 辅助

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

    private fun waterfallModule() = module(
        type = HomepageModuleType.Waterfall,
        state = ModuleLoadState.Loaded(books = emptyList())
    )

    private fun rankingModule() = module(
        type = HomepageModuleType.Ranking,
        state = ModuleLoadState.Loaded(books = emptyList())
    )

    private fun module(type: HomepageModuleType, state: ModuleLoadState) = HomepageModuleUi(
        sourceUrl = "https://example.test",
        setName = "set",
        globalId = "module-${type.name.lowercase()}",
        type = type,
        title = "title",
        state = state
    )

    // endregion
}
