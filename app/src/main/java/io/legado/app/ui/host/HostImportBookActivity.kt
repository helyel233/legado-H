package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.import.local.ImportBook
import io.legado.app.ui.book.import.local.ImportBookViewModel
import io.legado.app.uikit.facade.AppToast
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFastScroller
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppLoading
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppSearchField
import io.legado.app.uikit.v5.components.AppSelectionAction
import io.legado.app.uikit.v5.components.AppSelectionBar
import io.legado.app.uikit.v5.components.AppSelectionRow
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.components.rememberAppSelectionState
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.utils.isUri
import io.legado.app.utils.putPrefInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * P4-3：本地导入 V5 化（2026-10-13）。
 * 数据层复用 [ImportBookViewModel]；目录导航/存储引导/压缩包起读走共享壳
 * [rememberImportBookShell]（自 BaseImportBookActivity 抽出）。
 */
class HostImportBookActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, HostImportBookActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostImportBookScreen() }
    }
}

@Composable
private fun HostImportBookScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val d = visual.density
    val scope = rememberCoroutineScope()
    val shell = rememberImportBookShell()
    val viewModel = remember { ViewModelProvider(activity)[ImportBookViewModel::class.java] }

    var filter by remember { mutableStateOf("") }
    var pathText by remember { mutableStateOf("") }
    var progressText by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var jsDialogOpen by remember { mutableStateOf(false) }
    var jsInput by remember { mutableStateOf(AppConfig.bookImportFileName.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var initialized by remember { mutableStateOf(false) }

    val selection = rememberAppSelectionState<ImportBook>(idOf = { it.file.uri.toString() })
    val selectionMode = selection.selectionMode
    val listState = rememberLazyListState()

    val docs by viewModel.dataFlow.collectAsState(initial = emptyList())
    val scanProgress by viewModel.scanProgressFlow.collectAsState()
    val importProgress by viewModel.importProgressFlow.collectAsState()

    // 初始化：存储引导 → 根目录解析（逻辑自旧页搬运）
    LaunchedEffect(Unit) {
        shell.ensureStorage { ok ->
            scope.launch {
                if (ok && AppConfig.importBookPath.isNullOrBlank()) {
                    AppConfig.importBookPath = AppConfig.defaultBookTreeUri
                }
                initRootDoc(viewModel, onPath = { pathText = it }, onFallback = { shell.launchTreePicker() })
                initialized = true
            }
        }
    }

    LaunchedEffect(scanProgress) {
        progressText = scanProgress?.let {
            "扫描中：已扫${it.scannedDirs}个文件夹，发现${it.foundBooks}本，待扫${it.pendingDirs}个"
        }
        if (scanProgress == null && importProgress == null) {
            pathText.takeIf { it.isNotBlank() } // 路径文案由 init/upDocs 维护
        }
    }
    LaunchedEffect(importProgress) {
        progressText = importProgress?.let { p ->
            p.message.ifBlank {
                "导入中：${p.processed}/${p.total}，成功${p.imported}，失败${p.failed}"
            }
        }
    }

    val selected = selection.selectedFrom(docs)

    fun upPath() {
        val root = viewModel.rootDoc ?: return
        var path = root.name + File.separator
        var last = root
        for (doc in viewModel.subDocs) {
            last = doc
            path = path + doc.name + File.separator
        }
        pathText = path
        viewModel.loadDoc(last)
    }

    fun goBackDir(): Boolean {
        return if (viewModel.subDocs.isNotEmpty()) {
            viewModel.subDocs.removeAt(viewModel.subDocs.lastIndex)
            upPath()
            true
        } else false
    }

    BackHandler(enabled = true) {
        if (selectionMode) selection.exit()
        else if (!goBackDir()) activity.finish()
    }

    val selectionBar: @Composable () -> Unit = {
        if (selectionMode) {
            AppSelectionBar(
                primaryActions = listOf(
                    AppSelectionAction(stringResource(R.string.add_to_bookshelf)) {
                        val picked = HashSet(selected)
                        viewModel.addToBookshelfWithProgress(picked, onProgress = {}) {
                            picked.forEach { it.isOnBookShelf = true }
                            selection.exit()
                        }
                    },
                    AppSelectionAction(stringResource(R.string.v5_select_all)) { selection.selectAll(docs) },
                    AppSelectionAction(stringResource(R.string.v5_select_invert)) { selection.invert(docs) },
                ),
                overflowActions = listOf(
                    AppSelectionAction(stringResource(R.string.delete), danger = true) { confirmDelete = true },
                ),
                overflowTitle = stringResource(R.string.v5_batch_actions_title, selection.count),
                moreLabel = stringResource(R.string.v5_more),
            )
        }
    }

    HostV5Page(
        title = stringResource(R.string.local_book),
        subtitle = pathText.ifBlank { null },
        onBack = { activity.finish() },
        selectionCount = if (selectionMode) selection.count else null,
        onExitSelection = { selection.exit() },
        trailing = {
            AppOverflowMenuButton(contentDescription = stringResource(R.string.v5_more)) { menuOpen = true }
        },
        bottomBar = selectionBar,
    ) {
        Column(Modifier.fillMaxSize()) {
            // 路径行 + 返回上级
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = d.screenPadding, vertical = d.blockGap / 4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.blockGap / 2),
            ) {
                Text(
                    text = pathText.ifBlank { stringResource(R.string.select_book_folder) },
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (viewModel.subDocs.isNotEmpty()) {
                    AppGhostButton(text = "↑", onClick = { goBackDir() })
                }
            }
            AppSearchField(
                value = filter,
                onValueChange = {
                    filter = it
                    viewModel.updateCallBackFlow(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = d.screenPadding),
                placeholder = stringResource(R.string.screen),
            )
            if (progressText != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = d.screenPadding, vertical = d.blockGap / 4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d.blockGap / 2),
                ) {
                    AppLoading(modifier = Modifier.height(16.dp))
                    Text(
                        text = progressText!!,
                        style = visual.type.caption,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (docs.isEmpty()) {
                AppEmpty(
                    title = stringResource(R.string.local_book),
                    supporting = stringResource(R.string.empty_msg_import_book),
                )
            } else {
                AppFastScroller(
                    listState = listState,
                    topContentPadding = d.blockGap / 2,
                    bottomContentPadding = d.screenPadding,
                    endContentPadding = d.screenPadding,
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = d.screenPadding,
                            end = d.screenPadding,
                            top = d.blockGap / 2,
                            bottom = d.screenPadding,
                        ),
                        verticalArrangement = Arrangement.spacedBy(d.blockGap / 2),
                    ) {
                        items(docs, key = { it.file.uri.toString() }) { book ->
                            val isDir = book.isDir
                            AppSelectionRow(
                                title = book.name,
                                supporting = if (isDir) null else sizeText(book.size),
                                selected = selection.isSelected(book),
                                selectionMode = selectionMode,
                                onClick = {
                                    if (selectionMode) {
                                        if (!isDir) selection.toggle(book)
                                    } else if (isDir) {
                                        viewModel.subDocs.add(book.file)
                                        upPath()
                                    } else {
                                        startRead(context, shell, book.file)
                                    }
                                },
                                onLongClick = { if (!isDir) selection.enter(book) },
                                trailing = {
                                    if (!selectionMode && book.isOnBookShelf && !isDir) {
                                        Text(
                                            text = stringResource(R.string.bookshelf),
                                            style = visual.type.caption,
                                            color = colors.primary,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (menuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.local_book),
            actions = buildList {
                add(stringResource(R.string.select_folder) to { shell.launchTreePicker() })
                add(stringResource(R.string.scan_folder) to {
                    val root = viewModel.rootDoc
                    if (root != null) {
                        val last = viewModel.subDocs.lastOrNull() ?: root
                        scanJob?.cancel()
                        scanJob = scope.launch(Dispatchers.IO) { viewModel.scanDoc(last) }
                    }
                })
                add(stringResource(R.string.import_file_name) to {
                    jsInput = AppConfig.bookImportFileName.orEmpty()
                    jsDialogOpen = true
                })
                add(
                    (if (viewModel.sort == 0) "✓ " else "") + stringResource(R.string.sort_by_name) to {
                        viewModel.sort = 0
                        context.putPrefInt(PreferKey.localBookImportSort, 0)
                        viewModel.dataCallback?.upAdapter()
                    }
                )
                add(
                    (if (viewModel.sort == 1) "✓ " else "") + stringResource(R.string.sort_by_size) to {
                        viewModel.sort = 1
                        context.putPrefInt(PreferKey.localBookImportSort, 1)
                        viewModel.dataCallback?.upAdapter()
                    }
                )
                add(
                    (if (viewModel.sort == 2) "✓ " else "") + stringResource(R.string.sort_by_time) to {
                        viewModel.sort = 2
                        context.putPrefInt(PreferKey.localBookImportSort, 2)
                        viewModel.dataCallback?.upAdapter()
                    }
                )
            },
            onDismiss = { menuOpen = false },
        )
    }

    if (jsDialogOpen) {
        AppDialogFrame(
            title = stringResource(R.string.import_file_name),
            message = "使用js处理文件名变量src，将书名作者分别赋值到变量name author",
            content = {
                AppTextField(
                    value = jsInput,
                    onValueChange = { jsInput = it },
                    placeholder = "js",
                )
            },
            actions = {
                AppGhostButton(text = stringResource(R.string.cancel), onClick = { jsDialogOpen = false })
                AppFilledButton(
                    text = stringResource(R.string.ok),
                    onClick = {
                        AppConfig.bookImportFileName = jsInput
                        jsDialogOpen = false
                    },
                )
            },
        )
    }

    if (confirmDelete) {
        AppConfirmDialog(
            title = stringResource(R.string.delete),
            message = stringResource(R.string.sure_del),
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                viewModel.deleteDoc(HashSet(selected)) { selection.exit() }
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** 根目录解析（自旧页 initRootDoc/initRootPath 搬运；文件路径走权限申请）。 */
private suspend fun initRootDoc(
    viewModel: ImportBookViewModel,
    onPath: (String) -> Unit,
    onFallback: () -> Unit,
) {
    withContext(Dispatchers.IO) {
        val lastPath = AppConfig.importBookPath
        if (lastPath.isNullOrBlank()) {
            onFallback()
            return@withContext
        }
        val rootUri = if (lastPath.isUri()) Uri.parse(lastPath) else Uri.fromFile(File(lastPath))
        runCatching {
            if (rootUri.scheme == "content") {
                val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(appCtx0, rootUri)
                if (doc == null || doc.name.isNullOrEmpty() || !doc.isDirectory) {
                    onFallback()
                } else {
                    viewModel.subDocs.clear()
                    viewModel.rootDoc = FileDoc.fromDocumentFile(doc)
                    upPathSync(viewModel, onPath)
                }
            } else {
                val file = File(rootUri.path ?: "")
                if (!file.isDirectory) {
                    onFallback()
                } else {
                    viewModel.subDocs.clear()
                    viewModel.rootDoc = FileDoc.fromFile(file)
                    upPathSync(viewModel, onPath)
                }
            }
        }.onFailure { onFallback() }
    }
}

private fun upPathSync(viewModel: ImportBookViewModel, onPath: (String) -> Unit) {
    val root = viewModel.rootDoc ?: return
    var path = root.name + File.separator
    var last = root
    for (doc in viewModel.subDocs) {
        last = doc
        path = path + doc.name + File.separator
    }
    onPath(path)
    viewModel.loadDoc(last)
}

/** 文件点击起读（自旧页 startRead 搬运：命中书库修正 bookUrl，压缩包走壳）。 */
private fun startRead(context: Context, shell: ImportBookShell, fileDoc: FileDoc) {
    if (!ArchiveUtils.isArchive(fileDoc.name)) {
        appDb.bookDao.getBookByFileName(fileDoc.name)?.let { book ->
            val filePath = fileDoc.toString()
            if (book.bookUrl != filePath) {
                book.bookUrl = filePath
                appDb.bookDao.insert(book)
            }
            shell.startRead(book)
        } ?: AppToast.show(context.getString(R.string.no_book_found_bookshelf))
    } else {
        shell.onArchiveClick(fileDoc)
    }
}

private fun sizeText(size: Long): String {
    val text = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> String.format("%.1f MB", size / 1024.0 / 1024.0)
    }
    return text
}

private val appCtx0 = splitties.init.appCtx
