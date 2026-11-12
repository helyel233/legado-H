package io.legado.app.ui.config.compose

import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlin.math.abs
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.glass.GlassPanel
import io.legado.app.ui.glass.GlassSamplingDomain
import io.legado.app.ui.glass.glassPageBackgroundColor
import io.legado.app.ui.glass.rememberGlassConfigSignature
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.AppSettingSectionTitle
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.LegadoResourceIcon
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.appSettingRowDecoration
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.compose.rememberHasUsableBgImage
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.ColorUtils

private val PanelHorizontalPadding = 12.dp

/**
 * 宿主页面此刻是否**真的露出 App 壁纸**（由宿主显式声明，不由本组件猜）。
 *
 * 三态，缺一不可：
 * - `null`（默认，未声明）：交给 App 级「有没有背景图」判定，也就是本 Local 引入之前
 *   的那条老规则。**约 20 个设置页宿主（`ConfigActivity` 各页、`AppearanceKitActivity`、
 *   `HomepageModuleManageSheet` 等）不声明，行为一字不变。**
 * - `true`：宿主声明「我背后就是壁纸」，分区卡铺壁纸域玻璃（与「我的」页同一形态）。
 * - `false`：宿主声明「我背后不是壁纸」，**同时约定宿主自己负责面板表面**
 *   （阅读页「更多设置」面板铺的是当前正文的磨砂，见 `MoreConfigDialog`）。
 *   此时分区卡不铺玻璃，且填充改为半透明薄底（[CARD_FILL_YIELD_ALPHA]）——
 *   否则不透明的卡片会把面板的磨砂整块盖死，变成「实底套磨砂」。
 *
 * 为什么必须是「声明」而不是「探测」：运行期无法可靠判断任意外层容器是否不透明
 * （取决于布局、主题与运行时状态），把判定写成猜测只会引入假阴性。这与
 * `GlassSamplingDomain` 显式声明采样域是同一条纪律。
 */
val LocalGlassWallpaperAvailable = compositionLocalOf<Boolean?> { null }

/**
 * 宿主自己铺了磨砂面板时，分区卡的填充不透明度。
 *
 * 0.45 = 「能看出这是一张卡、又能透出面板底下的阅读内容」。取值来源是
 * `UiCorner.surfaceColor` 算出的卡片底色再压这个 alpha；1px 描边沿用 `colors.border`，
 * 所以卡片的可辨识度不依赖填充，降低了薄底对可读性的影响。
 */
private const val CARD_FILL_YIELD_ALPHA = 0.45f

@Composable
fun SettingSpecScreen(
    page: SettingPageSpec,
    scrollTargetKey: String?,
    drawPanelImage: Boolean = true,
    onTargetReady: (String) -> Unit,
    onTargetMissing: () -> Unit,
    onItemClick: (SettingItemSpec) -> Unit
) {
    val colors = rememberAppSettingPalette()
    val panelRadiusPx = colors.panelRadiusPx
    // 壁纸模式下页面根背景透明，分区卡背后是壁纸（壁纸域）；无背景图 / 墨水屏时
    // 保持主题页色与实底卡，观感逐值不变。
    //
    // 宿主可以用 [LocalGlassWallpaperAvailable] 覆盖这个结论：阅读页的配置弹窗声明 false
    // （背后是自绘正文，不是 App 壁纸），不声明时这里的表达式与引入本 Local 之前完全一致。
    //
    // `rememberHasUsableBgImage()` 一定要在 `?:` 左边**无条件**调用掉：写成
    // `declared ?: rememberHasUsableBgImage() && ...` 会把这次 remember 变成条件调用，
    // 宿主声明值翻转时该组合槽位会插入/移除，白白丢掉一次记忆化。先算成局部变量即可。
    val declaredWallpaperAvailable = LocalGlassWallpaperAvailable.current
    val appWallpaperVisible = rememberHasUsableBgImage() && !AppConfig.isEInkMode
    val wallpaperVisible = declaredWallpaperAvailable ?: appWallpaperVisible
    // 宿主声明「不露壁纸」等价于「宿主自己铺了面板表面」：分区卡必须让出填充，
    // 否则面板磨砂被不透明卡面整块盖死，又变回「实底套磨砂」。
    val cardFillYieldsToPanel = declaredWallpaperAvailable == false
    val glassSignature = rememberGlassConfigSignature()
    val sections = remember(page) {
        page.sections.mapNotNull { section ->
            val rows = section.items.filter { it.visible }
            rows.takeIf { it.isNotEmpty() }?.let {
                // 这里过滤 visible 后重建了 SectionSpec，折叠相关字段必须透传，
                // 否则所有 collapsible 分区都会静默退化成普通分区（折叠完全失效）
                SettingSectionSpec(
                    title = section.title,
                    items = it,
                    collapsible = section.collapsible,
                    collapsedByDefault = section.collapsedByDefault
                )
            }
        }
    }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val header = page.header
    // 头部区块是 LazyColumn 的第 0 项，分区整体后移一位；定位目标时要补上这个偏移，
    // 否则从搜索进来会滚到错误的区块。
    val sectionIndexOffset = if (header != null) 1 else 0
    val targetTitleOffsetPx = with(density) { 38.dp.roundToPx() }
    val targetRowOffsetPx = with(density) { 72.dp.roundToPx() }
    // 目标行的实测位置：行高随摘要行数（1~3 行）和滑杆行变化，按固定 72dp 估算必然对不准，
    // 所以先滚到估算位置，再按实测差值校正一次
    var listTopPx by remember { mutableStateOf(0f) }
    var targetRowTopPx by remember { mutableStateOf<Float?>(null) }
    // 命中行高亮：从搜索结果跳进来时，用户需要一眼看到「就是这一项」，
    // 尤其 switch/滑杆这类点击后不弹窗、界面上没有任何变化的行
    var highlightKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightKey) {
        if (highlightKey == null) return@LaunchedEffect
        delay(1600)
        highlightKey = null
    }
    LaunchedEffect(scrollTargetKey, sections) {
        val targetKey = scrollTargetKey?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        targetRowTopPx = null
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
            listState.animateScrollToItem(targetSectionIndex + sectionIndexOffset, scrollOffset)
            // 先点亮高亮再校正：目标行只有处于高亮态才会回报实测位置
            highlightKey = targetKey
            // 动画结束后目标行可能已经不在组合里（滚过头），所以每帧重新取实测位置，
            // 收敛不了就退回估算值，不阻塞 onTargetReady
            repeat(2) {
                withFrameNanos { }
                val rowTop = targetRowTopPx ?: return@repeat
                val delta = rowTop - listTopPx - targetRowOffsetPx
                if (abs(delta) < 2f) return@repeat
                listState.scrollBy(delta)
            }
            onTargetReady(targetKey)
        } else {
            onTargetMissing()
        }
    }

    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = colors.bodyFontFamily)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // 列表根背景：有壁纸时透明（透出壁纸），否则铺主题页色。
                //
                // 第三种情况同样必须透明：宿主自己铺了面板表面（`cardFillYieldsToPanel`，
                // 阅读页「更多设置」面板的正文磨砂）。漏掉这一位的话，这个**铺满整块面板**的
                // 不透明 `colors.page` 会把面板磨砂从根上盖死——分区卡改成薄底也白改，
                // 露出来的只会是主题页色，真机那条「黑边」会原样留着。
                .background(
                    glassPageBackgroundColor(
                        pageColor = colors.page,
                        hasBgImage = wallpaperVisible || cardFillYieldsToPanel
                    )
                )
                .onGloballyPositioned { listTopPx = it.positionInWindow().y }
                .navigationBarsPadding(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (header != null) {
                item(key = "page-header") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PanelHorizontalPadding)
                    ) {
                        header.content()
                    }
                }
            }
            itemsIndexed(
                sections,
                key = { index, section -> "${index}:${section.title}:${section.items.firstOrNull()?.key}" }
            ) { _, section ->
                SettingSectionPanel(
                    section = section,
                    colors = colors,
                    panelRadiusPx = panelRadiusPx,
                    drawPanelImage = drawPanelImage,
                    wallpaperVisible = wallpaperVisible,
                    cardFillYieldsToPanel = cardFillYieldsToPanel,
                    glassSignature = glassSignature,
                    // 带 targetKey 进来说明是要定位到某一项，此时所有分区必须展开，
                    // 否则目标项根本不在列表里，定位会静默失败
                    forceExpanded = !scrollTargetKey.isNullOrBlank(),
                    highlightKey = highlightKey,
                    onTargetRowPositioned = { targetRowTopPx = it },
                    onItemClick = onItemClick
                )
            }
        }
    }
}

@Composable
private fun SettingSectionPanel(
    section: SettingSectionSpec,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    drawPanelImage: Boolean,
    wallpaperVisible: Boolean,
    cardFillYieldsToPanel: Boolean,
    glassSignature: String,
    forceExpanded: Boolean,
    highlightKey: String?,
    onTargetRowPositioned: (Float) -> Unit,
    onItemClick: (SettingItemSpec) -> Unit
) {
    val context = LocalContext.current
    val panelImage = if (drawPanelImage) {
        remember(context, panelRadiusPx, colors.themeSignature) {
            UiCorner.panelImageDrawable(context, panelRadiusPx)
        }
    } else {
        null
    }
    val stateKey = section.title?.toString()
        ?: section.items.firstOrNull()?.key
        ?: ""
    var expanded by rememberSaveable(stateKey) {
        mutableStateOf(!section.collapsedByDefault)
    }
    // 首组合可能早于 onResume 里 consumeTargetKey() 设置 targetKey，
    // 所以不能用 init 时的 forceExpanded 一次性决定展开状态：
    // targetKey 到位后（含从 null 变非空）强制展开一次，
    // 消费完置回 null 时 forceExpanded 变 false，LaunchedEffect 重启但不改状态，
    // 用户手动展开/收起的状态不会被重置
    LaunchedEffect(forceExpanded) {
        if (forceExpanded) {
            expanded = true
        }
    }
    val sectionContent: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            AppSettingSectionTitle(
                title = section.title,
                palette = colors,
                collapsible = section.collapsible,
                expanded = expanded,
                onToggle = if (section.collapsible) {
                    { expanded = !expanded }
                } else {
                    null
                }
            )
            if (expanded) {
                section.items.forEachIndexed { index, item ->
                    SettingRow(
                        item = item,
                        colors = colors,
                        panelRadiusPx = panelRadiusPx,
                        showDivider = index != section.items.lastIndex,
                        isLast = index == section.items.lastIndex,
                        isHighlighted = highlightKey != null &&
                            (item.key == highlightKey || highlightKey in item.searchKeys),
                        onTargetPositioned = onTargetRowPositioned,
                        onItemClick = onItemClick
                    )
                }
            }
        }
    }
    val panelFallback = Modifier.appSettingPanelBackground(
        // 宿主自己铺了磨砂面板时，卡面让出填充（薄底），把面板底下的阅读内容透上来；
        // 其它情况保持原来的实底卡面，观感逐值不变。
        normalColor = if (cardFillYieldsToPanel) {
            ColorUtils.withAlpha(colors.row, CARD_FILL_YIELD_ALPHA)
        } else {
            colors.row
        },
        panelImage = panelImage,
        borderColor = colors.border,
        radiusPx = panelRadiusPx
    )
    if (wallpaperVisible) {
        // 分区卡与「我的」页 SettingsSectionCard 同一份壁纸域玻璃（GlassPanel 桥），
        // 材质 / 降级 / 圆角全部由底层管线决定，这里只声明采样域与圆角。
        //
        // `requireWallpaperHost = true` 不能省：`wallpaperVisible` 只回答「App 有没有背景图」，
        // 回答不了「宿主页面此刻露不露壁纸」。本页被阅读页的「更多设置」面板复用
        // （`MoreConfigDialog.ReadPreferenceFragment`），而阅读页正文由 ReadView 自绘阅读主题
        // 背景、页根背景从不置空 —— 少了这一位，阅读页的分区卡会去采样用户根本看不见的
        // App 壁纸（真机反馈：「面板里透出的是 app 壁纸的模糊版」）。
        // 同一判定顺带修掉 solid 档：不开这一位时 `GlassPanel` 只查采样域与设备，
        // 无玻璃档会拿到 `solidShellDrawable`（底栏色不透明壳），分区卡跟着变成底栏色；
        // 判否后走下面的 `panelFallback`，回到主题卡片色（与 `pagePanelBackground` 同一纪律）。
        // 判否时的渲染与下面 else 分支逐值相同，因此无背景图 / 墨水屏 / solid / 低内存
        // 的观感不变。
        GlassPanel(
            domain = GlassSamplingDomain.WALLPAPER,
            cornerRadiusPx = panelRadiusPx,
            configSignature = glassSignature,
            requireWallpaperHost = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PanelHorizontalPadding),
            fallback = panelFallback
        ) {
            sectionContent()
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PanelHorizontalPadding)
                .then(panelFallback)
        ) {
            sectionContent()
        }
    }
}

@Composable
private fun SettingRow(
    item: SettingItemSpec,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    showDivider: Boolean,
    isLast: Boolean,
    isHighlighted: Boolean,
    onTargetPositioned: (Float) -> Unit,
    onItemClick: (SettingItemSpec) -> Unit
) {
    if (item is SettingSliderSpec) {
        SettingSliderRow(
            item = item,
            colors = colors,
            panelRadiusPx = panelRadiusPx,
            showDivider = showDivider,
            isLast = isLast,
            isHighlighted = isHighlighted,
            onTargetPositioned = onTargetPositioned
        )
        return
    }
    val highlightAlpha by animateFloatAsState(
        targetValue = if (isHighlighted) 1f else 0f,
        animationSpec = tween(durationMillis = if (isHighlighted) 120 else 700)
    )
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val dialogStyle = rememberAppDialogStyle()
    var choiceAnchor by remember(item.key) { mutableStateOf<View?>(null) }
    var choicePopupHandle by remember(item.key) { mutableStateOf<ModernActionPopup.Handle?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            choicePopupHandle?.dismiss()
            choicePopupHandle = null
        }
    }
    fun openChoicePopup(choice: SettingChoiceSpec) {
        val anchor = choiceAnchor
        if (anchor == null) {
            onItemClick(choice)
            return
        }
        choicePopupHandle = ModernActionPopup.show(
            anchor = anchor,
            actions = choice.toPopupActions(),
            previousPopup = choicePopupHandle
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .onGloballyPositioned { coords ->
                if (isHighlighted) onTargetPositioned(coords.positionInWindow().y)
            }
            .appSettingRowDecoration(
                pressed = pressed,
                pressedColor = colors.rowPressed,
                dividerColor = colors.divider,
                showDivider = showDivider,
                radiusPx = panelRadiusPx,
                isLast = isLast,
                highlightColor = colors.accent.copy(alpha = 0.14f * highlightAlpha)
            )
            .settingRowClick(
                item = item,
                interactionSource = interactionSource,
                onItemClick = { clickedItem ->
                    if (clickedItem is SettingChoiceSpec) {
                        openChoicePopup(clickedItem)
                    } else {
                        onItemClick(clickedItem)
                    }
                }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        SettingText(
            item = item,
            colors = colors,
            modifier = Modifier.weight(1f)
        )
        when (item) {
            is SettingSwitchSpec -> {
                Spacer(modifier = Modifier.width(12.dp))
                val palette = dialogStyle.toMiuixPalette()
                LegadoMiuixSwitch(
                    checked = item.checked,
                    onCheckedChange = item.onCheckedChange,
                    palette = palette,
                    enabled = item.enabled
                )
            }

            is SettingChoiceSpec -> {
                Spacer(modifier = Modifier.width(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(dialogStyle.actionRadius))
                        .background(colors.accent.copy(alpha = if (item.enabled) 0.10f else 0.05f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    item.selectedOption?.iconName?.let { iconName ->
                        LegadoResourceIcon(
                            iconName = iconName,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = item.selectedLabel.toString(),
                        color = if (item.enabled) colors.accent else colors.disabledText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    AndroidView(
                        factory = { context ->
                            View(context).apply {
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                isClickable = false
                                isFocusable = false
                            }
                        },
                        update = { view -> choiceAnchor = view },
                        modifier = Modifier.size(1.dp)
                    )
                }
            }

            is SettingSliderSpec -> Unit
            is SettingActionSpec -> {
                if (item.navigates && item.enabled) {
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
    }
}

private fun SettingChoiceSpec.toPopupActions(): List<ModernActionPopup.Action> {
    return options.map { option ->
        ModernActionPopup.Action(
            title = option.label.toString(),
            description = option.description?.toString(),
            iconName = option.iconName,
            checked = option.value == selectedValue,
            enabled = enabled
        ) {
            onSelected(option.value)
        }
    }
}

private fun Modifier.settingRowClick(
    item: SettingItemSpec,
    interactionSource: MutableInteractionSource,
    onItemClick: (SettingItemSpec) -> Unit
): Modifier {
    // 开关行用 toggleable 而不是 clickable(role=Switch)：前者会把「已开启/已关闭」
    // 一起交给无障碍服务，后者只播报控件类型，用户听不出当前状态
    if (item is SettingSwitchSpec) {
        return toggleable(
            value = item.checked,
            enabled = item.enabled,
            interactionSource = interactionSource,
            indication = null,
            role = Role.Switch,
            onValueChange = item.onCheckedChange
        )
    }
    val action = item as? SettingActionSpec
    val onLongClick = action?.onLongClick
    return if (onLongClick != null) {
        combinedClickable(
            enabled = item.enabled,
            interactionSource = interactionSource,
            indication = null,
            role = item.clickRole(),
            onClick = { onItemClick(item) },
            onLongClick = onLongClick
        )
    } else {
        clickable(
            enabled = item.enabled,
            interactionSource = interactionSource,
            indication = null,
            role = item.clickRole(),
            onClick = { onItemClick(item) }
        )
    }
}

private fun SettingItemSpec.clickRole(): Role {
    return when (this) {
        is SettingSwitchSpec -> Role.Switch
        is SettingActionSpec,
        is SettingChoiceSpec -> Role.Button
        is SettingSliderSpec -> Role.Button
    }
}

@Composable
private fun SettingSliderRow(
    item: SettingSliderSpec,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    showDivider: Boolean,
    isLast: Boolean,
    isHighlighted: Boolean,
    onTargetPositioned: (Float) -> Unit
) {
    val palette = rememberAppDialogStyle().toMiuixPalette()
    val sliderValue = item.value.coerceIn(item.valueRange)
    val highlightAlpha by animateFloatAsState(
        targetValue = if (isHighlighted) 1f else 0f,
        animationSpec = tween(durationMillis = if (isHighlighted) 120 else 700)
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            .onGloballyPositioned { coords ->
                if (isHighlighted) onTargetPositioned(coords.positionInWindow().y)
            }
            .appSettingRowDecoration(
                pressed = false,
                pressedColor = colors.rowPressed,
                dividerColor = colors.divider,
                showDivider = showDivider,
                radiusPx = panelRadiusPx,
                isLast = isLast,
                highlightColor = colors.accent.copy(alpha = 0.14f * highlightAlpha)
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingText(
                item = item,
                colors = colors,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = item.valueFormatter(sliderValue),
                color = if (item.enabled) colors.accent else colors.disabledText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        AppThemedStepperSlider(
            value = sliderValue,
            range = item.valueRange,
            onValueChange = { item.onValueChange(it.coerceIn(item.valueRange)) },
            palette = palette,
            modifier = Modifier.fillMaxWidth(),
            enabled = item.enabled,
            step = item.step,
            onValueChangeFinished = item.onValueChangeFinished
        )
    }
}

@Composable
private fun SettingText(
    item: SettingItemSpec,
    colors: AppSettingPalette,
    modifier: Modifier = Modifier
) {
    val titleColor = if (item.enabled) colors.primaryText else colors.disabledText
    val summaryColor = if (item.enabled) colors.secondaryText else colors.disabledText
    Column(modifier = modifier) {
        Text(
            text = item.title.toString(),
            color = titleColor,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        item.summary?.takeIf { it.isNotBlank() }?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it.toString(),
                color = summaryColor,
                fontSize = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
