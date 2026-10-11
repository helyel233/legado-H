package io.legado.app.ui.host

import androidx.compose.foundation.background
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
    0 to "极简列表", 1 to "紧凑列表", 2 to "两列网格",
    3 to "三列网格", 4 to "四列网格", 5 to "五列网格",
)
private val SORT_OPTIONS = listOf(
    0 to "最近阅读", 1 to "更新时间", 2 to "书名", 3 to "手动排序", 4 to "智能",
)
private val LIST_STYLES = listOf(0 to "经典", 1 to "紧凑")

/** 书架设置（V5 版，替代旧 BookshelfConfigDialog）。全部改动写回偏好并即时生效。 */
@Composable
fun ShelfSettingsSheet(
    scope: ContentScope,
    layout: Int,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = io.legado.app.uikit.v5.runtime.LocalSemanticColors.current

    fun <T> commit(reapply: (T) -> Unit): (T) -> Unit = { v ->
        reapply(v)
        onChanged()
    }

    AppBottomSheetWrap(scope, title = "书架设置", onDismiss = onDismiss) {
        SheetSection(scope, "布局") {
            OptionChips(scope, LAYOUT_OPTIONS, layout) { v ->
                AppConfig.bookshelfLayout = v
                onChanged()
            }
        }
        SheetSection(scope, "排序") {
            OptionChips(scope, SORT_OPTIONS, AppConfig.bookshelfSort) { v ->
                AppConfig.bookshelfSort = v
                onChanged()
            }
        }
        SheetSection(scope, "列表样式（仅列表布局）") {
            OptionChips(scope, LIST_STYLES, AppConfig.bookshelfListItemStyle) { v ->
                AppConfig.bookshelfListItemStyle = v
                onChanged()
            }
        }
        SheetSection(scope, "简介行数（仅列表布局）") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${AppConfig.bookshelfListIntroLines} 行",
                    style = scope.type.body,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                AppSwitch(
                    checked = AppConfig.bookshelfListIntroLines > 0,
                    onCheckedChange = { on ->
                        AppConfig.bookshelfListIntroLines = if (on) 2 else 0
                        onChanged()
                    },
                )
            }
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
                        "重命名",
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
            Text("＋ 新建分组", style = scope.type.label, color = colors.primary)
        }
        Spacer(Modifier.height(scope.density.screenPadding))
    }

    // 新建 / 重命名输入
    if (creating || renameTarget != null) {
        AppDialog(onDismiss = { creating = false; renameTarget = null }) {
            Column(Modifier.padding(scope.density.screenPadding * 1.2f)) {
                Text(
                    if (renameTarget != null) "重命名分组" else "新建分组",
                    style = scope.type.title,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(scope.density.blockGap))
                AppTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = "分组名称",
                )
                Spacer(Modifier.height(scope.density.blockGap))
                Row(horizontalArrangement = Arrangement.spacedBy(scope.density.blockGap)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(colors.primary, scope.shape(ShapeKind.BUTTON))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { commitName() }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("确定", style = scope.type.label, color = colors.onPrimary)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(colors.surfaceVariant, scope.shape(ShapeKind.BUTTON))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { creating = false; renameTarget = null }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("取消", style = scope.type.label, color = colors.onSurface)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 添加网址

@Composable
fun AddByUrlDialog(
    scope: ContentScope,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = io.legado.app.uikit.v5.runtime.LocalSemanticColors.current
    var input by remember { mutableStateOf("") }
    AppDialog(onDismiss = onDismiss) {
        Column(Modifier.padding(scope.density.screenPadding * 1.2f)) {
            Text("添加网址", style = scope.type.title, color = colors.onSurface)
            Spacer(Modifier.height(scope.density.blockGap))
            AppTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = "url",
            )
            Spacer(Modifier.height(scope.density.blockGap))
            Row(horizontalArrangement = Arrangement.spacedBy(scope.density.blockGap)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(colors.primary, scope.shape(ShapeKind.BUTTON))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) {
                            if (input.isNotBlank()) {
                                onConfirm(input.trim())
                                onDismiss()
                            }
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("确定", style = scope.type.label, color = colors.onPrimary)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(colors.surfaceVariant, scope.shape(ShapeKind.BUTTON))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { onDismiss() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("取消", style = scope.type.label, color = colors.onSurface)
                }
            }
        }
    }
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
    items: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = io.legado.app.uikit.v5.runtime.LocalSemanticColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowItems.forEach { (value, label) ->
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
                            label,
                            style = scope.type.caption,
                            color = if (on) colors.onPrimary else colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
