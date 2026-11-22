package io.legado.app.ui.main.homepage

import android.app.Application
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.domain.model.HomepageModuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class HomepageRankingTabStateTest {

    private lateinit var context: Context
    private lateinit var parent: FrameLayout

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
        parent = FrameLayout(context)
    }

    @Test
    fun `ranking tab error shows error card and triggers tab retry callback`() {
        var retriedTab = -1
        val adapter = HomepageAdapter(
            context,
            object : HomepageAdapter.CallBack {
                override fun onBookClick(book: SearchBook) = Unit
                override fun onBookLongClick(book: SearchBook) = Unit
                override fun onModuleHeaderClick(module: HomepageModuleUi, exploreUrl: String?) = Unit
                override fun onKindClick(module: HomepageModuleUi, kind: ExploreKind) = Unit
                override fun onRetry(module: HomepageModuleUi) = Unit
                override fun onRetryRankingTab(module: HomepageModuleUi, tabIndex: Int) {
                    retriedTab = tabIndex
                }
                override fun onLoadMore(module: HomepageModuleUi) = Unit
                override fun onLoadMoreRankingTab(module: HomepageModuleUi, tabIndex: Int) = Unit
                override fun onSelectRankingTab(module: HomepageModuleUi, index: Int) = Unit
            },
        )
        val holder = adapter.createViewHolder(parent, 0)
        holder.bind(
            HomepageModuleUi(
                sourceUrl = "https://example.test",
                setName = "set",
                globalId = "mod_1",
                type = HomepageModuleType.Ranking,
                title = "排行榜",
                state = ModuleLoadState.RankingTabs(
                    tabs = listOf(
                        RankingTabData(title = "玄幻", books = emptyList()),
                        RankingTabData(title = "都市", books = null, errorMessage = "timeout"),
                    ),
                    selectedIndex = 1,
                ),
            )
        )

        val errorCard = holder.itemView.findViewById<View>(R.id.ll_error)
        val skeleton = holder.itemView.findViewById<View>(R.id.fl_skeleton)
        val tabsRow = holder.itemView.findViewById<View>(R.id.ll_tabs)

        assertEquals(View.VISIBLE, errorCard.visibility)
        assertEquals(View.GONE, skeleton.visibility)
        assertEquals(View.VISIBLE, tabsRow.visibility)

        errorCard.performClick()
        assertEquals(1, retriedTab)
    }

    @Test
    fun `ranking tab loading shows skeleton while keeping tab bar visible`() {
        val adapter = HomepageAdapter(
            context,
            object : HomepageAdapter.CallBack {
                override fun onBookClick(book: SearchBook) = Unit
                override fun onBookLongClick(book: SearchBook) = Unit
                override fun onModuleHeaderClick(module: HomepageModuleUi, exploreUrl: String?) = Unit
                override fun onKindClick(module: HomepageModuleUi, kind: ExploreKind) = Unit
                override fun onRetry(module: HomepageModuleUi) = Unit
                override fun onLoadMore(module: HomepageModuleUi) = Unit
                override fun onLoadMoreRankingTab(module: HomepageModuleUi, tabIndex: Int) = Unit
                override fun onSelectRankingTab(module: HomepageModuleUi, index: Int) = Unit
            },
        )
        val holder = adapter.createViewHolder(parent, 0)
        holder.bind(
            HomepageModuleUi(
                sourceUrl = "https://example.test",
                setName = "set",
                globalId = "mod_2",
                type = HomepageModuleType.Grid,
                title = "分类",
                state = ModuleLoadState.RankingTabs(
                    tabs = listOf(
                        RankingTabData(title = "玄幻", books = null),
                        RankingTabData(title = "都市", books = null),
                    ),
                    selectedIndex = 0,
                ),
            )
        )

        val errorCard = holder.itemView.findViewById<View>(R.id.ll_error)
        val skeleton = holder.itemView.findViewById<FrameLayout>(R.id.fl_skeleton)
        val tabsRow = holder.itemView.findViewById<View>(R.id.ll_tabs)

        assertEquals(View.GONE, errorCard.visibility)
        assertEquals(View.VISIBLE, skeleton.visibility)
        assertTrue(skeleton.childCount > 0)
        assertEquals(View.VISIBLE, tabsRow.visibility)
    }

    @Test
    fun `revalidating indicator toggles cleanly via bind and payload`() {
        val adapter = HomepageAdapter(
            context,
            object : HomepageAdapter.CallBack {
                override fun onBookClick(book: SearchBook) = Unit
                override fun onBookLongClick(book: SearchBook) = Unit
                override fun onModuleHeaderClick(module: HomepageModuleUi, exploreUrl: String?) = Unit
                override fun onKindClick(module: HomepageModuleUi, kind: ExploreKind) = Unit
                override fun onRetry(module: HomepageModuleUi) = Unit
                override fun onLoadMore(module: HomepageModuleUi) = Unit
                override fun onLoadMoreRankingTab(module: HomepageModuleUi, tabIndex: Int) = Unit
                override fun onSelectRankingTab(module: HomepageModuleUi, index: Int) = Unit
            },
        )
        val books = listOf(
            HomepageBookItemUi(SearchBook(bookUrl = "b1", origin = "https://example.test", name = "Book 1"))
        )
        val revalidatingModule = HomepageModuleUi(
            sourceUrl = "https://example.test",
            setName = "set",
            globalId = "mod_rev",
            type = HomepageModuleType.Grid,
            title = "网格",
            state = ModuleLoadState.Loaded(books = books, isRevalidating = true),
        )
        val settledModule = revalidatingModule.copy(
            state = ModuleLoadState.Loaded(books = books, isRevalidating = false),
        )
        val recyclerView = androidx.recyclerview.widget.RecyclerView(context).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
            itemAnimator = null
            this.adapter = adapter
        }
        adapter.submitModules(listOf(revalidatingModule))
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        recyclerView.layout(0, 0, 1080, 1920)
        val itemView = recyclerView.getChildAt(0)
        val titleSpinner = itemView.findViewById<View>(R.id.pb_revalidating)
        assertEquals(View.VISIBLE, titleSpinner.visibility)

        // 触发 DiffUtil -> RevalidatingPayload 轻量刷新
        adapter.submitModules(listOf(settledModule))
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        recyclerView.layout(0, 0, 1080, 1920)
        assertEquals(View.GONE, recyclerView.getChildAt(0).findViewById<View>(R.id.pb_revalidating).visibility)
    }
}
