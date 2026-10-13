package io.legado.app.ui.main.explore

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.SearchBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 发现套件会话（台账 #29-B13 第四步：编排层下沉）。
 * 拥有套件发现的界面状态与编排流程，旧壳与 V5 发现页共用；
 * 两边只做「绑定/解绑 + 渲染」。单例 + 自有作用域，界面解绑时调 [stop]。
 */
object DiscoverSuiteSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engine = DiscoverSuiteEngine(scope) { active }

    /** 界面是否绑定（对应旧壳 usingSuiteDiscovery，陈旧写回守卫的一部分）。 */
    @Volatile
    var active: Boolean = false
        private set

    private val _config = MutableStateFlow(DiscoverySuiteStore.load())
    val config: StateFlow<DiscoverySuiteConfig> = _config.asStateFlow()

    private val _selectedSuiteId = MutableStateFlow(DiscoverySuiteStore.selectedSuiteId())
    val selectedSuiteId: StateFlow<String> = _selectedSuiteId.asStateFlow()

    private val _widgetBooks = MutableStateFlow<Map<String, List<SearchBook>>>(emptyMap())
    val widgetBooks: StateFlow<Map<String, List<SearchBook>>> = _widgetBooks.asStateFlow()

    private val _rankedWidgetBooks =
        MutableStateFlow<Map<String, Map<String, List<SearchBook>>>>(emptyMap())
    val rankedWidgetBooks: StateFlow<Map<String, Map<String, List<SearchBook>>>> =
        _rankedWidgetBooks.asStateFlow()

    private val _loadingWidgetIds = MutableStateFlow<Set<String>>(emptySet())
    val loadingWidgetIds: StateFlow<Set<String>> = _loadingWidgetIds.asStateFlow()

    private val widgetSignatures = hashMapOf<String, String>()
    private var loadJob: Job? = null
    private val widgetLoadSemaphore = Semaphore(SUITE_WIDGET_LOAD_PARALLELISM)

    fun bind() {
        active = true
        refreshConfig()
    }

    /** 界面解绑：存快照 → 取消在途批量加载 → 关引擎任务。 */
    fun stop() {
        saveCurrentSuiteSnapshot()
        loadJob?.cancel()
        loadJob = null
        engine.cancelAll()
        active = false
    }

    fun selectedSuite(config: DiscoverySuiteConfig = _config.value): DiscoverySuite? {
        val selectedId = _selectedSuiteId.value
        return config.suites.firstOrNull { it.id == selectedId }
            ?: config.suites.firstOrNull()
    }

    /** 陈旧写回守卫：界面活跃 + 控件仍在当前套件且签名一致。 */
    private fun isCurrentWidget(widget: DiscoverySuiteWidget): Boolean {
        if (!active) return false
        val currentWidget = selectedSuite()?.widgets?.firstOrNull { it.id == widget.id }
            ?: return false
        return currentWidget.cacheSignature() == widget.cacheSignature()
    }

    private fun setLoading(widgetId: String, loading: Boolean) {
        val current = _loadingWidgetIds.value.toMutableSet()
        if (loading) current += widgetId else current -= widgetId
        _loadingWidgetIds.value = current
    }

    // ---------------------------------------------------------------- 配置 / 选择

    /** 重载配置 → 校正选中 → 恢复快照 → 批量加载（旧壳 refreshSuiteConfig 的编排，保序）。 */
    fun refreshConfig() {
        val config = DiscoverySuiteStore.load()
        var selectedId = _selectedSuiteId.value
        if (config.suites.none { it.id == selectedId }) {
            selectedId = config.suites.firstOrNull()?.id.orEmpty()
            if (selectedId.isNotBlank()) {
                DiscoverySuiteStore.setSelectedSuiteId(selectedId)
            }
        }
        _config.value = config
        _selectedSuiteId.value = selectedId
        restoreSnapshot(selectedSuite(config))
        loadSelectedWidgets()
    }

    /** 选择套件：先存当前快照，再落选中 id，再走完整刷新。 */
    fun selectSuite(suite: DiscoverySuite) {
        saveCurrentSuiteSnapshot()
        DiscoverySuiteStore.setSelectedSuiteId(suite.id)
        _selectedSuiteId.value = suite.id
        refreshConfig()
    }

    // ---------------------------------------------------------------- 首屏批量加载

    private fun loadSelectedWidgets() {
        loadJob?.cancel()
        loadJob = null
        _loadingWidgetIds.value = emptySet()
        // 切换/刷新套件时重置分页状态，避免旧套件残留的 exhausted/loading 永久阻塞加载更多
        engine.clearRuntimeState()
        val suite = selectedSuite() ?: run {
            _widgetBooks.value = emptyMap()
            _rankedWidgetBooks.value = emptyMap()
            widgetSignatures.clear()
            return
        }
        val widgetIds = suite.widgets.map { it.id }.toSet()
        _widgetBooks.value = _widgetBooks.value.filterKeys { it in widgetIds }
        _rankedWidgetBooks.value = _rankedWidgetBooks.value.filterKeys { it in widgetIds }
        widgetSignatures.keys.retainAll(widgetIds)
        engine.retainWidgets(widgetIds)
        val widgetsToLoad = suite.widgets.filterNot { widget ->
            val signature = widget.cacheSignature()
            when {
                widget.isSuiteButtonOnlyWidget() || widget.targets.isEmpty() -> {
                    setWidgetBooks(widget.id, emptyList())
                    _rankedWidgetBooks.value = _rankedWidgetBooks.value - widget.id
                    widgetSignatures[widget.id] = signature
                    true
                }
                widgetSignatures[widget.id] == signature &&
                    _widgetBooks.value.containsKey(widget.id) &&
                    (widget.type != DiscoverySuiteWidgetType.RankedList.value ||
                        _rankedWidgetBooks.value.containsKey(widget.id)) -> {
                    engine.prefetchCovers(_widgetBooks.value[widget.id].orEmpty())
                    true
                }
                else -> false
            }
        }
        if (widgetsToLoad.isEmpty()) {
            suite.widgets.forEach { widget ->
                // 横排控件触底懒加载，不在首屏主动预取下一页
                if (widget.type != DiscoverySuiteWidgetType.HorizontalBooks.value) {
                    engine.prepareNextRandomBatch(widget)
                }
            }
            saveCurrentSuiteSnapshot()
            return
        }
        loadJob = scope.launch {
            coroutineScope {
                widgetsToLoad.map { widget ->
                    async {
                        widgetLoadSemaphore.withPermit {
                            loadSingleWidget(widget)
                        }
                    }
                }.awaitAll()
            }
            saveCurrentSuiteSnapshot()
        }
    }

    private fun setWidgetBooks(widgetId: String, books: List<SearchBook>) {
        _widgetBooks.value = _widgetBooks.value + (widgetId to books)
    }

    /** 单控件加载（批量加载与刷新共用；含陈旧写回守卫与后续动作分发）。 */
    private suspend fun loadSingleWidget(widget: DiscoverySuiteWidget) {
        if (widget.isSuiteButtonOnlyWidget() || widget.targets.isEmpty()) {
            setWidgetBooks(widget.id, emptyList())
            _rankedWidgetBooks.value = _rankedWidgetBooks.value - widget.id
            widgetSignatures[widget.id] = widget.cacheSignature()
            return
        }
        setLoading(widget.id, true)
        try {
            if (widget.type == DiscoverySuiteWidgetType.RankedList.value) {
                val rankedBooks = try {
                    withContext(Dispatchers.IO) { engine.loadRankedWidgetBooks(widget) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLog.put("套件发现排行榜控件加载失败", e)
                    emptyMap()
                }
                if (!isCurrentWidget(widget)) return
                if (rankedBooks.isNotEmpty()) {
                    _rankedWidgetBooks.value = _rankedWidgetBooks.value + (widget.id to rankedBooks)
                    setWidgetBooks(widget.id, rankedBooks.values.flatten())
                    widgetSignatures[widget.id] = widget.cacheSignature()
                    engine.prefetchCovers(rankedBooks.values.flatten())
                }
                return
            }
            _rankedWidgetBooks.value = _rankedWidgetBooks.value - widget.id
            val books = try {
                withContext(Dispatchers.IO) { engine.loadWidgetBooks(widget) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("套件发现控件加载失败", e)
                emptyList()
            }
            if (!isCurrentWidget(widget)) return
            if (books.isNotEmpty()) {
                setWidgetBooks(widget.id, books)
                widgetSignatures[widget.id] = widget.cacheSignature()
                engine.prefetchCovers(books)
            }
            if (widget.type == DiscoverySuiteWidgetType.HorizontalBooks.value) {
                engine.prefetchCovers(books.drop(HORIZONTAL_SUITE_VISIBLE_COVER_COUNT))
                // 不主动预取下一页，交给横排触底懒加载
            } else {
                engine.prepareNextRandomBatch(widget)
            }
        } finally {
            setLoading(widget.id, false)
        }
    }

    // ---------------------------------------------------------------- 单控件刷新 / 加载更多

    /** 单控件刷新：排行榜重载；随机型优先吃预生成批次；其余重新加载。 */
    fun refreshWidget(widget: DiscoverySuiteWidget) {
        if (!active || widget.isSuiteButtonOnlyWidget()) return
        if (widget.id in _loadingWidgetIds.value) return
        if (widget.type == DiscoverySuiteWidgetType.RankedList.value) {
            scope.launch {
                loadSingleWidget(widget)
                if (isCurrentWidget(widget)) saveCurrentSuiteSnapshot()
            }
            return
        }
        if (widget.type != DiscoverySuiteWidgetType.HorizontalBooks.value) {
            val prepared = engine.takePreparedBatch(widget)
            if (prepared != null) {
                setWidgetBooks(widget.id, prepared)
                widgetSignatures[widget.id] = widget.cacheSignature()
                engine.prefetchCovers(prepared)
                saveCurrentSuiteSnapshot()
                engine.prepareNextRandomBatch(widget)
                return
            }
        }
        scope.launch {
            loadSingleWidget(widget)
            if (!isCurrentWidget(widget)) return@launch
            if (widget.type != DiscoverySuiteWidgetType.HorizontalBooks.value) {
                engine.prepareNextRandomBatch(widget)
            }
            saveCurrentSuiteSnapshot()
        }
    }

    fun loadMoreHorizontal(widget: DiscoverySuiteWidget) {
        if (!active || widget.type != DiscoverySuiteWidgetType.HorizontalBooks.value) return
        val state = engine.horizontalPagingFor(widget)
        if (state.loading || state.exhausted) return
        val current = _widgetBooks.value[widget.id].orEmpty()
        if (current.size >= HORIZONTAL_SUITE_MAX_BOOKS) {
            state.exhausted = true
            return
        }
        val page = state.nextPage
        state.loading = true
        scope.launch {
            try {
                val books = try {
                    withContext(Dispatchers.IO) { engine.loadHorizontalPage(widget, page) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLog.put("套件发现横排控件加载下一页失败", e)
                    emptyList()
                }
                if (!isCurrentWidget(widget)) {
                    state.loading = false
                    return@launch
                }
                val latestState = engine.horizontalPagingFor(widget)
                if (latestState.signature != state.signature) {
                    state.loading = false
                    return@launch
                }
                val merged = (_widgetBooks.value[widget.id].orEmpty() + books)
                    .distinctBy { it.suiteDeckKey() }
                    .take(HORIZONTAL_SUITE_MAX_BOOKS)
                if (merged.size > _widgetBooks.value[widget.id].orEmpty().size) {
                    setWidgetBooks(widget.id, merged)
                    widgetSignatures[widget.id] = widget.cacheSignature()
                    engine.prefetchCovers(books)
                    latestState.nextPage = page + 1
                    latestState.exhausted = merged.size >= HORIZONTAL_SUITE_MAX_BOOKS
                    saveCurrentSuiteSnapshot()
                } else {
                    latestState.exhausted = true
                }
                latestState.loading = false
            } finally {
                state.loading = false
            }
        }
    }

    fun loadMoreRanked(widget: DiscoverySuiteWidget, target: DiscoverySuiteWidgetTarget) {
        if (!active || widget.type != DiscoverySuiteWidgetType.RankedList.value) return
        val state = engine.rankedPagingFor(widget, target)
        if (state.loading || state.exhausted) return
        val key = target.deckKey()
        val current = _rankedWidgetBooks.value[widget.id]?.get(key).orEmpty()
        if (current.size >= RANKED_SUITE_SNAPSHOT_BOOK_LIMIT * 3) {
            state.exhausted = true
            return
        }
        val page = state.nextPage
        state.loading = true
        scope.launch {
            try {
                val books = try {
                    withContext(Dispatchers.IO) { engine.loadRankedPage(target, page) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLog.put("套件发现排行榜加载下一页失败", e)
                    emptyList()
                }
                if (!isCurrentWidget(widget)) {
                    state.loading = false
                    return@launch
                }
                val latestState = engine.rankedPagingFor(widget, target)
                if (latestState.signature != state.signature) {
                    state.loading = false
                    return@launch
                }
                val merged = (current + books)
                    .distinctBy { it.suiteDeckKey() }
                if (merged.size > current.size) {
                    val widgetRanked = _rankedWidgetBooks.value[widget.id].orEmpty() +
                        (key to merged)
                    _rankedWidgetBooks.value = _rankedWidgetBooks.value + (widget.id to widgetRanked)
                    setWidgetBooks(widget.id, widgetRanked.values.flatten())
                    widgetSignatures[widget.id] = widget.cacheSignature()
                    engine.prefetchCovers(books)
                    latestState.nextPage = page + 1
                    latestState.exhausted = books.isEmpty()
                    saveCurrentSuiteSnapshot()
                } else {
                    latestState.exhausted = true
                }
                latestState.loading = false
            } finally {
                state.loading = false
            }
        }
    }

    // ---------------------------------------------------------------- 快照

    /** 保存当前套件页面快照（内存 LRU + 磁盘缓存，按控件上限裁剪）。 */
    private fun saveCurrentSuiteSnapshot() {
        val suite = selectedSuite() ?: return
        if (_widgetBooks.value.isEmpty() && _rankedWidgetBooks.value.isEmpty()) return
        val snapshotWidgets = suite.widgets.take(DISCOVERY_SUITE_SNAPSHOT_WIDGET_LIMIT)
        val snapshot = DiscoverySuitePageSnapshot(
            suiteId = suite.id,
            signature = suite.cacheSignature(),
            widgetBooks = snapshotWidgets.associate { widget ->
                widget.id to _widgetBooks.value[widget.id]
                    .orEmpty()
                    .take(widget.snapshotBookLimit())
            }.filterValues { it.isNotEmpty() },
            rankedWidgetBooks = snapshotWidgets
                .filter { it.type == DiscoverySuiteWidgetType.RankedList.value }
                .associate { widget ->
                    widget.id to _rankedWidgetBooks.value[widget.id]
                        .orEmpty()
                        .mapValues { entry -> entry.value.take(RANKED_SUITE_SNAPSHOT_BOOK_LIMIT) }
                }
                .filterValues { it.isNotEmpty() },
            widgetSignatures = widgetSignatures.filterKeys { key ->
                snapshotWidgets.any { it.id == key }
            }
        )
        engine.saveSnapshotCacheAsync(snapshot)
    }

    /** 恢复快照：先查内存 LRU，miss 则读磁盘缓存并回填（含活跃/选中校验）。 */
    private fun restoreSnapshot(suite: DiscoverySuite?) {
        if (suite == null) return
        val signature = suite.cacheSignature()
        val memorySnapshot = DiscoverySuitePageSnapshotStore.get(suite.id, signature)
        if (memorySnapshot != null) {
            applySnapshot(memorySnapshot)
            return
        }
        val suiteId = suite.id
        scope.launch {
            val diskSnapshot = withContext(Dispatchers.IO) {
                engine.readSnapshotCache(suiteId, signature)
            } ?: return@launch
            if (!active || selectedSuite()?.id != suiteId ||
                selectedSuite()?.cacheSignature() != signature
            ) {
                return@launch
            }
            applySnapshot(diskSnapshot)
            DiscoverySuitePageSnapshotStore.put(diskSnapshot)
        }
    }

    private fun applySnapshot(snapshot: DiscoverySuitePageSnapshot) {
        _widgetBooks.value = snapshot.widgetBooks
        _rankedWidgetBooks.value = snapshot.rankedWidgetBooks
        widgetSignatures.clear()
        widgetSignatures.putAll(snapshot.widgetSignatures)
        engine.prefetchCovers(snapshot.widgetBooks.values.flatten())
    }
}

