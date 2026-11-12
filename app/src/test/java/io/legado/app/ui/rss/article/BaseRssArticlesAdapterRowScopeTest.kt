package io.legado.app.ui.rss.article

import android.app.Application
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.viewbinding.ViewBinding
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.RssArticle
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.glass.NO_WALLPAPER_SIGNATURE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * 文章行背景的「作用域」回归锁：哪些 ViewHolder 会被铺行底，哪些必须完全不碰。
 *
 * 这段逻辑（`isArticleRowViewType` + `applyRowBackground` 的 tag 去重）此前没有任何
 * 测试，而它的两种错法在真机上都很难一眼看出：
 *
 * - **给尾视图铺行底**：`RssArticlesFragment` 的尾视图是**跨适配器共享**的
 *   `LoadMoreView` 实例，一旦被写上背景 / keyed tag，就会把「加载更多 / 没有更多」
 *   的观感与状态带到别的列表（同一实例被复用）；
 * - **漏掉真实文章行**：行 viewType 若被误判成尾视图，用户看到的就仍是「这一页没玻璃化」
 *   —— 正是本次反馈的原始现象。
 *
 * 因此这里不测 drawable 长什么样（那要在真机 / 截图上看），只锁「谁被处理、谁不被处理」。
 *
 * 环境说明：Robolectric 下 `ThemeConfig.hasUsableBgImage` 恒为 false（没有配置背景图），
 * 走的是「无壁纸 → 透明行」这一支；玻璃 drawable 分支需要真实 BaseActivity 宿主与壁纸，
 * 属于真机验证项，见测试报告。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BaseRssArticlesAdapterRowScopeTest {

    private lateinit var context: Context
    private lateinit var parent: ViewGroup

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
        parent = FrameLayout(context)
    }

    @Test
    fun `precondition without background image the row branch is the transparent one`() {
        // 后面几条断言依赖「无背景图」这一支；环境变化时让它显式失败，而不是静默换个分支
        assertFalse(ThemeConfig.hasUsableBgImage(context))
    }

    @Test
    fun `header and footer holders are never given article row material`() {
        val adapter = probeAdapter()
        attachHeaderAndFooter(adapter)
        adapter.setItems(listOf(sampleArticle()))

        val headerHolder = adapter.createViewHolder(parent, adapter.getItemViewType(0))
        val rowHolder = adapter.createViewHolder(parent, adapter.getItemViewType(1))
        val footerHolder = adapter.createViewHolder(parent, adapter.getItemViewType(2))

        // 真实行：被打上签名 tag（无背景图时同时保持透明行）
        assertNotNull("真实文章行必须进入行背景管线", rowHolder.itemView.rowSignatureTag())
        assertEquals(NO_WALLPAPER_SIGNATURE, rowHolder.itemView.rowSignatureTag())
        assertNull(rowHolder.itemView.background)

        // 头 / 尾：一个字节都不许动（尾视图是共享 LoadMoreView 实例）
        assertNull("头视图不应被铺行底", headerHolder.itemView.background)
        assertNull("头视图不应被打 tag", headerHolder.itemView.rowSignatureTag())
        assertNull("尾视图不应被铺行底（共享 LoadMoreView）", footerHolder.itemView.background)
        assertNull("尾视图不应被打 tag（共享 LoadMoreView）", footerHolder.itemView.rowSignatureTag())
    }

    @Test
    fun `re-attaching a row keeps its material instead of rebuilding it`() {
        val adapter = probeAdapter()
        attachHeaderAndFooter(adapter)
        adapter.setItems(listOf(sampleArticle()))

        val rowHolder = adapter.createViewHolder(parent, adapter.getItemViewType(1))
        val firstTag = rowHolder.itemView.rowSignatureTag()
        val firstBackground: Drawable? = rowHolder.itemView.background

        // 滚动复用会反复走到 onViewAttachedToWindow；签名未变时必须原样保留
        adapter.onViewAttachedToWindow(rowHolder)
        adapter.onViewAttachedToWindow(rowHolder)

        assertSame(firstTag, rowHolder.itemView.rowSignatureTag())
        assertSame(firstBackground, rowHolder.itemView.background)
    }

    @Test
    fun `attaching header or footer does not touch them either`() {
        val adapter = probeAdapter()
        attachHeaderAndFooter(adapter)
        adapter.setItems(listOf(sampleArticle()))

        val headerHolder = adapter.createViewHolder(parent, adapter.getItemViewType(0))
        val footerHolder = adapter.createViewHolder(parent, adapter.getItemViewType(2))

        adapter.onViewAttachedToWindow(headerHolder)
        adapter.onViewAttachedToWindow(footerHolder)

        assertNull(headerHolder.itemView.background)
        assertNull(headerHolder.itemView.rowSignatureTag())
        assertNull(footerHolder.itemView.background)
        assertNull(footerHolder.itemView.rowSignatureTag())
    }

    @Test
    fun `style 3 rows that draw their own card surface opt out entirely`() {
        val adapter = probeAdapter(ownCardSurface = true)
        adapter.setItems(listOf(sampleArticle()))

        val rowHolder = adapter.createViewHolder(parent, adapter.getItemViewType(0))

        assertNull("自带实底卡面的样式不应再被铺一层玻璃", rowHolder.itemView.background)
        assertNull("自带实底卡面的样式不应打签名 tag", rowHolder.itemView.rowSignatureTag())
    }

    @Test
    fun `row signature tag key is a legal keyed tag key`() {
        // View.setTag(int, Object) 要求 key 的应用位 >= 2，否则真机抛 IllegalArgumentException
        // （Robolectric 不校验该契约，所以这里按契约断言）
        assertTrue(ROW_GLASS_SIGNATURE_TAG ushr 24 >= 2)
        assertNull("未使用过的 tag key 应读出 null", FrameLayout(context).getTag(ROW_GLASS_SIGNATURE_TAG))
    }

    // region 辅助

    private fun probeAdapter(ownCardSurface: Boolean = false): ProbeAdapter = ProbeAdapter(
        context = context,
        ownCardSurface = ownCardSurface,
        callBack = object : BaseRssArticlesAdapter.CallBack {
            override val isGridLayout = false
            override fun readRss(rssArticle: RssArticle) = Unit
        }
    )

    private fun attachHeaderAndFooter(adapter: BaseRssArticlesAdapter<*>) {
        adapter.addHeaderView { headerParent -> bindingOf(FrameLayout(headerParent.context)) }
        adapter.addFooterView { footerParent -> bindingOf(FrameLayout(footerParent.context)) }
    }

    private fun sampleArticle() = RssArticle(
        origin = "https://example.test",
        sort = "sort",
        title = "title",
        link = "https://example.test/1"
    )

    private fun bindingOf(view: View): ViewBinding = object : ViewBinding {
        override fun getRoot(): View = view
    }

    private fun View.rowSignatureTag(): Any? = getTag(ROW_GLASS_SIGNATURE_TAG)

    /**
     * 只用于驱动基类管线的最小适配器：不 inflate 任何业务布局，viewType 保持默认 0，
     * 与 `RssArticlesAdapter` / `1` / `2` / `4` 的真实行为一致（这四个类都不覆写
     * `getItemViewType`）。
     */
    private class ProbeAdapter(
        context: Context,
        private val ownCardSurface: Boolean,
        callBack: CallBack
    ) : BaseRssArticlesAdapter<ViewBinding>(context, callBack) {

        override val rowDrawsOwnCardSurface: Boolean get() = ownCardSurface

        override fun getViewBinding(parent: ViewGroup): ViewBinding = object : ViewBinding {
            override fun getRoot(): View = FrameLayout(parent.context)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ViewBinding,
            item: RssArticle,
            payloads: MutableList<Any>
        ) = Unit

        override fun registerListener(holder: ItemViewHolder, binding: ViewBinding) = Unit
    }

    // endregion
}
