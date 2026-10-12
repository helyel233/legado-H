package io.legado.app.ui.main.explore

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * 发现套件的运行时状态与签名工具（台账 #29-B13 抽取第一步：纯搬运，零行为变更）。
 *
 * 此前这些类/常量/扩展都是 ExploreFragment 的 file-private 成员，V5 发现页
 * （将来的 HostExploreContent 套件模式）拿不到。搬到这里供旧壳与新壳共用。
 *
 * 注意：
 * - [SuiteRandomDeck.prefetching]、两个 PagingState 的 nextPage/loading/exhausted
 *   都是 Main/IO 双线程访问，@Volatile 不能去掉。
 * - [DiscoverySuitePageSnapshotStore] 与 [DiscoveryCacheWriteScope] 是进程级单例，
 *   只能移动、不能另建第二份，否则旧壳与新页的快照/缓存会互相覆盖。
 */

// ---------------------------------------------------------------- 常量

internal const val MAX_SUITE_SELECTOR_SOURCES = 80
internal const val MAX_SUITE_SELECTOR_TARGETS = 160
internal const val SUITE_WIDGET_LOAD_PARALLELISM = 4
internal const val SUITE_TARGET_LOAD_PARALLELISM = 4
internal const val SUITE_RANDOM_BATCH_PARALLELISM = 3
internal const val SUITE_COVER_PRELOAD_PARALLELISM = 4
internal const val RANDOM_SUITE_BOOK_COUNT = 6
internal const val RANDOM_SUITE_PREFETCH_COUNT = 18
internal const val RANDOM_SUITE_COVER_PREFETCH_COUNT = 18
internal const val RANDOM_SUITE_MAX_PAGE = 5
internal const val RANDOM_SUITE_MAX_PREFETCH_ATTEMPTS = 12
internal const val HORIZONTAL_SUITE_VISIBLE_COVER_COUNT = 3
internal const val HORIZONTAL_SUITE_PAGE_BOOK_LIMIT = 18
internal const val HORIZONTAL_SUITE_MAX_BOOKS = 72
internal const val RANKED_SUITE_TARGET_LIMIT = 9
internal const val RANKED_SUITE_VISIBLE_COVER_COUNT = 12
internal const val WATERFALL_SUITE_BOOK_COUNT = 24
internal const val WATERFALL_SUITE_VISIBLE_COVER_COUNT = 8
internal const val SUITE_COVER_THUMB_WIDTH = 240
internal const val SUITE_COVER_THUMB_HEIGHT = 320
internal const val SUITE_SINGLE_COVER_PRELOAD_TIMEOUT_MS = 1000L
internal const val SUITE_VISIBLE_COVER_PRELOAD_TIMEOUT_MS = 1800L

internal const val DISCOVERY_SUITE_SNAPSHOT_RANDOM_LIMIT = 36
internal const val DISCOVERY_SUITE_SNAPSHOT_HORIZONTAL_LIMIT = 72
internal const val DISCOVERY_SUITE_SNAPSHOT_WATERFALL_LIMIT = 24
internal const val DISCOVERY_SUITE_SNAPSHOT_RANKED_TOTAL_LIMIT = 72
internal const val RANKED_SUITE_SNAPSHOT_BOOK_LIMIT = 24
internal const val DISCOVERY_SUITE_SNAPSHOT_WIDGET_LIMIT = 20
internal const val DISCOVERY_MODERN_CACHE_BOOK_LIMIT = 80
internal const val DISCOVERY_CACHE_TTL_MS = 72L * 60L * 60L * 1000L
internal const val DISCOVERY_MODERN_CACHE_PREFIX = "discovery_modern_result_"
internal const val DISCOVERY_SUITE_CACHE_PREFIX = "discovery_suite_snapshot_"

// ---------------------------------------------------------------- 运行时状态

internal data class SuiteRandomDeck(
    val signature: String,
    val mutex: Mutex = Mutex(),
    val queue: ArrayDeque<SearchBook> = ArrayDeque(),
    val seenKeys: LinkedHashSet<String> = linkedSetOf(),
    val nextPageByTarget: MutableMap<String, Int> = linkedMapOf(),
    var targetIndex: Int = 0,
    // Main 线程读、IO 线程写，加 @Volatile 保证可见性，避免重复预取。
    @Volatile var prefetching: Boolean = false
)

internal data class SuitePreparedBatch(
    val signature: String,
    val books: List<SearchBook>
)

internal data class SuiteDeckPageRequest(
    val tagUrl: String,
    val page: Int,
    val source: BookSource,
    val seed: Int
)

internal data class SuiteHorizontalPagingState(
    val signature: String,
    // nextPage/loading/exhausted 会被 Main(loadMore) 与 IO(首页加载) 访问，加 @Volatile 保证可见性。
    @Volatile var nextPage: Int = 2,
    @Volatile var loading: Boolean = false,
    @Volatile var exhausted: Boolean = false
)

internal data class SuiteRankedPagingState(
    val signature: String,
    // nextPage/loading/exhausted 会被 Main(loadMore) 与 IO(首页加载) 访问，加 @Volatile 保证可见性。
    @Volatile var nextPage: Int = 2,
    @Volatile var loading: Boolean = false,
    @Volatile var exhausted: Boolean = false
)

internal data class ModernDiscoverResultCache(
    val sourceUrl: String = "",
    val tagUrl: String = "",
    val books: List<SearchBook> = emptyList(),
    val nextPage: Int = 2,
    val hasMore: Boolean = true,
    val savedAt: Long = 0L
)

internal data class DiscoverySuitePageSnapshot(
    val suiteId: String,
    val signature: String,
    val widgetBooks: Map<String, List<SearchBook>>,
    val rankedWidgetBooks: Map<String, Map<String, List<SearchBook>>>,
    val widgetSignatures: Map<String, String>
)

internal fun DiscoverySuitePageSnapshot.compactForCache(): DiscoverySuitePageSnapshot {
    val incompleteWidgetIds = hashSetOf<String>()
    val compactWidgetBooks = linkedMapOf<String, List<SearchBook>>()
    widgetBooks.forEach { (widgetId, books) ->
        val compactBooks = books.mapNotNull(DiscoveryCachePolicy::compact)
        if (compactBooks.size != books.size) incompleteWidgetIds += widgetId
        if (compactBooks.isNotEmpty()) compactWidgetBooks[widgetId] = compactBooks
    }
    val compactRankedWidgetBooks = linkedMapOf<String, Map<String, List<SearchBook>>>()
    rankedWidgetBooks.forEach { (widgetId, rankedBooks) ->
        val compactRankedBooks = linkedMapOf<String, List<SearchBook>>()
        rankedBooks.forEach { (rank, books) ->
            val compactBooks = books.mapNotNull(DiscoveryCachePolicy::compact)
            if (compactBooks.size != books.size) incompleteWidgetIds += widgetId
            if (compactBooks.isNotEmpty()) compactRankedBooks[rank] = compactBooks
        }
        if (compactRankedBooks.isNotEmpty()) {
            compactRankedWidgetBooks[widgetId] = compactRankedBooks
        }
    }
    return copy(
        widgetBooks = compactWidgetBooks,
        rankedWidgetBooks = compactRankedWidgetBooks,
        widgetSignatures = widgetSignatures.filterKeys { it !in incompleteWidgetIds }
    )
}

internal fun DiscoverySuitePageSnapshot.hasBooks(): Boolean {
    return widgetBooks.isNotEmpty() || rankedWidgetBooks.isNotEmpty()
}

internal object DiscoverySuitePageSnapshotStore {
    private const val MAX_SNAPSHOTS = 2
    private val snapshots = object : LinkedHashMap<String, DiscoverySuitePageSnapshot>(
        MAX_SNAPSHOTS,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, DiscoverySuitePageSnapshot>
        ): Boolean {
            return size > MAX_SNAPSHOTS
        }
    }

    @Synchronized
    fun get(suiteId: String, signature: String): DiscoverySuitePageSnapshot? {
        return snapshots[suiteId]?.takeIf { it.signature == signature }
    }

    @Synchronized
    fun put(snapshot: DiscoverySuitePageSnapshot) {
        if (snapshot.suiteId.isBlank()) return
        snapshots[snapshot.suiteId] = snapshot
    }
}

internal object DiscoveryCacheWriteScope {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var suiteSaveJob: Job? = null
    private var suiteSaveVersion = 0L

    @Synchronized
    fun launchLatest(block: suspend CoroutineScope.(Long) -> Unit) {
        val saveVersion = ++suiteSaveVersion
        suiteSaveJob?.cancel()
        suiteSaveJob = scope.launch { block(saveVersion) }
    }

    @Synchronized
    fun isLatest(saveVersion: Long): Boolean = suiteSaveVersion == saveVersion
}
// ---------------------------------------------------------------- 签名 / 键工具

internal fun String.limitDiscoverText(max: Int): String {
    return if (length <= max) this else "${take(max.coerceAtLeast(2) - 1)}..."
}

internal fun DiscoverySuiteWidget.validRandomTargets(): List<DiscoverySuiteWidgetTarget> {
    return targets.filter { it.sourceUrl.isNotBlank() && it.tagUrl.isNotBlank() }
}

internal fun DiscoverySuite.cacheSignature(): String {
    return widgets.joinToString(separator = "\u001D") { widget ->
        "${widget.order}\u001C${widget.cacheSignature()}"
    }
}

internal fun DiscoverySuiteWidget.cacheSignature(): String {
    return listOf(
        id,
        type,
        displayLimit.toString(),
        validRandomTargets().joinToString(separator = "\u001C") { it.deckKey() }
    ).joinToString(separator = "\u001D")
}

internal fun DiscoverySuiteWidget.snapshotBookLimit(): Int {
    return when (type) {
        DiscoverySuiteWidgetType.HorizontalBooks.value -> DISCOVERY_SUITE_SNAPSHOT_HORIZONTAL_LIMIT
        DiscoverySuiteWidgetType.WaterfallBooks.value -> DISCOVERY_SUITE_SNAPSHOT_WATERFALL_LIMIT
        DiscoverySuiteWidgetType.RankedList.value -> DISCOVERY_SUITE_SNAPSHOT_RANKED_TOTAL_LIMIT
        else -> DISCOVERY_SUITE_SNAPSHOT_RANDOM_LIMIT
    }
}

internal fun DiscoverySuiteWidget.deckSignature(): String {
    return validRandomTargets().joinToString("|") { it.deckKey() } + "|$displayLimit|$type"
}

internal fun DiscoverySuiteWidget.horizontalPagingSignature(): String {
    return validRandomTargets().firstOrNull()?.deckKey().orEmpty() + "|$type"
}

internal fun DiscoverySuiteWidget.rankedPagingKey(target: DiscoverySuiteWidgetTarget): String {
    return "$id\n${target.deckKey()}"
}

internal fun DiscoverySuiteWidget.rankedPagingSignature(target: DiscoverySuiteWidgetTarget): String {
    return "${target.deckKey()}|$type|$displayLimit"
}

/** 套件 target 的唯一键（旧壳与 DiscoverySuiteHomeScreen 曾各定义一份，此处统一）。 */
internal fun DiscoverySuiteWidgetTarget.deckKey(): String {
    return "$sourceUrl\n$tagUrl"
}

internal fun DiscoverySuiteWidget.isSuiteButtonOnlyWidget(): Boolean {
    return type == DiscoverySuiteWidgetType.TagBar.value ||
        type == DiscoverySuiteWidgetType.RankButtons.value
}

internal fun SearchBook.suiteDeckKey(): String {
    return when {
        bookUrl.isNotBlank() -> "$origin|$bookUrl"
        author.isNotBlank() -> "$origin|$name|$author"
        else -> "$origin|$name"
    }
}
