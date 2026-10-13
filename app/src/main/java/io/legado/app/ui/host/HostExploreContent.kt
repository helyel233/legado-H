package io.legado.app.ui.host

import android.content.Context
import io.legado.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import com.script.rhino.runScriptWithContext
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.main.explore.DiscoverEngine
import io.legado.app.ui.main.explore.DiscoverTagItem
import io.legado.app.uikit.v5.components.AppBottomSheet
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppSearchField
import io.legado.app.uikit.v5.components.AppSkeletonList
import io.legado.app.uikit.v5.contract.AppContent
import io.legado.app.uikit.v5.contract.ChromeAction
import io.legado.app.uikit.v5.contract.ContentScope
import io.legado.app.uikit.v5.contract.DestIcon
import io.legado.app.uikit.v5.contract.DestTitle
import io.legado.app.uikit.v5.contract.ScreenChrome
import io.legado.app.uikit.v5.contract.TopBarMode
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P4 整改③：发现 Tab 对齐旧壳「现代发现」能力 —— 主力聚合源（光遇聚合等）的
 * 线路/类型/平台选择器、开关、脚本按钮、分组分类缺一不可。
 *
 * 分类解析与交互语义 100% 复用旧壳引擎（[DiscoverEngine]，从 ExploreFragment
 * 抽取的纯逻辑，旧文件零改动），仅呈现层 V5 化：
 *   源选择行（搜索 + 更换）→ 选择器行（chars 选项 → infoMap → action JS → 重载）
 *   → 开关/按钮 chips（脚本分发）→ 分组分类 chips → 旧 ExploreShowActivity 结果页
 */
class HostExploreContent : AppContent {

    override fun chrome(): ScreenChrome = ScreenChrome(
        topBar = TopBarMode.LARGE_TITLE,
        title = DestTitle.Text("发现"),
        subtitle = DestTitle.Text("源内分类与筛选"),
        showBack = false,
        contentOwnsScroll = true,
        actions = listOf(
            ChromeAction(
                id = "sourcePicker",
                icon = DestIcon.Vector(HostIcons.Search),
                contentDescription = DestTitle.Text("筛选发现源"),
                onClick = { HostExploreContentStatic.openPicker = true },
            ),
        ),
    )

    @Composable
    override fun Content(scope: ContentScope) {
        // 产品裁决（2026-10-13）：发现 Tab 专注「选源→看该源发现分类」；
        // 套件聚合归主页域 —— DiscoverSuiteEngine/Session 已就绪，主页 V5 化时接回
        HostDiscoverBody(scope)
    }
}

/** chrome actions 静态桥（同 HostHomeContent 模式）。 */
internal object HostExploreContentStatic {
    var openPicker by mutableStateOf(false)
}

@Composable
private fun HostDiscoverBody(scope: ContentScope) {
    val context = LocalContext.current
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val density = visual.density
    val coroutineScope = rememberCoroutineScope()

    val sources by appDb.bookSourceDao.flowExplore().collectAsState(initial = emptyList())

    // 记住所选源（与旧壳互通：AppConfig.modernDiscoverySourceUrl）
    var selectedUrl by remember { mutableStateOf(AppConfig.modernDiscoverySourceUrl) }
    LaunchedEffect(sources) {
        if (selectedUrl == null || sources.none { it.bookSourceUrl == selectedUrl }) {
            selectedUrl = sources.firstOrNull()?.bookSourceUrl
            AppConfig.modernDiscoverySourceUrl = selectedUrl
        }
    }

    val source by produceState<BookSource?>(null, selectedUrl) {
        value = selectedUrl?.let { url ->
            withContext(Dispatchers.IO) { appDb.bookSourceDao.getBookSource(url) }
        }
    }

    // 分类重载信号：选择器/开关/按钮动作后 +1
    var reloadKey by remember { mutableStateOf(0) }
    var loadingKinds by remember { mutableStateOf(true) }
    var tagItems by remember { mutableStateOf<List<DiscoverTagItem>>(emptyList()) }

    LaunchedEffect(source, reloadKey) {
        val s = source ?: return@LaunchedEffect
        loadingKinds = true
        val kinds = runCatching { withContext(Dispatchers.IO) { s.exploreKinds() } }
            .getOrElse { emptyList() }
        tagItems = DiscoverEngine.buildTagItems(s, kinds)
        loadingKinds = false
    }

    // 选择器弹层状态
    var selectItem by remember { mutableStateOf<DiscoverTagItem?>(null) }

    fun openExplore(targetSourceUrl: String, title: String, exploreUrl: String) {
        if (exploreUrl.isBlank()) return
        context.startActivity<io.legado.app.ui.book.explore.ExploreShowActivity> {
            putExtra("exploreName", title)
            putExtra("sourceUrl", targetSourceUrl)
            putExtra("exploreUrl", exploreUrl)
        }
    }

    fun runAction(item: DiscoverTagItem, onDone: () -> Unit = {}) {
        val s = source ?: return
        val action = item.kind.action?.takeIf { it.isNotBlank() } ?: return
        coroutineScope.launch {
            val script = DiscoverEngine.normalizeActionScript(action)
            val infoMap = DiscoverEngine.infoMap(s.bookSourceUrl)
            runCatching {
                withContext(Dispatchers.IO) {
                    var handled = false
                    val java = SourceLoginJsExtensions(
                        context as? androidx.appcompat.app.AppCompatActivity,
                        s,
                        callback = object : SourceLoginJsExtensions.Callback {
                            override fun upUiData(data: Map<String, Any?>?) = Unit
                            override fun reUiView(deltaUp: Boolean) = Unit
                            override fun showBrowser(
                                url: String, html: String?, preloadJs: String?, config: String?
                            ): Boolean = false

                            override fun open(
                                name: String, url: String?, title: String?, origin: String?
                            ): Boolean {
                                if (name != "explore") return false
                                val targetUrl = url?.takeIf { it.isNotBlank() } ?: return true
                                handled = true
                                val targetSource = origin?.takeIf { it.isNotBlank() } ?: s.bookSourceUrl
                                coroutineScope.launch {
                                    openExplore(targetSource, title ?: item.text, targetUrl)
                                }
                                return true
                            }
                        }
                    )
                    runScriptWithContext {
                        s.evalJS(script) {
                            put("java", java)
                            put("infoMap", infoMap)
                        }
                    }
                    handled
                }
            }
            onDone()
        }
    }

    fun onItemTap(item: DiscoverTagItem) {
        val s = source ?: return
        when (item.role) {
            DiscoverTagItem.Role.UrlTag ->
                openExplore(s.bookSourceUrl, item.kind.title, item.kind.url ?: "")

            DiscoverTagItem.Role.GlobalSelect ->
                selectItem = item

            DiscoverTagItem.Role.Toggle -> {
                val key = item.kind.title
                if (key.isBlank()) return
                val chars = item.kind.chars?.filterNotNull() ?: return
                if (chars.isEmpty()) return
                val infoMap = DiscoverEngine.infoMap(s.bookSourceUrl)
                val current = infoMap[key] ?: item.kind.default ?: chars.first()
                val index = chars.indexOf(current).takeIf { it >= 0 } ?: 0
                val next = chars.getOrNull(index + 1) ?: chars.first()
                infoMap[key] = next
                coroutineScope.launch {
                    withContext(Dispatchers.IO) { s.clearExploreKindsCache() }
                    runAction(item) { reloadKey++ }
                }
            }

            DiscoverTagItem.Role.ActionButton, DiscoverTagItem.Role.ScriptUrl ->
                runAction(item)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // 源选择行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = density.screenPadding, vertical = 4.dp)
                .clip(visual.shape(ShapeKind.BUTTON))
                .background(colors.surfaceVariant)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { HostExploreContentStatic.openPicker = true }
                .padding(horizontal = density.screenPadding / 1.4f, vertical = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = source?.bookSourceName ?: stringResource(R.string.v5_explore_pick_source),
                style = visual.type.title,
                color = colors.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text("更换 ▾", style = visual.type.caption, color = colors.primary)
        }
        Spacer(Modifier.height(density.blockGap / 2))

        when {
            source == null -> AppEmpty(
                title = stringResource(R.string.v5_explore_no_sources_title),
                supporting = stringResource(R.string.v5_explore_no_sources_supporting),
                actionLabel = stringResource(R.string.v5_explore_manage_sources),
                onAction = {
                    context.startActivity<io.legado.app.ui.book.source.manage.BookSourceActivity>()
                },
            )

            loadingKinds -> AppSkeletonList(rows = 4)

            tagItems.isEmpty() -> AppEmpty(
                title = "该源没有可用分类",
                supporting = "检查书源的探索规则",
            )

            else -> {
                // ① 选择器行（线路/类型/平台…）：label + 当前值 → 点开选项弹层
                tagItems.filter { it.role == DiscoverTagItem.Role.GlobalSelect }
                    .forEach { item ->
                        val current = DiscoverEngine.currentValue(item.kind, source!!.bookSourceUrl)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = density.screenPadding, vertical = 5.dp)
                                .clip(visual.shape(ShapeKind.FIELD))
                                .background(colors.surfaceVariant)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                ) { onItemTap(item) }
                                .padding(horizontal = density.screenPadding / 1.4f, vertical = 10.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.text,
                                style = visual.type.label,
                                color = colors.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = current.ifBlank { "点击选择" },
                                style = visual.type.body,
                                color = colors.onSurface,
                                maxLines = 1,
                            )
                        }
                    }

                // ② 开关/按钮 chips（脚本类）
                val controls = tagItems.filter {
                    it.role == DiscoverTagItem.Role.Toggle ||
                        it.role == DiscoverTagItem.Role.ActionButton ||
                        it.role == DiscoverTagItem.Role.ScriptUrl
                }
                if (controls.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = density.screenPadding),
                        horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                    ) {
                        controls.forEach { item ->
                            AppChip(item.text, selected = false, onClick = { onItemTap(item) })
                        }
                    }
                    Spacer(Modifier.height(density.blockGap / 2))
                }

                // ③ 分组分类 chips → 结果页
                val groups = tagItems
                    .filter { it.role == DiscoverTagItem.Role.UrlTag }
                    .groupBy { it.group ?: "" }
                groups.forEach { (group, items) ->
                    if (groups.size > 1 || group.isNotBlank()) {
                        Text(
                            text = group,
                            style = visual.type.label,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(
                                horizontal = density.screenPadding, vertical = 4.dp,
                            ),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = density.screenPadding),
                        horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                    ) {
                        items.forEach { item ->
                            AppChip(item.text, selected = false, onClick = { onItemTap(item) })
                        }
                    }
                    Spacer(Modifier.height(density.blockGap / 3))
                }
                Spacer(Modifier.height(density.screenPadding))
            }
        }
    }

    // 选择器选项弹层
    selectItem?.let { item ->
        val s = source
        if (s != null) {
            SelectOptionsSheet(
                scope = scope,
                title = item.text,
                options = item.kind.chars?.filterNotNull().orEmpty(),
                current = DiscoverEngine.currentValue(item.kind, s.bookSourceUrl),
                onPick = { value ->
                    selectItem = null
                    val infoMap = DiscoverEngine.infoMap(s.bookSourceUrl)
                    infoMap[item.kind.title] = value
                    coroutineScope.launch {
                        withContext(Dispatchers.IO) { s.clearExploreKindsCache() }
                        runAction(item) { reloadKey++ }
                    }
                },
                onDismiss = { selectItem = null },
            )
        }
    }

    // 源选择弹层（筛选发现源）
    if (HostExploreContentStatic.openPicker) {
        SourcePickerSheet(
            scope = scope,
            sources = sources,
            selectedUrl = selectedUrl,
            onPick = {
                selectedUrl = it.bookSourceUrl
                AppConfig.modernDiscoverySourceUrl = it.bookSourceUrl
                HostExploreContentStatic.openPicker = false
            },
            onManage = {
                HostExploreContentStatic.openPicker = false
                context.startActivity<io.legado.app.ui.book.source.manage.BookSourceActivity>()
            },
            onDismiss = { HostExploreContentStatic.openPicker = false },
        )
    }
}

@Composable
private fun SelectOptionsSheet(
    scope: ContentScope,
    title: String,
    options: List<String>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val density = visual.density
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(density.screenPadding)) {
            Text(title, style = visual.type.title, color = colors.onSurface)
            Spacer(Modifier.height(density.blockGap))
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(visual.shape(ShapeKind.FIELD))
                        .background(
                            if (option == current) colors.primary.copy(alpha = 0.14f)
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { onPick(option) }
                        .padding(horizontal = density.screenPadding / 1.4f, vertical = 11.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        text = option,
                        style = visual.type.body,
                        color = if (option == current) colors.primary else colors.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourcePickerSheet(
    scope: ContentScope,
    sources: List<io.legado.app.data.entities.BookSourcePart>,
    selectedUrl: String?,
    onPick: (io.legado.app.data.entities.BookSourcePart) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val density = visual.density
    var query by remember { mutableStateOf("") }

    AppBottomSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .padding(density.screenPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            AppSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.v5_explore_filter_sources),
            )
            Spacer(Modifier.height(density.blockGap / 2))
            val filtered = if (query.isBlank()) sources else sources.filter {
                it.bookSourceName.contains(query, true) ||
                    it.bookSourceGroup?.contains(query, true) == true
            }
            filtered.forEach { src ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(visual.shape(ShapeKind.FIELD))
                        .background(
                            if (src.bookSourceUrl == selectedUrl) colors.primary.copy(alpha = 0.14f)
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { onPick(src) }
                        .padding(horizontal = density.screenPadding / 1.4f, vertical = 10.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = src.bookSourceName,
                            style = visual.type.body,
                            color = if (src.bookSourceUrl == selectedUrl) colors.primary else colors.onSurface,
                            maxLines = 1,
                        )
                        if (!src.bookSourceGroup.isNullOrBlank()) {
                            Text(
                                text = src.bookSourceGroup ?: "",
                                style = visual.type.caption,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Text(
                text = "管理书源",
                style = visual.type.label,
                color = colors.primary,
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onManage() },
            )
        }
    }
}
