package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.utils.ACache
import io.legado.app.model.CheckSource
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.manage.BookSourceSort
import io.legado.app.ui.book.source.manage.BookSourceViewModel
import io.legado.app.ui.book.source.manage.GroupManageDialog
import io.legado.app.ui.config.CheckSourceConfig
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.compose.showComposeSuggestionTextInputDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppSearchField
import io.legado.app.uikit.v5.components.AppSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.share
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity

/**
 * P4-3 管理页原生化①：书源管理 V5 原生。
 *
 * 能力对齐 **1.7.3 分支**（origin/archive/pre-1.7.3 的 BookSourceActivity）：
 *  - 筛选：状态 chips + 分组 chips（含 group: 前缀搜索）
 *  - 排序：手动(默认)/自动(权重)/名称/URL/更新时间/启用/响应速度 × 升/降序
 *  - 按域名分组显示（开关）
 *  - 导入：新建 / 在线（URL 输入 + 历史建议）/ 本地文件（txt/json）/ 扫码
 *  - 分组管理（GroupManageDialog）
 *  - 选择模式：全选/反选、启用/禁用、选书开/关、置顶/置底、加分组/移分组、
 *    导出(SAF)、分享、校验书源（关键词 + 校验配置）、校验选中区间、删除
 *
 * 数据层全复用 BookSourceViewModel + bookSourceDao reactive Flow。
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

private const val IMPORT_RECORD_KEY = "bookSourceRecordKey"

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
    var groupByDomain by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var groupSheetFor by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var moreSelectionOpen by remember { mutableStateOf(false) }

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

    // 按域名分组（连续同 host 插分组头）
    val displayRows = remember(sortedSources, groupByDomain) {
        if (!groupByDomain) {
            sortedSources.map { DomainRow.Source(it) }
        } else {
            val rows = mutableListOf<DomainRow>()
            var lastHost: String? = null
            sortedSources.forEach { source ->
                val host = runCatching { Uri.parse(source.bookSourceUrl).host }.getOrNull()
                if (host != null && host != lastHost) {
                    rows.add(DomainRow.Header(host))
                    lastHost = host
                }
                rows.add(DomainRow.Source(source))
            }
            rows
        }
    }

    val selectionMode = selected.isNotEmpty()
    val selectedSources = sortedSources.filter { it.bookSourceUrl in selected }

    val exportLauncher = rememberLauncherForActivityResult(HandleFileContract()) {}
    val importDocLauncher = rememberLauncherForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ImportBookSourceDialog(uri.toString())
                .show(activity.supportFragmentManager, "importSource")
        }
    }
    val qrLauncher = rememberLauncherForActivityResult(QrCodeResult()) {
        it ?: return@rememberLauncherForActivityResult
        ImportBookSourceDialog(it).show(activity.supportFragmentManager, "importSource")
    }

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

    fun shareSelection() {
        viewModel.saveToFile(
            selectedSources, sortedSources.size, searchKey.trim().ifBlank { null },
            sortAscending, sort,
        ) { file, _ -> context.share(file, "application/json") }
    }

    /** 在线导入（URL 输入 + 历史建议，缓存与 1.7.3 同一 key）。 */
    fun importOnLine() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls = aCache.getAsString(IMPORT_RECORD_KEY)
            ?.splitNotBlank(",")?.toMutableList() ?: mutableListOf()
        activity.showComposeSuggestionTextInputDialog(
            title = activity.getString(R.string.import_on_line),
            hint = "url",
            suggestions = cacheUrls,
            deletable = true,
            onPositive = { text ->
                if (text.isAbsUrl() && !cacheUrls.contains(text)) {
                    cacheUrls.add(0, text)
                    aCache.put(IMPORT_RECORD_KEY, cacheUrls.joinToString(","))
                }
                ImportBookSourceDialog(text)
                    .show(activity.supportFragmentManager, "importSource")
            },
            onSuggestionDeleted = { url ->
                cacheUrls.remove(url)
                aCache.put(IMPORT_RECORD_KEY, cacheUrls.joinToString(","))
            },
        )
    }

    /** 校验书源（关键词 + 校验配置入口，与 1.7.3 同流程）。 */
    fun checkSources() {
        activity.showComposeTextInputDialog(
            title = activity.getString(R.string.search_book_key),
            hint = "search word",
            initialValue = CheckSource.keyword,
            neutralText = activity.getString(R.string.check_source_config),
            onPositive = { text ->
                activity.keepScreenOn(true)
                if (text.isNotEmpty()) CheckSource.keyword = text
                CheckSource.start(activity, selectedSources)
            },
            onNeutral = {
                CheckSourceConfig().show(activity.supportFragmentManager, "checkSourceConfig")
            },
        )
    }

    /** 校验选中区间：把首末选中之间的源一并选中。 */
    fun checkSelectedInterval() {
        val positions = sortedSources.mapIndexedNotNull { index, source ->
            if (source.bookSourceUrl in selected) index else null
        }
        if (positions.isEmpty()) return
        val newSelected = selected.toMutableSet()
        for (index in positions.min()..positions.max()) {
            newSelected.add(sortedSources[index].bookSourceUrl)
        }
        selected = newSelected
    }

    fun exitSelection() {
        selected = emptySet()
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
        ) {
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
                Spacer(Modifier.height(density.blockGap))

                // 状态筛选（一排）
                Text(
                    text = "状态",
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = density.screenPadding),
                )
                Spacer(Modifier.height(density.blockGap / 3))
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
                }

                // 分组筛选（一排，独立于状态，与 1.7.3 的 menu_group 对应）
                if (groups.isNotEmpty()) {
                    Spacer(Modifier.height(density.blockGap))
                    Text(
                        text = "分组",
                        style = visual.type.caption,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = density.screenPadding),
                    )
                    Spacer(Modifier.height(density.blockGap / 3))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = density.screenPadding),
                        horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                    ) {
                        AppChip("全部", searchKey.isBlank(), onClick = { searchKey = "" })
                        groups.forEach { g ->
                            AppChip(g, searchKey == "group:$g", onClick = {
                                searchKey = "group:$g"
                            })
                        }
                    }
                }
                Spacer(Modifier.height(density.blockGap))

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
                        items(displayRows, key = { row ->
                            when (row) {
                                is DomainRow.Header -> "h:${row.host}"
                                is DomainRow.Source -> row.source.bookSourceUrl
                            }
                        }) { row ->
                            when (row) {
                                is DomainRow.Header -> Text(
                                    text = row.host,
                                    style = visual.type.caption,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                                is DomainRow.Source -> {
                                    val source = row.source
                                    val isSelected = source.bookSourceUrl in selected
                                    SourceRow(
                                        source = source,
                                        selected = isSelected,
                                        selectionMode = selectionMode,
                                        onToggle = { on -> viewModel.enable(on, listOf(source)) },
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
            }
        }
        }

        // 选择模式操作条：常用 5 项固定可见 + 「更多」收进溢出菜单
        // （对齐 1.7.3 SelectActionBar 的「主操作 + 溢出」结构；全部平铺在横滑条里
        //   会导致后半段（校验/区间/分组/导出/分享）滑出屏幕无法发现）
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
                        .padding(horizontal = density.blockGap, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SelectionAction("全选") {
                        selected = sortedSources.map { it.bookSourceUrl }.toSet()
                    }
                    SelectionAction("反选") {
                        val all = sortedSources.map { it.bookSourceUrl }.toSet()
                        selected = all - selected
                    }
                    SelectionAction("启用") { viewModel.enableSelection(selectedSources) }
                    SelectionAction("禁用") { viewModel.disableSelection(selectedSources) }
                    SelectionAction("删除", danger = true) {
                        viewModel.del(selectedSources)
                        exitSelection()
                    }
                    SelectionAction("更多 ⋮") { moreSelectionOpen = true }
                }
            }
        }
    }

    // 选择模式「更多」溢出菜单（1.7.3 book_source_sel 全量）
    if (moreSelectionOpen) {
        AppMenuSheet(
            title = "批量操作（已选 ${selected.size} 项）",
            actions = listOf(
                "选书开" to { viewModel.enableSelectExplore(selectedSources) },
                "选书关" to { viewModel.disableSelectExplore(selectedSources) },
                "置顶" to {
                    viewModel.topSource(*selectedSources.toTypedArray())
                    exitSelection()
                },
                "置底" to {
                    viewModel.bottomSource(*selectedSources.toTypedArray())
                    exitSelection()
                },
                "加分组…" to {
                    groupSheetFor = { text ->
                        viewModel.selectionAddToGroups(selectedSources, text)
                        exitSelection()
                    }
                },
                "移出分组…" to {
                    groupSheetFor = { text ->
                        viewModel.selectionRemoveFromGroups(selectedSources, text)
                        exitSelection()
                    }
                },
                "校验书源…" to { checkSources() },
                "校验选中区间" to { checkSelectedInterval() },
                "导出…" to { exportSelection() },
                "分享…" to { shareSelection() },
            ),
            onDismiss = { moreSelectionOpen = false },
        )
    }

    // 主溢出菜单（对齐 1.7.3）
    if (menuOpen) {
        AppMenuSheet(
            title = "书源管理",
            actions = buildList {
                add("新建书源" to { context.startActivity<BookSourceEditActivity>() })
                add("在线导入…" to { importOnLine() })
                add("本地导入…" to {
                    importDocLauncher.launch {
                        mode = HandleFileContract.FILE
                        allowExtensions = arrayOf("txt", "json")
                    }
                })
                add("扫码导入" to { qrLauncher.launch(null) })
                add("分组管理…" to {
                    GroupManageDialog().show(activity.supportFragmentManager, "groupManage")
                })
                add("排序方式…" to { sortOpen = true })
                add((if (groupByDomain) "✓ " else "") + "按域名分组" to {
                    groupByDomain = !groupByDomain
                    menuOpen = false
                })
                add("帮助" to { activity.showHelp("SourceMBookHelp") })
            },
            onDismiss = { menuOpen = false },
        )
    }

    // 排序（对齐 1.7.3：手动=默认 / 自动=权重）
    if (sortOpen) {
        val sortActions = listOf(
            Triple("手动排序", BookSourceSort.Default) { sort = BookSourceSort.Default },
            Triple("自动排序（权重）", BookSourceSort.Weight) { sort = BookSourceSort.Weight },
            Triple("名称", BookSourceSort.Name) { sort = BookSourceSort.Name },
            Triple("URL", BookSourceSort.Url) { sort = BookSourceSort.Url },
            Triple("更新时间", BookSourceSort.Update) { sort = BookSourceSort.Update },
            Triple("启用状态", BookSourceSort.Enable) { sort = BookSourceSort.Enable },
            Triple("响应速度", BookSourceSort.Respond) { sort = BookSourceSort.Respond },
        ).map { (label, s, apply) ->
            (if (sort == s) "✓ " else "") + label to {
                apply()
                sortOpen = false
            }
        }
        AppMenuSheet(
            title = "排序方式",
            actions = sortActions + listOf(
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

/** 列表行：域名分组头 或 源条目。 */
private sealed interface DomainRow {
    data class Header(val host: String) : DomainRow
    data class Source(val source: BookSourcePart) : DomainRow
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
