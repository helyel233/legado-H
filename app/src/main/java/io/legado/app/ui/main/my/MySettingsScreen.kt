package io.legado.app.ui.main.my

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.glass.GlassPanel
import io.legado.app.ui.glass.GlassSamplingDomain
import io.legado.app.ui.glass.glassPageBackgroundColor
import io.legado.app.ui.glass.rememberGlassConfigSignature
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.AppSettingSectionTitle
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.appSettingRowDecoration
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.compose.rememberHasUsableBgImage

internal enum class MySettingsRowKind {
    Action,
    ThemeMode,
    AutoTaskService
}

internal data class MySettingsThemeOption(
    val value: String,
    val label: String,
    val summary: String = ""
)

internal data class MySettingsSubSearchItem(
    val ownerKey: String,
    val title: String,
    val summary: String,
    val key: String,
    val ownerConfigTag: String,
    /**
     * 设置项自己声明的别名（[io.legado.app.ui.config.compose.SettingItemSpec.searchKeys]）。
     * 用户记得住「沉浸式状态栏」，但可能搜「状态栏」；别名让这些口语词也能命中，
     * 而标题本身不必为了可搜性被塞满关键词。
     */
    val aliases: List<String> = emptyList()
) {
    val searchText: String =
        (listOf(title, summary, key) + aliases).joinToString(" ").lowercase()
}

internal data class MySettingsRowModel(
    val key: String,
    val title: String,
    val summary: String? = null,
    val kind: MySettingsRowKind = MySettingsRowKind.Action,
    val danger: Boolean = false,
    /** 行首图标，主流设置页靠图标扫读；为 null 时退化成原来的纯文字行 */
    val iconRes: Int? = null
) {
    /** 可点进下一层的行才显示箭头；开关/选择/动作行的交互由行尾控件自说明 */
    val showChevron: Boolean
        get() = !danger && kind == MySettingsRowKind.Action
}

internal data class MySettingsSectionModel(
    val title: String,
    val rows: List<MySettingsRowModel>
)

internal data class MyServiceToggleUiState(
    val checked: Boolean,
    val summary: String
)

private data class VisibleSection(
    val title: String,
    val rows: List<VisibleRow>
)

private data class VisibleRow(
    val row: MySettingsRowModel,
    val summary: String,
    val searchTarget: MySettingsSubSearchItem?
)

private val SettingsHorizontalPadding = 12.dp

@Composable
internal fun MySettingsScreen(
    sections: List<MySettingsSectionModel>,
    /**
     * 深层设置项索引。用 lambda 而不是 List：索引要把 11 个设置页的 spec 各构建一次，
     * 首次打开「我的」页时并不需要它，等用户真的开始搜索再算。
     */
    subSearchItemsProvider: () -> List<MySettingsSubSearchItem>,
    searchQuery: String,
    themeOptions: List<MySettingsThemeOption>,
    themeModeValue: String,
    autoTaskServiceState: MyServiceToggleUiState,
    onThemeModeSelect: (String) -> Unit,
    onAutoTaskServiceCheckedChange: (Boolean) -> Unit,
    onAutoTaskServiceClick: () -> Unit,
    onRowClick: (String, MySettingsSubSearchItem?) -> Unit
) {
    val colors = rememberAppSettingPalette()
    val panelRadiusPx = colors.panelRadiusPx
    // 壁纸模式下页面根背景必须透明（BaseFragment 已把宿主背景置空），否则玻璃采样的是
    // 被页面盖住的壁纸；无背景图 / 墨水屏时保持主题页色，观感与之前逐值一致。
    val wallpaperVisible = rememberHasUsableBgImage() && !AppConfig.isEInkMode
    val glassSignature = rememberGlassConfigSignature()
    val query = remember(searchQuery) { searchQuery.trim() }
    val subSearchItems = remember(query) {
        if (query.isEmpty()) emptyList() else subSearchItemsProvider()
    }
    // 过滤结果只依赖这几个输入；不 remember 的话每次重组（开关状态、主题切换）都要
    // 重跑一遍全量过滤，列表越全越浪费
    val visibleSections = remember(
        sections,
        subSearchItems,
        query,
        autoTaskServiceState
    ) {
        buildVisibleSections(
            sections = sections,
            subSearchItems = subSearchItems,
            searchQuery = searchQuery,
            autoTaskServiceState = autoTaskServiceState,
        )
    }

    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = colors.bodyFontFamily)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(glassPageBackgroundColor(colors.page, wallpaperVisible))
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = dimensionResource(R.dimen.main_content_bottom_bar_padding) + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(visibleSections, key = { it.title }) { section ->
                    SettingsSectionCard(
                        section = section,
                        colors = colors,
                        panelRadiusPx = panelRadiusPx,
                        glassSignature = glassSignature,
                        searchQuery = searchQuery,
                        themeOptions = themeOptions,
                        themeModeValue = themeModeValue,
                        autoTaskServiceState = autoTaskServiceState,
                        onThemeModeSelect = onThemeModeSelect,
                        onAutoTaskServiceCheckedChange = onAutoTaskServiceCheckedChange,
                        onAutoTaskServiceClick = onAutoTaskServiceClick,
                        onRowClick = onRowClick
                    )
                }
                if (visibleSections.isEmpty()) {
                    item("empty") {
                        EmptySettingsFrame(
                            colors = colors,
                            panelRadiusPx = panelRadiusPx,
                            glassSignature = glassSignature
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    section: VisibleSection,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    glassSignature: String,
    searchQuery: String,
    themeOptions: List<MySettingsThemeOption>,
    themeModeValue: String,
    autoTaskServiceState: MyServiceToggleUiState,
    onThemeModeSelect: (String) -> Unit,
    onAutoTaskServiceCheckedChange: (Boolean) -> Unit,
    onAutoTaskServiceClick: () -> Unit,
    onRowClick: (String, MySettingsSubSearchItem?) -> Unit
) {
    val context = LocalContext.current
    val panelImage = remember(context, panelRadiusPx, colors.themeSignature) {
        UiCorner.panelImageDrawable(context, panelRadiusPx)
    }
    GlassPanel(
        domain = GlassSamplingDomain.WALLPAPER,
        cornerRadiusPx = panelRadiusPx,
        configSignature = glassSignature,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsHorizontalPadding),
        fallback = Modifier.appSettingPanelBackground(
            normalColor = colors.row,
            panelImage = panelImage,
            borderColor = colors.border,
            radiusPx = panelRadiusPx
        ),
        // 页面内分区卡必须问一句「背后真的有壁纸吗」。requireWallpaperHost = false 只判采样域
        // 与设备：solid 档（用户选「无玻璃」）会被判为可用，于是画出 ThemeStore.bottomBackground
        // 的不透明壳、把上面 row 色的 fallback 顶掉；无背景图时同理（domainAvailable 不看背景图
        // 开关）。置 true 后「solid / 无背景图 / 墨水屏 / 低内存」四档都逐值回退。
        requireWallpaperHost = true
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AppSettingSectionTitle(title = section.title, palette = colors)
            section.rows.forEachIndexed { index, item ->
                val isLastRowInSection = index == section.rows.lastIndex
                val showDivider = !isLastRowInSection
                when (item.row.kind) {
                    MySettingsRowKind.ThemeMode -> ThemeModeRow(
                        item = item,
                        colors = colors,
                        panelRadiusPx = panelRadiusPx,
                        searchQuery = searchQuery,
                        isLast = isLastRowInSection,
                        showDivider = showDivider,
                        options = themeOptions,
                        selectedValue = themeModeValue,
                        onSelect = onThemeModeSelect
                    )

                    MySettingsRowKind.AutoTaskService -> ServiceSwitchRow(
                        item = item,
                        state = autoTaskServiceState,
                        colors = colors,
                        panelRadiusPx = panelRadiusPx,
                        searchQuery = searchQuery,
                        isFirst = false,
                        isLast = isLastRowInSection,
                        showDivider = showDivider,
                        onCheckedChange = onAutoTaskServiceCheckedChange,
                        onClick = onAutoTaskServiceClick
                    )

                    MySettingsRowKind.Action -> SettingsActionRow(
                        item = item,
                        colors = colors,
                        panelRadiusPx = panelRadiusPx,
                        searchQuery = searchQuery,
                        isFirst = false,
                        isLast = isLastRowInSection,
                        showDivider = showDivider,
                        onClick = { onRowClick(item.row.key, item.searchTarget) }
                    )
                }
            }
        }
    }
}

/**
 * 把命中片段染成强调色：搜索时用户要一眼看出「这行为什么被搜出来」，
 * 否则 20+ 行结果里只能自己逐字比对。区分大小写只用于定位，染色范围按 query 长度算。
 *
 * lowerText 只用于查找，索引必须先确认能映射回原文才染色：
 * lowercase() 是全 Unicode 映射，个别字符小写后会变长（如 İ → "i̇" 占 2 个码元），
 * 此时 lowerText 的下标会比原文偏移，直接染色轻则染错范围、重则 addStyle 收到
 * start > end 抛 IllegalArgumentException。
 */
private fun highlightMatches(text: String, query: String, accent: Color): AnnotatedString {
    if (query.isBlank() || text.isEmpty()) return AnnotatedString(text)
    val lowerText = text.lowercase()
    val lowerQuery = query.lowercase()
    if (lowerText.length != text.length) return AnnotatedString(text)
    var index = lowerText.indexOf(lowerQuery)
    if (index < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        while (index >= 0) {
            val end = index + lowerQuery.length
            addStyle(SpanStyle(color = accent), index, end)
            index = lowerText.indexOf(lowerQuery, end)
        }
    }
}

@Composable
private fun SettingsLeadingIcon(
    item: MySettingsRowModel,
    colors: AppSettingPalette
) {
    // iconRes 必须是 vector/位图：painterResource 不支持 <selector>（如底部导航的
    // ic_bottom_ai/xxx_*_e 双态选择器），传进去会在组合期抛 IllegalArgumentException 崩掉整个 Tab。
    val iconRes = item.iconRes ?: return
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = if (item.danger) colors.danger else colors.accent,
        modifier = Modifier.size(22.dp)
    )
    Spacer(modifier = Modifier.width(14.dp))
}

@Composable
private fun SettingsActionRow(
    item: VisibleRow,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    searchQuery: String,
    isFirst: Boolean,
    isLast: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val textColor = if (item.row.danger) colors.danger else colors.primaryText
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .appSettingRowDecoration(
                pressed = pressed,
                pressedColor = colors.rowPressed,
                dividerColor = colors.divider,
                showDivider = showDivider,
                radiusPx = panelRadiusPx,
                isFirst = isFirst,
                isLast = isLast,
                danger = item.row.danger,
                dangerColor = colors.danger
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        SettingsLeadingIcon(item = item.row, colors = colors)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatches(item.row.title, searchQuery, colors.accent),
                color = textColor,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.summary.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = highlightMatches(item.summary, searchQuery, colors.accent),
                    color = colors.secondaryText,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (item.row.showChevron) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_arrow_right),
                contentDescription = null,
                tint = colors.secondaryText,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 选择行（主题模式）：交互契约是「行内分段控件，当前项高亮，一次点击直达」，
 * 不再经弹窗二次选择；整行不响应点击，避免与分段控件抢手势。
 */
@Composable
private fun ThemeModeRow(
    item: VisibleRow,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    searchQuery: String,
    isLast: Boolean,
    showDivider: Boolean,
    options: List<MySettingsThemeOption>,
    selectedValue: String,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .appSettingRowDecoration(
                pressed = false,
                pressedColor = colors.rowPressed,
                dividerColor = colors.divider,
                showDivider = showDivider,
                radiusPx = panelRadiusPx,
                isFirst = false,
                isLast = isLast
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsLeadingIcon(item = item.row, colors = colors)
            Text(
                text = highlightMatches(item.row.title, searchQuery, colors.accent),
                color = colors.primaryText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        // 36dp = 行首图标 22dp + 间距 14dp，分段控件与标题左对齐
        ThemeSegmentedControl(
            options = options,
            selectedValue = selectedValue,
            colors = colors,
            onSelect = onSelect,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 36.dp)
        )
    }
}

@Composable
private fun ThemeSegmentedControl(
    options: List<MySettingsThemeOption>,
    selectedValue: String,
    colors: AppSettingPalette,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(colors.rowPressed))
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { option ->
            val selected = option.value == selectedValue
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 2.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) colors.accent else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(option.value) }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option.label,
                    color = if (selected) colors.onAccent else colors.secondaryText,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    // 「E-Ink(墨水屏)」这类长标签在窄屏下放不下，跑马灯兜底
                    modifier = Modifier.basicMarquee(iterations = 1)
                )
            }
        }
    }
}

@Composable
private fun ServiceSwitchRow(
    item: VisibleRow,
    state: MyServiceToggleUiState,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    searchQuery: String,
    isFirst: Boolean,
    isLast: Boolean,
    showDivider: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .appSettingRowDecoration(
                pressed = pressed,
                pressedColor = colors.rowPressed,
                dividerColor = colors.divider,
                showDivider = showDivider,
                radiusPx = panelRadiusPx,
                isFirst = isFirst,
                isLast = isLast
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        SettingsLeadingIcon(item = item.row, colors = colors)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatches(item.row.title, searchQuery, colors.accent),
                color = colors.primaryText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = highlightMatches(state.summary, searchQuery, colors.accent),
                color = colors.secondaryText,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        LegadoMiuixSwitch(
            checked = state.checked,
            onCheckedChange = onCheckedChange,
            palette = colors.toMiuixPalette()
        )
    }
}

@Composable
private fun EmptySettingsFrame(
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    glassSignature: String
) {
    val context = LocalContext.current
    val panelImage = remember(context, panelRadiusPx, colors.themeSignature) {
        UiCorner.panelImageDrawable(context, panelRadiusPx)
    }
    GlassPanel(
        domain = GlassSamplingDomain.WALLPAPER,
        cornerRadiusPx = panelRadiusPx,
        configSignature = glassSignature,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsHorizontalPadding),
        fallback = Modifier.appSettingPanelBackground(
            normalColor = colors.row,
            panelImage = panelImage,
            borderColor = colors.border,
            radiusPx = panelRadiusPx
        ),
        // 页面内分区卡必须问一句「背后真的有壁纸吗」。requireWallpaperHost = false 只判采样域
        // 与设备：solid 档（用户选「无玻璃」）会被判为可用，于是画出 ThemeStore.bottomBackground
        // 的不透明壳、把上面 row 色的 fallback 顶掉；无背景图时同理（domainAvailable 不看背景图
        // 开关）。置 true 后「solid / 无背景图 / 墨水屏 / 低内存」四档都逐值回退。
        requireWallpaperHost = true
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AppSettingSectionTitle(
                title = context.getString(R.string.settings_search_results),
                palette = colors
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 60.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = context.getString(R.string.settings_search_empty),
                    color = colors.secondaryText,
                    fontSize = 14.sp
                )
            }
        }
    }
}

/**
 * 搜索时除了保留命中的一级行，还把命中的深层设置项单独列成行：
 * 用户搜「沉浸」看到的就是「沉浸式状态栏」这样的具体开关，而不是只看到
 * 「界面设置」一行还要自己进去再找一遍。子项行用所属页面名当副标题，
 * 点击直接深链到该设置并定位。
 */
private fun buildVisibleSections(
    sections: List<MySettingsSectionModel>,
    subSearchItems: List<MySettingsSubSearchItem>,
    searchQuery: String,
    autoTaskServiceState: MyServiceToggleUiState
): List<VisibleSection> {
    val query = searchQuery.trim().lowercase()
    return sections.mapNotNull { section ->
        val rows = section.rows.flatMap { row ->
            val summary = row.effectiveSummary(autoTaskServiceState = autoTaskServiceState)
            val matchedSubItems = if (query.isBlank()) {
                emptyList()
            } else {
                subSearchItems.filter { item ->
                    item.ownerKey == row.key && item.searchText.contains(query)
                }.sortedBy { it.matchRank(query) }
            }
            val selfMatched = query.isBlank()
                || row.title.lowercase().contains(query)
                || summary.lowercase().contains(query)
                || row.key.lowercase().contains(query)
            val visibleRows = ArrayList<VisibleRow>(matchedSubItems.size + 1)
            if (selfMatched) {
                visibleRows.add(VisibleRow(row = row, summary = summary, searchTarget = null))
            }
            // 子项行一律按普通可点行渲染：开关/主题模式那几种行需要外部状态，
            // 子项没有，走了会显示成未勾选的空壳。
            // 副标题写成「分区 › 页面」的面包屑，和 iOS 设置搜索结果一致，
            // 用户点进去之前就知道这项在哪
            val parentPath = "${section.title} › ${row.title}"
            matchedSubItems.mapTo(visibleRows) { item ->
                VisibleRow(
                    row = MySettingsRowModel(
                        key = item.key,
                        title = item.title,
                        summary = parentPath,
                        iconRes = row.iconRes
                    ),
                    summary = parentPath,
                    searchTarget = item
                )
            }
            visibleRows
        }
        rows.takeIf { it.isNotEmpty() }?.let { VisibleSection(section.title, it) }
    }
}

/**
 * 命中质量：标题开头命中 > 标题包含 > 仅摘要或别名命中。
 * 搜「主题」时「主题包管理」应该排在某个摘要里提到主题的开关前面；
 * sortedBy 是稳定排序，同档次保持页面顺序，不会每次输入都跳动。
 */
private fun MySettingsSubSearchItem.matchRank(query: String): Int {
    val lowerTitle = title.lowercase()
    return when {
        lowerTitle.startsWith(query) -> 0
        lowerTitle.contains(query) -> 1
        else -> 2
    }
}

private fun MySettingsRowModel.effectiveSummary(
    autoTaskServiceState: MyServiceToggleUiState
): String {
    return when (kind) {
        // 主题模式的当前值由分段控件自己展示，摘要保持静态描述
        MySettingsRowKind.ThemeMode -> summary.orEmpty()
        MySettingsRowKind.AutoTaskService -> autoTaskServiceState.summary
        MySettingsRowKind.Action -> summary.orEmpty()
    }
}

private fun AppSettingPalette.toMiuixPalette(): LegadoMiuixPalette {
    return LegadoMiuixPalette(
        accent = accent,
        surface = Color(row),
        surfaceVariant = Color(row),
        primaryText = primaryText,
        secondaryText = secondaryText,
        danger = danger,
        onAccent = onAccent
    )
}
