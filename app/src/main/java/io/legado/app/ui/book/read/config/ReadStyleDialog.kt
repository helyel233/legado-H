package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.liuyueyi.quick.transfer.constants.TransType
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PageAnimationSpeed
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReaderFontWeight
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.reader.config.ReadConfigEvent
import io.legado.app.reader.config.ReadConfigEvent.Background
import io.legado.app.reader.config.ReadConfigEvent.HeaderFooterTips
import io.legado.app.reader.config.ReadConfigEvent.InvalidateTextPage
import io.legado.app.reader.config.ReadConfigEvent.Relayout
import io.legado.app.reader.config.ReadConfigEvent.TipStyle
import io.legado.app.reader.config.ReadConfigEvent.Typography
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.ui.widget.compose.releaseComposeImage
import io.legado.app.ui.widget.image.CircleImageView
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppSlider
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlin.math.roundToInt

/**
 * 阅读样式面板（V5 基座版）。
 *
 * 旧基类 `ReaderBottomSheetComposeDialogFragment`(BOTTOM + AnimDialogBottom + MATCH_PARENT) 对应 V5 基座的五个 open val；
 * 旧 `maxSheetHeightFraction = 0.70f` 改为面板内 `heightIn(max = screenHeightDp * 0.70f)`。
 * **外观跟随阅读页背景色**：改用共享的 `rememberReaderPagePalette()`。
 * 组件替换：`ReaderSectionCard` → Box+shape+palette.panel；`ReaderSegmentedOptions` → LazyRow/Row + `AppChip(selected)`；
 * `ReaderTextAction` → AppGhostButton；`ReaderSwitchRow` → `AppRowWithSwitch(card = false)`（**降级：原 summary 副标题不再显示**，
 * 因 AppRowWithSwitch 未确认支持 summary 参数）；`LegadoMiuixSlider`/`AppThemedStepperSlider` → `AppSlider(steps = range.count()-1)`；
 * 样式预览的 `CircleImageView`（AndroidView + releaseComposeImage）原样保留。
 * 业务逻辑全部保留：`changeBgTextConfig` 的「仅本书独立样式 vs 全局切换」双分支、动画切换时先 `setPageAnim(-1)` 再绑定 delegate、
 * `showBgTextConfig` 的「先切样式再关面板并打开配置」（原 `view?.post{}` 改为主队列 post）、`FontSelectDialog.CallBack` 实现。
 */
internal class ReadStyleDialog : V5DialogFragment(),
    FontSelectDialog.CallBack {

    override val dialogTheme: Int = R.style.Theme_Legado_ComposeDialog_Bottom
    override val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    override val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    override val dialogGravity: Int = Gravity.BOTTOM
    override val dialogWindowAnimations: Int = R.style.AnimDialogBottom

    private val maxSheetHeightFraction: Float = 0.70f

    private val callBack get() = activity as? ReadBookActivity

    internal var indentDialogOpen by mutableStateOf(false)

    override fun onDismiss(dialog: DialogInterface) {
        ReadBookConfig.save()
        super.onDismiss(dialog)
    }

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val v5Colors = LocalSemanticColors.current
        val palette = rememberReaderPagePalette()
        val surface = Color(palette.surface)
        val textColor = Color(palette.textColor)
        val secondaryText = Color(palette.secondaryTextColor)
        val accent = Color(palette.accentColor)

        var selectedAnim by rememberSaveable { mutableIntStateOf(ReadBook.pageAnim()) }
        val templateOnly = ReadBook.usesPageTemplate()
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
            contentColor = textColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(
                        if (templateOnly) R.string.reader_template_title
                        else if (ReadBookConfig.usingEpubLayout) R.string.epub_layout_profile
                        else R.string.native_layout_profile
                    ),
                    style = visual.type.title,
                    color = textColor,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                if (templateOnly) {
                    AppGhostButton(
                        text = stringResource(R.string.reader_template_title),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showDialogFragment<ReaderTemplateDialog>() },
                    )
                } else {
                    TextMetricSection(
                        palette = palette,
                        textColor = textColor,
                        secondaryText = secondaryText,
                        accent = accent
                    )
                    AnimAndToolsSection(
                        palette = palette,
                        textColor = textColor,
                        secondaryText = secondaryText,
                        selectedAnim = selectedAnim,
                        onSelectedAnimChange = { selectedAnim = it }
                    )
                    StyleLibrarySection(
                        palette = palette,
                        textColor = textColor,
                        secondaryText = secondaryText,
                        onThemeApplied = { selectedAnim = ReadBook.pageAnim() }
                    )
                }
            }
        }

        if (indentDialogOpen) {
            AppFilterDialog(
                title = getString(R.string.text_indent),
                options = resources.getStringArray(R.array.indent).toList(),
                selectedIndex = -1,
                onSelect = { index ->
                    indentDialogOpen = false
                    ReadBookConfig.paragraphIndent = "　".repeat(index)
                    ReadConfigEvent.post(Typography, Relayout)
                },
                onDismiss = { indentDialogOpen = false },
            )
        }
    }

    @Composable
    private fun SectionCard(
        panelColor: Color,
        content: @Composable () -> Unit
    ) {
        val visual = LocalVisualSpec.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(visual.shape(ShapeKind.BAR))
                .background(panelColor)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }

    @Composable
    private fun AnimAndToolsSection(
        palette: ReaderSheetStyle.Palette,
        textColor: Color,
        secondaryText: Color,
        selectedAnim: Int,
        onSelectedAnimChange: (Int) -> Unit
    ) {
        val visual = LocalVisualSpec.current
        val accent = Color(palette.accentColor)
        val panel = Color(palette.panel)
        var selectedAnimationSpeed by rememberSaveable {
            mutableIntStateOf(ReadBookConfig.pageAnimationSpeed.preferenceValue)
        }
        var textWeight by rememberSaveable { mutableIntStateOf(ReadBookConfig.textWeight) }
        var chineseMode by rememberSaveable { mutableIntStateOf(AppConfig.chineseConverterType) }
        var shareLayout by rememberSaveable { mutableStateOf(ReadBookConfig.shareLayout) }
        val chineseLabels = stringArrayResource(R.array.chinese_mode)
        val pageAnimOptions = pageAnimOptions()
        val speedOptions = pageAnimationSpeedOptions()

        SectionCard(panelColor = panel) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(pageAnimOptions.size) { index ->
                    val (value, label) = pageAnimOptions[index]
                    AppChip(
                        text = label,
                        selected = value == selectedAnim.toString(),
                        onClick = {
                            val anim = value.toIntOrNull() ?: return@AppChip
                            if (selectedAnim != anim) {
                                val previousPageAnim = ReadBook.pageAnim()
                                ReadBook.book?.setPageAnim(-1)
                                ReadBookConfig.pageAnim = anim
                                onSelectedAnimChange(anim)
                                callBack?.applyPageAnimationChange(previousPageAnim)
                            }
                        },
                    )
                }
            }
            Text(
                text = stringResource(R.string.page_animation_speed),
                style = visual.type.caption,
                color = secondaryText,
                fontWeight = FontWeight.Medium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                speedOptions.forEach { (value, label) ->
                    AppChip(
                        text = label,
                        selected = value == selectedAnimationSpeed.toString(),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val preferenceValue = value.toIntOrNull() ?: return@AppChip
                            val speed = PageAnimationSpeed.fromPreference(preferenceValue)
                            if (selectedAnimationSpeed != speed.preferenceValue) {
                                ReadBookConfig.pageAnimationSpeed = speed
                                selectedAnimationSpeed = speed.preferenceValue
                            }
                        },
                    )
                }
            }
            FontWeightSlider(
                value = textWeight,
                panelColor = panel,
                textColor = textColor,
                accent = accent,
                onValueChange = { value ->
                    textWeight = value
                    ReadBookConfig.textWeight = value
                },
                onValueChangeFinished = {
                    ReadConfigEvent.post(Typography, InvalidateTextPage, TipStyle)
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                AppGhostButton(
                    text = stringResource(R.string.text_font),
                    modifier = Modifier.weight(1f),
                    onClick = { showDialogFragment<FontSelectDialog>() },
                )
                AppGhostButton(
                    text = stringResource(R.string.text_indent),
                    modifier = Modifier.weight(1f),
                    onClick = { showTextIndentDialog() },
                )
                AppGhostButton(
                    text = stringResource(R.string.padding),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        dismissAllowingStateLoss()
                        callBack?.showPaddingConfig()
                    },
                )
                if (!ReadBookConfig.usingEpubLayout) {
                    AppGhostButton(
                        text = chineseLabels.getOrElse(chineseMode) { "" },
                        modifier = Modifier.weight(1f),
                        onClick = {
                            chineseMode = (chineseMode + 1) % chineseLabels.size
                            AppConfig.chineseConverterType = chineseMode
                            ChineseUtils.unLoad(*TransType.entries.toTypedArray())
                            ReadConfigEvent.post(Relayout)
                        },
                    )
                }
                AppGhostButton(
                    text = stringResource(R.string.information),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        TipConfigDialog().show(childFragmentManager, "tipConfigDialog")
                    },
                )
            }
            AppRowWithSwitch(
                title = stringResource(R.string.share_layout),
                checked = shareLayout,
                onCheckedChange = { checked ->
                    shareLayout = checked
                    ReadBookConfig.shareLayout = checked
                    ReadConfigEvent.post(Background, HeaderFooterTips, Relayout)
                },
                card = false,
            )
        }
    }

    @Composable
    private fun FontWeightSlider(
        value: Int,
        panelColor: Color,
        textColor: Color,
        accent: Color,
        onValueChange: (Int) -> Unit,
        onValueChangeFinished: () -> Unit
    ) {
        val visual = LocalVisualSpec.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(visual.shape(ShapeKind.BAR))
                .background(panelColor)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.text_font_weight),
                    modifier = Modifier.weight(1f),
                    color = textColor,
                    style = visual.type.caption,
                    fontWeight = FontWeight(value)
                )
                Text(
                    text = value.toString(),
                    color = accent,
                    style = visual.type.caption,
                    fontWeight = FontWeight.SemiBold
                )
            }
            AppSlider(
                value = value.toFloat(),
                onValueChange = {
                    onValueChange(
                        it.roundToInt().coerceIn(ReaderFontWeight.MIN, ReaderFontWeight.MAX)
                    )
                },
                onValueChangeFinished = onValueChangeFinished,
                valueRange = ReaderFontWeight.MIN.toFloat()..ReaderFontWeight.MAX.toFloat(),
                steps = ReaderFontWeight.MAX - ReaderFontWeight.MIN - 1,
            )
        }
    }

    @Composable
    private fun TextMetricSection(
        palette: ReaderSheetStyle.Palette,
        textColor: Color,
        secondaryText: Color,
        accent: Color
    ) {
        var textSize by rememberSaveable { mutableIntStateOf(ReadBookConfig.textSize - 5) }
        var letterSpacing by rememberSaveable {
            mutableIntStateOf((ReadBookConfig.letterSpacing * 100).toInt() + 50)
        }
        var lineSpacing by rememberSaveable { mutableIntStateOf(ReadBookConfig.lineSpacingExtra) }
        var paragraphSpacing by rememberSaveable { mutableIntStateOf(ReadBookConfig.paragraphSpacing) }

        SectionCard(panelColor = Color(palette.panel)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    MetricSliderTile(
                        label = stringResource(R.string.text_size),
                        value = textSize,
                        valueText = (textSize + 5).toString(),
                        range = 0..45,
                        panelColor = Color(palette.panel),
                        textColor = textColor,
                        accent = accent,
                        modifier = Modifier.weight(1f),
                        onValueChange = {
                            textSize = it
                            ReadBookConfig.textSize = it + 5
                            ReadConfigEvent.post(Typography, Relayout)
                        }
                    )
                    MetricSliderTile(
                        label = stringResource(R.string.text_letter_spacing),
                        value = letterSpacing,
                        valueText = ((letterSpacing - 50) / 100f).toString(),
                        range = 0..100,
                        panelColor = Color(palette.panel),
                        textColor = textColor,
                        accent = accent,
                        modifier = Modifier.weight(1f),
                        onValueChange = {
                            letterSpacing = it
                            ReadBookConfig.letterSpacing = (it - 50) / 100f
                            ReadConfigEvent.post(Typography, Relayout)
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    MetricSliderTile(
                        label = stringResource(R.string.line_size),
                        value = lineSpacing,
                        valueText = ((lineSpacing - 10) / 10f).toString(),
                        range = 0..20,
                        panelColor = Color(palette.panel),
                        textColor = textColor,
                        accent = accent,
                        modifier = Modifier.weight(1f),
                        onValueChange = {
                            lineSpacing = it
                            ReadBookConfig.lineSpacingExtra = it
                            ReadConfigEvent.post(Typography, Relayout)
                        }
                    )
                    MetricSliderTile(
                        label = stringResource(R.string.paragraph_size),
                        value = paragraphSpacing,
                        valueText = (paragraphSpacing / 10f).toString(),
                        range = 0..20,
                        panelColor = Color(palette.panel),
                        textColor = textColor,
                        accent = accent,
                        modifier = Modifier.weight(1f),
                        onValueChange = {
                            paragraphSpacing = it
                            ReadBookConfig.paragraphSpacing = it
                            ReadConfigEvent.post(Typography, Relayout)
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun MetricSliderTile(
        label: String,
        value: Int,
        valueText: String,
        range: IntRange,
        panelColor: Color,
        textColor: Color,
        accent: Color,
        modifier: Modifier = Modifier,
        onValueChange: (Int) -> Unit
    ) {
        val visual = LocalVisualSpec.current
        Column(
            modifier = modifier
                .heightIn(min = 58.dp)
                .clip(visual.shape(ShapeKind.BAR))
                .background(panelColor)
                .padding(horizontal = 7.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    color = textColor,
                    style = visual.type.caption,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = valueText,
                    color = accent,
                    style = visual.type.caption,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
            AppSlider(
                value = value.coerceIn(range).toFloat(),
                onValueChange = { onValueChange(it.roundToInt().coerceIn(range)) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = range.count() - 1,
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun StyleLibrarySection(
        palette: ReaderSheetStyle.Palette,
        textColor: Color,
        secondaryText: Color,
        onThemeApplied: () -> Unit
    ) {
        var selectedIndex by rememberSaveable { mutableIntStateOf(ReadBookConfig.styleSelect) }
        var version by rememberSaveable { mutableIntStateOf(0) }
        var onlyThisBook by rememberSaveable { mutableStateOf(ReadBookConfig.onlyThisBook) }
        val context = LocalContext.current
        val configs = remember(version) { ReadBookConfig.configList.toList() }
        val accent = Color(palette.accentColor)

        SectionCard(panelColor = Color(palette.panel)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                configs.forEachIndexed { index, config ->
                    StylePreviewItem(
                        config = config,
                        selected = selectedIndex == index,
                        textColor = textColor,
                        secondaryText = secondaryText,
                        accent = accent,
                        onClick = {
                            changeBgTextConfig(index)
                            selectedIndex = ReadBookConfig.styleSelect
                            onThemeApplied()
                            version++
                        },
                        onLongClick = {
                            showBgTextConfig(index)
                        }
                    )
                }
                AddStyleItem(
                    panelColor = Color(palette.panel),
                    textColor = textColor,
                    secondaryText = secondaryText
                ) {
                    if (onlyThisBook) {
                        context.toastOnUi(R.string.read_style_only_this_book_add_forbidden)
                        return@AddStyleItem
                    }
                    ReadBookConfig.configList.add(ReadBookConfig.Config())
                    showBgTextConfig(ReadBookConfig.configList.lastIndex)
                }
            }
            AppRowWithSwitch(
                title = stringResource(R.string.read_style_only_this_book),
                checked = onlyThisBook,
                onCheckedChange = { enabled ->
                    ReadBookConfig.setOnlyThisBook(enabled)
                    onlyThisBook = enabled
                    selectedIndex = ReadBookConfig.styleSelect
                    onThemeApplied()
                    version++
                    ReadConfigEvent.post(Background, HeaderFooterTips, Relayout)
                },
                card = false,
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun StylePreviewItem(
        config: ReadBookConfig.Config,
        selected: Boolean,
        textColor: Color,
        secondaryText: Color,
        accent: Color,
        onClick: () -> Unit,
        onLongClick: () -> Unit
    ) {
        val context = LocalContext.current
        val visual = LocalVisualSpec.current
        Column(
            modifier = Modifier.width(62.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
            ) {
                AndroidView(
                    factory = { viewContext ->
                        CircleImageView(viewContext).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            val padding = 6.dpToPx()
                            setPadding(padding, padding, padding, padding)
                        }
                    },
                    update = { imageView ->
                        imageView.setText(config.name.ifBlank { context.getString(R.string.text) })
                        imageView.setTypeface(context.uiTypeface())
                        imageView.setTextColor(config.curTextColor())
                        imageView.setImageDrawable(config.curBgDrawable(100, 150))
                        imageView.borderColor = if (selected) context.accentColor else config.curTextColor()
                        imageView.setTextBold(selected)
                    },
                    onRelease = { it.releaseComposeImage() },
                    modifier = Modifier.size(48.dp)
                )
            }
            Text(
                text = config.name.ifBlank { stringResource(R.string.text) },
                color = if (selected) accent else secondaryText,
                style = visual.type.caption,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }

    @Composable
    private fun AddStyleItem(
        panelColor: Color,
        textColor: Color,
        secondaryText: Color,
        onClick: () -> Unit
    ) {
        val visual = LocalVisualSpec.current
        Column(
            modifier = Modifier
                .width(62.dp)
                .heightIn(min = 68.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(panelColor)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.add),
                    tint = textColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = stringResource(R.string.add),
                color = secondaryText,
                style = visual.type.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }

    private fun showTextIndentDialog() {
        indentDialogOpen = true
    }

    private fun changeBgTextConfig(index: Int) {
        if (ReadBookConfig.onlyThisBook) {
            // 仅本书预设：套用全局样式库中的预设为本书独立样式，不改动全局选中项。
            ReadBook.book?.setPageAnim(-1)
            ReadBookConfig.useStyleForBook(index)
            callBack?.upPageAnim()
            ReadConfigEvent.post(Background, HeaderFooterTips, Relayout)
            if (AppConfig.readBarStyleFollowPage) {
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }
            return
        }
        val oldIndex = ReadBookConfig.styleSelect
        if (index != oldIndex) {
            ReadBook.book?.setPageAnim(-1)
            ReadBookConfig.styleSelect = index
            // Page animation belongs to the selected reading theme. Rebind the
            // delegate now instead of waiting for the activity to be recreated.
            callBack?.upPageAnim()
            ReadConfigEvent.post(Background, HeaderFooterTips, Relayout)
            if (AppConfig.readBarStyleFollowPage) {
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }
        }
    }

    private fun showBgTextConfig(index: Int): Boolean {
        changeBgTextConfig(index)
        Handler(Looper.getMainLooper()).post {
            dismissAllowingStateLoss()
            callBack?.showBgTextConfig()
        }
        return true
    }

    @Composable
    private fun pageAnimOptions(): List<Pair<String, String>> {
        return listOf(
            PageAnim.coverPageAnim.toString() to stringResource(R.string.page_anim_cover),
            PageAnim.linkedCoverPageAnim.toString() to stringResource(R.string.page_anim_linked_cover),
            PageAnim.slidePageAnim.toString() to stringResource(R.string.page_anim_slide),
            PageAnim.simulationPageAnim.toString() to stringResource(R.string.page_anim_simulation),
            PageAnim.scrollPageAnim.toString() to stringResource(R.string.page_anim_scroll),
            PageAnim.noAnim.toString() to stringResource(R.string.page_anim_none)
        )
    }

    @Composable
    private fun pageAnimationSpeedOptions(): List<Pair<String, String>> {
        return listOf(
            PageAnimationSpeed.EXTREME.preferenceValue.toString() to
                stringResource(R.string.page_animation_speed_extreme),
            PageAnimationSpeed.STANDARD.preferenceValue.toString() to
                stringResource(R.string.page_animation_speed_standard),
            PageAnimationSpeed.RELAXED.preferenceValue.toString() to
                stringResource(R.string.page_animation_speed_relaxed),
            PageAnimationSpeed.ELEGANT.preferenceValue.toString() to
                stringResource(R.string.page_animation_speed_elegant)
        )
    }

    override val curFontPath: String
        get() = ReadBookConfig.textFont

    override fun selectFont(path: String) {
        if (ReadBook.usesPageTemplate()) return
        if (path != ReadBookConfig.textFont || path.isEmpty()) {
            ReadBookConfig.textFont = path
            ReadConfigEvent.post(Typography, Relayout)
        }
    }
}
