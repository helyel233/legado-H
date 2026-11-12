package io.legado.app.ui.main.homepage

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.ThemeConfig
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
 * 首页模块内嵌书籍卡卡面的回归锁（第二轮 C 项的最深一环）。
 *
 * `HomepageAdapter.refreshCardSurfaces()` 只负责把重铺推到 `rvContent`，真正的卡面在
 * [HomepageBooksAdapter] 里；这一环出错的形态有两种，都只在真机上看得出来：
 *
 * 1. **改档不重铺**：卡面只在 `onCreateViewHolder` 铺一次，holder 复用后会停在旧材质上
 *    （solid 档仍显示玻璃）；
 * 2. **误套**：把「行」当「卡」铺一层，网格 / 横幅 / 排行榜行的按压涟漪或透明底被换成
 *    圆角实底，排版观感直接变样。
 *
 * 环境说明：Robolectric 下 `ThemeConfig.hasUsableBgImage` 恒为 false，落的是
 * 「无壁纸 → 原卡面实底」这一支；玻璃材质观感属真机验证项。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class HomepageBooksCardSurfaceTest {

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
    fun `card mode row is surfaced on creation`() {
        val adapter = adapter(HomepageBooksAdapter.MODE_CARD)
        val itemView = adapter.createViewHolder(parent, 0).itemView

        assertEquals(
            "卡片模式自带卡面，创建时就应铺好（无壁纸档 = 原实底 + 无壁纸签名）",
            NO_WALLPAPER_SIGNATURE,
            itemView.getTag(GLASS_SIGNATURE_TAG)
        )
        assertTrue("原卡面实底必须真的贴上", itemView.background != null)
    }

    @Test
    fun `card mode row is re-surfaced when the host pushes a refresh`() {
        val adapter = adapter(HomepageBooksAdapter.MODE_CARD)
        val itemView = adapter.createViewHolder(parent, 0).itemView
        val staleBackground = ColorDrawable(Color.RED)
        itemView.setTag(GLASS_SIGNATURE_TAG, null)
        itemView.background = staleBackground

        val recyclerView = layOut(listOf(itemView))
        adapter.refreshVisibleCardSurfaces(recyclerView)

        assertNotSame("回到前台重铺必须换掉过期卡面", staleBackground, itemView.background)
        assertEquals(
            "必须写回签名，否则下一次判定会以为已经铺过而跳过",
            NO_WALLPAPER_SIGNATURE,
            itemView.getTag(GLASS_SIGNATURE_TAG)
        )
    }

    @Test
    fun `waterfall mode row is re-surfaced when the host pushes a refresh`() {
        val adapter = adapter(HomepageBooksAdapter.MODE_WATERFALL)
        val itemView = adapter.createViewHolder(parent, 0).itemView
        assertEquals(NO_WALLPAPER_SIGNATURE, itemView.getTag(GLASS_SIGNATURE_TAG))
        val staleBackground = ColorDrawable(Color.GREEN)
        itemView.setTag(GLASS_SIGNATURE_TAG, null)
        itemView.background = staleBackground

        adapter.refreshVisibleCardSurfaces(layOut(listOf(itemView)))

        assertNotSame(staleBackground, itemView.background)
        assertEquals(NO_WALLPAPER_SIGNATURE, itemView.getTag(GLASS_SIGNATURE_TAG))
    }

    @Test
    fun `rank mode row is never given a card surface`() {
        val adapter = adapter(HomepageBooksAdapter.MODE_RANK)
        val itemView = adapter.createViewHolder(parent, 0).itemView
        val backgroundBefore = itemView.background

        assertNull(
            "排行榜行不是卡片：不得写玻璃签名，否则会被当成已铺过",
            itemView.getTag(GLASS_SIGNATURE_TAG)
        )
        adapter.refreshVisibleCardSurfaces(layOut(listOf(itemView)))
        assertSame("重铺不得替换非卡片形态的行底", backgroundBefore, itemView.background)
        assertNull(itemView.getTag(GLASS_SIGNATURE_TAG))
    }

    @Test
    fun `grid mode row is never given a card surface`() {
        val adapter = adapter(HomepageBooksAdapter.MODE_GRID)
        val itemView = adapter.createViewHolder(parent, 0).itemView

        adapter.refreshVisibleCardSurfaces(layOut(listOf(itemView)))

        assertNull("网格行只靠按压涟漪，套卡面会改变排版观感", itemView.getTag(GLASS_SIGNATURE_TAG))
    }

    @Test
    fun `re-surfacing twice keeps the same drawable`() {
        val adapter = adapter(HomepageBooksAdapter.MODE_CARD)
        val itemView = adapter.createViewHolder(parent, 0).itemView
        val recyclerView = layOut(listOf(itemView))

        adapter.refreshVisibleCardSurfaces(recyclerView)
        val first = itemView.background
        adapter.refreshVisibleCardSurfaces(recyclerView)

        assertSame("签名未变时不得重建卡面（每次 onResume 都会走到这里）", first, itemView.background)
    }

    // region 辅助

    private fun adapter(mode: Int) = HomepageBooksAdapter(
        context,
        mode,
        object : HomepageBooksAdapter.CallBack {
            override fun onBookClick(book: SearchBook) = Unit
            override fun onBookLongClick(book: SearchBook) = Unit
        }
    )

    /**
     * 把已创建的行视图装进一个带 LayoutManager 的 RecyclerView。
     *
     * 宿主链路是 `getChildViewHolder(...)` / `getChildAt(i)` 遍历已显示子项，容器必须有
     * LayoutManager 才能 `addView`；这里不挂 adapter（不重新 bind，正是要验的行为）。
     */
    private fun layOut(children: List<View>): RecyclerView {
        val recyclerView = RecyclerView(context)
        recyclerView.layoutManager = LinearLayoutManager(context)
        children.forEachIndexed { index, child ->
            if (child.parent != null) {
                (child.parent as? android.view.ViewGroup)?.removeView(child)
            }
            recyclerView.addView(child)
            assertEquals(index + 1, recyclerView.childCount)
        }
        return recyclerView
    }

    // endregion
}
