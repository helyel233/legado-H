package io.legado.app.base.adapter

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemTextBinding
import io.legado.app.domain.model.BookShelfState
import io.legado.app.ui.book.changesource.ChangeChapterTocAdapter
import io.legado.app.ui.book.explore.ExploreShowBookCallback
import io.legado.app.ui.book.explore.ExploreShowWaterfallAdapter
import io.legado.app.ui.main.homepage.HomepageBookItemUi
import io.legado.app.ui.main.homepage.HomepageBooksAdapter
import io.legado.app.ui.main.rss.RssAdapter
import io.legado.app.ui.rss.favorites.RssFavoritesAdapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * T1b 第一批已审计 [RecyclerAdapter] 子类开启 DiffUtil 的回归测试：
 *
 * 重点锁定两件事：
 * 1. 默认开启 diff 后，更新单个条目只派发局部变更（`onItemRangeChanged(position = 1, count = 1)`），
 *    不会触发全量重置（`onChanged`）；
 * 2. 对于覆写了只比主键的 `equals()` 的实体（[RssSource]、[SearchBook]、[BookChapter]），
 *    修改其可见字段（名称、书架状态、章节标题等）仍能被 `diffItemCallback.areContentsTheSame`
 *    捕获并触发该条目刷新；
 * 3. 未审计的默认子类（`useDiffUtil == false`）与依赖位置序号的 `HomepageBooksAdapter(MODE_RANK)`
 *    保持全量刷新语义不变。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class RecyclerAdapterDiffBatchTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
    }

    @After
    fun tearDown() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `rssAdapter dispatches targeted item change when source name changes`() {
        val owner = FakeLifecycleOwner()
        val adapter = RssAdapter(
            context = context,
            fragment = Fragment(),
            callBack = object : RssAdapter.CallBack {
                override fun openRss(rssSource: RssSource) = Unit
                override fun edit(rssSource: RssSource) = Unit
                override fun toTop(rssSource: RssSource) = Unit
                override fun login(rssSource: RssSource) = Unit
                override fun del(rssSource: RssSource) = Unit
                override fun disable(rssSource: RssSource) = Unit
            },
            lifecycle = owner.lifecycle
        )
        val initial = listOf(
            RssSource(sourceUrl = "https://rss.example/1", sourceName = "RSS 1"),
            RssSource(sourceUrl = "https://rss.example/2", sourceName = "RSS 2"),
            RssSource(sourceUrl = "https://rss.example/3", sourceName = "RSS 3")
        )
        submitAndAwaitDiff(adapter, initial)

        val observer = RecordingObserver()
        adapter.registerAdapterDataObserver(observer)

        val updated = listOf(
            RssSource(sourceUrl = "https://rss.example/1", sourceName = "RSS 1"),
            RssSource(sourceUrl = "https://rss.example/2", sourceName = "RSS 2 Updated"),
            RssSource(sourceUrl = "https://rss.example/3", sourceName = "RSS 3")
        )
        submitAndAwaitDiff(adapter, updated, observer)

        assertEquals("DiffUtil 不得退化为全量 notifyDataSetChanged", 0, observer.fullResets)
        assertEquals(listOf(1 to 1), observer.changedRanges)
        assertEquals("RSS 2 Updated", adapter.getItem(1)?.sourceName)
    }

    @Test
    fun `rssFavoritesAdapter dispatches targeted item change when title changes`() {
        val adapter = RssFavoritesAdapter(
            context = context,
            callBack = object : RssFavoritesAdapter.CallBack {
                override fun readRss(rssStar: RssStar) = Unit
                override fun delStar(rssStar: RssStar) = Unit
            }
        )
        val initial = listOf(
            RssStar(origin = "https://rss.example", link = "https://rss.example/a1", title = "Article 1"),
            RssStar(origin = "https://rss.example", link = "https://rss.example/a2", title = "Article 2"),
            RssStar(origin = "https://rss.example", link = "https://rss.example/a3", title = "Article 3")
        )
        submitAndAwaitDiff(adapter, initial)

        val observer = RecordingObserver()
        adapter.registerAdapterDataObserver(observer)

        val updated = listOf(
            RssStar(origin = "https://rss.example", link = "https://rss.example/a1", title = "Article 1"),
            RssStar(origin = "https://rss.example", link = "https://rss.example/a2", title = "Article 2 Edited"),
            RssStar(origin = "https://rss.example", link = "https://rss.example/a3", title = "Article 3")
        )
        submitAndAwaitDiff(adapter, updated, observer)

        assertEquals(0, observer.fullResets)
        assertEquals(listOf(1 to 1), observer.changedRanges)
        assertEquals("Article 2 Edited", adapter.getItem(1)?.title)
    }

    @Test
    fun `exploreShowWaterfallAdapter dispatches targeted item change when book title changes`() {
        val adapter = ExploreShowWaterfallAdapter(
            context = context,
            callBack = object : ExploreShowBookCallback {
                override fun isInBookshelf(book: SearchBook): Boolean = false
                override fun showBookInfo(book: SearchBook) = Unit
            },
            columns = 2
        )
        val initial = listOf(
            SearchBook(bookUrl = "https://book.example/1", origin = "src", name = "Book 1"),
            SearchBook(bookUrl = "https://book.example/2", origin = "src", name = "Book 2"),
            SearchBook(bookUrl = "https://book.example/3", origin = "src", name = "Book 3")
        )
        submitAndAwaitDiff(adapter, initial)

        val observer = RecordingObserver()
        adapter.registerAdapterDataObserver(observer)

        val updated = listOf(
            SearchBook(bookUrl = "https://book.example/1", origin = "src", name = "Book 1"),
            SearchBook(bookUrl = "https://book.example/2", origin = "src", name = "Book 2 Renamed"),
            SearchBook(bookUrl = "https://book.example/3", origin = "src", name = "Book 3")
        )
        submitAndAwaitDiff(adapter, updated, observer)

        assertEquals(0, observer.fullResets)
        assertEquals(listOf(1 to 1), observer.changedRanges)
        assertEquals("Book 2 Renamed", adapter.getItem(1)?.name)
    }

    @Test
    fun `exploreShowWaterfallAdapter dispatches targeted item change when isInBookshelf flips`() {
        val shelfUrls = mutableSetOf<String>()
        val adapter = ExploreShowWaterfallAdapter(
            context = context,
            callBack = object : ExploreShowBookCallback {
                override fun isInBookshelf(book: SearchBook): Boolean = book.bookUrl in shelfUrls
                override fun showBookInfo(book: SearchBook) = Unit
            },
            columns = 2
        )
        val initial = listOf(
            SearchBook(bookUrl = "https://book.example/1", origin = "src", name = "Book 1"),
            SearchBook(bookUrl = "https://book.example/2", origin = "src", name = "Book 2"),
            SearchBook(bookUrl = "https://book.example/3", origin = "src", name = "Book 3")
        )
        submitAndAwaitDiff(adapter, initial)

        val observer = RecordingObserver()
        adapter.registerAdapterDataObserver(observer)

        // Flip bookshelf state externally for item 1 and resubmit identical SearchBook items
        shelfUrls += "https://book.example/2"
        val updated = listOf(
            SearchBook(bookUrl = "https://book.example/1", origin = "src", name = "Book 1"),
            SearchBook(bookUrl = "https://book.example/2", origin = "src", name = "Book 2"),
            SearchBook(bookUrl = "https://book.example/3", origin = "src", name = "Book 3")
        )
        submitAndAwaitDiff(adapter, updated, observer)

        assertEquals(0, observer.fullResets)
        assertEquals(listOf(1 to 1), observer.changedRanges)
        val payloadBundle = observer.changedPayloads.singleOrNull() as? Bundle
        assertEquals(true, payloadBundle?.getBoolean("isInBookshelf"))
    }

    @Test
    fun `setItems snapshots synchronizedList safely under concurrent mutation`() {
        val adapter = ChangeChapterTocAdapter(
            context = context,
            callback = object : ChangeChapterTocAdapter.Callback {
                override fun clickChapter(bookChapter: BookChapter, nextChapterUrl: String?) = Unit
            }
        )
        val sharedList = Collections.synchronizedList(mutableListOf<BookChapter>())
        repeat(20) { i ->
            sharedList += BookChapter(
                url = "https://book.example/c$i",
                bookUrl = "https://book.example",
                index = i,
                title = "Ch $i"
            )
        }

        val running = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>(null)
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        try {
            executor.submit {
                started.countDown()
                var nextIdx = 20
                while (running.get()) {
                    try {
                        synchronized(sharedList) {
                            sharedList += BookChapter(
                                url = "https://book.example/c$nextIdx",
                                bookUrl = "https://book.example",
                                index = nextIdx,
                                title = "Ch $nextIdx"
                            )
                            if (sharedList.size > 40) {
                                sharedList.removeAt(0)
                            }
                            nextIdx++
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            repeat(25) {
                adapter.setItems(sharedList)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
            }
            running.set(false)
            assertNull(failure.get())
        } finally {
            running.set(false)
            executor.shutdownNow()
        }
    }

    @Test
    fun `changeChapterTocAdapter dispatches targeted item change when chapter title changes`() {
        val adapter = ChangeChapterTocAdapter(
            context = context,
            callback = object : ChangeChapterTocAdapter.Callback {
                override fun clickChapter(bookChapter: BookChapter, nextChapterUrl: String?) = Unit
            }
        )
        val initial = listOf(
            BookChapter(url = "https://book.example/c1", bookUrl = "https://book.example", index = 0, title = "Ch 1"),
            BookChapter(url = "https://book.example/c2", bookUrl = "https://book.example", index = 1, title = "Ch 2"),
            BookChapter(url = "https://book.example/c3", bookUrl = "https://book.example", index = 2, title = "Ch 3")
        )
        submitAndAwaitDiff(adapter, initial)

        val observer = RecordingObserver()
        adapter.registerAdapterDataObserver(observer)

        val updated = listOf(
            BookChapter(url = "https://book.example/c1", bookUrl = "https://book.example", index = 0, title = "Ch 1"),
            BookChapter(url = "https://book.example/c2", bookUrl = "https://book.example", index = 1, title = "Ch 2 Revised"),
            BookChapter(url = "https://book.example/c3", bookUrl = "https://book.example", index = 2, title = "Ch 3")
        )
        submitAndAwaitDiff(adapter, updated, observer)

        assertEquals(0, observer.fullResets)
        assertEquals(listOf(1 to 1), observer.changedRanges)
        assertEquals("Ch 2 Revised", adapter.getItem(1)?.title)
    }

    @Test
    fun `homepageBooksAdapter dispatches targeted item change in card mode when book name changes`() {
        val adapter = HomepageBooksAdapter(
            context = context,
            mode = HomepageBooksAdapter.MODE_CARD,
            callBack = object : HomepageBooksAdapter.CallBack {
                override fun onBookClick(book: SearchBook) = Unit
                override fun onBookLongClick(book: SearchBook) = Unit
            }
        )
        val initial = listOf(
            HomepageBookItemUi(SearchBook(bookUrl = "https://hp/1", origin = "s", name = "HP 1")),
            HomepageBookItemUi(SearchBook(bookUrl = "https://hp/2", origin = "s", name = "HP 2")),
            HomepageBookItemUi(SearchBook(bookUrl = "https://hp/3", origin = "s", name = "HP 3"))
        )
        submitAndAwaitDiff(adapter, initial)

        val observer = RecordingObserver()
        adapter.registerAdapterDataObserver(observer)

        // SearchBook.equals 只看 bookUrl，这里刻意保持 shelfState 和 bookUrl 不变只改 name
        val updated = listOf(
            HomepageBookItemUi(SearchBook(bookUrl = "https://hp/1", origin = "s", name = "HP 1")),
            HomepageBookItemUi(SearchBook(bookUrl = "https://hp/2", origin = "s", name = "HP 2 Updated")),
            HomepageBookItemUi(SearchBook(bookUrl = "https://hp/3", origin = "s", name = "HP 3"))
        )
        submitAndAwaitDiff(adapter, updated, observer)

        assertEquals(0, observer.fullResets)
        assertEquals(listOf(1 to 1), observer.changedRanges)
        assertEquals("HP 2 Updated", adapter.getItem(1)?.book?.name)
    }

    @Test
    fun `homepageBooksAdapter rank mode and unaudited base adapter keep full reset behavior`() {
        val rankAdapter = HomepageBooksAdapter(
            context = context,
            mode = HomepageBooksAdapter.MODE_RANK,
            callBack = object : HomepageBooksAdapter.CallBack {
                override fun onBookClick(book: SearchBook) = Unit
                override fun onBookLongClick(book: SearchBook) = Unit
            }
        )
        val rankObserver = RecordingObserver()
        rankAdapter.registerAdapterDataObserver(rankObserver)
        rankAdapter.setItems(
            listOf(HomepageBookItemUi(SearchBook(bookUrl = "https://hp/1", origin = "s", name = "HP 1")))
        )
        assertEquals(1, rankObserver.fullResets)

        val plainAdapter = object : RecyclerAdapter<String, ItemTextBinding>(context) {
            override fun getViewBinding(parent: ViewGroup): ItemTextBinding {
                return ItemTextBinding.inflate(inflater, parent, false)
            }

            override fun convert(
                holder: ItemViewHolder,
                binding: ItemTextBinding,
                item: String,
                payloads: MutableList<Any>
            ) = Unit

            override fun registerListener(holder: ItemViewHolder, binding: ItemTextBinding) = Unit
        }
        val plainObserver = RecordingObserver()
        plainAdapter.registerAdapterDataObserver(plainObserver)
        plainAdapter.setItems(listOf("a", "b"))
        assertEquals(1, plainObserver.fullResets)
    }

    private fun <T> submitAndAwaitDiff(
        adapter: RecyclerAdapter<T, *>,
        items: List<T>,
        existingObserver: RecordingObserver? = null
    ) {
        val observer = existingObserver ?: RecordingObserver().also {
            adapter.registerAdapterDataObserver(it)
        }
        val beforeEvents = observer.totalEvents
        adapter.setItems(items)
        val deadline = System.currentTimeMillis() + 3000L
        while (observer.totalEvents == beforeEvents && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (observer.totalEvents == beforeEvents) {
                Thread.sleep(10)
            }
        }
        if (existingObserver == null) {
            adapter.unregisterAdapterDataObserver(observer)
        }
        assertTrue("DiffUtil 结果未在超时前派发", observer.totalEvents > beforeEvents)
    }

    private class RecordingObserver : RecyclerView.AdapterDataObserver() {
        var fullResets = 0
        val changedRanges = mutableListOf<Pair<Int, Int>>()
        val changedPayloads = mutableListOf<Any?>()
        val insertedRanges = mutableListOf<Pair<Int, Int>>()
        val removedRanges = mutableListOf<Pair<Int, Int>>()
        var totalEvents = 0

        override fun onChanged() {
            fullResets++
            totalEvents++
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            changedRanges += positionStart to itemCount
            changedPayloads += null
            totalEvents++
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            changedRanges += positionStart to itemCount
            changedPayloads += payload
            totalEvents++
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            insertedRanges += positionStart to itemCount
            totalEvents++
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            removedRanges += positionStart to itemCount
            totalEvents++
        }

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
            totalEvents++
        }
    }

    private class FakeLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle get() = registry
    }
}
