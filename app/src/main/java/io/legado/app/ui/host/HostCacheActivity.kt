package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isLocal
import io.legado.app.help.cache.CacheCoordinator
import io.legado.app.help.cache.CacheRequestSource
import io.legado.app.help.cache.CacheSubmission
import io.legado.app.help.config.AppConfig
import io.legado.app.service.ExportBookService
import io.legado.app.ui.book.cache.CacheViewModel
import io.legado.app.ui.book.cache.activeTasksFor
import io.legado.app.ui.book.cache.hasActiveTasks
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.uikit.facade.AppToast
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppSelectionRow
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.ACache
import io.legado.app.utils.checkWrite
import io.legado.app.utils.cnCompare
import io.legado.app.utils.enableCustomExport
import io.legado.app.utils.eventObservableAsState
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.FileDoc
import io.legado.app.utils.startService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * P4-3：缓存管理 V5 化（2026-10-12）。
 *
 * 数据层复用 [CacheViewModel]（缓存章节统计）+ CacheCoordinator（下载任务协调，
 * StateFlow 快照，菜单/行内状态由它驱动）+ ExportBookService（导出）。
 * 进度事件经 `eventObservableAsState`（#27-3：后端事件 → Compose State，
 * 无需旧 Activity 基类）。
 *
 * 已知裁剪：自定义导出的 epub 分卷文件名 JS 编辑从简（范围输入保留，留空=全部导出）。
 */
class HostCacheActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context, groupId: Long) {
            context.startActivity(
                Intent(context, HostCacheActivity::class.java).apply {
                    putExtra("groupId", groupId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostCacheScreen(intent.getLongExtra("groupId", -1)) }
    }
}

@Composable
private fun HostCacheScreen(initialGroupId: Long) {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val density = visual.density
    val viewModel = remember { ViewModelProvider(activity)[CacheViewModel::class.java] }

    var groupId by rememberSaveable { mutableStateOf(initialGroupId) }
    var groupName by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var groupSheetOpen by remember { mutableStateOf(false) }
    var charsetEditOpen by remember { mutableStateOf(false) }
    var fileNameEditOpen by remember { mutableStateOf(false) }
    var customExportBook by remember { mutableStateOf<Book?>(null) }
    var customExportPath by remember { mutableStateOf("") }
    // 事件刷新计数（行内统计/导出进度重组驱动）
    var refresh by remember { mutableIntStateOf(0) }
    // launcher 回调里用的书籍快照（books 声明在其后，经此桥接）
    var latestBooks by remember { mutableStateOf(emptyList<Book>()) }

    val exportEvent by eventObservableAsState<String>(EventBus.EXPORT_BOOK, "")
    val downloadEvent by eventObservableAsState<String>(EventBus.UP_DOWNLOAD, "")
    val saveContentEvent by eventObservableAsState<Pair<Book, BookChapter>>(
        EventBus.SAVE_CONTENT, Pair(Book(), BookChapter()),
    )
    androidx.compose.runtime.LaunchedEffect(exportEvent, downloadEvent, saveContentEvent) {
        saveContentEvent.second.url.let { chapterUrl ->
            if (chapterUrl.isNotEmpty()) {
                viewModel.cacheChapters[saveContentEvent.first.bookUrl]?.add(chapterUrl)
            }
        }
        refresh++
    }

    val coordinator by CacheCoordinator.snapshot.collectAsState()

    val exportDirLauncher = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        val readyPath = result.uri?.let { uri ->
            val path = if (uri.isContentScheme()) uri.toString() else uri.path
            path?.also { ACache.get().put("exportBookPath", it) }
        } ?: return@rememberLauncherForActivityResult
        val pos = result.requestCode
        if (pos == -10) {
            startExportAll(readyPath, latestBooks)
        } else {
            latestBooks.getOrNull(pos)?.let { book ->
                exportBook(activity, book, readyPath) { b, p ->
                    customExportBook = b
                    customExportPath = p
                }
            }
        }
    }

    // 数据流（分组过滤 + 排序，对齐旧页）
    val books by remember(groupId) {
        appDb.bookDao.flowByGroup(groupId).map { list ->
            list.filter { !it.isAudio }.let { books ->
                when (AppConfig.getBookSortByGroupId(groupId)) {
                    1 -> books.sortedByDescending { it.latestChapterTime }
                    2 -> books.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
                    3 -> books.sortedBy { it.order }
                    4 -> books.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
                    else -> books.sortedByDescending { it.durChapterTime }
                }
            }
        }.catch { AppLog.put("缓存管理-V5获取书籍列表失败", it) }
            .flowOn(Dispatchers.IO).conflate()
    }.collectAsState(initial = emptyList())

    val groups by remember {
        appDb.bookGroupDao.flowAll().catch { AppLog.put("缓存管理-V5获取分组失败", it) }
            .flowOn(Dispatchers.IO).conflate()
    }.collectAsState(initial = emptyList())

    androidx.compose.runtime.LaunchedEffect(books, refresh) {
        viewModel.loadCacheFiles(books)
        latestBooks = books
    }
    androidx.compose.runtime.LaunchedEffect(groups, groupId) {
        groupName = withContext(Dispatchers.IO) {
            appDb.bookGroupDao.getByID(groupId)?.groupName
        } ?: context.getString(R.string.no_group)
    }

    fun submitBookDownload(book: Book, start: Int, end: Int) {
        if (book.isLocal) return
        kotlin.runCatching {
            CacheCoordinator.submitBookDownload(
                book = book,
                chapterIndexes = start..end,
                source = CacheRequestSource.CACHE_ACTIVITY,
                reviewIncremental = true,
            )
        }.onFailure { AppLog.put("提交缓存任务失败：${it.localizedMessage}", it) }
    }

    val running = coordinator.hasActiveTasks()

    HostV5Page(
        title = stringResource(R.string.cache_manage_title),
        subtitle = groupName,
        onBack = { activity.finish() },
        trailing = {
            AppOverflowMenuButton(contentDescription = stringResource(R.string.v5_more)) { menuOpen = true }
        },
    ) {
        if (books.isEmpty()) {
            Column(Modifier.fillMaxSize()) {
                AppEmpty(title = stringResource(R.string.no_book))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = density.screenPadding,
                    end = density.screenPadding,
                    top = density.blockGap / 2,
                    bottom = density.screenPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                itemsIndexed(books, key = { _, b -> b.bookUrl }) { index, book ->
                    refresh.let { } // 事件计数参与重组
                    val cacheCount = viewModel.cacheChapters[book.bookUrl]?.size
                    val exportProgress = ExportBookService.exportProgress[book.bookUrl]
                    val exportMsg = ExportBookService.exportMsg[book.bookUrl]
                    val downloading = coordinator.activeTasksFor(book.bookUrl).isNotEmpty()
                    CacheRow(
                        book = book,
                        cacheCount = cacheCount,
                        exportingMsg = exportMsg,
                        exportingProgress = exportProgress,
                        downloading = downloading,
                        onToggleDownload = {
                            val active = coordinator.activeTasksFor(book.bookUrl)
                            if (active.isNotEmpty()) {
                                active.forEach {
                                    CacheCoordinator.cancel(CacheSubmission(it.sessionId, it.taskId))
                                }
                            } else {
                                submitBookDownload(book, 0, book.lastChapterIndex)
                            }
                        },
                        onExport = {
                            activity.lifecycleScope.launch {
                                val path = ACache.get().getAsString("exportBookPath")
                                val writable = !path.isNullOrEmpty() &&
                                    withContext(Dispatchers.IO) { FileDoc.fromDir(path).checkWrite() }
                                if (!writable) {
                                    exportDirLauncher.launch {
                                        mode = HandleFileContract.DIR
                                        requestCode = index
                                    }
                                } else {
                                    exportBook(activity, book, path!!) { b, p ->
                                        customExportBook = b
                                        customExportPath = p
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    // ⋮ 菜单（对齐旧 book_cache 菜单）
    if (menuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.cache_manage_title),
            actions = listOf(
                stringResource(if (running) R.string.stop else R.string.action_download) to {
                    if (running) {
                        CacheCoordinator.cancelAll()
                    } else {
                        books.forEach { book ->
                            submitBookDownload(book, book.durChapterIndex, book.lastChapterIndex)
                        }
                    }
                    menuOpen = false
                },
                stringResource(R.string.download_all) to {
                    books.forEach { book -> submitBookDownload(book, 0, book.lastChapterIndex) }
                    menuOpen = false
                },
                stringResource(R.string.export_all) to {
                    val path = ACache.get().getAsString("exportBookPath")
                    if (path.isNullOrEmpty()) {
                        exportDirLauncher.launch {
                            mode = HandleFileContract.DIR
                            requestCode = -10
                        }
                    } else {
                        startExportAll(path, books)
                    }
                    menuOpen = false
                },
                stringResource(R.string.group_select) to {
                    groupSheetOpen = true
                    menuOpen = false
                },
                "${stringResource(R.string.export_type)}(${if (AppConfig.exportType == 1) "epub" else "txt"})" to {
                    AppConfig.exportType = if (AppConfig.exportType == 1) 0 else 1
                    menuOpen = false
                },
                "${stringResource(R.string.export_charset)}(${AppConfig.exportCharset})" to {
                    charsetEditOpen = true
                    menuOpen = false
                },
                stringResource(R.string.export_file_name) to {
                    fileNameEditOpen = true
                    menuOpen = false
                },
                ((if (AppConfig.exportUseReplace) "✓ " else "") + stringResource(R.string.use_replace)) to {
                    AppConfig.exportUseReplace = !AppConfig.exportUseReplace
                    menuOpen = false
                },
                ((if (AppConfig.exportToWebDav) "✓ " else "") + stringResource(R.string.export_to_web_dav)) to {
                    AppConfig.exportToWebDav = !AppConfig.exportToWebDav
                    menuOpen = false
                },
                ((if (AppConfig.parallelExportBook) "✓ " else "") + stringResource(R.string.parallel_export_book)) to {
                    AppConfig.parallelExportBook = !AppConfig.parallelExportBook
                    menuOpen = false
                },
                stringResource(R.string.log) to {
                    HostLogActivity.start(context)
                    menuOpen = false
                },
            ),
            onDismiss = { menuOpen = false },
        )
    }

    // 分组切换
    if (groupSheetOpen) {
        AppMenuSheet(
            title = stringResource(R.string.group_select),
            actions = groups.map { g ->
                (if (g.groupId == groupId) "✓ " else "") + g.groupName to {
                    groupId = g.groupId
                    groupSheetOpen = false
                }
            },
            onDismiss = { groupSheetOpen = false },
        )
    }

    // 导出编码
    if (charsetEditOpen) {
        var charset by remember { mutableStateOf(AppConfig.exportCharset) }
        io.legado.app.uikit.v5.components.AppInputDialog(
            title = stringResource(R.string.set_charset),
            value = charset,
            onValueChange = { charset = it },
            placeholder = "UTF-8",
            confirmLabel = stringResource(R.string.v5_ok),
            cancelLabel = stringResource(R.string.v5_cancel),
            onConfirm = { text ->
                AppConfig.exportCharset = text.ifBlank { "UTF-8" }
                charsetEditOpen = false
            },
            onDismiss = { charsetEditOpen = false },
        )
    }

    // 导出文件名 JS 规则
    if (fileNameEditOpen) {
        var js by remember { mutableStateOf(AppConfig.bookExportFileName ?: "") }
        io.legado.app.uikit.v5.components.AppInputDialog(
            title = stringResource(R.string.export_file_name),
            value = js,
            onValueChange = { js = it },
            placeholder = "Variable: name, author",
            confirmLabel = stringResource(R.string.v5_ok),
            cancelLabel = stringResource(R.string.v5_cancel),
            onConfirm = { text ->
                AppConfig.bookExportFileName = text
                fileNameEditOpen = false
            },
            onDismiss = { fileNameEditOpen = false },
        )
    }

    // 自定义导出（章节范围；留空 = 全部导出）
    customExportBook?.let { book ->
        var scope by remember { mutableStateOf("") }
        io.legado.app.uikit.v5.components.AppInputDialog(
            title = stringResource(R.string.custom_export_section),
            value = scope,
            onValueChange = { scope = it },
            placeholder = "留空=全部；如 1-5,8,10-18",
            confirmLabel = stringResource(R.string.v5_ok),
            cancelLabel = stringResource(R.string.v5_cancel),
            onConfirm = { text ->
                if (text.isBlank()) {
                    startExportOne(context, book, customExportPath)
                } else {
                    context.startService<ExportBookService> {
                        action = IntentAction.start
                        putExtra("bookUrl", book.bookUrl)
                        putExtra("exportType", "epub")
                        putExtra("exportPath", customExportPath)
                        putExtra("epubSize", 1)
                        putExtra("epubScope", text)
                    }
                }
                customExportBook = null
            },
            onDismiss = { customExportBook = null },
        )
    }
}

@Composable
private fun CacheRow(
    book: Book,
    cacheCount: Int?,
    exportingMsg: String?,
    exportingProgress: Int?,
    downloading: Boolean,
    onToggleDownload: () -> Unit,
    onExport: () -> Unit,
) {
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val density = visual.density
    val cacheText = when {
        book.isLocal -> stringResource(R.string.local_book)
        cacheCount == null -> stringResource(R.string.loading)
        else -> stringResource(R.string.download_count, cacheCount, book.totalChapterNum)
    }
    AppSelectionRow(
        title = book.name,
        supporting = stringResource(R.string.author_show, book.getRealAuthor()) + " · " + cacheText,
        supportingSecondary = exportingMsg ?: exportingProgress?.let {
            "${stringResource(R.string.exporting)} $it/${book.totalChapterNum}"
        },
        selected = false,
        selectionMode = false,
        onClick = onToggleDownload,
        onLongClick = onExport,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!book.isLocal) {
                    Text(
                        text = stringResource(if (downloading) R.string.stop else R.string.action_download),
                        style = visual.type.caption,
                        color = colors.primary,
                        modifier = Modifier.padding(end = density.blockGap / 2),
                    )
                }
                Text(
                    text = stringResource(R.string.export_str),
                    style = visual.type.caption,
                    color = colors.primary,
                )
            }
        },
    )
}

private fun startExportAll(path: String, books: List<Book>) {
    if (books.isEmpty()) {
        AppToast.show(splitties.init.appCtx.getString(R.string.no_book))
        return
    }
    books.forEach { startExportOne(splitties.init.appCtx, it, path) }
}

private fun startExportOne(context: Context, book: Book, path: String) {
    val exportType = if (AppConfig.exportType == 1) "epub" else "txt"
    context.startService<ExportBookService> {
        action = IntentAction.start
        putExtra("bookUrl", book.bookUrl)
        putExtra("exportType", exportType)
        putExtra("exportPath", path)
    }
}

private fun exportBook(
    context: Context,
    book: Book,
    path: String,
    onCustomExport: (Book, String) -> Unit,
) {
    if (enableCustomExport()) {
        onCustomExport(book, path)
    } else {
        startExportOne(context, book, path)
    }
}
