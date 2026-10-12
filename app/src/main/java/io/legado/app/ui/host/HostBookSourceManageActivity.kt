package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.manage.BookSourceSort
import io.legado.app.ui.book.source.manage.BookSourceViewModel
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppSearchField
import io.legado.app.uikit.v5.components.AppSkeletonList
import io.legado.app.uikit.v5.components.AppSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.startActivity

/**
 * P4-3 管理页原生化①：书源管理 V5 原生。
 *
 * 取代旧 BookSourceActivity 呈现层（1323 行 View 壳 + 选择模式底栏）。
 * 数据层全复用 [BookSourceViewModel]（置顶/置底/启停/分组/导出）与
 * bookSourceDao 的 reactive Flow（全部/启用/禁用/需登录/无分组/选书/分组/搜索）。
 *
 * 交互对齐旧壳：
 *  - 状态筛选 chips（全部/已启用/已禁用/需登录/无分组/选书开/选书关）+ 搜索（含 group: 前缀）
 *  - 排序（默认/名称/URL/权重/更新时间/启用/响应速度 × 升降序），默认排序支持升降序
 *  - 长按进选择模式 → 批量：全选/反选、启用/禁用（选书+选发现）、置顶/置底、
 *    加分组/移分组、删除、导出（SAF）
 *  - 单击条目 = 编辑；行内开关 = 启停（选择模式中单击 = 勾选）
 */
class HostBookSourceManageActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, HostBookSourceManageActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        setContentView(
            ComposeView(this).apply {
                setContent { HostV5ThemeRoot { HostBookSourceManageScreen() } }
            }
        )
    }
}

private enum class StatusFilter(val label: String) {
    ALL("全部"), ENABLED("已启用"), DISABLED("已禁用"),
    LOGIN("需登录"), NO_GROUP("无分组"), EXPLORE_ON("选书开"), EXPLORE_OFF("选书关"),
}

@Composable
private fun HostBookSourceManageScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val density = visual.density
    val viewModel = remember {
        ViewModelProvider(activity)[BookSourceViewModel::class.java]
    }

    var searchKey by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf(StatusFilter.ALL) }
    var sort by remember { mutableStateOf(BookSourceSort.Default) }
    var sortAscending by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var groupSheetFor by remember { mutableStateOf<((String) -> Unit)?>(null) }

    val sources by remember(searchKey) {
        val key = searchKey.trim()
        when {
            key.startsWith("group:") -> appDb.bookSourceDao.flowGroupSearch(key.removePrefix("group:"))
            key.isNotBlank() -> appDb.bookSourceDao.flowSearch(key)
            else -> when (statusFilter) {
                StatusFilter.ALL -> appDb.bookSourceDao.flowAll()
                StatusFilter.ENABLED -> appDb.bookSourceDao.flowEnabled()
                StatusFilter.DISABLED -> appDb.bookSourceDao.flowDisabled()
                StatusFilter.LOGIN -> appDb.bookSourceDao.flowLogin()
                StatusFilter.NO_GROUP -> appDb.bookSourceDao.flowNoGroup()
                StatusFilter.EXPLORE_ON -> appDb.bookSourceDao.flowEnabledExplore()
                StatusFilter.EXPLORE_OFF -> appDb.bookSourceDao.flowDisabledExplore()
            }
        }
    }.collectAsState(initial = emptyList())

    val groups by remember { appDb.bookSourceDao.flowGroups() }
        .collectAsState(initial = emptyList())

    val sortedSources = remember(sources, sort, sortAscending) {
        val comparator = when (sort) {
            BookSourceSort.Default -> compareBy<BookSourcePart> { it.customOrder }
            BookSourceSort.Name -> compareBy { it.bookSourceName.lowercase() }
            BookSourceSort.Url -> compareBy { it.bookSourceUrl }
            BookSourceSort.Weight -> compareBy { it.weight }
            BookSourceSort.Update -> compareBy { it.lastUpdateTime }
            BookSourceSort.Enable -> compareBy { it.enabled }
            BookSourceSort.Respond -> compareBy { it.respondTime }
        }
        if (sortAscending) sources.sortedWith(comparator)
        else sources.sortedWith(comparator).asReversed()
    }

    val selectionMode = selected.isNotEmpty()
    val selectedSources = sortedSources.filter { it.bookSourceUrl in selected }

    val exportLauncher = rememberLauncherForActivityResult(HandleFileContract()) {}
    fun exportSelection() {
        viewModel.saveToFile(
            selectedSources, sortedSources.size, searchKey.trim().ifBlank { null },
            sortAscending, sort,
        ) { file, name ->
            exportLauncher.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(name, file, "application/json")
            }
        }
    }

    fun exitSelection() {
        selected = emptySet()
    }

    Column(Modifier.fillMaxSize()) {
        HostV5Page(
            title = if (selectionMode) "已选 ${selected.size} 项" else "书源管理",
            subtitle = if (!selectionMode) "${sortedSources.size} 个源" else null,
            onBack = { if (selectionMode) exitSelection() else activity.finish() },
            trailing = {
                Text(
                    text = "⋮",
                    style = visual.type.title,
                    color = colors.primary,
                    modifier = Modifier
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { menuOpen = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                AppSearchField(
                    value = searchKey,
                    onValueChange = { searchKey = it },
                    modifier = Modifier.padding(horizontal = density.screenPadding),
                    placeholder = "搜索源名称 / group:分组名",
                )
                Spacer(Modifier.height(density.blockGap / 2))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = density.screenPadding),
                    horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    StatusFilter.entries.forEach { f ->
                        AppChip(f.label, statusFilter == f, onClick = {
                            statusFilter = f
                            searchKey = ""
                        })
                    }
                    groups.take(8).forEach { g ->
                        AppChip("分组:$g", searchKey == "group:$g", onClick = {
                            searchKey = "group:$g"
                        })
                    }
                }
                Spacer(Modifier.height(density.blockGap / 2))

                when {
                    sortedSources.isEmpty() -> AppEmpty(
                        title = "没有书源",
                        supporting = "右上角 ⋮ → 导入书源，或新建一个",
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = density.screenPadding,
                            end = density.screenPadding,
                            top = density.blockGap / 2,
                            bottom = if (selectionMode) 96.dp else density.screenPadding,
                        ),
                        verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                    ) {
                        items(sortedSources, key = { it.bookSourceUrl }) { source ->
                            val isSelected = source.bookSourceUrl in selected
                            SourceRow(
                                source = source,
                                selected = isSelected,
                                selectionMode = selectionMode,
                                onToggle = { on ->
                                    viewModel.enable(on, listOf(source))
                                },
                                onClick = {
                                    if (selectionMode) {
                                        selected = if (isSelected) selected - source.bookSourceUrl
                                        else selected + source.bookSourceUrl
                                    } else {
                                        context.startActivity<BookSourceEditActivity> {
                                            putExtra("bookSourceUrl", source.bookSourceUrl)
                                        }
                                    }
                                },
                                onLongClick = {
                                    selected = selected + source.bookSourceUrl
                                },
                            )
                        }
                    }
                }
            }
        }

        // 选择模式操作条
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap / 2),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(visual.shape(ShapeKind.BAR))
                        .background(colors.surface.copy(alpha = 0.85f), visual.shape(ShapeKind.BAR))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = density.blockGap, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(density.blockGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SelectionAction(label = "全选") {
                        selected = sortedSources.map { it.bookSourceUrl }.toSet()
                    }
                    SelectionAction(label = "反选") {
                        val all = sortedSources.map { it.bookSourceUrl }.toSet()
                        selected = all - selected
                    }
                    SelectionAction(label = "启用") {
                        viewModel.enableSelection(selectedSources)
                    }
                    SelectionAction(label = "禁用") {
                        viewModel.disableSelection(selectedSources)
                    }
                    SelectionAction(label = "选书开") {
                        viewModel.enableSelectExplore(selectedSources)
                    }
                    SelectionAction(label = "选书关") {
                        viewModel.disableSelectExplore(selectedSources)
                    }
                    SelectionAction(label = "置顶") {
                        viewModel.topSource(*selectedSources.toTypedArray())
                        exitSelection()
                    }
                    SelectionAction(label = "置底") {
                        viewModel.bottomSource(*selectedSources.toTypedArray())
                        exitSelection()
                    }
                    SelectionAction(label = "加分组") {
                        groupSheetFor = { groupsText ->
                            viewModel.selectionAddToGroups(selectedSources, groupsText)
                            exitSelection()
                        }
                    }
                    SelectionAction(label = "移分组") {
                        groupSheetFor = { groupsText ->
                            viewModel.selectionRemoveFromGroups(selectedSources, groupsText)
                            exitSelection()
                        }
                    }
                    SelectionAction(label = "导出") { exportSelection() }
                    SelectionAction(label = "删除", danger = true) {
                        viewModel.del(selectedSources)
                        exitSelection()
                    }
                }
            }
        }
    }

    // 溢出菜单
    if (menuOpen) {
        AppMenuSheet(
            title = "书源管理",
            actions = listOf(
                "新建书源" to {
                    context.startActivity<BookSourceEditActivity>()
                },
                "导入书源" to {
                    ImportBookSourceDialog().show(activity.supportFragmentManager, "importSource")
                },
                "排序方式…" to { sortOpen = true },
            ),
            onDismiss = { menuOpen = false },
        )
    }

    // 排序选择
    if (sortOpen) {
        AppMenuSheet(
            title = "排序方式",
            actions = listOf(
                BookSourceSort.Default to "默认",
                BookSourceSort.Name to "名称",
                BookSourceSort.Url to "URL",
                BookSourceSort.Weight to "权重",
                BookSourceSort.Update to "更新时间",
                BookSourceSort.Enable to "启用状态",
                BookSourceSort.Respond to "响应速度",
            ).map { (s, label) ->
                (if (sort == s) "✓ " else "") + label to {
                    sort = s
                    sortOpen = false
                }
            } + listOf(
                (if (sortAscending) "✓ " else "") + "升序" to {
                    sortAscending = true
                    sortOpen = false
                },
                (if (!sortAscending) "✓ " else "") + "降序" to {
                    sortAscending = false
                    sortOpen = false
                },
            ),
            onDismiss = { sortOpen = false },
        )
    }

    // 分组输入
    groupSheetFor?.let { onDone ->
        var groupsText by remember { mutableStateOf("") }
        AppDialog(onDismiss = { groupSheetFor = null }) {
            Column(Modifier.padding(density.screenPadding)) {
                Text("分组（多个用英文逗号分隔）", style = visual.type.title, color = colors.onSurface)
                Spacer(Modifier.height(density.blockGap))
                AppTextField(
                    value = groupsText,
                    onValueChange = { groupsText = it },
                    placeholder = "如：默认,精品",
                )
                Spacer(Modifier.height(density.blockGap))
                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(colors.primary, visual.shape(ShapeKind.BUTTON))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) {
                                onDone(groupsText.trim())
                                groupSheetFor = null
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("确定", style = visual.type.label, color = colors.onPrimary)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(colors.surfaceVariant, visual.shape(ShapeKind.BUTTON))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { groupSheetFor = null }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("取消", style = visual.type.label, color = colors.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: BookSourcePart,
    selected: Boolean,
    selectionMode: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val density = visual.density

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(visual.shape(ShapeKind.CARD))
            .background(
                if (selected) colors.primary.copy(alpha = 0.14f)
                else colors.surface.copy(alpha = 0.72f),
                visual.shape(ShapeKind.CARD),
            )
            .combinedClickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = density.cardPadding, vertical = density.cardPadding / 1.6f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Text(
                text = if (selected) "☑" else "☐",
                style = visual.type.title,
                color = colors.primary,
                modifier = Modifier.padding(end = density.blockGap),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = source.bookSourceName,
                style = visual.type.body,
                color = colors.onSurface,
                maxLines = 1,
            )
            val group = source.bookSourceGroup
            if (!group.isNullOrBlank()) {
                Text(
                    text = group,
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (source.hasLoginUrl) {
            Text(
                text = "登录",
                style = visual.type.caption,
                color = colors.primary,
                modifier = Modifier.padding(end = density.blockGap),
            )
        }
        if (!selectionMode) {
            AppSwitch(checked = source.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun SelectionAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    Text(
        text = label,
        style = visual.type.label,
        color = if (danger) colors.error else colors.primary,
        modifier = Modifier
            .clip(visual.shape(ShapeKind.TAG))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
