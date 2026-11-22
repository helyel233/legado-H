package io.legado.app.ui.main.homepage

import android.app.Application
import android.text.Html
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.repository.HomepageModulesRepository
import io.legado.app.domain.gateway.HomepageModulesGateway
import io.legado.app.domain.model.BookShelfState
import io.legado.app.domain.model.CustomSetItem
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.domain.model.ModuleDef
import io.legado.app.domain.model.ModuleItem
import io.legado.app.help.book.BookshelfMatcher
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.source.sortUrls
import io.legado.app.model.rss.Rss
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import io.legado.app.R
import splitties.init.appCtx

internal data class TabSelectionState(
    val index: Int = 0,
    val setUrls: List<String> = emptyList(),
)

internal object HomepageTabSelectionPolicy {

    fun toSetId(setUrl: String): String {
        return if (HomepageViewModel.isCustomSetUrl(setUrl)) {
            HomepageViewModel.customSetIdFromUrl(setUrl)
        } else {
            setUrl
        }
    }

    fun resolveActiveSetId(
        layoutMode: Int,
        tabSelection: TabSelectionState,
        fallbackSetUrls: List<String>,
    ): String? {
        if (layoutMode == 0) return null
        val urls = tabSelection.setUrls.ifEmpty { fallbackSetUrls }
        if (urls.isEmpty()) return null
        val safeIndex = tabSelection.index.coerceIn(0, urls.lastIndex)
        return toSetId(urls[safeIndex])
    }

    fun resolveLoadSetIds(
        layoutMode: Int,
        preloadMode: Int,
        tabSelection: TabSelectionState,
        fallbackSetUrls: List<String>,
    ): Set<String>? {
        if (layoutMode == 0) return null
        val urls = tabSelection.setUrls.ifEmpty { fallbackSetUrls }
        if (urls.isEmpty()) return emptySet()
        val safeIndex = tabSelection.index.coerceIn(0, urls.lastIndex)
        val preload = preloadMode == 1
        val start = if (preload) (safeIndex - 1).coerceAtLeast(0) else safeIndex
        val end = if (preload) (safeIndex + 1).coerceAtMost(urls.lastIndex) else safeIndex
        return (start..end).mapNotNull { i ->
            urls.getOrNull(i)?.let(::toSetId)
        }.toSet()
    }
}

/**
 * 聚合主页 ViewModel
 *
 * 负责主页的数据加载、状态管理和业务逻辑，包括：
 * - 模块内容的异步加载与状态管理（加载中/成功/错误）
 * - 书源 homepageModules JSON 的同步与增量更新（基于 MD5 哈希的变更检测）
 * - 自定义集的创建、编辑、删除和排序
 * - 模块的启用/禁用、排序、编辑和删除
 * - 书架状态查询与书籍添加
 * - 发现页分类（ExploreKind）的获取
 *
 * 模块 ID 编码格式：setId::sourceUrl::moduleKey；集 ID 前缀：src_（书源集）、rss_（订阅源集）、cs_（用户自定义集）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomepageViewModel(application: Application) : BaseViewModel(application) {

    companion object {
        private const val CUSTOM_SET_URL_PREFIX = "custom://"

        /** 将自定义集 ID 转换为 URL 格式 */
        fun customSetUrl(id: String) = "$CUSTOM_SET_URL_PREFIX$id"

        /** 判断 URL 是否为自定义集 */
        fun isCustomSetUrl(url: String) = url.startsWith(CUSTOM_SET_URL_PREFIX)

        /** 从 URL 中提取自定义集 ID */
        fun customSetIdFromUrl(url: String): String = url.removePrefix(CUSTOM_SET_URL_PREFIX)

        /** 判断模块是否为无限流类型（每个集仅允许存在一个） */
        fun isInfinite(type: String?): Boolean {
            return type == HomepageModuleType.Waterfall.key
                    || type == HomepageModuleType.InfiniteGrid.key
        }

        /** 从书源的 homepageModules JSON 解析模块定义列表 */
        private fun parseModuleDefs(sourceUrl: String, json: String): List<ModuleDef> =
            GSON.fromJsonArray<ModuleDef>(json).getOrDefault(emptyList())
                .map { it.copy(sourceUrl = sourceUrl) }

        /** 计算 JSON 字符串的 MD5 哈希值，用于增量同步的变更检测 */
        private fun jsonHash(json: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(json.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        /** 按集分组并保持顺序（自定义集优先，书源集随后） */
        private fun List<ModuleItem>.groupBySourceOrdered(): Map<String, List<ModuleItem>> {
            val result = linkedMapOf<String, MutableList<ModuleItem>>()
            for (module in this) {
                val key = module.customSetId?.let { customSetUrl(it) } ?: module.sourceUrl
                result.getOrPut(key) { mutableListOf() }.add(module)
            }
            return result
        }
    }

    private val gateway: HomepageModulesGateway =
        HomepageModulesRepository(appDb.homepageModuleDao, appDb.homepageCustomSetDao)

    private val _effects = MutableSharedFlow<HomepageEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    private val cacheStore = HomepageCacheStore()
    private val loadScheduler = HomepageLoadScheduler(viewModelScope)

    /** 已派发但尚未完成前置检查的模块/页签，防止多次 emit 间隙重复入队 */
    private val dispatchedModuleIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val dispatchedTabKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _isRefreshing = MutableStateFlow(false)
    private val _pendingRefreshIds = MutableStateFlow<Set<String>>(emptySet())
    private val _configVersion = MutableStateFlow(0L)
    private val _moduleContentStates = MutableStateFlow<Map<String, ModuleLoadState>>(emptyMap())
    private val _bookSourcesCache =
        MutableStateFlow<Map<String, BookSourcePartLite>>(emptyMap())
    private val _rssSourceNames = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _visibleModuleIds = MutableStateFlow<Set<String>>(emptySet())

    /** 分源Tab 模式：当前选中页索引与页对应的集 URL 列表（响应式驱动按需加载） */
    private val _tabSelection = MutableStateFlow(TabSelectionState())

    private val localModulesFlow = gateway.flowEnabled()
    val allModulesCache = gateway.flowAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 自定义集列表同步读取：每次 _configVersion 变化时直接从数据库重新读取，
     * 确保排序变更后各 Flow 立即拿到最新顺序，规避 Room Flow 异步发射延迟。
     */
    private val customSetsSync = _configVersion.mapLatest {
        gateway.flowCustomSets().first()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val orderedModuleDefsFlow = combine(localModulesFlow, _configVersion) { modules, _ ->
        modules.groupBySourceOrdered()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val rawModulesFlow = combine(
        orderedModuleDefsFlow,
        _moduleContentStates,
        _bookSourcesCache,
        customSetsSync,
    ) { grouped, contentStates, sourcesCache, customSets ->
        val setNames = customSets.associate { it.id to it.name }
        val sortedSetIds = customSets.sortedBy { it.sortOrder }.map { it.id }
        val hidden = hiddenSetUrls

        sortedSetIds.flatMap { setId ->
            val isSourceSet = setId.startsWith("src_") || setId.startsWith("rss_")
            val setUrl = if (isSourceSet) setId else customSetUrl(setId)
            if (setUrl in hidden) return@flatMap emptyList()
            val mods = grouped[customSetUrl(setId)] ?: emptyList()
            mods.map { module ->
                val source = sourcesCache[module.sourceUrl]
                val sourceName = source?.name ?: module.sourceUrl
                val setName = module.customSetId?.let { setNames[it] } ?: sourceName
                val exploreUrl = module.url ?: source?.exploreUrl
                HomepageModuleUi(
                    sourceUrl = module.sourceUrl,
                    setName = setName,
                    globalId = module.id,
                    type = HomepageModuleType.fromKey(module.type),
                    title = module.displayTitle,
                    exploreUrl = exploreUrl,
                    customSetId = module.customSetId,
                    layoutConfig = module.layoutConfig,
                    state = contentStates[module.id] ?: ModuleLoadState.Loading,
                )
            }
        }
    }

    private val displayModulesFlow = combine(
        rawModulesFlow,
        BookshelfMatcher.version
    ) { modules, _ ->
        modules.map { module ->
            updateModuleShelfState(module) { item ->
                BookshelfMatcher.getState(item.book.name, item.book.author, item.book.bookUrl)
            }
        }
    }

    /**
     * 更新模块中书籍的书架状态，统一处理 Loaded 和 RankingTabs 两种状态。
     */
    private fun updateModuleShelfState(
        module: HomepageModuleUi,
        resolveState: (HomepageBookItemUi) -> BookShelfState
    ): HomepageModuleUi {
        val state = module.state
        return when (state) {
            is ModuleLoadState.Loaded -> {
                module.copy(state = state.copy(
                    books = state.books.map { item ->
                        val newShelfState = resolveState(item)
                        if (item.shelfState == newShelfState) item
                        else item.copy(shelfState = newShelfState)
                    }
                ))
            }
            is ModuleLoadState.RankingTabs -> {
                module.copy(state = state.copy(
                    tabs = state.tabs.map { tab ->
                        val books = tab.books ?: return@map tab
                        tab.copy(books = books.map { item ->
                            val newShelfState = resolveState(item)
                            if (item.shelfState == newShelfState) item
                            else item.copy(shelfState = newShelfState)
                        })
                    }
                ))
            }
            else -> module
        }
    }

    // ==================== 管理数据流 ====================

    private val hiddenSetUrls: Set<String>
        get() {
            val json = AppConfig.homepageSourceHidden
            if (json.isBlank()) return emptySet()
            return GSON.fromJsonArray<String>(json).getOrDefault(emptySet()).toSet()
        }

    private fun saveHiddenSetUrls(urls: Set<String>) {
        AppConfig.homepageSourceHidden = GSON.toJson(urls)
    }

    /** 集列表管理界面的数据流 */
    val setsFlow = combine(customSetsSync, allModulesCache, _configVersion) { sets, modules, _ ->
        val hidden = hiddenSetUrls
        sets.map { cs ->
            val isSourceSet = cs.id.startsWith("src_") || cs.id.startsWith("rss_")
            val setUrl = if (isSourceSet) cs.id else customSetUrl(cs.id)
            val count = modules.count { it.customSetId == cs.id }
            val sourceType = when {
                cs.id.startsWith("src_") -> "book"
                cs.id.startsWith("rss_") -> "rss"
                else -> null
            }
            HomepageSourceManageUi(
                sourceUrl = setUrl,
                sourceName = cs.name,
                isSelected = setUrl !in hidden,
                moduleCount = count,
                isCustomSet = !isSourceSet,
                sourceType = sourceType,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 可添加模块的书源列表（启用且具备发现能力的书源） */
    val browseSourcesFlow = combine(
        _bookSourcesCache,
        allModulesCache,
        _configVersion
    ) { sources, modules, _ ->
        sources.values.map { source ->
            val count = modules.count { it.sourceUrl == source.url }
            HomepageSourceManageUi(
                sourceUrl = source.url,
                sourceName = source.name,
                sourceGroup = source.group,
                moduleCount = count,
                isCustomSet = false,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val manageStateFlow = combine(
        setsFlow,
        browseSourcesFlow,
        allModulesCache,
        _bookSourcesCache,
        _rssSourceNames
    ) { sets, browseSources, modules, sources, rssNames ->
        val sourceNames = sources.values.associate { it.url to it.name } + rssNames
        val allJoined = modules.map { mod ->
            HomepageModuleManageUi(
                id = mod.id,
                sourceUrl = mod.sourceUrl,
                sourceName = sourceNames[mod.sourceUrl] ?: mod.sourceUrl,
                moduleKey = mod.moduleKey,
                title = mod.displayTitle,
                customSetTitle = mod.customSetTitle,
                customSetId = mod.customSetId,
                isVisible = mod.isEnabled,
                type = mod.type,
                url = mod.url,
                args = mod.args,
                layoutConfig = mod.layoutConfig,
                originalTitle = mod.title,
                sourceType = if (rssNames.containsKey(mod.sourceUrl)) "rss" else "book",
            )
        }
        HomepageManageUiState(
            sets = sets,
            browseSources = browseSources,
            rssSources = rssNames.map { (url, name) ->
                HomepageSourceManageUi(
                    sourceUrl = url,
                    sourceName = name,
                    isCustomSet = false,
                )
            },
            allJoinedModules = allJoined,
            sourceNames = sourceNames,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomepageManageUiState())

    /** 首页 Tab 所需的可见集列表（与管理弹窗解耦，避免模块加载频繁带动管理列表重算） */
    private val visibleSetsFlow = combine(customSetsSync, allModulesCache, _configVersion) { sets, modules, _ ->
        val hidden = hiddenSetUrls
        sets.mapNotNull { cs ->
            val isSourceSet = cs.id.startsWith("src_") || cs.id.startsWith("rss_")
            val setUrl = if (isSourceSet) cs.id else customSetUrl(cs.id)
            if (setUrl in hidden) return@mapNotNull null
            val count = modules.count { it.customSetId == cs.id && it.isEnabled }
            if (count <= 0) return@mapNotNull null
            val sourceType = when {
                cs.id.startsWith("src_") -> "book"
                cs.id.startsWith("rss_") -> "rss"
                else -> null
            }
            HomepageSourceManageUi(
                sourceUrl = setUrl,
                sourceName = cs.name,
                isSelected = true,
                moduleCount = count,
                isCustomSet = !isSourceSet,
                sourceType = sourceType,
            )
        }
    }

    val uiState: StateFlow<HomepageUiState> = combine(
        displayModulesFlow,
        _isRefreshing,
        visibleSetsFlow,
        _configVersion
    ) { modules, isRefreshing, visibleSets, _ ->
        HomepageUiState(
            modules = modules,
            isRefreshing = isRefreshing,
            visibleSets = visibleSets,
            layoutMode = AppConfig.homepageLayoutMode,
            preloadMode = AppConfig.homepagePreload,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomepageUiState())

    init {
        // 跟踪所有启用了发现功能的书源（用于浏览书源模块列表），不自动同步模块
        viewModelScope.launch {
            appDb.bookSourceDao.flowEnabledExplore().collect { sources ->
                _bookSourcesCache.value = sources.associate {
                    it.bookSourceUrl to BookSourcePartLite(
                        url = it.bookSourceUrl,
                        name = it.bookSourceName,
                        group = it.bookSourceGroup,
                        exploreUrl = null,
                    )
                }
            }
        }

        // 跟踪所有订阅源名称
        viewModelScope.launch {
            appDb.rssSourceDao.flowAll().collect { sources ->
                _rssSourceNames.value = sources.associate { it.sourceUrl to it.sourceName }
            }
        }

        // 自动加载进入 Loading 状态的模块；同时监听 _tabSelection 确保分源Tab 切换立即触发加载
        viewModelScope.launch {
            combine(uiState, _tabSelection) { state, tabSelection ->
                state to tabSelection
            }.collect { (state, tabSelection) ->
                val fallbackUrls = state.visibleSets.map { it.sourceUrl }
                val loadSetIds = HomepageTabSelectionPolicy.resolveLoadSetIds(
                    layoutMode = state.layoutMode,
                    preloadMode = state.preloadMode,
                    tabSelection = tabSelection,
                    fallbackSetUrls = fallbackUrls,
                )
                val activeSetId = HomepageTabSelectionPolicy.resolveActiveSetId(
                    layoutMode = state.layoutMode,
                    tabSelection = tabSelection,
                    fallbackSetUrls = fallbackUrls,
                )
                val visibleIds = _visibleModuleIds.value
                state.modules.forEachIndexed { index, ui ->
                    if (ui.state !is ModuleLoadState.Loading) return@forEachIndexed
                    val eligible = loadSetIds == null ||
                        (ui.customSetId != null && ui.customSetId in loadSetIds)
                    if (!eligible) return@forEachIndexed
                    if (!dispatchedModuleIds.add(ui.globalId)) return@forEachIndexed

                    val priority = when {
                        state.layoutMode == 1 -> if (ui.customSetId == activeSetId) {
                            HomepageLoadScheduler.PRIORITY_VISIBLE
                        } else {
                            HomepageLoadScheduler.PRIORITY_PRELOAD
                        }
                        ui.globalId in visibleIds || (visibleIds.isEmpty() && index < 3) ->
                            HomepageLoadScheduler.PRIORITY_VISIBLE
                        else -> HomepageLoadScheduler.PRIORITY_PRELOAD
                    }
                    launch {
                        try {
                            if (loadScheduler.isActive(ui.globalId)) return@launch
                            val module = gateway.getById(ui.globalId)
                            if (module != null) {
                                loadModule(
                                    module = module,
                                    priority = priority,
                                    order = index,
                                    forceRefresh = false,
                                )
                            }
                        } finally {
                            dispatchedModuleIds.remove(ui.globalId)
                        }
                    }
                }
            }
        }

        // 跟踪主动刷新任务完成状态，并在全量/当前Tab 刷新完成后收起进度圈
        viewModelScope.launch {
            _pendingRefreshIds.collect { pending ->
                if (_isRefreshing.value && pending.isEmpty()) {
                    kotlinx.coroutines.delay(250)
                    if (_pendingRefreshIds.value.isEmpty()) {
                        _isRefreshing.value = false
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        autoAssembleJob?.cancel()
        loadScheduler.close()
    }

    // ==================== 模块加载 ====================

    private suspend fun syncModulesFromSource(source: BookSource): SyncSourceResult {
        val json = source.homepageModules
        if (json.isNullOrBlank()) return SyncSourceResult(hasConfig = false, moduleCount = 0)
        val parsedDefs = parseModuleDefs(source.bookSourceUrl, json)
        if (parsedDefs.isEmpty()) return SyncSourceResult(hasConfig = false, moduleCount = 0)
        ensureSetForSource(source.bookSourceUrl, source.bookSourceName)
        val newHash = jsonHash(json)

        val existingModules = gateway.flowBySource(source.bookSourceUrl).first()
        val existingById = existingModules.associateBy { it.id }
        val parsedIds = parsedDefs.map { it.globalId }.toSet()

        val toUpsert = mutableListOf<ModuleItem>()
        for (i in parsedDefs.indices) {
            val def = parsedDefs[i]
            val existing = existingById[def.globalId]
            if (existing != null) {
                if (existing.isUserCreated) continue
                if (existing.sourceJsonHash == newHash) continue
                toUpsert.add(
                    existing.copy(
                        type = def.type, title = def.title, args = def.args, url = def.url,
                        sourceJsonHash = newHash, syncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                toUpsert.add(
                    ModuleItem(
                        id = def.globalId,
                        sourceUrl = source.bookSourceUrl,
                        moduleKey = def.key,
                        type = def.type,
                        title = def.title,
                        args = def.args,
                        url = def.url,
                        isEnabled = true,
                        customSetId = "src_${source.bookSourceUrl}",
                        sortOrder = i,
                        sourceJsonHash = newHash,
                        syncedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        if (toUpsert.isNotEmpty()) gateway.upsertAll(toUpsert)
        if (parsedIds.isNotEmpty()) gateway.deleteStale(source.bookSourceUrl, parsedIds.toList())
        return SyncSourceResult(hasConfig = true, moduleCount = parsedDefs.size)
    }

    private suspend fun loadModule(
        module: ModuleItem,
        priority: Int = HomepageLoadScheduler.PRIORITY_VISIBLE,
        order: Int = 0,
        forceRefresh: Boolean = false,
    ) {
        if (module.type == HomepageModuleType.ButtonGroup.key) {
            val cacheKey = HomepageCachePolicy.buttonsKey(module.id, module.sourceUrl, module.args)
            val cacheHit = if (!forceRefresh) {
                withContext(Dispatchers.IO) { cacheStore.readButtons(cacheKey) }
            } else {
                null
            }
            if (cacheHit != null) {
                _moduleContentStates.update {
                    it + (module.id to ModuleLoadState.Buttons(cacheHit.data.kinds))
                }
                if (cacheHit.isFresh) {
                    finishRefreshTrack(module.id)
                    return
                }
            }
            val effectivePriority = if (cacheHit != null && !forceRefresh) {
                HomepageLoadScheduler.PRIORITY_REVALIDATE
            } else {
                priority
            }
            val keepExisting = cacheHit != null ||
                _moduleContentStates.value[module.id] is ModuleLoadState.Buttons
            loadScheduler.enqueue(
                key = module.id,
                sourceUrl = module.sourceUrl,
                priority = effectivePriority,
                order = order,
                onFinally = { finishRefreshTrack(module.id) },
            ) {
                kotlin.runCatching {
                    val selectedTitles = parseKindTitlesFromArgs(module.args)
                    if (selectedTitles.isNullOrEmpty()) {
                        emptyList()
                    } else {
                        val rssSource = withContext(Dispatchers.IO) {
                            appDb.rssSourceDao.getByKey(module.sourceUrl)
                        }
                        if (rssSource != null) {
                            val allKinds = rssSource.sortUrls().map { (title, url) ->
                                ExploreKind(title = title, url = url)
                            }
                            selectedTitles.mapNotNull { t -> allKinds.find { it.title == t } }
                        } else {
                            val source = withContext(Dispatchers.IO) {
                                appDb.bookSourceDao.getBookSource(module.sourceUrl)
                            } ?: throw Exception("Source not found")
                            val allKinds = withContext(Dispatchers.IO) { source.exploreKinds() }
                            selectedTitles.mapNotNull { t -> allKinds.find { it.title == t } }
                        }
                    }
                }.onSuccess { kinds ->
                    withContext(Dispatchers.IO) { cacheStore.writeButtons(cacheKey, kinds) }
                    _moduleContentStates.update {
                        it + (module.id to ModuleLoadState.Buttons(kinds))
                    }
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    if (!keepExisting) {
                        _moduleContentStates.update {
                            it + (module.id to ModuleLoadState.Error(e.stackTraceStr))
                        }
                    } else if (forceRefresh) {
                        _effects.tryEmit(
                            HomepageEffect.ShowSnackbar(
                                e.localizedMessage ?: appCtx.getString(R.string.homepage_load_failed)
                            )
                        )
                    }
                }
            }
            return
        }

        // 多分类 tab 模式：args 包含 [{t:标题, u:URL}]（1~N 个），按钮组以外所有类型通用
        val rankingCategoryPairs = parseRankingCategories(module.args)
        if (!rankingCategoryPairs.isNullOrEmpty()) {
            val rssSource = withContext(Dispatchers.IO) {
                appDb.rssSourceDao.getByKey(module.sourceUrl)
            }
            val existingState = _moduleContentStates.value[module.id] as? ModuleLoadState.RankingTabs
            val selectedIndex = existingState?.selectedIndex
                ?.coerceIn(0, rankingCategoryPairs.lastIndex) ?: 0

            val cacheHits = if (!forceRefresh) {
                withContext(Dispatchers.IO) {
                    rankingCategoryPairs.mapIndexed { idx, (_, url) ->
                        val tabKey = HomepageCachePolicy.rankingTabKey(
                            module.id, module.sourceUrl, idx, url
                        )
                        cacheStore.readBooks(tabKey)
                    }
                }
            } else {
                List(rankingCategoryPairs.size) { null }
            }

            val initialTabs = rankingCategoryPairs.mapIndexed { idx, (title, url) ->
                val hit = cacheHits[idx]
                val existingTab = existingState?.tabs?.getOrNull(idx)
                when {
                    hit != null -> RankingTabData(
                        title = title,
                        exploreUrl = url.ifBlank { null },
                        books = mapShelfItems(hit.data.books),
                        page = 1,
                        hasMore = hit.data.hasMore,
                        isLoadingMore = false,
                        isRevalidating = !hit.isFresh,
                    )
                    existingTab?.books != null -> existingTab.copy(
                        title = title,
                        exploreUrl = url.ifBlank { null },
                        isRevalidating = forceRefresh && idx == selectedIndex,
                    )
                    else -> RankingTabData(
                        title = title,
                        exploreUrl = url.ifBlank { null },
                        page = 1,
                        hasMore = true,
                        isLoadingMore = false,
                    )
                }
            }
            _moduleContentStates.update {
                it + (module.id to ModuleLoadState.RankingTabs(initialTabs, selectedIndex))
            }

            val (activeTitle, activeUrl) = rankingCategoryPairs[selectedIndex]
            val activeHit = cacheHits[selectedIndex]
            if (!forceRefresh && activeHit?.isFresh == true) {
                finishRefreshTrack(module.id)
                return
            }
            val activePriority = if (activeHit != null && !forceRefresh) {
                HomepageLoadScheduler.PRIORITY_REVALIDATE
            } else {
                priority
            }
            val hasExistingActive = activeHit != null ||
                existingState?.tabs?.getOrNull(selectedIndex)?.books != null
            loadRankingTab(
                moduleId = module.id,
                sourceUrl = module.sourceUrl,
                rssSource = rssSource,
                index = selectedIndex,
                title = activeTitle,
                url = activeUrl,
                page = 1,
                priority = activePriority,
                order = order,
                keepExistingOnError = hasExistingActive,
                showFailureToast = forceRefresh && hasExistingActive,
                onDone = { finishRefreshTrack(module.id) },
            )
            return
        }

        // 单 URL 模块：先读 SWR 磁盘快照
        val cacheKey = HomepageCachePolicy.moduleKey(module.id, module.sourceUrl, module.url)
        val cacheHit = if (!forceRefresh) {
            withContext(Dispatchers.IO) { cacheStore.readBooks(cacheKey) }
        } else {
            null
        }
        if (cacheHit != null) {
            _moduleContentStates.update {
                it + (module.id to ModuleLoadState.Loaded(
                    books = mapShelfItems(cacheHit.data.books),
                    hasMore = cacheHit.data.hasMore,
                    page = 1,
                    isLoadingMore = false,
                    isRevalidating = !cacheHit.isFresh,
                ))
            }
            if (cacheHit.isFresh) {
                finishRefreshTrack(module.id)
                return
            }
        }

        val existingLoaded = _moduleContentStates.value[module.id] as? ModuleLoadState.Loaded
        val keepExisting = cacheHit != null || existingLoaded != null
        val effectivePriority = if (cacheHit != null && !forceRefresh) {
            HomepageLoadScheduler.PRIORITY_REVALIDATE
        } else {
            priority
        }
        loadScheduler.enqueue(
            key = module.id,
            sourceUrl = module.sourceUrl,
            priority = effectivePriority,
            order = order,
            onFinally = { finishRefreshTrack(module.id) },
        ) {
            kotlin.runCatching {
                fetchSingleModuleBooks(module)
            }.onSuccess { (books, hasMore) ->
                withContext(Dispatchers.IO) { cacheStore.writeBooks(cacheKey, books, hasMore) }
                _moduleContentStates.update {
                    it + (module.id to ModuleLoadState.Loaded(
                        books = mapShelfItems(books),
                        hasMore = hasMore,
                        page = 1,
                        isLoadingMore = false,
                        isRevalidating = false,
                    ))
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                if (keepExisting) {
                    _moduleContentStates.update { states ->
                        val prev = states[module.id] as? ModuleLoadState.Loaded ?: return@update states
                        states + (module.id to prev.copy(isRevalidating = false))
                    }
                    if (forceRefresh) {
                        _effects.tryEmit(
                            HomepageEffect.ShowSnackbar(
                                e.localizedMessage ?: appCtx.getString(R.string.homepage_load_failed)
                            )
                        )
                    }
                } else {
                    _moduleContentStates.update {
                        it + (module.id to ModuleLoadState.Error(e.stackTraceStr))
                    }
                }
            }
        }
    }

    private suspend fun fetchSingleModuleBooks(module: ModuleItem): Pair<List<SearchBook>, Boolean> {
        val rssSource = withContext(Dispatchers.IO) {
            appDb.rssSourceDao.getByKey(module.sourceUrl)
        }
        return if (rssSource != null) {
            val sortUrl = module.url ?: rssSource.sourceUrl
            val sortName = module.title.ifBlank { rssSource.sourceName }
            val (articles, _) = withContext(Dispatchers.IO) {
                Rss.getArticlesAwait(sortName, sortUrl, rssSource, page = 1)
            }
            val books = articles.map { article ->
                val introText = article.description?.let {
                    Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                }
                SearchBook(
                    bookUrl = article.link,
                    origin = rssSource.sourceUrl,
                    originName = rssSource.sourceName,
                    name = article.title,
                    coverUrl = article.image,
                    intro = introText,
                    author = rssSource.sourceName,
                    latestChapterTitle = article.pubDate,
                )
            }
            books to false
        } else {
            exploreBooks(
                sourceUrl = module.sourceUrl,
                moduleUrl = module.url,
                page = 1
            )
        }
    }

    private fun mapShelfItems(books: List<SearchBook>): List<HomepageBookItemUi> {
        return books.map { book ->
            HomepageBookItemUi(
                book = book,
                shelfState = BookshelfMatcher.getState(book.name, book.author, book.bookUrl)
            )
        }
    }

    private fun finishRefreshTrack(moduleId: String) {
        _pendingRefreshIds.update { ids ->
            if (moduleId in ids) ids - moduleId else ids
        }
    }

    private suspend fun exploreBooks(
        sourceUrl: String,
        moduleUrl: String?,
        page: Int
    ): Pair<List<SearchBook>, Boolean> = withContext(Dispatchers.IO) {
        val source = appDb.bookSourceDao.getBookSource(sourceUrl)
            ?: throw Exception("Source not found: $sourceUrl")
        val url = moduleUrl ?: source.exploreUrl
            ?: throw Exception("No explore url: $sourceUrl")
        val books = WebBook.exploreBookAwait(source, url, page)
        books to books.isNotEmpty()
    }

    fun loadMoreModule(globalId: String) {
        val currentState = _moduleContentStates.value[globalId] as? ModuleLoadState.Loaded ?: return
        if (currentState.isLoadingMore || !currentState.hasMore) return
        val nextPage = currentState.page + 1
        _moduleContentStates.update { it + (globalId to currentState.copy(isLoadingMore = true)) }
        viewModelScope.launch {
            kotlin.runCatching {
                val module = gateway.getById(globalId) ?: throw Exception("Module not found")
                // 有 args 的走 tab 流的 loadMoreRankingTab，到这里 args 为空，直接用模块 URL
                exploreBooks(
                    sourceUrl = module.sourceUrl,
                    moduleUrl = module.url,
                    page = nextPage
                )
            }.onSuccess { (books, hasMore) ->
                _moduleContentStates.update { states ->
                    val lastState = states[globalId] as? ModuleLoadState.Loaded ?: return@update states
                    val existingUrls = lastState.books.map { it.book.bookUrl }.toSet()
                    val deduped = books.filter { it.bookUrl !in existingUrls }.map { book ->
                        HomepageBookItemUi(
                            book = book,
                            shelfState = BookshelfMatcher.getState(
                                book.name, book.author, book.bookUrl
                            )
                        )
                    }
                    val finalHasMore = if (deduped.isEmpty()) false else hasMore
                    states + (globalId to ModuleLoadState.Loaded(
                        books = lastState.books + deduped,
                        hasMore = finalHasMore,
                        isLoadingMore = false,
                        page = nextPage
                    ))
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _moduleContentStates.update { states ->
                    val lastState = states[globalId] as? ModuleLoadState.Loaded ?: return@update states
                    states + (globalId to lastState.copy(isLoadingMore = false))
                }
                _effects.tryEmit(HomepageEffect.ShowSnackbar(appCtx.getString(R.string.ui_main_failed_load_more, e.message)))
            }
        }
    }

    /** 切换排行榜 Tab 时优先命中 SWR 缓存，未命中或过期再按需加载 */
    fun selectRankingTab(globalId: String, index: Int) {
        val prevState = _moduleContentStates.value[globalId] as? ModuleLoadState.RankingTabs ?: return
        if (index !in prevState.tabs.indices) return
        _moduleContentStates.update { states ->
            val current = states[globalId] as? ModuleLoadState.RankingTabs ?: return@update states
            states + (globalId to current.copy(selectedIndex = index))
        }
        val tab = prevState.tabs.getOrNull(index) ?: return
        if (tab.books != null && tab.errorMessage == null && !tab.isRevalidating) return

        val tabDispatchKey = "${globalId}_tab_$index"
        if (!dispatchedTabKeys.add(tabDispatchKey)) return

        viewModelScope.launch {
            try {
                if (loadScheduler.isActive(tabDispatchKey)) return@launch
                val module = gateway.getById(globalId) ?: return@launch
                val rssSource = withContext(Dispatchers.IO) {
                    appDb.rssSourceDao.getByKey(module.sourceUrl)
                }
                val state = _moduleContentStates.value[globalId] as? ModuleLoadState.RankingTabs
                    ?: return@launch
                val currentTab = state.tabs.getOrNull(index) ?: return@launch
                if (currentTab.books != null || currentTab.errorMessage != null) return@launch

                val tabUrl = currentTab.exploreUrl ?: ""
                val tabCacheKey = HomepageCachePolicy.rankingTabKey(
                    globalId, module.sourceUrl, index, tabUrl
                )
                val cacheHit = withContext(Dispatchers.IO) { cacheStore.readBooks(tabCacheKey) }
                if (cacheHit != null) {
                    _moduleContentStates.update { states ->
                        val cur = states[globalId] as? ModuleLoadState.RankingTabs
                            ?: return@update states
                        val updatedTabs = cur.tabs.toMutableList()
                        val old = updatedTabs.getOrNull(index) ?: return@update states
                        updatedTabs[index] = old.copy(
                            books = mapShelfItems(cacheHit.data.books),
                            page = 1,
                            hasMore = cacheHit.data.hasMore,
                            isLoadingMore = false,
                            errorMessage = null,
                            isRevalidating = !cacheHit.isFresh,
                        )
                        states + (globalId to cur.copy(tabs = updatedTabs))
                    }
                    if (cacheHit.isFresh) return@launch
                }

                val priority = if (cacheHit != null) {
                    HomepageLoadScheduler.PRIORITY_REVALIDATE
                } else {
                    HomepageLoadScheduler.PRIORITY_VISIBLE
                }
                loadRankingTab(
                    moduleId = globalId,
                    sourceUrl = module.sourceUrl,
                    rssSource = rssSource,
                    index = index,
                    title = currentTab.title,
                    url = tabUrl,
                    page = 1,
                    priority = priority,
                    keepExistingOnError = cacheHit != null,
                )
            } finally {
                dispatchedTabKeys.remove(tabDispatchKey)
            }
        }
    }

    // ==================== 多分类 Tab 加载 ====================

    private suspend fun loadRankingTab(
        moduleId: String,
        sourceUrl: String,
        rssSource: RssSource?,
        index: Int,
        title: String,
        url: String,
        page: Int = 1,
        priority: Int = HomepageLoadScheduler.PRIORITY_VISIBLE,
        order: Int = 0,
        keepExistingOnError: Boolean = false,
        showFailureToast: Boolean = false,
        onDone: (() -> Unit)? = null,
    ) {
        val jobKey = "${moduleId}_tab_$index"
        val tabCacheKey = HomepageCachePolicy.rankingTabKey(moduleId, sourceUrl, index, url)
        loadScheduler.enqueue(
            key = jobKey,
            sourceUrl = sourceUrl,
            priority = priority,
            order = order,
            onFinally = onDone,
        ) {
            kotlin.runCatching {
                val books = if (rssSource != null) {
                    val (articles, _) = withContext(Dispatchers.IO) {
                        Rss.getArticlesAwait(
                            title.ifBlank { rssSource.sourceName }, url, rssSource, page = page
                        )
                    }
                    articles.map { article ->
                        SearchBook(
                            bookUrl = article.link,
                            origin = rssSource.sourceUrl,
                            originName = rssSource.sourceName,
                            name = article.title,
                            coverUrl = article.image,
                            intro = article.description?.let {
                                Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                            },
                            author = rssSource.sourceName,
                            latestChapterTitle = article.pubDate,
                        )
                    }
                } else {
                    exploreBooks(
                        sourceUrl = sourceUrl,
                        moduleUrl = url.ifBlank { null },
                        page = page
                    ).first
                }
                books to books.isNotEmpty()
            }.onSuccess { (books, hasMore) ->
                if (page == 1) {
                    withContext(Dispatchers.IO) {
                        cacheStore.writeBooks(tabCacheKey, books, hasMore && rssSource == null)
                    }
                }
                val bookItems = mapShelfItems(books)
                _moduleContentStates.update { states ->
                    val current = states[moduleId] as? ModuleLoadState.RankingTabs
                        ?: return@update states
                    val updatedTabs = current.tabs.toMutableList()
                    val oldTab = updatedTabs.getOrNull(index) ?: return@update states
                    val newBooks = if (page == 1) {
                        bookItems
                    } else {
                        val existingUrls = oldTab.books?.map { it.book.bookUrl }?.toSet() ?: emptySet()
                        val deduped = bookItems.filter { it.book.bookUrl !in existingUrls }
                        (oldTab.books ?: emptyList()) + deduped
                    }
                    val finalHasMore = if (page == 1) hasMore else (hasMore && newBooks.size > (oldTab.books?.size ?: 0))
                    updatedTabs[index] = oldTab.copy(
                        books = newBooks,
                        page = page,
                        hasMore = finalHasMore,
                        isLoadingMore = false,
                        errorMessage = null,
                        isRevalidating = false,
                    )
                    states + (moduleId to current.copy(tabs = updatedTabs))
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _moduleContentStates.update { states ->
                    val current = states[moduleId] as? ModuleLoadState.RankingTabs
                        ?: return@update states
                    val updatedTabs = current.tabs.toMutableList()
                    val oldTab = updatedTabs.getOrNull(index) ?: return@update states
                    updatedTabs[index] = if (keepExistingOnError && oldTab.books != null) {
                        oldTab.copy(
                            isLoadingMore = false,
                            isRevalidating = false,
                        )
                    } else {
                        oldTab.copy(
                            errorMessage = e.stackTraceStr,
                            isLoadingMore = false,
                            isRevalidating = false,
                        )
                    }
                    states + (moduleId to current.copy(tabs = updatedTabs))
                }
                if (showFailureToast) {
                    _effects.tryEmit(
                        HomepageEffect.ShowSnackbar(
                            e.localizedMessage ?: appCtx.getString(R.string.homepage_load_failed)
                        )
                    )
                }
            }
        }
    }

    fun loadMoreRankingTab(globalId: String, tabIndex: Int) {
        val state = _moduleContentStates.value[globalId] as? ModuleLoadState.RankingTabs ?: return
        val tab = state.tabs.getOrNull(tabIndex) ?: return

        if (tab.isLoadingMore) return

        val nextPage = tab.page + 1
        val effectiveHasMore = if (!tab.hasMore && tab.books != null && tab.books.isNotEmpty()) {
            true
        } else {
            tab.hasMore
        }
        if (!effectiveHasMore) return

        _moduleContentStates.update { states ->
            val current = states[globalId] as? ModuleLoadState.RankingTabs ?: return@update states
            val updatedTabs = current.tabs.toMutableList()
            updatedTabs[tabIndex] = updatedTabs[tabIndex].copy(
                isLoadingMore = true,
                hasMore = true,
                errorMessage = null
            )
            states + (globalId to current.copy(tabs = updatedTabs))
        }

        viewModelScope.launch {
            val module = gateway.getById(globalId) ?: return@launch
            val rssSource = withContext(Dispatchers.IO) {
                appDb.rssSourceDao.getByKey(module.sourceUrl)
            }
            loadRankingTab(
                moduleId = globalId,
                sourceUrl = module.sourceUrl,
                rssSource = rssSource,
                index = tabIndex,
                title = tab.title,
                url = tab.exploreUrl ?: "",
                page = nextPage,
                priority = HomepageLoadScheduler.PRIORITY_VISIBLE,
                keepExistingOnError = true,
                showFailureToast = true,
            )
        }
    }

    /**
     * 刷新主页模块内容（支持按当前 Tab 局部刷新；已有内容时保留旧数据并后台拉新，不回退整页骨架屏）
     */
    fun onRefresh(targetSetUrl: String? = null) {
        viewModelScope.launch {
            val state = uiState.value
            val fallbackUrls = state.visibleSets.map { it.sourceUrl }
            val targetSetId = when {
                targetSetUrl != null -> HomepageTabSelectionPolicy.toSetId(targetSetUrl)
                state.layoutMode == 1 -> HomepageTabSelectionPolicy.resolveActiveSetId(
                    layoutMode = state.layoutMode,
                    tabSelection = _tabSelection.value,
                    fallbackSetUrls = fallbackUrls,
                )
                else -> null
            }
            val targetModules = state.modules.filter {
                targetSetId == null || it.customSetId == targetSetId
            }
            if (targetModules.isEmpty()) {
                _isRefreshing.value = false
                return@launch
            }
            _isRefreshing.value = true
            _pendingRefreshIds.value = targetModules.map { it.globalId }.toSet()

            targetModules.forEachIndexed { index, ui ->
                val module = gateway.getById(ui.globalId)
                if (module == null) {
                    finishRefreshTrack(ui.globalId)
                    return@forEachIndexed
                }
                if (ui.state is ModuleLoadState.Error) {
                    _moduleContentStates.update { it + (ui.globalId to ModuleLoadState.Loading) }
                }
                val visiblePriority = if (ui.globalId in _visibleModuleIds.value || index < 3) {
                    HomepageLoadScheduler.PRIORITY_VISIBLE
                } else {
                    HomepageLoadScheduler.PRIORITY_PRELOAD
                }
                loadModule(
                    module = module,
                    priority = visiblePriority,
                    order = index,
                    forceRefresh = true,
                )
            }
        }
    }

    // ==================== 布局模式与可见区追踪 ====================

    /** 设置主页布局模式（0: 混合列表, 1: 分源Tab） */
    fun setLayoutMode(mode: Int) {
        AppConfig.homepageLayoutMode = mode
        notifyConfigChanged()
    }

    /** 设置分源Tab 预加载模式（0: 仅当前集, 1: 当前集 + 相邻集） */
    fun setPreloadMode(mode: Int) {
        AppConfig.homepagePreload = mode
        notifyConfigChanged()
    }

    /**
     * 更新分源Tab 模式下当前选中的页索引与集 URL 列表（驱动响应式按需加载）。
     */
    fun updateCurrentTab(tabIndex: Int, setUrls: List<String>) {
        val next = TabSelectionState(tabIndex, setUrls)
        if (_tabSelection.value != next) {
            _tabSelection.value = next
        }
    }

    /** 上报当前可视区域模块 ID，动态提升队列中可视模块的加载优先级 */
    fun updateVisibleModuleIds(ids: Set<String>) {
        if (_visibleModuleIds.value == ids) return
        _visibleModuleIds.value = ids
        if (ids.isNotEmpty()) {
            viewModelScope.launch {
                loadScheduler.updateVisibleKeys(ids)
            }
        }
    }

    fun retryModule(globalId: String) {
        val currentState = _moduleContentStates.value[globalId]
        if (currentState is ModuleLoadState.RankingTabs) {
            retryRankingTab(globalId, currentState.selectedIndex)
            return
        }
        viewModelScope.launch {
            val module = gateway.getById(globalId) ?: return@launch
            _moduleContentStates.update { it + (globalId to ModuleLoadState.Loading) }
            loadModule(
                module = module,
                priority = HomepageLoadScheduler.PRIORITY_VISIBLE,
                order = 0,
                forceRefresh = true,
            )
        }
    }

    fun retryRankingTab(globalId: String, tabIndex: Int) {
        val state = _moduleContentStates.value[globalId] as? ModuleLoadState.RankingTabs ?: return
        val tab = state.tabs.getOrNull(tabIndex) ?: return
        _moduleContentStates.update { states ->
            val current = states[globalId] as? ModuleLoadState.RankingTabs ?: return@update states
            val updatedTabs = current.tabs.toMutableList()
            val old = updatedTabs.getOrNull(tabIndex) ?: return@update states
            updatedTabs[tabIndex] = old.copy(errorMessage = null, isRevalidating = false)
            states + (globalId to current.copy(tabs = updatedTabs))
        }
        viewModelScope.launch {
            val module = gateway.getById(globalId) ?: return@launch
            val rssSource = withContext(Dispatchers.IO) {
                appDb.rssSourceDao.getByKey(module.sourceUrl)
            }
            loadRankingTab(
                moduleId = globalId,
                sourceUrl = module.sourceUrl,
                rssSource = rssSource,
                index = tabIndex,
                title = tab.title,
                url = tab.exploreUrl ?: "",
                page = 1,
                priority = HomepageLoadScheduler.PRIORITY_VISIBLE,
                keepExistingOnError = false,
            )
        }
    }

    /** 确保书源对应的集存在（不存在则自动创建） */
    private suspend fun ensureSetForSource(sourceUrl: String, sourceName: String): String {
        val setId = "src_$sourceUrl"
        if (gateway.getCustomSetById(setId) == null) gateway.upsertCustomSet(
            CustomSetItem(id = setId, name = sourceName)
        )
        return setId
    }

    /** 确保订阅源对应的集存在 */
    private suspend fun ensureRssSetForSource(sourceUrl: String, sourceName: String): String {
        val setId = "rss_$sourceUrl"
        if (gateway.getCustomSetById(setId) == null) gateway.upsertCustomSet(
            CustomSetItem(id = setId, name = sourceName)
        )
        return setId
    }

    fun getCurrentBookShelfState(book: SearchBook): BookShelfState {
        return BookshelfMatcher.getState(
            name = book.name,
            author = book.author,
            bookUrl = book.bookUrl
        )
    }

    fun onAddToShelf(book: SearchBook) {
        execute {
            val b = book.toBook()
            b.removeType(BookType.notShelf)
            if (b.order == 0) b.order = appDb.bookDao.minOrder - 1
            appDb.bookDao.insert(b)
        }
    }

    fun onBookClick(book: SearchBook) {
        viewModelScope.launch(Dispatchers.IO) {
            // RSS 订阅源文章不保存搜索历史（searchBooks 表对书源有外键约束语义）
            if (!appDb.rssSourceDao.has(book.origin)) {
                appDb.searchBookDao.insert(book)
            }
        }
    }

    // ==================== 管理方法 ====================

    fun toggleSet(setUrl: String, visible: Boolean) {
        val hidden = hiddenSetUrls.toMutableSet()
        if (visible) hidden.remove(setUrl) else hidden.add(setUrl)
        saveHiddenSetUrls(hidden)
        notifyConfigChanged()
    }

    fun syncSourceModules(sourceUrl: String, onDone: ((SyncSourceResult) -> Unit)? = null) {
        viewModelScope.launch {
            val fullSource = withContext(Dispatchers.IO) {
                appDb.bookSourceDao.getBookSource(sourceUrl)
            }
            if (fullSource == null) {
                onDone?.invoke(SyncSourceResult(hasConfig = false, moduleCount = 0))
                return@launch
            }
            val result = syncModulesFromSource(fullSource)
            if (result.hasConfig) {
                notifyConfigChanged()
            }
            onDone?.invoke(result)
        }
    }

    private var autoAssembleJob: Job? = null

    fun isAutoAssembling(): Boolean = autoAssembleJob?.isActive == true

    fun cancelAutoAssembleStarterHomepage() {
        autoAssembleJob?.cancel()
        autoAssembleJob = null
    }

    /**
     * 一键装配起步首页（带总超时 18s 与可取消控制）：
     * 1. 优先同步已配置 homepageModules JSON 的启用发现书源；
     * 2. 不足时并发扫描前几个启用发现书源（或订阅源）的分类，生成开箱即用的推荐模块。
     */
    fun autoAssembleStarterHomepage(onDone: ((AutoSetupResult) -> Unit)? = null) {
        autoAssembleJob?.cancel()
        autoAssembleJob = viewModelScope.launch {
            var outcome = AutoSetupResult(AutoSetupStatus.NO_SOURCES)
            try {
                outcome = withTimeoutOrNull(18_000L) {
                    var addedCount = 0
                    val candidateParts = withContext(Dispatchers.IO) {
                        appDb.bookSourceDao.flowEnabledExplore().first()
                    }
                    val fullSources = withContext(Dispatchers.IO) {
                        candidateParts.take(12).mapNotNull { part ->
                            appDb.bookSourceDao.getBookSource(part.bookSourceUrl)
                        }
                    }
                    for (source in fullSources) {
                        if (!source.homepageModules.isNullOrBlank()) {
                            val result = syncModulesFromSource(source)
                            if (result.hasConfig) {
                                addedCount += result.moduleCount
                            }
                        }
                    }
                    val rssSources = if (addedCount == 0) {
                        withContext(Dispatchers.IO) {
                            appDb.rssSourceDao.flowAll().first().filter { it.enabled }
                        }
                    } else {
                        emptyList()
                    }
                    if (addedCount == 0 && fullSources.isEmpty() && rssSources.isEmpty()) {
                        return@withTimeoutOrNull AutoSetupResult(AutoSetupStatus.NO_SOURCES)
                    }
                    if (addedCount == 0) {
                        val (sets, modules) = withContext(Dispatchers.IO) {
                            HomepageModuleBuilder.buildStarterModules(
                                bookSources = fullSources,
                                rssSources = rssSources,
                            )
                        }
                        if (modules.isNotEmpty()) {
                            sets.forEach { set ->
                                if (gateway.getCustomSetById(set.id) == null) {
                                    gateway.upsertCustomSet(set)
                                }
                            }
                            gateway.upsertAll(modules)
                            addedCount = modules.size
                        }
                    }
                    if (addedCount > 0) {
                        notifyConfigChanged()
                        AutoSetupResult(AutoSetupStatus.ADDED, addedCount)
                    } else {
                        AutoSetupResult(AutoSetupStatus.NO_CATEGORIES)
                    }
                } ?: AutoSetupResult(AutoSetupStatus.NO_CATEGORIES)
            } catch (_: CancellationException) {
                outcome = AutoSetupResult(AutoSetupStatus.CANCELLED)
            } finally {
                autoAssembleJob = null
            }
            onDone?.invoke(outcome)
        }
    }

    /** 从源详情勾选分类后一步添加到首页（免去多层弹窗与 JSON 表单） */
    fun quickAddExploreModule(
        sourceUrl: String,
        sourceName: String,
        sourceType: String,
        setId: String?,
        typeKey: String,
        kinds: List<ExploreKind>,
        customTitle: String? = null,
    ) {
        if (kinds.isEmpty()) return
        viewModelScope.launch {
            val effectiveSetId = setId ?: if (sourceType == "rss") {
                ensureRssSetForSource(sourceUrl, sourceName)
            } else {
                ensureSetForSource(sourceUrl, sourceName)
            }
            val def = HomepageModuleBuilder.buildExploreModuleDef(
                sourceUrl = sourceUrl,
                typeKey = typeKey,
                kinds = kinds,
                customTitle = customTitle,
            )
            val existingCount = allModulesCache.value.count { it.customSetId == effectiveSetId }
            val globalId = ModuleDef.globalIdOf(sourceUrl, def.key, effectiveSetId)
            gateway.upsertAll(
                listOf(
                    ModuleItem(
                        id = globalId,
                        sourceUrl = sourceUrl,
                        moduleKey = def.key,
                        type = def.type,
                        title = def.title,
                        args = def.args,
                        url = def.url,
                        isEnabled = true,
                        customSetId = effectiveSetId,
                        sortOrder = existingCount,
                        isUserCreated = true,
                        syncedAt = System.currentTimeMillis(),
                    )
                )
            )
            notifyConfigChanged()
        }
    }

    fun toggleModule(moduleId: String, enabled: Boolean) {
        viewModelScope.launch {
            gateway.setEnabled(moduleId, enabled)
            notifyConfigChanged()
        }
    }

    private fun notifyConfigChanged() {
        _configVersion.update { it + 1 }
    }

    /** 将已存在的模块加入指定集（或启用），不存在则按定义新建 */
    fun joinModule(sourceUrl: String, setId: String?, def: ModuleDef) {
        viewModelScope.launch {
            val effectiveSetId = setId ?: run {
                val source = _bookSourcesCache.value[sourceUrl]
                ensureSetForSource(sourceUrl, source?.name ?: sourceUrl)
            }
            val globalId = ModuleDef.globalIdOf(sourceUrl, def.key, effectiveSetId)
            val existing = gateway.getById(globalId)
            if (existing != null) {
                gateway.setEnabled(globalId, true)
                gateway.setCustomSetId(globalId, effectiveSetId)
            } else {
                gateway.upsertAll(listOf(
                    ModuleItem(
                        id = globalId,
                        sourceUrl = sourceUrl,
                        moduleKey = def.key,
                        type = def.type,
                        title = def.title,
                        args = def.args,
                        url = def.url,
                        isEnabled = true,
                        customSetId = effectiveSetId,
                        sortOrder = allModulesCache.value.count { it.customSetId == effectiveSetId },
                        isUserCreated = true,
                        layoutConfig = def.layoutConfig,
                        syncedAt = System.currentTimeMillis()
                    )
                ))
            }
            notifyConfigChanged()
        }
    }

    /** 手动添加自定义模块；key 空白时按时间戳生成 */
    fun addCustomModule(sourceUrl: String, setId: String?, def: ModuleDef) {
        viewModelScope.launch {
            val effectiveSetId = setId ?: ensureSetForSource(
                sourceUrl, _bookSourcesCache.value[sourceUrl]?.name ?: sourceUrl
            )
            val key = def.key.ifBlank { "custom_${System.currentTimeMillis()}" }
            val globalId = ModuleDef.globalIdOf(sourceUrl, key, effectiveSetId)
            gateway.upsertAll(listOf(
                ModuleItem(
                    id = globalId,
                    sourceUrl = sourceUrl,
                    moduleKey = key,
                    type = def.type,
                    title = def.title,
                    args = def.args,
                    url = def.url,
                    isEnabled = true,
                    customSetId = effectiveSetId,
                    sortOrder = allModulesCache.value.count { it.customSetId == effectiveSetId },
                    isUserCreated = true,
                    layoutConfig = def.layoutConfig,
                    syncedAt = System.currentTimeMillis()
                )
            ))
            notifyConfigChanged()
        }
    }

    /** 获取书源发现分类（支持 @js:/<js> 动态分类） */
    suspend fun getExploreKinds(sourceUrl: String): List<ExploreKind> {
        return runCatching {
            // 查库也要放 IO：调用方在主线程 launch，DAO 查询放在 withContext 之前等于没生效
            val source = withContext(Dispatchers.IO) {
                appDb.bookSourceDao.getBookSource(sourceUrl)
            } ?: return emptyList()
            withContext(Dispatchers.IO) { source.exploreKinds() }
        }.getOrDefault(emptyList())
    }

    /** 获取订阅源分类列表 */
    suspend fun getRssKinds(sourceUrl: String): List<Pair<String, String>> {
        return runCatching {
            // 同上：查库必须切到 IO 线程
            val rssSource = withContext(Dispatchers.IO) {
                appDb.rssSourceDao.getByKey(sourceUrl)
            } ?: return emptyList()
            withContext(Dispatchers.IO) { rssSource.sortUrls() }
        }.getOrDefault(emptyList()).ifEmpty { listOf("" to sourceUrl) }
    }

    /** 为订阅源添加自定义模块 */
    fun addRssCustomModule(sourceUrl: String, setId: String?, def: ModuleDef, sourceName: String) {
        viewModelScope.launch {
            val effectiveSetId = setId ?: ensureRssSetForSource(sourceUrl, sourceName)
            val key = def.key.ifBlank { "rss_${System.currentTimeMillis()}" }
            val globalId = ModuleDef.globalIdOf(sourceUrl, key, effectiveSetId)
            gateway.upsertAll(listOf(
                ModuleItem(
                    id = globalId,
                    sourceUrl = sourceUrl,
                    moduleKey = key,
                    type = def.type,
                    title = def.title,
                    args = def.args,
                    url = def.url,
                    isEnabled = true,
                    customSetId = effectiveSetId,
                    sortOrder = allModulesCache.value.count { it.customSetId == effectiveSetId },
                    isUserCreated = true,
                    layoutConfig = def.layoutConfig,
                    syncedAt = System.currentTimeMillis()
                )
            ))
            notifyConfigChanged()
        }
    }

    /**
     * 编辑模块：只更新 type/url/args/layoutConfig；无限流模块每集仅允许一个；
     * 标题与原定义不同时写入 customTitle 以保留用户改名。
     */
    fun updateModule(globalId: String, def: ModuleDef) {
        viewModelScope.launch {
            val existing = gateway.getById(globalId) ?: return@launch
            if (isInfinite(def.type) && !isInfinite(existing.type)) {
                val siblings = allModulesCache.value.filter {
                    it.customSetId == existing.customSetId && it.id != existing.id
                }
                if (siblings.any { isInfinite(it.type) }) {
                    _effects.tryEmit(HomepageEffect.ShowSnackbar(appCtx.getString(R.string.ui_main_each_collection_can_contain_only)))
                    return@launch
                }
            }
            val newCustomTitle = if (def.title != existing.title) {
                if (def.title.isBlank()) null else def.title
            } else {
                existing.customTitle
            }
            gateway.upsertAll(listOf(
                existing.copy(
                    type = def.type,
                    url = def.url,
                    args = def.args,
                    layoutConfig = def.layoutConfig,
                    customTitle = newCustomTitle,
                )
            ))
            val contentChanged = existing.type != def.type ||
                existing.url != def.url ||
                existing.args != def.args
            if (contentChanged) {
                invalidateModuleCache(existing)
                loadScheduler.cancel(globalId)
                _moduleContentStates.update { it - globalId }
            }
            notifyConfigChanged()
        }
    }

    /**
     * 删除模块：源集模块连带删除所有自定义集中的副本，自定义集副本只删自身。
     */
    fun deleteModule(globalId: String) {
        viewModelScope.launch {
            val module = gateway.getById(globalId) ?: return@launch
            invalidateModuleCache(module)
            loadScheduler.cancel(globalId)
            val isSourceSet = module.customSetId?.startsWith("src_") == true
                    || module.customSetId?.startsWith("rss_") == true
            if (isSourceSet) {
                gateway.deleteBySourceAndKey(module.sourceUrl, module.moduleKey)
            } else {
                gateway.delete(globalId)
            }
            _moduleContentStates.update { states ->
                states.filterKeys { key ->
                    key != globalId
                }
            }
            notifyConfigChanged()
        }
    }

    private suspend fun invalidateModuleCache(module: ModuleItem) {
        withContext(Dispatchers.IO) {
            cacheStore.invalidate(
                HomepageCachePolicy.moduleKey(module.id, module.sourceUrl, module.url)
            )
            cacheStore.invalidate(
                HomepageCachePolicy.buttonsKey(module.id, module.sourceUrl, module.args)
            )
            parseRankingCategories(module.args)?.forEachIndexed { idx, (_, url) ->
                cacheStore.invalidate(
                    HomepageCachePolicy.rankingTabKey(module.id, module.sourceUrl, idx, url)
                )
            }
        }
    }

    fun reorderModules(orderedIds: List<String>) {
        viewModelScope.launch {
            gateway.batchSetSortOrders(
                orderedIds.mapIndexed { index, id -> id to index }.toMap()
            )
            notifyConfigChanged()
        }
    }

    fun reorderCustomSets(orderedUrls: List<String>) {
        viewModelScope.launch {
            val orders = orderedUrls.mapNotNull { url ->
                if (isCustomSetUrl(url)) customSetIdFromUrl(url) else null
            }.mapIndexed { index, id -> id to index }
            gateway.batchSetCustomSetSortOrders(orders.toMap())
            notifyConfigChanged()
        }
    }

    fun setCustomSetTitle(moduleId: String, title: String?) {
        viewModelScope.launch {
            gateway.setCustomSetTitle(moduleId, title)
            notifyConfigChanged()
        }
    }

    fun createCustomSet(name: String) {
        viewModelScope.launch {
            gateway.createCustomSet(name)
            notifyConfigChanged()
        }
    }

    fun renameCustomSet(id: String, name: String) {
        viewModelScope.launch {
            gateway.renameCustomSet(id, name)
            notifyConfigChanged()
        }
    }

    /**
     * 删除集：源集（src_/rss_）连集内模块（含各集副本）一并删除；
     * 自定义集只删除属于该集的模块。
     */
    fun deleteCustomSet(id: String) {
        viewModelScope.launch {
            val isSourceSet = id.startsWith("src_") || id.startsWith("rss_")
            if (isSourceSet) {
                val sourceUrl = id.removePrefix("src_").removePrefix("rss_")
                val modules = allModulesCache.value.filter { it.sourceUrl == sourceUrl }
                val keys = modules.map { it.moduleKey }.distinct()
                for (key in keys) {
                    gateway.deleteBySourceAndKey(sourceUrl, key)
                }
                _moduleContentStates.update { states ->
                    states.filterKeys { key ->
                        modules.none { it.id == key }
                    }
                }
                gateway.deleteCustomSet(id)
            } else {
                gateway.deleteCustomSet(id)
            }
            notifyConfigChanged()
        }
    }

    /**
     * 把模块分配到自定义集：目标集已有该模块则仅启用，否则创建副本；
     * 移除（customSetId 为 null）时，源集模块禁用，自定义集副本删除。
     */
    fun assignModuleToCustomSet(moduleId: String, customSetId: String?) {
        viewModelScope.launch {
            val existing = gateway.getById(moduleId) ?: return@launch
            if (customSetId == null) {
                val isSourceSet = existing.customSetId?.startsWith("src_") == true
                        || existing.customSetId?.startsWith("rss_") == true
                if (isSourceSet) {
                    gateway.setEnabled(moduleId, false)
                } else {
                    gateway.delete(moduleId)
                }
            } else {
                val newId = ModuleDef.globalIdOf(
                    existing.sourceUrl, existing.moduleKey, customSetId
                )
                val target = gateway.getById(newId)
                if (target != null) {
                    gateway.setEnabled(newId, true)
                } else {
                    gateway.upsertAll(listOf(
                        existing.copy(
                            id = newId,
                            customSetId = customSetId,
                            isEnabled = true,
                        )
                    ))
                }
            }
            notifyConfigChanged()
        }
    }

    // ==================== args 解析 ====================

    /** 解析多分类 tab 的 args：[{t:标题, u:URL}]；1~N 个都按 tab 模式（单分类即单 tab） */
    private fun parseRankingCategories(args: String?): List<Pair<String, String>>? =
        HomepageModuleBuilder.parseCategories(args)

    /** 解析按钮组 args 的分类标题，兼容新 [{t,u}] 与旧 ["title"] 两种格式 */
    private fun parseKindTitlesFromArgs(args: String?): List<String>? =
        HomepageModuleBuilder.parseKindTitles(args)
}

/** 书源轻量缓存 DTO：避免大量书源时持有完整 BookSource */
data class BookSourcePartLite(
    val url: String,
    val name: String,
    val group: String?,
    val exploreUrl: String?,
)
