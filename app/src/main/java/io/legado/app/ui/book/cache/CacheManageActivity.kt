package io.legado.app.ui.book.cache

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.R as AppCompatR
import androidx.appcompat.widget.SearchView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivityCacheManageBinding
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.cache.CacheCoordinator
import io.legado.app.help.cache.CacheLifecycle
import io.legado.app.help.cache.CachePhase
import io.legado.app.help.cache.CacheSubmission
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.cloud.S3ContainerScope
import io.legado.app.lib.dialogs.AndroidAlertBuilder
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.gone
import io.legado.app.utils.cnCompare
import io.legado.app.utils.applyTint
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CacheManageActivity :
    VMBaseActivity<ActivityCacheManageBinding, CacheManageViewModel>(),
    CacheChapterDialog.Callback {

    companion object {
        const val EXTRA_INITIAL_SEARCH_KEY = "initialSearchKey"
    }

    override val binding by viewBinding(ActivityCacheManageBinding::inflate)
    override val viewModel by viewModels<CacheManageViewModel>()

    private var audioTaskReloadJob: Job? = null
    private var lastMissingTaskReloadAt = 0L
    private val handledTerminalTaskReloads = hashSetOf<String>()
    private var cloudContainerId: String? = null
    private var containerMenuItem: MenuItem? = null
    private var sortMenuItem: MenuItem? = null
    private var searchMenuItem: MenuItem? = null
    private var rawItems: List<CacheBookItem> = emptyList()
    private var searchKey: String = ""
    private var sortMode: CacheManageSortMode = CacheManageSortMode.RECENT

    private var screenMode by mutableStateOf(CacheManageMode.BOOK)
    private var screenItems by mutableStateOf<List<CacheBookItem>>(emptyList())
    private var screenSummary by mutableStateOf<CacheSummary?>(null)
    private var screenLoading by mutableStateOf(false)
    private var audioTaskStateMap by mutableStateOf<Map<String, AudioCacheTaskState>>(emptyMap())
    private var webDavTaskStateMap by mutableStateOf<Map<String, WebDavTaskState>>(emptyMap())

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        observeData()
        observeTasks()
        val initialSearchKey = intent.getStringExtra(EXTRA_INITIAL_SEARCH_KEY).orEmpty().trim()
        if (initialSearchKey.isNotBlank()) {
            searchKey = initialSearchKey
        }
        viewModel.load(CacheManageMode.BOOK)
    }

    override fun onResume() {
        super.onResume()
        invalidateOptionsMenu()
    }

    private fun initView() = binding.run {
        composeView.setContent {
            CacheManageScreen(
                mode = screenMode,
                items = screenItems,
                summary = screenSummary,
                loading = screenLoading,
                audioTaskStates = audioTaskStateMap,
                webDavTaskStates = webDavTaskStateMap,
                onModeChange = ::switchMode,
                onOpenChapters = ::openChapters,
                onUpload = ::upload,
                onRestore = ::restoreToBookshelf,
                onDelete = ::deleteBookCache,
                onStopAudio = ::stopAudioCache,
                onOpenReviews = ::openReviewSnapshots,
                onSelectSource = ::selectSource,
                onDownload = ::download,
                onSelectSyncAction = ::selectSyncAction,
                onUploadAll = ::uploadAll,
                onDeleteAll = ::deleteAll
            )
        }
        updateSortButton()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        searchMenuItem = menu.add(0, MENU_SEARCH, 0, R.string.cache_manage_search_book).apply {
            setIcon(R.drawable.ic_search)
            actionView = SearchView(this@CacheManageActivity).also(::setupSearchView)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
            setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    updateSearchKey("")
                    return true
                }
            })
        }
        sortMenuItem = menu.add(0, MENU_SORT, 1, R.string.cache_manage_sort_title).apply {
            setIcon(R.drawable.ic_baseline_sort_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        containerMenuItem = menu.add(0, MENU_CONTAINER, 2, R.string.s3_bucket).apply {
            setIcon(R.drawable.ic_outline_cloud_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.add(0, MENU_BOOK_CACHE_EXPORT, 3, R.string.book_cache_export_title)
        updateSortButton()
        updateContainerMenu()
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_BOOK_CACHE_EXPORT) {
            startActivity<BookCacheExportActivity>()
            return true
        }
        if (item.itemId == MENU_SORT) {
            showSortSelector()
            return true
        }
        if (item.itemId == MENU_CONTAINER) {
            showContainerSelector()
            return true
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun setupSearchView(view: SearchView) {
        view.applyTint(primaryTextColor)
        view.queryHint = getString(R.string.cache_manage_search_book)
        view.isSubmitButtonEnabled = false
        view.maxWidth = Int.MAX_VALUE
        hideSearchActionButtons(view)
        view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                updateSearchKey(query.orEmpty())
                view.clearFocus()
                view.post { hideSearchActionButtons(view) }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                updateSearchKey(newText.orEmpty())
                view.post { hideSearchActionButtons(view) }
                return true
            }
        })
    }

    private fun hideSearchActionButtons(view: SearchView) {
        view.findViewById<View?>(AppCompatR.id.search_close_btn)?.gone()
        view.findViewById<View?>(AppCompatR.id.search_go_btn)?.gone()
        view.findViewById<View?>(AppCompatR.id.search_voice_btn)?.gone()
    }

    private fun updateContainerMenu() {
        val containers = AppCloudStorage.listContainers().filter { it.enabled }
        val item = containerMenuItem ?: return
        if (AppCloudStorage.type != CloudStorageType.S3) {
            cloudContainerId = containers.firstOrNull()?.id
            item.isVisible = false
            return
        }
        cloudContainerId = AppCloudStorage.selectedContainer(S3ContainerScope.CACHE)?.id
            ?: containers.firstOrNull()?.id
        item.isVisible = true
        item.title = containers.firstOrNull { it.id == cloudContainerId }
            ?.let(AppCloudStorage::containerDisplayLabel)
            ?: getString(R.string.s3_bucket)
    }

    private fun showContainerSelector() {
        lifecycleScope.launch {
            val containers = withContext(Dispatchers.IO) {
                AppCloudStorage.listContainers().filter { it.enabled }
            }
            if (containers.isEmpty()) {
                toastOnUi(R.string.cloud_storage_config_required)
                return@launch
            }
            val selected = cloudContainerId ?: AppCloudStorage.selectedContainer(S3ContainerScope.CACHE)?.id
            selector(getString(R.string.s3_bucket), containers.map(AppCloudStorage::containerDisplayLabel)) { _, index ->
                val container = containers.getOrNull(index) ?: return@selector
                if (container.id == selected) return@selector
                AppCloudStorage.selectContainer(S3ContainerScope.CACHE, container.id)
                cloudContainerId = container.id
                updateContainerMenu()
                viewModel.clearDisplay()
                viewModel.load()
            }
        }
    }

    private fun observeData() {
        viewModel.itemsLiveData.observe(this) { items ->
            rawItems = items
            applyFilters()
        }
        viewModel.summaryLiveData.observe(this) { summary ->
            screenSummary = summary
        }
        viewModel.loadingLiveData.observe(this) { loading ->
            screenLoading = loading
        }
    }

    private fun updateSearchKey(key: String) {
        val value = key.trim()
        if (searchKey == value) return
        searchKey = value
        applyFilters()
    }

    private fun showSortSelector() {
        val modes = CacheManageSortMode.entries
        selector(getString(R.string.cache_manage_sort_title), modes.map { getString(it.titleRes) }) { _, index ->
            val mode = modes.getOrNull(index) ?: return@selector
            if (sortMode == mode) return@selector
            sortMode = mode
            updateSortButton()
            applyFilters()
        }
    }

    private fun updateSortButton() {
        sortMenuItem?.title = getString(R.string.cache_manage_sort_current, getString(sortMode.titleRes))
    }

    private fun applyFilters() {
        screenItems = rawItems
            .asSequence()
            .filter { it.matchesSearch(searchKey) }
            .sortedWith(sortMode.comparator())
            .toList()
    }

    private fun observeTasks() {
        lifecycleScope.launch {
            viewModel.audioTaskStateFlow.collectLatest { states ->
                audioTaskStateMap = states
                if (viewModel.mode == CacheManageMode.AUDIO) {
                    reloadAudioItemsWhenNeeded(states)
                }
            }
        }
        lifecycleScope.launch {
            WebDavTaskManager.states.collectLatest { states ->
                webDavTaskStateMap = states
                reloadItemsWhenWebDavTaskFinished(states)
            }
        }
    }

    private fun reloadItemsWhenWebDavTaskFinished(states: Map<String, WebDavTaskState>) {
        states.values
            .filter { !it.active && it.status.isTerminalForListRefresh() }
            .forEach { state ->
                val key = "webdav:${state.key}:${state.type}:${state.status}"
                if (handledTerminalTaskReloads.add(key)) {
                    viewModel.load()
                }
            }
    }
    private fun reloadAudioItemsWhenNeeded(states: Map<String, AudioCacheTaskState>) {
        val stateValues = states.values
        val activeTaskBookUrls = stateValues
            .asSequence()
            .filter { it.active }
            .mapTo(hashSetOf<String>()) { it.bookUrl }
        if (activeTaskBookUrls.isNotEmpty()) {
            val visibleBookUrls = hashSetOf<String>()
            screenItems.forEach { item ->
                if (item.sourceVariants.isEmpty()) {
                    visibleBookUrls.add(item.book.bookUrl)
                } else {
                    item.sourceVariants.forEach { visibleBookUrls.add(it.book.bookUrl) }
                }
            }
            val missingActiveTasks = activeTaskBookUrls - visibleBookUrls
            if (missingActiveTasks.isNotEmpty()) {
                val now = System.currentTimeMillis()
                if (now - lastMissingTaskReloadAt > MISSING_TASK_RELOAD_INTERVAL_MS && !viewModel.isLoading()) {
                    lastMissingTaskReloadAt = now
                    scheduleAudioTaskReload(MISSING_TASK_RELOAD_DELAY_MS)
                }
            }
        }
        stateValues
            .filter { !it.active && it.status.isTerminalForListRefresh() }
            .forEach { state ->
                val key = "${state.bookUrl}:${state.status}:${state.completedChapters}:${state.totalChapters}"
                if (handledTerminalTaskReloads.add(key)) {
                    scheduleAudioTaskReload(TERMINAL_TASK_RELOAD_DELAY_MS)
                }
            }
    }

    private fun scheduleAudioTaskReload(delayMs: Long) {
        if (audioTaskReloadJob?.isActive == true) return
        audioTaskReloadJob = lifecycleScope.launch {
            delay(delayMs)
            if (viewModel.mode == CacheManageMode.AUDIO && !viewModel.isLoading()) {
                viewModel.load(CacheManageMode.AUDIO)
            }
        }
    }

    private fun switchMode(mode: CacheManageMode) {
        if (viewModel.mode == mode) return
        screenMode = mode
        rawItems = emptyList()
        applyFilters()
        viewModel.load(mode)
    }

    private fun openChapters(item: CacheBookItem) {
        if (item.localCachedCount <= 0) {
            toastOnUi(R.string.cache_manage_download_first)
            return
        }
        showDialogFragment(CacheChapterDialog.newInstance(item.book))
    }

    private fun upload(item: CacheBookItem) {
        selectSyncStrategy(R.string.cache_manage_upload_strategy_title) { strategy ->
            val queued = WebDavTaskManager.enqueueCacheUpload(item) {
                viewModel.uploadCacheItem(item, strategy)
            }
            toastOnUi(if (queued) R.string.cache_manage_upload_queued else R.string.cache_manage_webdav_task_duplicate)
        }
    }

    private fun download(item: CacheBookItem) {
        selectSyncStrategy(R.string.cache_manage_download_strategy_title) { strategy ->
            val queued = WebDavTaskManager.enqueueCacheDownload(item) {
                viewModel.downloadRemoteCache(item, strategy)
            }
            toastOnUi(if (queued) R.string.cache_manage_download_queued else R.string.cache_manage_webdav_task_duplicate)
        }
    }

    private fun selectSyncAction(item: CacheBookItem) {
        val actions = listOf(
            R.string.cache_manage_upload to { upload(item) },
            R.string.action_download to { download(item) }
        )
        selector(getString(R.string.cache_manage_sync_action), actions.map { getString(it.first) }) { _, index ->
            actions.getOrNull(index)?.second?.invoke()
        }
    }

    private fun restoreToBookshelf(item: CacheBookItem) {
        lifecycleScope.launch {
            kotlin.runCatching {
                viewModel.restoreCacheToBookshelf(item)
            }.onSuccess { success ->
                if (success) {
                    toastOnUi(
                        if (item.inBookshelf) R.string.cache_manage_use_cache_success
                        else R.string.cache_manage_add_bookshelf_success
                    )
                    viewModel.load()
                } else {
                    toastOnUi(R.string.cache_manage_no_cache)
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun deleteBookCache(item: CacheBookItem) {
        selectDeleteTarget(
            item = item,
            localAvailable = item.localCachedCount > 0,
            remoteAvailable = item.hasRemoteCache()
        ) { target ->
            confirmDeleteTarget(target, 1) {
                viewModel.deleteBookCache(item, target) { result ->
                    toastDeleteResult(result)
                }
            }
        }
    }

    private fun stopAudioCache(item: CacheBookItem) {
        val bookUrls = buildList {
            add(item.book.bookUrl)
            item.sourceVariants.forEach { add(it.book.bookUrl) }
        }
        val task = bookUrls.firstNotNullOfOrNull { bookUrl ->
            CacheCoordinator.snapshot.value.activeTasksFor(bookUrl)
                .firstOrNull { it.phase == CachePhase.MEDIA }
        } ?: return
        val submission = CacheSubmission(task.sessionId, task.taskId)
        when (task.status) {
            CacheLifecycle.RUNNING -> {
                if (!CacheCoordinator.pause(submission)) toastOnUi(R.string.error)
            }

            CacheLifecycle.PAUSED -> {
                if (!CacheCoordinator.resume(submission)) toastOnUi(R.string.error)
            }

            else -> Unit
        }
    }

    private fun openReviewSnapshots(item: CacheBookItem) {
        showDialogFragment(ReviewSnapshotStatusDialog.newInstance(item.book))
    }

    private fun selectSource(item: CacheBookItem) {
        val variants = item.sourceVariants
        if (variants.size <= 1) return
        val labels: List<CharSequence> = variants.map { variant ->
            buildString {
                append(
                    if (variant.sourceAvailable) {
                        variant.sourceName
                    } else {
                        getString(R.string.cache_manage_source_deleted, variant.sourceName)
                    }
                )
                append(" · ")
                append(variant.cacheCountText(this@CacheManageActivity))
            }
        }
        selector(getString(R.string.cache_manage_select_source), labels) { _, index ->
            val variant = variants.getOrNull(index) ?: return@selector
            viewModel.selectSource(item.groupKey, variant.sourceKey)
        }
    }

    private fun uploadAll() {
        val items = screenItems.filter {
            it.cachedCount > 0 && !it.hasLockedCacheTask(audioTaskStateMap)
        }
        if (items.isEmpty()) {
            toastOnUi(R.string.cache_manage_batch_empty)
            return
        }
        val queued = items.count { item ->
            WebDavTaskManager.enqueueCacheUpload(item) {
                viewModel.uploadCacheItem(item)
            }
        }
        toastOnUi(getString(R.string.cache_manage_batch_upload_queued, queued))
    }

    private fun deleteAll() {
        val items = screenItems.filter {
            !it.hasLockedCacheTask(audioTaskStateMap) &&
                (it.localCachedCount > 0 || it.hasRemoteCache())
        }
        if (items.isEmpty()) {
            toastOnUi(R.string.cache_manage_batch_empty)
            return
        }
        val localAvailable = items.any { it.localCachedCount > 0 }
        val remoteAvailable = items.any { it.hasRemoteCache() }
        selectDeleteTarget(localAvailable = localAvailable, remoteAvailable = remoteAvailable) { target ->
            val targets = items.filter { item -> target.canDelete(item) }
            if (targets.isEmpty()) {
                toastOnUi(R.string.cache_manage_batch_empty)
                return@selectDeleteTarget
            }
            confirmDeleteTarget(target, targets.size) {
                viewModel.deleteBookCaches(targets, target) { result ->
                    toastDeleteResult(result)
                }
            }
        }
    }

    private fun selectDeleteTarget(
        item: CacheBookItem? = null,
        localAvailable: Boolean,
        remoteAvailable: Boolean,
        onSelected: (CacheDeleteTarget) -> Unit
    ) {
        val targets = CacheDeleteTarget.entries.filter { target ->
            when (target) {
                CacheDeleteTarget.LOCAL -> localAvailable
                CacheDeleteTarget.REMOTE -> remoteAvailable
                CacheDeleteTarget.BOTH -> localAvailable && remoteAvailable
            }
        }
        if (targets.isEmpty()) {
            toastOnUi(R.string.cache_manage_no_cache)
            return
        }
        if (targets.size == 1) {
            onSelected(targets.first())
            return
        }
        val title = item?.let { getString(R.string.cache_manage_delete_book_title, it.book.name) }
            ?: getString(R.string.delete)
        selector(title, targets.map { getString(it.labelRes) }) { _, index ->
            targets.getOrNull(index)?.let(onSelected)
        }
    }

    private fun confirmDeleteTarget(
        target: CacheDeleteTarget,
        count: Int,
        onConfirmed: () -> Unit
    ) {
        val message = when (target) {
            CacheDeleteTarget.LOCAL -> getString(R.string.cache_manage_delete_local_confirm, count)
            CacheDeleteTarget.REMOTE -> getString(R.string.cache_manage_delete_remote_confirm, count)
            CacheDeleteTarget.BOTH -> getString(R.string.cache_manage_delete_both_confirm, count)
        }
        AndroidAlertBuilder(this).apply {
            setTitle(R.string.delete)
            setMessage(message)
            yesButton { onConfirmed() }
            noButton()
            show()
        }
    }

    private fun selectSyncStrategy(
        titleRes: Int,
        onSelected: (CacheSyncStrategy) -> Unit
    ) {
        val strategies = CacheSyncStrategy.entries
        selector(getString(titleRes), strategies.map { getString(it.labelRes) }) { _, index ->
            strategies.getOrNull(index)?.let(onSelected)
        }
    }

    private fun toastDeleteResult(result: CacheDeleteResult) {
        result.messageRes?.let {
            toastOnUi(it)
            return
        }
        toastOnUi(result.errorMessage ?: getString(R.string.error))
    }

    override fun onCacheChanged() {
        viewModel.load()
    }

    override fun openCacheChapter(book: Book, chapter: BookChapter) {
        val target = book.apply {
            durChapterIndex = chapter.index
            durChapterTitle = chapter.title
            durChapterPos = 0
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                appDb.bookDao.update(target)
            }
            startActivityForBook(target)
        }
    }
}

private fun CacheTaskStatus.isTerminalForListRefresh(): Boolean {
    return this == CacheTaskStatus.COMPLETED ||
        this == CacheTaskStatus.PAUSED ||
        this == CacheTaskStatus.CANCELLED ||
        this == CacheTaskStatus.FAILED
}

private fun WebDavTaskStatus.isTerminalForListRefresh(): Boolean {
    return this == WebDavTaskStatus.COMPLETED ||
        this == WebDavTaskStatus.CANCELLED ||
        this == WebDavTaskStatus.FAILED
}

private const val MISSING_TASK_RELOAD_INTERVAL_MS = 2500L
private const val MISSING_TASK_RELOAD_DELAY_MS = 250L
private const val TERMINAL_TASK_RELOAD_DELAY_MS = 600L
private const val MENU_SEARCH = 0x53ff
private const val MENU_SORT = 0x5400
private const val MENU_CONTAINER = 0x5401
private const val MENU_BOOK_CACHE_EXPORT = 0x5402

enum class CacheManageSortMode(val titleRes: Int) {
    RECENT(R.string.cache_manage_sort_time),
    LAST_READ(R.string.cache_manage_sort_last_read),
    NAME(R.string.cache_manage_sort_name);

    fun comparator(): Comparator<CacheBookItem> {
        return when (this) {
            RECENT -> Comparator { o1, o2 ->
                o2.lastCacheUpdatedAt().compareTo(o1.lastCacheUpdatedAt()).takeIf { it != 0 }
                    ?: o1.book.name.cnCompare(o2.book.name).takeIf { it != 0 }
                    ?: o1.sourceName.cnCompare(o2.sourceName)
            }
            LAST_READ -> Comparator { o1, o2 ->
                o2.lastReadAt().compareTo(o1.lastReadAt()).takeIf { it != 0 }
                    ?: o1.book.name.cnCompare(o2.book.name).takeIf { it != 0 }
                    ?: o1.sourceName.cnCompare(o2.sourceName).takeIf { it != 0 }
                    ?: o2.lastCacheUpdatedAt().compareTo(o1.lastCacheUpdatedAt())
            }
            NAME -> Comparator { o1, o2 ->
                o1.book.name.cnCompare(o2.book.name).takeIf { it != 0 }
                    ?: o1.sourceName.cnCompare(o2.sourceName).takeIf { it != 0 }
                    ?: o2.lastCacheUpdatedAt().compareTo(o1.lastCacheUpdatedAt())
            }
        }
    }
}

private fun CacheBookItem.matchesSearch(key: String): Boolean {
    if (key.isBlank()) return true
    return book.name.contains(key, ignoreCase = true) ||
        book.author.contains(key, ignoreCase = true) ||
        sourceName.contains(key, ignoreCase = true) ||
        sourceVariants.any {
            it.book.name.contains(key, ignoreCase = true) ||
                it.book.author.contains(key, ignoreCase = true) ||
                it.sourceName.contains(key, ignoreCase = true)
        }
}

private fun CacheBookItem.lastCacheUpdatedAt(): Long {
    val variantTime = sourceVariants.maxOfOrNull {
        maxOf(it.localUpdatedAt, it.remoteUpdatedAt)
    } ?: 0L
    return maxOf(localUpdatedAt, remoteUpdatedAt, variantTime)
}

private fun CacheBookItem.lastReadAt(): Long {
    val variantTime = sourceVariants.maxOfOrNull { variant ->
        variant.book.durChapterTime.takeIf { variant.inBookshelf } ?: 0L
    } ?: 0L
    val itemTime = book.durChapterTime.takeIf { inBookshelf } ?: 0L
    return maxOf(itemTime, variantTime)
}

private fun CacheBookItem.hasLockedCacheTask(
    taskStates: Map<String, AudioCacheTaskState>
): Boolean {
    if (taskStates[book.bookUrl].locksCacheActions()) return true
    if (WebDavTaskManager.snapshot(cacheKey)?.active == true) return true
    return sourceVariants.any {
        taskStates[it.book.bookUrl].locksCacheActions() ||
            WebDavTaskManager.snapshot(it.cacheKey)?.active == true
    }
}

private fun AudioCacheTaskState?.locksCacheActions(): Boolean {
    return this?.active == true || this?.status == CacheTaskStatus.PAUSED
}

private fun CacheDeleteTarget.canDelete(item: CacheBookItem): Boolean {
    return when (this) {
        CacheDeleteTarget.LOCAL -> item.localCachedCount > 0
        CacheDeleteTarget.REMOTE -> item.hasRemoteCache()
        CacheDeleteTarget.BOTH -> item.localCachedCount > 0 || item.hasRemoteCache()
    }
}
