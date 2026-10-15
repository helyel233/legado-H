package io.legado.app.ui.host

import io.legado.app.uikit.v5.components.AppSlider
import kotlin.math.roundToInt
import io.legado.app.R

import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.contract.ContentScope
import io.legado.app.uikit.v5.token.ShapeKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P4-1b 书架的 V5 原生弹层集（替代旧 DialogFragment 复用 —— 用户裁定：
 * 最终要完全抛弃老 UI，一切弹层必须在新统一架构上实现）。
 *
 * 迁移纪律（用户 2026-11-12 指示）：搬迁页面前先扫描功能清单，
 * 在新架构上想好实现再动手，禁止为图快复用旧 UI 组件。
 */

// ---------------------------------------------------------------- 书架设置

private val LAYOUT_OPTIONS = listOf(
    0 to R.string.v5_shelf_layout_minimal, 1 to R.string.v5_shelf_layout_compact,
    2 to R.string.v5_shelf_grid_2, 3 to R.string.v5_shelf_grid_3,
    4 to R.string.v5_shelf_grid_4, 5 to R.string.v5_shelf_grid_5,
    6 to R.string.v5_shelf_grid_6,
)
private val SORT_OPTIONS = listOf(
    0 to R.string.v5_shelf_sort_recent, 1 to R.string.v5_shelf_sort_update,
    2 to R.string.v5_shelf_book_name, 3 to R.string.v5_shelf_sort_manual, 4 to R.string.v5_shelf_sort_smart,
    5 to R.string.bookshelf_px_5,
)
private val LIST_STYLES = listOf(0 to R.string.v5_shelf_minimal, 1 to R.string.v5_shelf_layout_compact)

/** 书架设置（V5 版，替代旧 BookshelfConfigDialog）。全部改动写回偏好并即时生效。 */
@Composable
fun ShelfSettingsSheet(
    scope: ContentScope,
    layout: Int,
    onLayoutChange: (Int) -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = io.legado.app.uikit.v5.runtime.LocalSemanticColors.current

    AppBottomSheetWrap(scope, title = stringResource(R.string.v5_shelf_settings), onDismiss = onDismiss) {
        SheetSection(scope, "布局") {
            OptionChips(scope, LAYOUT_OPTIONS, layout) { v ->
                AppConfig.bookshelfLayout = v
                onLayoutChange(v) // 立即反映到网格列数，不等关面板
                onChanged()
            }
        }
        SheetSection(scope, "排序") {
            OptionChips(scope, SORT_OPTIONS, AppConfig.bookshelfSort) { v ->
                AppConfig.bookshelfSort = v
                onChanged()
            }
        }
        SheetSection(scope, "显示") {
            val switches = listOf(
                "未读数" to { AppConfig.showUnread },
                "最后更新时间" to { AppConfig.showLastUpdateTime },
                "待更新数" to { AppConfig.showWaitUpCount },
                "阅读后回到顶部" to { AppConfig.bookshelfReturnToTopAfterRead },
            )
            switches.forEach { (label, read) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = scope.type.body,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    AppSwitch(
                        checked = read(),
                        onCheckedChange = { on ->
                            when (label) {
                                "未读数" -> AppConfig.showUnread = on
                                "最后更新时间" -> AppConfig.showLastUpdateTime = on
                                "待更新数" -> AppConfig.showWaitUpCount = on
                                "阅读后回到顶部" -> AppConfig.bookshelfReturnToTopAfterRead = on
                            }
                            onChanged()
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // 书名显示（仅网格布局有意义，沿用旧 BookshelfConfigDialog 三态）
            Text("书名（网格）", style = scope.type.caption, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "显示", 1 to "隐藏", 2 to "覆盖").forEach { (v, label) ->
                    AppChip(
                        text = label,
                        selected = AppConfig.showBookname == v,
                        onClick = {
                            AppConfig.showBookname = v
                            onChanged()
                        },
                    )
                }
            }
        }
        SheetSection(scope, "列表样式（仅列表布局）") {
            OptionChips(scope, LIST_STYLES, AppConfig.bookshelfListItemStyle) { v ->
                AppConfig.bookshelfListItemStyle = v
                onChanged()
            }
        }
        SheetSection(scope, "简介行数（仅列表布局）") {
            OptionChips(
                scope,
                listOf(0 to R.string.v5_shelf_intro_0, 1 to R.string.v5_shelf_intro_1,
                    2 to R.string.v5_shelf_intro_2, 3 to R.string.v5_shelf_intro_3),
                AppConfig.bookshelfListIntroLines,
            ) { v ->
                AppConfig.bookshelfListIntroLines = v
                onChanged()
            }
        }
        SheetSection(scope, "书架间距") {
            AppSlider(
                value = AppConfig.bookshelfMargin.toFloat(),
                onValueChange = { AppConfig.bookshelfMargin = it.roundToInt().coerceIn(0, 60) },
                valueRange = 0f..60f,
                steps = 59,
                valueLabel = "${AppConfig.bookshelfMargin} dp",
            )
        }
        Spacer(Modifier.height(scope.density.screenPadding))
    }
}

// ---------------------------------------------------------------- 分组管理

/** 分组管理（V5 版，替代旧 GroupManageDialog）：显隐开关 + 新建 / 重命名 / 删除。 */
@Composable
fun GroupManageSheet(
    scope: ContentScope,
    onDismiss: () -> Unit,
) {
    val colors = io.legado.app.uikit.v5.runtime.LocalSemanticColors.current
    val context = LocalContext.current
    val groups by produceState<List<BookGroup>>(emptyList()) {
        appDb.bookGroupDao.flowAll().collect { value = it }
    }
    var renameTarget by remember { mutableStateOf<BookGroup?>(null) }
    var creating by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    fun commitName() {
        val name = input.trim()
        if (name.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            val target = renameTarget
            if (target != null) {
                appDb.bookGroupDao.update(target.copy(groupName = name))
            } else {
                val id = runCatching { appDb.bookGroupDao.getUnusedId() }.getOrElse { System.currentTimeMillis() }
                val maxOrder = runCatching { appDb.bookGroupDao.maxOrder }.getOrElse { 0 }
                appDb.bookGroupDao.insert(
                    BookGroup(
                        groupId = id, groupName = name,
                        order = maxOrder + 1, show = true,
                    )
                )
            }
            withContext(Dispatchers.Main) {
                renameTarget = null
                creating = false
                input = ""
            }
        }
    }

    AppBottomSheetWrap(scope, title = "分组管理", onDismiss = onDismiss) {
        Text(
            "开关控制分组是否显示在书架",
            style = scope.type.caption,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(scope.density.blockGap / 2))
        groups.sortedBy { it.order }.forEach { group ->
            val isVirtual = group.groupId < 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        group.groupName,
                        style = scope.type.body,
                        color = colors.onSurface,
                        maxLines = 1,
                    )
                }
                if (!isVirtual) {
                    Text(
                        stringResource(R.string.v5_shelf_rename),
                        style = scope.type.caption,
                        color = colors.primary,
                        modifier = Modifier
                            .padding(end = scope.density.blockGap)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) {
                                renameTarget = group
                                input = group.groupName
                            },
                    )
                    Text(
                        "删除",
                        style = scope.type.caption,
                        color = colors.error,
                        modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) {
                            CoroutineScope(Dispatchers.IO).launch {
                                appDb.bookGroupDao.delete(group)
                            }
                        },
                    )
                }
                AppSwitch(
                    checked = group.show,
                    onCheckedChange = { on ->
                        CoroutineScope(Dispatchers.IO).launch {
                            appDb.bookGroupDao.update(group.copy(show = on))
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(scope.density.blockGap))
        // 新建分组
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.primary.copy(alpha = 0.14f), scope.shape(ShapeKind.BUTTON))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    creating = true
                    input = ""
                }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.v5_shelf_new_group), style = scope.type.label, color = colors.primary)
        }
        Spacer(Modifier.height(scope.density.screenPadding))
    }

    // 新建 / 重命名输入（AppInputDialog 统一件）
    if (creating || renameTarget != null) {
        AppInputDialog(
            title = if (renameTarget != null) stringResource(R.string.v5_shelf_rename_group)
            else stringResource(R.string.v5_shelf_new_group),
            value = input,
            onValueChange = { input = it },
            placeholder = "分组名称",
            confirmLabel = stringResource(R.string.v5_ok2),
            cancelLabel = stringResource(R.string.v5_cancel2),
            onConfirm = { commitName() },
            onDismiss = { creating = false; renameTarget = null },
        )
    }
}

// ---------------------------------------------------------------- 添加网址

@Composable
fun AddByUrlDialog(
    scope: ContentScope,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    AppInputDialog(
        title = "添加网址",
        value = input,
        onValueChange = { input = it },
        placeholder = "url",
        confirmLabel = stringResource(R.string.v5_ok2),
        cancelLabel = stringResource(R.string.v5_cancel2),
        onConfirm = { text ->
            if (text.isNotBlank()) {
                onConfirm(text)
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    )
}

// ---------------------------------------------------------------- 通用件

/** 弹层通用外壳：E3 材质 + 标题 + 滚动内容。 */
@Composable
fun AppBottomSheetWrap(
    scope: ContentScope,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    io.legado.app.uikit.v5.components.AppBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(scope.density.screenPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                title,
                style = scope.type.title,
                color = io.legado.app.uikit.v5.runtime.LocalSemanticColors.current.onSurface,
            )
            Spacer(Modifier.height(scope.density.blockGap))
            content()
        }
    }
}

@Composable
fun SheetSection(scope: ContentScope, title: String, content: @Composable () -> Unit) {
    val colors = io.legado.app.uikit.v5.runtime.LocalSemanticColors.current
    Column {
        Text(title, style = scope.type.label, color = colors.primary)
        Spacer(Modifier.height(6.dp))
        content()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun OptionChips(
    scope: ContentScope,
    items: List<Pair<Int, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = io.legado.app.uikit.v5.runtime.LocalSemanticColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowItems.forEach { (value, labelRes) ->
                    val on = value == selected
                    Box(
                        modifier = Modifier
                            .clip(scope.shape(ShapeKind.TAG))
                            .background(if (on) colors.primary else colors.surfaceVariant)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { onSelect(value) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            stringResource(labelRes),
                            style = scope.type.caption,
                            color = if (on) colors.onPrimary else colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
