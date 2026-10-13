package io.legado.app.ui.host

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.legado.app.R
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.model.localBook.LocalBook
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi

/**
 * P4-3：导入页（本地/远程）共享壳助手。
 * 从 BaseImportBookActivity 的 protected 方法抽出：书籍目录选择/存储位置引导、
 * 起读、压缩包内文件选择与"导入后阅读"。本地/远程两个 V5 页共用。
 */
class ImportBookShell internal constructor(
    /** 弹 SAF 选书籍目录。 */
    val launchTreePicker: () -> Unit,
    /** 确保书籍保存位置；已设置直接 true，否则弹引导。 */
    val ensureStorage: (onReady: (Boolean) -> Unit) -> Unit,
    val startRead: (Book) -> Unit,
    val onArchiveClick: (FileDoc) -> Unit,
)

@Composable
fun rememberImportBookShell(): ImportBookShell {
    val context = LocalContext.current
    var pendingStorage by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var helpOpen by remember { mutableStateOf(false) }
    var archiveChoices by remember { mutableStateOf<Pair<FileDoc, List<String>>?>(null) }
    var importAsk by remember { mutableStateOf<Pair<FileDoc, String>?>(null) }
    val helpText = remember {
        runCatching {
            context.assets.open("storageHelp.md").readBytes().decodeToString()
        }.getOrDefault("")
    }

    val treeLauncher = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        val ok = result.uri != null
        result.uri?.let { AppConfig.defaultBookTreeUri = it.toString() }
        pendingStorage?.invoke(ok)
        pendingStorage = null
    }

    val shell = remember {
        ImportBookShell(
            launchTreePicker = { treeLauncher.launch { } },
            ensureStorage = { cb ->
                if (!AppConfig.defaultBookTreeUri.isNullOrBlank()) cb(true)
                else {
                    pendingStorage = cb
                    helpOpen = true
                }
            },
            startRead = { book -> context.startActivityForBook(book) },
            onArchiveClick = { fileDoc ->
                val names = ArchiveUtils.getArchiveFilesName(fileDoc) {
                    it.matches(AppPattern.bookFileRegex)
                }
                when (names.size) {
                    0 -> context.toastOnUi(R.string.unsupport_archivefile_entry)
                    1 -> resolveArchive(context, fileDoc, names[0]) { doc, name ->
                        importAsk = doc to name
                    }
                    else -> archiveChoices = fileDoc to names
                }
            },
        )
    }

    if (helpOpen) {
        AppConfirmDialog(
            title = context.getString(R.string.select_book_folder),
            message = helpText,
            confirmLabel = context.getString(R.string.ok),
            cancelLabel = context.getString(R.string.cancel),
            onConfirm = {
                helpOpen = false
                shell.launchTreePicker()
            },
            onDismiss = {
                helpOpen = false
                pendingStorage?.invoke(false)
                pendingStorage = null
            },
        )
    }

    archiveChoices?.let { (doc, names) ->
        AppMenuSheet(
            title = context.getString(R.string.start_read),
            actions = names.map { name ->
                name to {
                    archiveChoices = null
                    resolveArchive(context, doc, name) { d, n ->
                        importAsk = d to n
                    }
                }
            },
            onDismiss = { archiveChoices = null },
        )
    }

    importAsk?.let { (doc, name) ->
        AppConfirmDialog(
            title = context.getString(R.string.draw),
            message = context.getString(R.string.no_book_found_bookshelf),
            confirmLabel = context.getString(R.string.ok),
            cancelLabel = context.getString(R.string.cancel),
            onConfirm = {
                LocalBook.importArchiveFile(doc.uri, name) { it.contains(name) }
                    .firstOrNull()?.let { shell.startRead(it) }
                importAsk = null
            },
            onDismiss = { importAsk = null },
        )
    }

    return shell
}

/** 压缩包内文件：书库有则起读，没有则回调询问"导入后阅读"。 */
private fun resolveArchive(
    context: Context,
    fileDoc: FileDoc,
    name: String,
    onMissing: (FileDoc, String) -> Unit,
) {
    appDb.bookDao.getBookByFileName(name)?.let {
        context.startActivityForBook(it)
    } ?: onMissing(fileDoc, name)
}
