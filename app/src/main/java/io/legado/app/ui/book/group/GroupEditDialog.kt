package io.legado.app.ui.book.group

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.AppAsyncImage
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.inputStream
import io.legado.app.utils.readUri
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.FileOutputStream

/**
 * 书籍分组编辑（V5 基座版）：新建 / 编辑分组。
 *
 * 尺寸由旧 `AppDialogSize.Form` 映射为 widthFraction 0.94f / maxWidthDp 660；
 * 旧覆写的 `dialogGravity = CENTER` + `dialogWindowAnimations = AnimDialogCenter` 恰是 V5 基座默认值，**不重复覆写**。
 * 组件替换：旧桥 `BookCoverImage` → V5 `AppAsyncImage`（本地路径/网络 URL 均可），
 * 自定义 OutlinedTextField → AppTextField，LegadoMiuixActionButton → AppGhostButton/AppFilledButton，
 * **删除确认由旧 `alert(...)` 换为 V5 `AppConfirmDialog(danger = true)`**（危险操作红线，且与 CrashLogsDialog/DebugLogDialog 一致）；
 * 排序下拉仍用 M3 `DropdownMenu`（基座已提供 M3 主题桥，仅配色换 V5 语义色）。
 * 业务保留：封面选择走 HandleFileContract（http(s) 直接存 URL，本地文件按 MD5 命名落 covers/ 目录）、
 * 名称非空校验、`bookSort` 的 spinner index = bookSort + 1 映射（保存时 `sortIndex - 1`）、
 * 「groupId > 0 或 == Long.MIN_VALUE 才允许删除」。
 */
internal class GroupEditDialog() : V5DialogFragment() {

    constructor(bookGroup: BookGroup? = null) : this() {
        arguments = Bundle().apply {
            putParcelable("group", bookGroup?.copy())
        }
    }

    /** 旧 AppDialogSize.Form(widthFraction = 0.94f, maxWidthDp = 660) */
    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int = 660

    private val viewModel by viewModels<GroupViewModel>()
    private var coverPath by mutableStateOf<String?>(null)
    private var deleteConfirmOpen by mutableStateOf(false)

    private val selectImage = registerForActivityResult(HandleFileContract()) {
        val uri = it.uri ?: return@registerForActivityResult
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            coverPath = uri.toString()
            return@registerForActivityResult
        }
        readUri(uri) { fileDoc, inputStream ->
            try {
                var file = requireContext().externalFiles
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = it.uri.inputStream(requireContext()).getOrThrow().use { tmp ->
                    MD5Utils.md5Encode(tmp) + suffix
                }
                file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                coverPath = file.absolutePath
            } catch (e: Exception) {
                appCtx.toastOnUi(e.localizedMessage)
            }
        }
    }

    @Composable
    override fun Content() {
        @Suppress("DEPRECATION")
        val initialGroup: BookGroup? = remember { arguments?.getParcelable("group") }
        if (coverPath == null) {
            coverPath = initialGroup?.cover
        }

        GroupEditContent(
            existingGroup = initialGroup,
            coverPath = coverPath,
            onSelectImage = {
                selectImage.launch {
                    mode = HandleFileContract.IMAGE
                }
            },
            onClearCover = { coverPath = null },
            onSave = { name, sort, refresh, onlyRead ->
                if (name.isBlank()) {
                    requireContext().toastOnUi("分组名称不能为空")
                    return@GroupEditContent
                }
                initialGroup?.let {
                    it.groupName = name
                    it.cover = coverPath
                    it.bookSort = sort
                    it.enableRefresh = refresh
                    it.onlyUpdateRead = onlyRead
                    viewModel.upGroup(it) { dismiss() }
                } ?: viewModel.addGroup(
                    name, sort, refresh, onlyRead, coverPath
                ) { dismiss() }
            },
            onDelete = { deleteConfirmOpen = true },
            onCancel = { dismiss() }
        )

        if (deleteConfirmOpen) {
            AppConfirmDialog(
                title = stringResource(R.string.delete),
                message = stringResource(R.string.sure_del),
                confirmLabel = stringResource(R.string.delete),
                cancelLabel = stringResource(R.string.no),
                danger = true,
                onConfirm = {
                    deleteConfirmOpen = false
                    initialGroup?.let { group ->
                        viewModel.delGroup(group) { dismiss() }
                    }
                },
                onDismiss = { deleteConfirmOpen = false },
            )
        }
    }
}

@Composable
private fun GroupEditContent(
    existingGroup: BookGroup?,
    coverPath: String?,
    onSelectImage: () -> Unit,
    onClearCover: () -> Unit,
    onSave: (name: String, sort: Int, refresh: Boolean, onlyRead: Boolean) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val sortOptions = stringArrayResource(R.array.book_sort)

    var groupName by remember { mutableStateOf(existingGroup?.groupName.orEmpty()) }
    // bookSort: -1=默认; spinner index = bookSort + 1
    var sortIndex by remember {
        mutableIntStateOf(
            ((existingGroup?.bookSort ?: -1) + 1).coerceIn(0, sortOptions.size - 1)
        )
    }
    var enableRefresh by remember { mutableStateOf(existingGroup?.enableRefresh ?: true) }
    var onlyUpdateRead by remember { mutableStateOf(existingGroup?.onlyUpdateRead ?: false) }

    val canDelete = existingGroup != null &&
        (existingGroup.groupId > 0 || existingGroup.groupId == Long.MIN_VALUE)

    AppDialog(onDismiss = onCancel) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
        ) {
            Text(
                text = if (existingGroup == null) {
                    stringResource(R.string.add_group)
                } else {
                    stringResource(R.string.edit)
                },
                style = visual.type.title,
                color = colors.onSurface
            )
            // 封面图
            Box(
                modifier = Modifier
                    .size(width = 90.dp, height = 120.dp)
                    .clickable { onSelectImage() }
            ) {
                AppAsyncImage(
                    model = coverPath,
                    contentDescription = null,
                    cornerRadius = 8.dp,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            AppTextField(
                value = groupName,
                onValueChange = { groupName = it },
                placeholder = stringResource(R.string.group_name),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // 排序选择
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.sort),
                    style = visual.type.body,
                    color = colors.onSurface
                )
                Spacer(modifier = Modifier.width(12.dp))
                SortDropdown(
                    options = sortOptions.toList(),
                    selectedIndex = sortIndex,
                    onSelect = { sortIndex = it }
                )
            }

            // 复选框
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { enableRefresh = !enableRefresh },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = enableRefresh,
                    onCheckedChange = { enableRefresh = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = colors.primary,
                        uncheckedColor = colors.onSurfaceVariant,
                        checkmarkColor = colors.onPrimary
                    )
                )
                Text(
                    text = stringResource(R.string.refresh),
                    style = visual.type.body,
                    color = colors.onSurface
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onlyUpdateRead = !onlyUpdateRead },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = onlyUpdateRead,
                    onCheckedChange = { onlyUpdateRead = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = colors.primary,
                        uncheckedColor = colors.onSurfaceVariant,
                        checkmarkColor = colors.onPrimary
                    )
                )
                Text(
                    text = stringResource(R.string.only_update_read),
                    style = visual.type.body,
                    color = colors.onSurface
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 3)) {
                if (canDelete) {
                    AppGhostButton(
                        text = stringResource(R.string.delete),
                        modifier = Modifier.weight(1f),
                        onClick = onDelete,
                    )
                }
                AppGhostButton(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.weight(1f),
                    onClick = onCancel,
                )
                AppFilledButton(
                    text = stringResource(R.string.ok),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onSave(groupName, sortIndex - 1, enableRefresh, onlyUpdateRead)
                    },
                )
            }
        }
    }
}

@Composable
private fun SortDropdown(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            text = options.getOrNull(selectedIndex) ?: options.first(),
            color = colors.primary,
            style = visual.type.body,
            modifier = Modifier
                .clip(visual.shape(ShapeKind.BUTTON))
                .background(colors.surfaceVariant)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            color = colors.onSurface,
                            style = visual.type.body
                        )
                    },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    }
                )
            }
        }
    }
}
