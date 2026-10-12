package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.ui.file.FileManageViewModel
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppIcons
import io.legado.app.uikit.v5.components.AppSearchField
import io.legado.app.uikit.v5.components.AppSelectionRow
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.openFileUri
import java.io.File

/**
 * P4-3：文件管理 V5 化（2026-10-12）。
 * 复用 [FileManageViewModel]（目录导航/删除）；面包屑 + 搜索 + 目录/文件行。
 */
class HostFileManageActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROOT_PATH = "rootPath"
        const val EXTRA_TITLE = "title"

        fun start(context: Context, rootPath: String? = null, title: String? = null) {
            context.startActivity(
                Intent(context, HostFileManageActivity::class.java).apply {
                    rootPath?.let { putExtra(EXTRA_ROOT_PATH, it) }
                    title?.let { putExtra(EXTRA_TITLE, it) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rootPath = intent.getStringExtra(EXTRA_ROOT_PATH)
        setV5Content {
            HostFileManageScreen(
                pageTitle = intent.getStringExtra(EXTRA_TITLE)
                    ?: getString(R.string.file_manage),
                rootPath = rootPath,
            )
        }
    }
}

@Composable
private fun HostFileManageScreen(pageTitle: String, rootPath: String?) {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val density = visual.density
    val viewModel = remember {
        ViewModelProvider(activity)[FileManageViewModel::class.java]
    }

    var searchQuery by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    androidx.compose.runtime.LaunchedEffect(rootPath) {
        rootPath?.takeIf { it.isNotBlank() }?.let { File(it) }?.apply { mkdirs() }
            ?.let(viewModel::setRoot)
    }

    val files by viewModel.filesLiveData.observeAsStateCompat(emptyList())
    val subDocs by androidx.compose.runtime.remember {
        androidx.compose.runtime.derivedStateOf { viewModel.subDocs.toList() }
    }

    fun handleBack() {
        if (viewModel.lastDir != viewModel.rootDoc) {
            viewModel.subDocs.removeLastOrNull()
            viewModel.upFiles(viewModel.lastDir)
        } else {
            activity.finish()
        }
    }

    fun onFileClick(file: File) {
        if (file.name == "..") {
            handleBack()
        } else if (file.isDirectory && !file.isHiddenWorkspaceBackupDir()) {
            viewModel.subDocs.add(file)
            viewModel.upFiles(file)
        } else if (file.isDirectory) {
            return
        } else {
            context.openFileUri(
                androidx.core.content.FileProvider.getUriForFile(context, AppConst.authority, file),
            )
        }
    }

    val filtered = remember(files, searchQuery) {
        val key = searchQuery.trim()
        if (key.isEmpty()) files
        else files.filter { it.name.contains(key, ignoreCase = true) }
    }

    BackHandler { handleBack() }

    HostV5Page(
        title = pageTitle,
        subtitle = null,
        onBack = { handleBack() },
    ) {
        Column(Modifier.fillMaxSize()) {
            // 面包屑：根目录 + 当前层级（可点回跳）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = density.screenPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = viewModel.rootDoc?.name ?: "/",
                    style = visual.type.caption,
                    color = colors.primary,
                    modifier = Modifier
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) {
                            viewModel.subDocs.clear()
                            viewModel.upFiles(viewModel.rootDoc)
                        }
                        .padding(vertical = 4.dp),
                )
                subDocs.forEachIndexed { index, dir ->
                    Text(
                        text = " > ${dir.name}",
                        style = visual.type.caption,
                        color = colors.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) {
                                viewModel.subDocs =
                                    viewModel.subDocs.subList(0, index + 1).toMutableList()
                                viewModel.upFiles(viewModel.subDocs.lastOrNull())
                            }
                            .padding(vertical = 4.dp),
                    )
                }
            }

            AppSearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.padding(
                    horizontal = density.screenPadding,
                    vertical = density.blockGap / 2,
                ),
                placeholder = stringResource(R.string.search),
            )

            if (filtered.isEmpty()) {
                AppEmpty(title = stringResource(R.string.empty))
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
                    items(filtered, key = { it.absolutePath }) { file ->
                        AppSelectionRow(
                            title = file.name,
                            selected = false,
                            selectionMode = false,
                            onClick = { onFileClick(file) },
                            onLongClick = { deleteTarget = file },
                            leading = {
                                Icon(
                                    imageVector = if (file.isDirectory) AppIcons.Chevron
                                    else AppIcons.Inbox,
                                    contentDescription = null,
                                    tint = colors.onSurfaceVariant,
                                    modifier = Modifier.size(density.iconSize * 0.7f),
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    // 删除确认（B 门）
    deleteTarget?.let { file ->
        AppConfirmDialog(
            title = file.name,
            message = file.absolutePath,
            confirmLabel = stringResource(R.string.v5_delete),
            cancelLabel = stringResource(R.string.v5_cancel),
            danger = true,
            onConfirm = {
                deleteTarget = null
                viewModel.delFile(file)
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

private fun File.isHiddenWorkspaceBackupDir(): Boolean {
    return isDirectory && name.lowercase() in setOf(".backup", ".backups")
}
