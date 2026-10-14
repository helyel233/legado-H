package io.legado.app.ui.book.read.config

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.usesDirectReader
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.epubcore.layout.EpubReaderChromeModePolicy
import io.legado.app.reader.config.ReadConfigEvent
import io.legado.app.reader.config.ReadConfigEvent.HeaderFooterTips
import io.legado.app.reader.config.ReadConfigEvent.Relayout
import io.legado.app.reader.config.ReadConfigEvent.TipStyle
import io.legado.app.reader.config.ReadConfigEvent.Typography
import io.legado.app.ui.config.AdvancedTipManageActivity
import io.legado.app.ui.config.AdvancedTitleManageActivity
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppSlider
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.hexString
import io.legado.app.utils.observeEvent
import kotlin.math.roundToInt

/**
 * 页眉页脚（提示条）配置面板（V5 基座版）。
 *
 * 旧基类 BOTTOM/AnimDialogBottom/MATCH_PARENT → V5 五个 open val；`maxSheetHeightFraction = 0.76f`
 * → 面板内 `heightIn(max = screenHeightDp*0.76f)`；**外观跟随阅读页背景色**用共享 `rememberReaderPagePalette()`。
 * 组件替换：`ReaderSectionCard` → Box+shape+palette.panel；`AppThemedStepperSlider` → `AppSlider(steps = range.count()-1)`；
 * `LegadoMiuixChoiceRow(compact, showSelectedMark = false)` → `AppChip(selected)`；自定义 TipValueRow/TipCompactValue 保留结构只换色。
 * **业务逻辑全部保留**：titleMode 越界校正、`titleModeToUiIndex/uiIndexToTitleMode` 的 UI↔存储映射（高级标题模式插在中间）、
 * `clearRepeat()` 的六槽位去重、`chooseTip` 后若为自定义模板则接着打开模板编辑器、
 * 颜色选取器走 `ColorPickerDialog`（TIP_COLOR/TIP_DIVIDER_COLOR 两个 dialogId）与 `EventBus.TIP_COLOR` 刷新。
 */
internal class TipConfigDialog : V5DialogFragment() {

    companion object {
        const val TIP_COLOR = 7897
        const val TIP_DIVIDER_COLOR = 7898
    }

    override val dialogTheme: Int = R.style.Theme_Legado_ComposeDialog_Bottom
    override val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    override val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    override val dialogGravity: Int = Gravity.BOTTOM
    override val dialogWindowAnimations: Int = R.style.AnimDialogBottom

    private val maxSheetHeightFraction: Float = 0.76f

    private var colorRefreshTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ReadBookConfig.titleMode !in 0..AdvancedTitleConfig.TITLE_MODE_ADVANCED) {
            ReadBookConfig.titleMode = 0
        }
        observeEvent<String>(EventBus.TIP_COLOR) {
            colorRefreshTick++
        }
    }

    // ── V5 弹窗状态 ─────────────────────────────────────────────
    private var selectorRequest by mutableStateOf<SelectorRequest?>(null)
    private var templateRequest by mutableStateOf<TemplateRequest?>(null)

    private class SelectorRequest(
        val title: String,
        val labels: List<String>,
        val onSelected: (Int) -> Unit
    )

    private class TemplateRequest(
        val title: String,
        val initial: String,
        val onSaved: (String) -> Unit
    )

    private fun showActionSelector(
        title: String,
        labels: List<String>,
        onSelected: (Int) -> Unit
    ) {
        selectorRequest = SelectorRequest(title, labels, onSelected)
    }

    private fun showTemplateEditor(
        title: String,
        initialValue: String,
        onSaved: (String) -> Unit
    ) {
        templateRequest = TemplateRequest(
            title = getString(R.string.tip_custom_template_edit, title),
            initial = initialValue,
            onSaved = onSaved
        )
    }

    @Composable
    override fun Content() {
        val palette = rememberReaderPagePalette()
        val surface = Color(palette.surface)
        val maxHeight = (LocalConfiguration.current.screenHeightDp * maxSheetHeightFraction)
            .toInt()
            .dp

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .navigationBarsPadding(),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            color = surface,
            contentColor = Color(palette.textColor)
        ) {
            TipConfigContent(
                palette = palette,
                colorRefreshTick = colorRefreshTick,
                onShowAdvancedTitleConfig = {
                    startActivity(Intent(requireContext(), AdvancedTitleManageActivity::class.java))
                },
                onShowSelector = ::showActionSelector,
                onShowTemplateEditor = ::showTemplateEditor,
                onShowTipColorPicker = {
                    ColorPickerDialog.newBuilder()
                        .setShowAlphaSlider(false)
                        .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                        .setDialogId(TIP_COLOR)
                        .show(requireActivity())
                },
                onShowTipDividerColorPicker = {
                    ColorPickerDialog.newBuilder()
                        .setShowAlphaSlider(false)
                        .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                        .setDialogId(TIP_DIVIDER_COLOR)
                        .show(requireActivity())
                },
                onColorChanged = { colorRefreshTick++ }
            )
        }
        TipConfigDialogs()
    }

    @Composable
    private fun TipConfigDialogs() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density

        selectorRequest?.let { req ->
            AppFilterDialog(
                title = req.title,
                options = req.labels,
                selectedIndex = -1,
                onSelect = { index ->
                    selectorRequest = null
                    req.onSelected(index)
                },
                onDismiss = { selectorRequest = null },
            )
        }

        templateRequest?.let { req ->
            var text by remember(req) { mutableStateOf(req.initial) }
            AppDialog(onDismiss = { templateRequest = null }) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    Text(
                        text = req.title,
                        style = visual.type.title,
                        color = colors.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.tip_custom_template_help),
                        style = visual.type.caption,
                        color = colors.onSurfaceVariant,
                    )
                    AppTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = stringResource(R.string.tip_custom_template_hint),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 10,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                        AppGhostButton(
                            text = stringResource(R.string.cancel),
                            modifier = Modifier.weight(1f),
                            onClick = { templateRequest = null },
                        )
                        AppFilledButton(
                            text = stringResource(R.string.confirm),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                templateRequest = null
                                req.onSaved(text)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TipConfigContent(
    palette: ReaderSheetStyle.Palette,
    colorRefreshTick: Int,
    onShowAdvancedTitleConfig: () -> Unit,
    onShowSelector: (String, List<String>, (Int) -> Unit) -> Unit,
    onShowTemplateEditor: (String, String, (String) -> Unit) -> Unit,
    onShowTipColorPicker: () -> Unit,
    onShowTipDividerColorPicker: () -> Unit,
    onColorChanged: () -> Unit
) {
    val context = LocalContext.current
    val panel = Color(palette.panel)
    val surface = Color(palette.surface)
    val textColor = Color(palette.textColor)
    val secondaryText = Color(palette.secondaryTextColor)
    val accent = Color(palette.accentColor)
    val directEpub = ReadBook.book?.usesDirectReader == true
    var titleMode by rememberSaveable { mutableIntStateOf(ReadBookConfig.titleMode) }
    var titleSize by rememberSaveable { mutableIntStateOf(ReadBookConfig.titleSize) }
    var titleTopSpacing by rememberSaveable { mutableIntStateOf(ReadBookConfig.titleTopSpacing) }
    var titleBottomSpacing by rememberSaveable {
        mutableIntStateOf(ReadBookConfig.titleBottomSpacing)
    }
    var headerMode by rememberSaveable { mutableIntStateOf(ReadTipConfig.headerMode) }
    var footerMode by rememberSaveable { mutableIntStateOf(ReadTipConfig.footerMode) }
    var headerLeft by rememberSaveable { mutableIntStateOf(ReadTipConfig.tipHeaderLeft) }
    var headerMiddle by rememberSaveable { mutableIntStateOf(ReadTipConfig.tipHeaderMiddle) }
    var headerRight by rememberSaveable { mutableIntStateOf(ReadTipConfig.tipHeaderRight) }
    var footerLeft by rememberSaveable { mutableIntStateOf(ReadTipConfig.tipFooterLeft) }
    var footerMiddle by rememberSaveable { mutableIntStateOf(ReadTipConfig.tipFooterMiddle) }
    var footerRight by rememberSaveable { mutableIntStateOf(ReadTipConfig.tipFooterRight) }
    val headerModes = remember(context, directEpub) {
        EpubReaderChromeModePolicy.selectableModes(
            modes = ReadTipConfig.getHeaderModes(context),
            directEpub = directEpub,
            advancedMode = ReadTipConfig.HEADER_MODE_ADVANCED
        )
    }
    val footerModes = remember(context, directEpub) {
        EpubReaderChromeModePolicy.selectableModes(
            modes = ReadTipConfig.getFooterModes(context),
            directEpub = directEpub,
            advancedMode = ReadTipConfig.FOOTER_MODE_ADVANCED
        )
    }
    val tipNames = ReadTipConfig.tipNames
    val tipValues = ReadTipConfig.tipValues.toList()
    val titleModeOptions = if (directEpub) {
        listOf(
            stringResource(R.string.title_left),
            stringResource(R.string.title_center),
            stringResource(R.string.title_hide)
        )
    } else {
        listOf(
            stringResource(R.string.title_left),
            stringResource(R.string.title_center),
            stringResource(R.string.advanced_title_mode_label),
            stringResource(R.string.title_hide)
        )
    }
    fun titleModeToUiIndex(mode: Int): Int {
        if (directEpub) return if (mode == 2) 2 else mode.coerceIn(0, 2)
        return when (mode) {
            AdvancedTitleConfig.TITLE_MODE_ADVANCED -> 2
            2 -> 3
            else -> mode
        }.coerceIn(0, titleModeOptions.lastIndex)
    }
    fun uiIndexToTitleMode(index: Int): Int {
        if (directEpub) return if (index == 2) 2 else index
        return when (index) {
            2 -> AdvancedTitleConfig.TITLE_MODE_ADVANCED
            3 -> 2
            else -> index
        }
    }
    fun tipName(value: Int): String {
        val index = tipValues.indexOf(value)
        return tipNames.getOrElse(index) { tipNames[ReadTipConfig.none] }
    }
    fun clearRepeat(value: Int) {
        if (value == ReadTipConfig.none) return
        if (headerLeft == value) { headerLeft = ReadTipConfig.none; ReadTipConfig.tipHeaderLeft = ReadTipConfig.none }
        if (headerMiddle == value) { headerMiddle = ReadTipConfig.none; ReadTipConfig.tipHeaderMiddle = ReadTipConfig.none }
        if (headerRight == value) { headerRight = ReadTipConfig.none; ReadTipConfig.tipHeaderRight = ReadTipConfig.none }
        if (footerLeft == value) { footerLeft = ReadTipConfig.none; ReadTipConfig.tipFooterLeft = ReadTipConfig.none }
        if (footerMiddle == value) { footerMiddle = ReadTipConfig.none; ReadTipConfig.tipFooterMiddle = ReadTipConfig.none }
        if (footerRight == value) { footerRight = ReadTipConfig.none; ReadTipConfig.tipFooterRight = ReadTipConfig.none }
    }
    fun customTemplateOf(slotKey: String): String = when (slotKey) {
        "headerLeft" -> ReadTipConfig.tipHeaderLeftTemplate
        "headerMiddle" -> ReadTipConfig.tipHeaderMiddleTemplate
        "headerRight" -> ReadTipConfig.tipHeaderRightTemplate
        "footerLeft" -> ReadTipConfig.tipFooterLeftTemplate
        "footerMiddle" -> ReadTipConfig.tipFooterMiddleTemplate
        else -> ReadTipConfig.tipFooterRightTemplate
    }

    fun setCustomTemplate(slotKey: String, value: String) {
        when (slotKey) {
            "headerLeft" -> ReadTipConfig.tipHeaderLeftTemplate = value
            "headerMiddle" -> ReadTipConfig.tipHeaderMiddleTemplate = value
            "headerRight" -> ReadTipConfig.tipHeaderRightTemplate = value
            "footerLeft" -> ReadTipConfig.tipFooterLeftTemplate = value
            "footerMiddle" -> ReadTipConfig.tipFooterMiddleTemplate = value
            else -> ReadTipConfig.tipFooterRightTemplate = value
        }
    }
    fun chooseTip(title: String, slotKey: String, onAssign: (Int) -> Unit) {
        onShowSelector(title, tipNames) { index ->
            val value = tipValues.getOrElse(index) { ReadTipConfig.none }
            clearRepeat(value)
            onAssign(value)
            ReadConfigEvent.post(HeaderFooterTips, TipStyle)
            if (value == ReadTipConfig.customTemplate) {
                onShowTemplateEditor(title, customTemplateOf(slotKey)) { text ->
                    setCustomTemplate(slotKey, text)
                    ReadConfigEvent.post(HeaderFooterTips, TipStyle)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Publisher/generated document headings belong to the Direct document.
        if (!directEpub) TipSection(panelColor = panel) {
            TipCompactSlider(
                label = stringResource(R.string.title_font_size),
                value = titleSize,
                range = 0..20,
                textColor = textColor,
                accent = accent
            ) { titleSize = it; ReadBookConfig.titleSize = it; ReadConfigEvent.post(Typography, Relayout) }
            TipCompactSlider(
                label = stringResource(R.string.title_margin_top),
                value = titleTopSpacing,
                range = 0..100,
                textColor = textColor,
                accent = accent
            ) { titleTopSpacing = it; ReadBookConfig.titleTopSpacing = it; ReadConfigEvent.post(Typography, Relayout) }
            TipCompactSlider(
                label = stringResource(R.string.title_margin_bottom),
                value = titleBottomSpacing,
                range = 0..100,
                textColor = textColor,
                accent = accent
            ) { titleBottomSpacing = it; ReadBookConfig.titleBottomSpacing = it; ReadConfigEvent.post(Typography, Relayout) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                titleModeOptions.forEachIndexed { index, label ->
                    AppChip(
                        text = label,
                        selected = titleModeToUiIndex(titleMode) == index,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val newMode = uiIndexToTitleMode(index)
                            titleMode = newMode
                            ReadBookConfig.titleMode = newMode
                            ReadConfigEvent.post(Relayout)
                            if (newMode == AdvancedTitleConfig.TITLE_MODE_ADVANCED) {
                                onShowAdvancedTitleConfig()
                            }
                        },
                    )
                }
            }
        }
        // 页眉
        TipPlacementSection(
            title = stringResource(R.string.header),
            showLabel = if (EpubReaderChromeModePolicy.isSupported(
                    directEpub,
                    headerMode,
                    ReadTipConfig.HEADER_MODE_ADVANCED
                )
            ) {
                headerModes[headerMode].orEmpty()
            } else {
                stringResource(R.string.disabled)
            },
            leftLabel = tipName(headerLeft),
            middleLabel = tipName(headerMiddle),
            rightLabel = tipName(headerRight),
            panelColor = panel,
            surfaceColor = surface,
            textColor = textColor,
            secondaryText = secondaryText,
            accent = accent,
            onShowClick = {
                val keys = headerModes.keys.toList()
                onShowSelector(context.getString(R.string.header), headerModes.values.toList()) { index ->
                    headerMode = keys.getOrElse(index) { 0 }
                    ReadTipConfig.headerMode = headerMode
                    ReadConfigEvent.post(HeaderFooterTips)
                    if (headerMode == ReadTipConfig.HEADER_MODE_ADVANCED) {
                        AdvancedTipManageActivity.start(context, io.legado.app.help.config.AdvancedTipSlot.HEADER)
                    }
                }
            },
            onLeftClick = { chooseTip(context.getString(R.string.left), "headerLeft") { headerLeft = it; ReadTipConfig.tipHeaderLeft = it } },
            onMiddleClick = { chooseTip(context.getString(R.string.middle), "headerMiddle") { headerMiddle = it; ReadTipConfig.tipHeaderMiddle = it } },
            onRightClick = { chooseTip(context.getString(R.string.right), "headerRight") { headerRight = it; ReadTipConfig.tipHeaderRight = it } },
            manageLabel = if (!directEpub && headerMode == ReadTipConfig.HEADER_MODE_ADVANCED) {
                stringResource(R.string.advanced_header_manage)
            } else null,
            onManageClick = if (!directEpub && headerMode == ReadTipConfig.HEADER_MODE_ADVANCED) {
                { AdvancedTipManageActivity.start(context, io.legado.app.help.config.AdvancedTipSlot.HEADER) }
            } else null
        )
        // 页脚
        TipPlacementSection(
            title = stringResource(R.string.footer),
            showLabel = if (EpubReaderChromeModePolicy.isSupported(
                    directEpub,
                    footerMode,
                    ReadTipConfig.FOOTER_MODE_ADVANCED
                )
            ) {
                footerModes[footerMode].orEmpty()
            } else {
                stringResource(R.string.disabled)
            },
            leftLabel = tipName(footerLeft),
            middleLabel = tipName(footerMiddle),
            rightLabel = tipName(footerRight),
            panelColor = panel,
            surfaceColor = surface,
            textColor = textColor,
            secondaryText = secondaryText,
            accent = accent,
            onShowClick = {
                val keys = footerModes.keys.toList()
                onShowSelector(context.getString(R.string.footer), footerModes.values.toList()) { index ->
                    footerMode = keys.getOrElse(index) { 0 }
                    ReadTipConfig.footerMode = footerMode
                    ReadConfigEvent.post(HeaderFooterTips)
                    if (footerMode == ReadTipConfig.FOOTER_MODE_ADVANCED) {
                        AdvancedTipManageActivity.start(context, io.legado.app.help.config.AdvancedTipSlot.FOOTER)
                    }
                }
            },
            onLeftClick = { chooseTip(context.getString(R.string.left), "footerLeft") { footerLeft = it; ReadTipConfig.tipFooterLeft = it } },
            onMiddleClick = { chooseTip(context.getString(R.string.middle), "footerMiddle") { footerMiddle = it; ReadTipConfig.tipFooterMiddle = it } },
            onRightClick = { chooseTip(context.getString(R.string.right), "footerRight") { footerRight = it; ReadTipConfig.tipFooterRight = it } },
            manageLabel = if (!directEpub && footerMode == ReadTipConfig.FOOTER_MODE_ADVANCED) {
                stringResource(R.string.advanced_footer_manage)
            } else null,
            onManageClick = if (!directEpub && footerMode == ReadTipConfig.FOOTER_MODE_ADVANCED) {
                { AdvancedTipManageActivity.start(context, io.legado.app.help.config.AdvancedTipSlot.FOOTER) }
            } else null
        )
        // 颜色
        TipColorSection(
            colorRefreshTick = colorRefreshTick,
            panelColor = panel,
            surfaceColor = surface,
            textColor = textColor,
            accent = accent,
            onTipColorClick = {
                onShowSelector(context.getString(R.string.text_color), ReadTipConfig.tipColorNames) { index ->
                    when (index) {
                        0 -> { ReadTipConfig.tipColor = 0; onColorChanged(); ReadConfigEvent.post(HeaderFooterTips) }
                        1 -> onShowTipColorPicker()
                    }
                }
            },
            onDividerColorClick = {
                onShowSelector(context.getString(R.string.tip_divider_color), ReadTipConfig.tipDividerColorNames) { index ->
                    when (index) {
                        0, 1 -> { ReadTipConfig.tipDividerColor = index - 1; onColorChanged(); ReadConfigEvent.post(HeaderFooterTips) }
                        2 -> onShowTipDividerColorPicker()
                    }
                }
            }
        )
    }
}

@Composable
private fun TipSection(
    panelColor: Color,
    content: @Composable () -> Unit
) {
    val visual = LocalVisualSpec.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(visual.shape(ShapeKind.BAR))
            .background(panelColor)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}

@Composable
private fun TipCompactSlider(
    label: String,
    value: Int,
    range: IntRange,
    textColor: Color,
    accent: Color,
    onValueChange: (Int) -> Unit
) {
    val visual = LocalVisualSpec.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = textColor,
            style = visual.type.caption,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(56.dp)
        )
        Text(
            text = value.toString(),
            color = accent,
            style = visual.type.caption,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.width(32.dp)
        )
        AppSlider(
            value = value.coerceIn(range).toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.count() - 1,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TipPlacementSection(
    title: String,
    showLabel: String,
    leftLabel: String,
    middleLabel: String,
    rightLabel: String,
    panelColor: Color,
    surfaceColor: Color,
    textColor: Color,
    secondaryText: Color,
    accent: Color,
    onShowClick: () -> Unit,
    onLeftClick: () -> Unit,
    onMiddleClick: () -> Unit,
    onRightClick: () -> Unit,
    manageLabel: String? = null,
    onManageClick: (() -> Unit)? = null
) {
    val visual = LocalVisualSpec.current
    TipSection(panelColor = panelColor) {
        Text(
            text = title,
            style = visual.type.title,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        TipValueRow(
            title = stringResource(R.string.show_hide),
            value = showLabel,
            surfaceColor = surfaceColor,
            textColor = textColor,
            accent = accent,
            onClick = onShowClick
        )
        if (manageLabel != null && onManageClick != null) {
            TipValueRow(
                title = manageLabel,
                value = stringResource(R.string.advanced_title_manage),
                surfaceColor = surfaceColor,
                textColor = textColor,
                accent = accent,
                onClick = onManageClick
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            TipCompactValue(
                title = stringResource(R.string.left),
                value = leftLabel,
                surfaceColor = surfaceColor,
                secondaryText = secondaryText,
                textColor = textColor,
                modifier = Modifier.weight(1f),
                onClick = onLeftClick
            )
            TipCompactValue(
                title = stringResource(R.string.middle),
                value = middleLabel,
                surfaceColor = surfaceColor,
                secondaryText = secondaryText,
                textColor = textColor,
                modifier = Modifier.weight(1f),
                onClick = onMiddleClick
            )
            TipCompactValue(
                title = stringResource(R.string.right),
                value = rightLabel,
                surfaceColor = surfaceColor,
                secondaryText = secondaryText,
                textColor = textColor,
                modifier = Modifier.weight(1f),
                onClick = onRightClick
            )
        }
    }
}

@Composable
private fun TipColorSection(
    colorRefreshTick: Int,
    panelColor: Color,
    surfaceColor: Color,
    textColor: Color,
    accent: Color,
    onTipColorClick: () -> Unit,
    onDividerColorClick: () -> Unit
) {
    val tipColorLabel = remember(colorRefreshTick) { tipColorText() }
    val dividerColorLabel = remember(colorRefreshTick) { tipDividerColorText() }
    TipSection(panelColor = panelColor) {
        TipValueRow(
            title = stringResource(R.string.text_color),
            value = tipColorLabel,
            surfaceColor = surfaceColor,
            textColor = textColor,
            accent = accent,
            onClick = onTipColorClick
        )
        TipValueRow(
            title = stringResource(R.string.tip_divider_color),
            value = dividerColorLabel,
            surfaceColor = surfaceColor,
            textColor = textColor,
            accent = accent,
            onClick = onDividerColorClick
        )
    }
}

@Composable
private fun TipValueRow(
    title: String,
    value: String,
    surfaceColor: Color,
    textColor: Color,
    accent: Color,
    onClick: () -> Unit
) {
    val visual = LocalVisualSpec.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(visual.shape(ShapeKind.BAR))
            .background(surfaceColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = textColor,
                style = visual.type.body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                color = accent,
                style = visual.type.body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TipCompactValue(
    title: String,
    value: String,
    surfaceColor: Color,
    secondaryText: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val visual = LocalVisualSpec.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(visual.shape(ShapeKind.BAR))
            .background(surfaceColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            color = secondaryText,
            style = visual.type.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            color = textColor,
            style = visual.type.caption,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun tipColorText(): String {
    val names = ReadTipConfig.tipColorNames
    val color = ReadTipConfig.tipColor
    return if (color == 0) {
        names.first()
    } else {
        "#${color.hexString}"
    }
}

private fun tipDividerColorText(): String {
    val names = ReadTipConfig.tipDividerColorNames
    return when (val color = ReadTipConfig.tipDividerColor) {
        -1, 0 -> names[color + 1]
        else -> "#${color.hexString}"
    }
}
