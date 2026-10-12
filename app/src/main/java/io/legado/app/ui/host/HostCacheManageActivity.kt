package io.legado.app.ui.host
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import io.legado.app.uikit.v5.components.AppFastScroller
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.cache.CacheCoordinator
import io.legado.app.help.cache.CacheLifecycle
import io.legado.app.help.cache.CachePhase
import io.legado.app.help.cache.CacheSubmission
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.cloud.S3ContainerScope
import io.legado.app.ui.book.cache.ReviewSnapshotStatusDialog
import io.legado.app.ui.book.cache.AudioCacheTaskState
import io.legado.app.ui.book.cache.BookCacheExportActivity
import io.legado.app.ui.book.cache.CacheBookItem
import io.legado.app.ui.book.cache.CacheChapterDialog
import io.legado.app.ui.book.cache.CacheDeleteTarget
import io.legado.app.ui.book.cache.CacheManageMode
import io.legado.app.ui.book.cache.CacheManageSortMode
import io.legado.app.ui.book.cache.CacheManageTaskReloadController
import io.legado.app.ui.book.cache.CacheManageViewModel
import io.legado.app.ui.book.cache.CacheSummary
import io.legado.app.ui.book.cache.CacheSyncStrategy
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.activeTasksFor
import io.legado.app.ui.book.cache.cacheCountText
import io.legado.app.ui.book.cache.hasLockedCacheTask
import io.legado.app.ui.book.cache.hasRemoteCache
import io.legado.app.ui.book.cache.matchesSearch
import io.legado.app.ui.book.cache.toVisibleBookUrls
import io.legado.app.uikit.facade.AppToast
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppDangerButton
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFilterChip
import io.legado.app.uikit.v5.components.AppFilterChipRow
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppLoading
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppSearchField
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P4-3 管理页原生化②：缓存与导出管理 V5 壳（台账 #2）。
 * 数据层全复用 CacheManageViewModel（业务后端零拷贝）；
 * 「任务终态/失踪 → 刷新」调度复用 CacheManageTaskReloadController；
 * 列表/筛选/排序辅助函数直接引用旧文件 internal 顶层函数。
 * 旧 CacheManageActivity 退役前保留（功能对照期），入口已切到本页。
 */
class HostCacheManageActivity : AppCompatActivity(), CacheChapterDialog.Callback {

    companion object {
        const val EXTRA_INITIAL_SEARCH_KEY = "initialSearchKey"

        fun start(context: Context, initialSearchKey: String? = null) {
            context.startActivity(
                Intent(context, HostCacheManageActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_INITIAL_SEARCH_KEY, initialSearchKey)
                }
            )
        }
    }

    /** Compose 界面注册的刷新回调（CacheChapterDialog 回落用）。 */
    internal var cacheChangedListener: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialSearchKey = intent.getStringExtra(EXTRA_INITIAL_SEARCH_KEY)
        setV5Content { HostCacheManageScreen(initialSearchKey) }
    }

    override fun onCacheChanged() {
        cacheChangedListener?.invoke()
    }

    override fun openCacheChapter(book: Book, chapter: BookChapter) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                appDb.bookDao.update(
                    book.apply {
                        durChapterIndex = chapter.index
                        durChapterTitle = chapter.title
                        durChapterPos = 0
                    }
                )
            }
            startActivityForBook(book)
        }
    }
}
/** 通用单选选择器（排序/同步/删除目标/换源/存储桶共用一个 AppFilterDialog）。 */
private data class CacheChooser(
    val title: String,
    val options: List<String>,
    val onSelect: (index: Int) -> Unit,
)

/** 删除确认（危险操作 B 门）。 */
private data class CachePendingDelete(
    val message: String,
    val onConfirmed: () -> Unit,
)

@Composable
private fun HostCacheManageScreen(initialSearchKey: String?) {
    val context = LocalContext.current
    val activity = context as HostCacheManageActivity
    val colors = io.legado.app.uikit.v5.runtime.LocalSemanticColors.current
    val visual = io.legado.app.uikit.v5.runtime.LocalVisualSpec.current
    val density = visual.density

    val viewModel = remember {
        ViewModelProvider(activity)[CacheManageViewModel::class.java]
    }

    val rawItems by viewModel.itemsLiveData.observeAsStateCompat(emptyList())
    val summary by viewModel.summaryLiveData.observeAsStateCompat<CacheSummary?>(null)
    val loading by viewModel.loadingLiveData.observeAsStateCompat(false)
    val audioTaskStates by viewModel.audioTaskStateFlow.collectAsState()
    val webDavTaskStates by WebDavTaskManager.states.collectAsState()

    var mode by rememberSaveable { mutableStateOf(CacheManageMode.BOOK) }
    var searchKey by rememberSaveable { mutableStateOf(initialSearchKey?.trim().orEmpty()) }
    var sortMode by rememberSaveable { mutableStateOf(CacheManageSortMode.RECENT) }
    var menuOpen by remember { mutableStateOf(false) }
    var chooser by remember { mutableStateOf<CacheChooser?>(null) }
    var pendingDelete by remember { mutableStateOf<CachePendingDelete?>(null) }

    LaunchedEffect(Unit) {
        activity.cacheChangedListener = { viewModel.load() }
        viewModel.load(CacheManageMode.BOOK)
    }

    // 筛选 + 排序（辅助函数复用旧文件 internal 顶层实现，零拷贝）
    val items = remember(rawItems, searchKey, sortMode) {
        rawItems.asSequence()
            .filter { it.matchesSearch(searchKey) }
            .sortedWith(sortMode.comparator())
            .toList()
    }

    // 任务刷新调度（下沉控制器，与旧壳同源；闭包读委托局部变量取实时值）
    val scope = rememberCoroutineScope()
    val reloadController = remember(scope) {
        CacheManageTaskReloadController(
            scope = scope,
            audioModeActive = { viewModel.mode == CacheManageMode.AUDIO && !viewModel.isLoading() },
            currentBookUrls = {
                rawItems.asSequence()
                    .filter { it.matchesSearch(searchKey) }
                    .toList()
                    .toVisibleBookUrls()
            },
            reloadAudio = { viewModel.load(CacheManageMode.AUDIO) },
            reloadCurrent = { viewModel.load() },
        )
    }
    LaunchedEffect(webDavTaskStates) {
        reloadController.onWebDavStates(webDavTaskStates)
    }
    LaunchedEffect(audioTaskStates) {
        if (mode == CacheManageMode.AUDIO) {
            reloadController.onAudioStates(audioTaskStates)
        }
    }
    fun switchMode(newMode: CacheManageMode) {
        if (viewModel.mode == newMode) return
        mode = newMode
        viewModel.load(newMode)
    }

    fun sortModeChooser() {
        val modes = CacheManageSortMode.entries
        chooser = CacheChooser(
            context.getString(R.string.cache_manage_sort_title),
            modes.map { context.getString(it.titleRes) },
        ) { index ->
            modes.getOrNull(index)?.let { sortMode = it }
        }
    }

    fun openChapters(item: CacheBookItem) {
        if (item.localCachedCount <= 0) {
            AppToast.show(context.getString(R.string.cache_manage_download_first))
            return
        }
        CacheChapterDialog.newInstance(item.book)
            .show(activity.supportFragmentManager, "cacheChapter")
    }

    fun syncStrategyThen(title: String, item: CacheBookItem, isUpload: Boolean) {
        val strategies = CacheSyncStrategy.entries
        chooser = CacheChooser(title, strategies.map { context.getString(it.labelRes) }) { index ->
            strategies.getOrNull(index)?.let { strategy ->
                scope.launch {
                    if (isUpload) {
                        viewModel.uploadCacheItem(item, strategy)
                    } else {
                        viewModel.downloadRemoteCache(item, strategy)
                    }
                }
            }
        }
    }

    fun upload(item: CacheBookItem) {
        syncStrategyThen(context.getString(R.string.cache_manage_upload_strategy_title), item, isUpload = true)
    }

    fun download(item: CacheBookItem) {
        syncStrategyThen(context.getString(R.string.cache_manage_download_strategy_title), item, isUpload = false)
    }

    fun selectSyncAction(item: CacheBookItem) {
        val actions = listOf(
            context.getString(R.string.cache_manage_upload),
            context.getString(R.string.action_download),
        )
        chooser = CacheChooser(
            context.getString(R.string.cache_manage_sync_action),
            actions,
        ) { index ->
            when (index) {
                0 -> upload(item)
                else -> download(item)
            }
        }
    }

    fun restoreToBookshelf(item: CacheBookItem) {
        scope.launch {
            kotlin.runCatching {
                viewModel.restoreCacheToBookshelf(item)
            }.onSuccess { success ->
                if (success) {
                    AppToast.show(
                        context.getString(
                            if (item.inBookshelf) R.string.cache_manage_use_cache_success
                            else R.string.cache_manage_add_bookshelf_success
                        )
                    )
                    viewModel.load()
                } else {
                    AppToast.show(context.getString(R.string.cache_manage_no_cache))
                }
            }.onFailure {
                AppToast.show(it.localizedMessage ?: context.getString(R.string.error))
            }
        }
    }
    fun chooseDeleteTarget(
        title: String,
        localAvailable: Boolean,
        remoteAvailable: Boolean,
        onSelected: (CacheDeleteTarget) -> Unit,
    ) {
        val targets = CacheDeleteTarget.entries.filter { target ->
            when (target) {
                CacheDeleteTarget.LOCAL -> localAvailable
                CacheDeleteTarget.REMOTE -> remoteAvailable
                CacheDeleteTarget.BOTH -> localAvailable && remoteAvailable
            }
        }
        if (targets.isEmpty()) {
            AppToast.show(context.getString(R.string.cache_manage_no_cache))
            return
        }
        if (targets.size == 1) {
            onSelected(targets.first())
            return
        }
        chooser = CacheChooser(title, targets.map { context.getString(it.labelRes) }) { index ->
            targets.getOrNull(index)?.let(onSelected)
        }
    }

    fun deleteBookCache(item: CacheBookItem) {
        chooseDeleteTarget(
            title = context.getString(R.string.cache_manage_delete_book_title, item.book.name),
            localAvailable = item.localCachedCount > 0,
            remoteAvailable = item.hasRemoteCache(),
        ) { target ->
            val message = context.getString(
                when (target) {
                    CacheDeleteTarget.LOCAL -> R.string.cache_manage_delete_local_confirm
                    CacheDeleteTarget.REMOTE -> R.string.cache_manage_delete_remote_confirm
                    CacheDeleteTarget.BOTH -> R.string.cache_manage_delete_both_confirm
                },
                1
            )
            pendingDelete = CachePendingDelete(message) {
                viewModel.deleteBookCache(item, target) { result ->
                    AppToast.show(
                        result.messageRes?.let { context.getString(it) }
                            ?: result.errorMessage
                            ?: context.getString(R.string.error)
                    )
                }
            }
        }
    }

    fun deleteAll() {
        val targets = items.filter {
            !it.hasLockedCacheTask(audioTaskStates) &&
                (it.localCachedCount > 0 || it.hasRemoteCache())
        }
        if (targets.isEmpty()) {
            AppToast.show(context.getString(R.string.cache_manage_batch_empty))
            return
        }
        chooseDeleteTarget(
            title = context.getString(R.string.delete),
            localAvailable = targets.any { it.localCachedCount > 0 },
            remoteAvailable = targets.any { it.hasRemoteCache() },
        ) { target ->
            val deletable = targets.filter { item ->
                when (target) {
                    CacheDeleteTarget.LOCAL -> item.localCachedCount > 0
                    CacheDeleteTarget.REMOTE -> item.hasRemoteCache()
                    CacheDeleteTarget.BOTH -> item.localCachedCount > 0 || item.hasRemoteCache()
                }
            }
            if (deletable.isEmpty()) {
                AppToast.show(context.getString(R.string.cache_manage_batch_empty))
                return@chooseDeleteTarget
            }
            val message = context.getString(
                when (target) {
                    CacheDeleteTarget.LOCAL -> R.string.cache_manage_delete_local_confirm
                    CacheDeleteTarget.REMOTE -> R.string.cache_manage_delete_remote_confirm
                    CacheDeleteTarget.BOTH -> R.string.cache_manage_delete_both_confirm
                },
                deletable.size
            )
            pendingDelete = CachePendingDelete(message) {
                viewModel.deleteBookCaches(deletable, target) { result ->
                    AppToast.show(
                        result.messageRes?.let { context.getString(it) }
                            ?: result.errorMessage
                            ?: context.getString(R.string.error)
                    )
                }
            }
        }
    }
    fun uploadAll() {
        val targets = items.filter {
            it.cachedCount > 0 && !it.hasLockedCacheTask(audioTaskStates)
        }
        if (targets.isEmpty()) {
            AppToast.show(context.getString(R.string.cache_manage_batch_empty))
            return
        }
        val queued = targets.count { item ->
            WebDavTaskManager.enqueueCacheUpload(item) {
                viewModel.uploadCacheItem(item)
            }
        }
        AppToast.show(context.getString(R.string.cache_manage_batch_upload_queued, queued))
    }

    fun stopAudioCache(item: CacheBookItem) {
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
                if (!CacheCoordinator.pause(submission)) {
                    AppToast.show(context.getString(R.string.error))
                }
            }

            CacheLifecycle.PAUSED -> {
                if (!CacheCoordinator.resume(submission)) {
                    AppToast.show(context.getString(R.string.error))
                }
            }

            else -> Unit
        }
    }

    fun selectSource(item: CacheBookItem) {
        val variants = item.sourceVariants
        if (variants.size <= 1) return
        val labels = variants.map { variant ->
            buildString {
                append(
                    if (variant.sourceAvailable) variant.sourceName
                    else context.getString(R.string.cache_manage_source_deleted, variant.sourceName)
                )
                append(" · ")
                append(variant.cacheCountText(context))
            }
        }
        chooser = CacheChooser(context.getString(R.string.cache_manage_select_source), labels) { index ->
            variants.getOrNull(index)?.let { variant ->
                viewModel.selectSource(item.groupKey, variant.sourceKey)
            }
        }
    }

    fun openContainerChooser() {
        scope.launch {
            val containers = withContext(Dispatchers.IO) {
                AppCloudStorage.listContainers().filter { it.enabled }
            }
            if (containers.isEmpty()) {
                AppToast.show(context.getString(R.string.cloud_storage_config_required))
                return@launch
            }
            val selected = AppCloudStorage.selectedContainer(S3ContainerScope.CACHE)?.id
                ?: containers.firstOrNull()?.id
            chooser = CacheChooser(
                context.getString(R.string.s3_bucket),
                containers.map(AppCloudStorage::containerDisplayLabel),
            ) { index ->
                val container = containers.getOrNull(index) ?: return@CacheChooser
                if (container.id == selected) return@CacheChooser
                AppCloudStorage.selectContainer(S3ContainerScope.CACHE, container.id)
                viewModel.clearDisplay()
                viewModel.load()
            }
        }
    }
    HostV5Page(
        title = stringResource(R.string.cache_manage_title),
        onBack = { activity.finish() },
        trailing = {
            AppOverflowMenuButton(
                contentDescription = stringResource(R.string.cache_manage_title),
            ) { menuOpen = true }
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap / 2),
                horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                AppGhostButton(
                    stringResource(R.string.cache_manage_upload_all),
                    modifier = Modifier.weight(1f),
                ) { uploadAll() }
                AppDangerButton(
                    stringResource(R.string.cache_manage_delete_all),
                    modifier = Modifier.weight(1f),
                ) { deleteAll() }
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppSearchField(
                value = searchKey,
                onValueChange = { searchKey = it },
                placeholder = stringResource(R.string.cache_manage_search_book),
                modifier = Modifier
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap / 2),
            )
            val modes = CacheManageMode.entries
            AppFilterChipRow(
                chips = modes.map { AppFilterChip(stringResource(it.titleRes), it == mode) },
                onChipClick = { index -> switchMode(modes[index]) },
                modifier = Modifier.padding(vertical = density.blockGap / 2),
            )
            Text(
                text = summary?.let {
                    stringResource(R.string.cache_manage_summary_state, it.bookCount, it.cachedChapterCount)
                }.orEmpty(),
                style = visual.type.caption,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = density.screenPadding),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (items.isEmpty() && !loading) {
                    AppEmpty(
                        title = stringResource(R.string.cache_manage_empty, stringResource(mode.titleRes)),
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    val cacheListState = rememberLazyListState()
                    AppFastScroller(
                        listState = cacheListState,
                        topContentPadding = density.blockGap / 2,
                        bottomContentPadding = density.blockGap / 2,
                        endContentPadding = density.screenPadding,
                    ) {
                    LazyColumn(
                        state = cacheListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = density.screenPadding,
                            vertical = density.blockGap / 2,
                        ),
                        verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                    ) {
                        items(items, key = { it.groupKey }, contentType = { "cacheBook" }) { item ->
                            HostCacheBookCard(
                                item = item,
                                audioTaskStates = audioTaskStates,
                                webDavTaskStates = webDavTaskStates,
                                onOpenChapters = ::openChapters,
                                onUpload = ::upload,
                                onDownload = ::download,
                                onSelectSyncAction = ::selectSyncAction,
                                onRestore = ::restoreToBookshelf,
                                onDelete = ::deleteBookCache,
                                onStopAudio = ::stopAudioCache,
                                onOpenReviews = {
                                    ReviewSnapshotStatusDialog.newInstance(it.book)
                                        .show(activity.supportFragmentManager, "reviewSnapshot")
                                },
                                onSelectSource = ::selectSource,
                            )
                        }
                        }
                    }
                }
                if (loading) {
                    AppLoading(modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
    if (menuOpen) {
        val actions = buildList {
            add(stringResource(R.string.cache_manage_sort_title) to { sortModeChooser() })
            if (AppCloudStorage.type == CloudStorageType.S3) {
                add(stringResource(R.string.s3_bucket) to { openContainerChooser() })
            }
            add(stringResource(R.string.book_cache_export_title) to {
                activity.startActivity<BookCacheExportActivity>()
            })
        }
        AppMenuSheet(
            title = stringResource(R.string.cache_manage_title),
            actions = actions,
            onDismiss = { menuOpen = false },
        )
    }

    chooser?.let { c ->
        AppFilterDialog(
            title = c.title,
            options = c.options,
            selectedIndex = -1,
            onSelect = { index ->
                chooser = null
                c.onSelect(index)
            },
            onDismiss = { chooser = null },
        )
    }

    pendingDelete?.let { pending ->
        AppConfirmDialog(
            title = stringResource(R.string.delete),
            message = pending.message,
            confirmLabel = stringResource(R.string.yes),
            cancelLabel = stringResource(R.string.no),
            danger = true,
            onConfirm = {
                pendingDelete = null
                pending.onConfirmed()
            },
            onDismiss = { pendingDelete = null },
        )
    }
}
