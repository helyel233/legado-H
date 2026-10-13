package io.legado.app.ui.config.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppListItem
import io.legado.app.uikit.v5.components.AppSectionHeader
import io.legado.app.uikit.v5.components.AppSelectionRow
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppSlider
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import kotlin.math.roundToInt

/**
 * 设置页 V5 渲染（P5-1，台账 #2 最后一块）。
 *
 * Spec 契约（SettingPageSpec/Section/Item）不变，只把渲染从旧 palette 换成 V5
 * 组件 —— 全部 10 个设置分区页面单点生效。搜索定位（targetKey 滚动估算）、
 * 菜单桥、refreshSettings 机制均不受影响。
 *
 * 行型映射：
 * - SettingSwitchSpec → AppRowWithSwitch
 * - SettingChoiceSpec → AppSelectionRow + AppFilterDialog（弹单选）
 * - SettingActionSpec → AppListItem（带长按走 AppSelectionRow）
 * - SettingSliderSpec → AppSlider（step 尺寸换算成刻度数）
 *
 * 前置：Fragment 的 ComposeView 需包在 HostV5ThemeRoot 里（V5 取值环境），
 * 见 ComposeSettingFragment.onCreateView。
 */

@Composable
fun SettingSpecScreen(
    page: SettingPageSpec,
    scrollTargetKey: String?,
    @Suppress("UNUSED_PARAMETER") drawPanelImage: Boolean = true,
    onTargetReady: (String) -> Unit,
    onTargetMissing: () -> Unit,
    onItemClick: (SettingItemSpec) -> Unit
) {
    val sections = remember(page) {
        page.sections.mapNotNull { section ->
            val rows = section.items.filter { it.visible }
            rows.takeIf { it.isNotEmpty() }?.let {
                SettingSectionSpec(title = section.title, items = it)
            }
        }
    }
    val listState = rememberLazyListState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val targetTitleOffsetPx = with(density) { 38.dp.roundToPx() }
    val targetRowOffsetPx = with(density) { 72.dp.roundToPx() }
    LaunchedEffect(scrollTargetKey, sections) {
        val targetKey = scrollTargetKey?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        var targetSectionIndex = -1
        var targetRowIndex = -1
        sections.forEachIndexed { sectionIndex, section ->
            if (targetSectionIndex >= 0) return@forEachIndexed
            val rowIndex = section.items.indexOfFirst {
                it.key == targetKey || targetKey in it.searchKeys
            }
            if (rowIndex >= 0) {
                targetSectionIndex = sectionIndex
                targetRowIndex = rowIndex
            }
        }
        if (targetSectionIndex >= 0) {
            val section = sections[targetSectionIndex]
            val scrollOffset = targetRowIndex * targetRowOffsetPx +
                if (section.title.isNullOrBlank()) 0 else targetTitleOffsetPx
            listState.animateScrollToItem(targetSectionIndex, scrollOffset)
            onTargetReady(targetKey)
        } else {
            onTargetMissing()
        }
    }

    // Choice 行的单选弹层（V5：AppFilterDialog）
    var choiceItem by remember { mutableStateOf<SettingChoiceSpec?>(null) }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(
            sections,
            key = { index, section -> "${index}:${section.title}:${section.items.firstOrNull()?.key}" }
        ) { _, section ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                val sectionTitle = section.title
                if (!sectionTitle.isNullOrBlank()) {
                    AppSectionHeader(title = sectionTitle.toString())
                }
                section.items.forEach { item ->
                    SettingRowV5(
                        item = item,
                        onItemClick = onItemClick,
                        onOpenChoice = { choiceItem = it }
                    )
                }
            }
        }
    }

    choiceItem?.let { choice ->
        AppFilterDialog(
            title = choice.title.toString(),
            options = choice.options.map { option ->
                option.label.toString() + (option.description?.let { " · $it" } ?: "")
            },
            selectedIndex = choice.options.indexOfFirst { it.value == choice.selectedValue },
            onSelect = { index ->
                choiceItem = null
                choice.options.getOrNull(index)?.let { option ->
                    if (choice.enabled) choice.onSelected(option.value)
                }
            },
            onDismiss = { choiceItem = null },
        )
    }
}

@Composable
private fun SettingRowV5(
    item: SettingItemSpec,
    onItemClick: (SettingItemSpec) -> Unit,
    onOpenChoice: (SettingChoiceSpec) -> Unit
) {
    if (item is SettingSliderSpec) {
        SettingSliderRowV5(item)
        return
    }
    when (item) {
        is SettingSwitchSpec -> {
            AppRowWithSwitch(
                title = item.title.toString(),
                summary = item.summary?.toString(),
                checked = item.checked,
                onCheckedChange = item.onCheckedChange,
                enabled = item.enabled
            )
        }

        is SettingChoiceSpec -> {
            val colors = LocalSemanticColors.current
            val visual = LocalVisualSpec.current
            AppSelectionRow(
                title = item.title.toString(),
                supporting = item.summary?.toString(),
                selected = false,
                selectionMode = false,
                onClick = { if (item.enabled) onOpenChoice(item) },
                onLongClick = {},
                trailing = {
                    Text(
                        text = item.selectedLabel.toString(),
                        style = visual.type.caption,
                        color = if (item.enabled) colors.primary else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }

        is SettingActionSpec -> {
            val action = item
            val click: (() -> Unit)? = if (action.enabled) {
                { onItemClick(action) }
            } else null
            if (action.onLongClick != null) {
                val longClick: (() -> Unit)? = if (action.enabled) {
                    { action.onLongClick.invoke() }
                } else null
                AppSelectionRow(
                    title = action.title.toString(),
                    supporting = action.summary?.toString(),
                    selected = false,
                    selectionMode = false,
                    onClick = click ?: {},
                    onLongClick = longClick ?: {}
                )
            } else {
                AppListItem(
                    headline = action.title.toString(),
                    supporting = action.summary?.toString(),
                    onClick = click
                )
            }
        }

        else -> Unit
    }
}

@Composable
private fun SettingSliderRowV5(item: SettingSliderSpec) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val sliderValue = item.value.coerceIn(item.valueRange.first, item.valueRange.last)
    // 旧 step 是「步长尺寸」（Int），V5 AppSlider 的 steps 是「刻度数」，换算一次
    val steps = ((item.valueRange.last - item.valueRange.first) / item.step)
        .coerceAtLeast(1) - 1
    val range = item.valueRange.first.toFloat()..item.valueRange.last.toFloat()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title.toString(),
                    style = visual.type.body,
                    color = if (item.enabled) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.summary?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it.toString(),
                        style = visual.type.caption,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = item.valueFormatter(sliderValue),
                style = visual.type.caption,
                color = if (item.enabled) colors.primary else colors.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        AppSlider(
            value = sliderValue.toFloat(),
            onValueChange = { raw ->
                val snapped = if (item.step > 1) {
                    (item.valueRange.first + ((raw - item.valueRange.first) / item.step).roundToInt() * item.step)
                } else {
                    raw.roundToInt()
                }
                item.onValueChange(snapped.coerceIn(item.valueRange.first, item.valueRange.last))
            },
            valueRange = range,
            steps = steps,
            enabled = item.enabled,
            onValueChangeFinished = item.onValueChangeFinished
        )
    }
}
