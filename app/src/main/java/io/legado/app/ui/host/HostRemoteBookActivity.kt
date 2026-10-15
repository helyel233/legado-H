package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.model.remote.RemoteBook
import io.legado.app.ui.book.import.remote.RemoteBookSort
import io.legado.app.ui.book.import.remote.RemoteBookViewModel
import io.legado.app.uikit.facade.AppToast
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFastScroller
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppLoading
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppSearchField
import io.legado.app.uikit.v5.components.AppSelectionAction
import io.legado.app.uikit.v5.components.AppSelectionBar
import io.legado.app.uikit.v5.components.AppSelectionRow
import io.legado.app.uikit.v5.components.rememberAppSelectionState
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.find
import io.legado.app.utils.showHelp
import java.io.File

/**
 * P4-3：远程导入（WebDAV）V5 化（2026-10-13）。
 * 数据层复用 [RemoteBookViewModel]；目录导航/压缩包起读走 [rememberImportBookShell]。
 * 首次帮助弹窗按迁移门规范改为菜单项 + 手动触发，不再进页面即弹。
 */
class HostRemoteBookActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, HostRemoteBookActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostRemoteBookScreen() }
    }
}

@Composable
private fun HostRemoteBookScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val d = visual.density
    val scope = rememberCoroutineScope()
    val shell = rememberImportBookShell()
    val viewModel = remember { ViewModelProvider(activity)[RemoteBookViewModel::class.java] }

    var filter by remember { mutableStateOf("") }
    var pathText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var redownloadTarget by remember { mutableStateOf<RemoteBook?>(null) }

    val selection = rememberAppSelectionState<RemoteBook>(idOf = { it.path })
    val selectionMode = selection.selectionMode
    val listState = rememberLazyListState()

    val books by viewModel.dataFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // 初始化：存储引导 → initData → upPath
    LaunchedEffect(Unit) {
        shell.ensureStorage { ok ->
            if (!ok) {
                activity.finish()
                return@ensureStorage
            }
            viewModel.initData {
                upPath(viewModel, onPath = { pathText = it }, onLoading = { loading = it })
            }
        }
    }

    fun upPath() = upPath(viewModel, onPath = { pathText = it }, onLoading = { loading = it })

    fun goBackDir(): Boolean {
        if (viewModel.dirList.isEmpty()) return false
        viewModel.dirList.removeLastOrNull()
        upPath()
        return true
    }

    BackHandler(enabled = true) {
        if (selectionMode) selection.exit()
        else if (!goBackDir()) activity.finish()
    }

    fun startRead(remoteBook: RemoteBook) {
        val name = remoteBook.filename
        if (!ArchiveUtils.isArchive(name)) {
            appDb.bookDao.getBookByFileName(name)?.let { shell.startRead(it) }
        } else {
            val treeUri = AppConfig.defaultBookTreeUri ?: return
            val doc: FileDoc? = FileDoc.fromUri(Uri.parse(treeUri), true).find(name)
            if (doc == null) {
                redownloadTarget = remoteBook
            } else {
                shell.onArchiveClick(doc)
            }
        }
    }

    val selectionBar: @Composable () -> Unit = {
        if (selectionMode) {
            AppSelectionBar(
                primaryActions = listOf(
                    AppSelectionAction(stringResource(R.string.add_to_bookshelf)) {
                        loading = true
                        viewModel.addToBookshelf(HashSet(selection.selectedFrom(books))) {
                            selection.exit()
                            loading = false
                        }
                    },
                    AppSelectionAction(stringResource(R.string.v5_select_all)) { selection.selectAll(books) },
                    AppSelectionAction(stringResource(R.string.v5_select_invert)) { selection.invert(books) },
                ),
                overflowTitle = stringResource(R.string.v5_batch_actions_title, selection.count),
                moreLabel = stringResource(R.string.v5_more),
            )
        }
    }

    HostV5Page(
        title = stringResource(R.string.remote_book),
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = d.screenPadding, vertical = d.blockGap / 4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.blockGap / 2),
            ) {
                Text(
                    text = pathText.ifBlank { stringResource(R.string.remote_book) },
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (viewModel.dirList.isNotEmpty()) {
                    AppGhostButton(text = "↑", onClick = { goBackDir() })
                }
                if (loading) AppLoading(modifier = Modifier.height(16.dp))
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
            Spacer(Modifier.height(d.blockGap / 4))

            if (books.isEmpty()) {
                AppEmpty(
                    title = stringResource(R.string.remote_book),
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
                        items(books, key = { it.path }) { book ->
                            val isDir = book.isDir
                            AppSelectionRow(
                                title = book.filename,
                                supporting = if (isDir) null else sizeText(book.size),
                                selected = selection.isSelected(book),
                                selectionMode = selectionMode,
                                onClick = {
                                    if (selectionMode) {
                                        if (!isDir) selection.toggle(book)
                                    } else if (isDir) {
                                        viewModel.dirList.add(book)
                                        upPath()
                                    } else {
                                        startRead(book)
                                    }
                                },
                                onLongClick = {
                                    if (!isDir) {
                                        if (selectionMode) selection.toggle(book)
                                        else selection.enter(book)
                                    }
                                },
                                trailing = {
                                    if (!selectionMode && book.isOnBookShelf && !isDir) {
                                        AppGhostButton(
                                            text = "重新加入",
                                            onClick = { redownloadTarget = book },
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
            title = stringResource(R.string.remote_book),
            actions = buildList {
                add(stringResource(R.string.refresh) to { upPath() })
                add(stringResource(R.string.server_config) to {
                    io.legado.app.ui.book.import.remote.ServersDialog()
                        .show(activity.supportFragmentManager, "servers")
                })
                add(stringResource(R.string.log) to {
                    io.legado.app.ui.about.AppLogDialog()
                        .show(activity.supportFragmentManager, "appLog")
                })
                add(stringResource(R.string.help) to { activity.showHelp("webDavBookHelp") })
                add(
                    (if (viewModel.sortKey == RemoteBookSort.Name) "✓ " else "") +
                        stringResource(R.string.sort_by_name) to {
                        if (viewModel.sortKey == RemoteBookSort.Name) {
                            viewModel.sortAscending = !viewModel.sortAscending
                        } else {
                            viewModel.sortAscending = true
                            viewModel.sortKey = RemoteBookSort.Name
                        }
                        upPath()
                    }
                )
                add(
                    (if (viewModel.sortKey == RemoteBookSort.Default) "✓ " else "") +
                        stringResource(R.string.sort_by_time) to {
                        if (viewModel.sortKey == RemoteBookSort.Default) {
                            viewModel.sortAscending = !viewModel.sortAscending
                        } else {
                            viewModel.sortAscending = true
                            viewModel.sortKey = RemoteBookSort.Default
                        }
                        upPath()
                    }
                )
            },
            onDismiss = { menuOpen = false },
        )
    }

    // 压缩包本地不存在 → 询问下载后阅读
    redownloadTarget?.let { book ->
        AppConfirmDialog(
            title = stringResource(R.string.draw),
            message = stringResource(R.string.archive_not_found),
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = {
                loading = true
                viewModel.addToBookshelf(hashSetOf(book)) {
                    loading = false
                    startRead(book)
                }
                redownloadTarget = null
            },
            onDismiss = { redownloadTarget = null },
        )
    }
}

private fun upPath(
    viewModel: RemoteBookViewModel,
    onPath: (String) -> Unit,
    onLoading: (Boolean) -> Unit,
) {
    var path = if (viewModel.isDefaultWebdav) "books" + File.separator else File.separator
    viewModel.dirList.forEach { path = path + it.filename + File.separator }
    onPath(path)
    viewModel.dataCallback?.clear()
    viewModel.loadRemoteBookList(viewModel.dirList.lastOrNull()?.path) { onLoading(it) }
}

private fun sizeText(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> String.format("%.1f MB", size / 1024.0 / 1024.0)
    }
}
