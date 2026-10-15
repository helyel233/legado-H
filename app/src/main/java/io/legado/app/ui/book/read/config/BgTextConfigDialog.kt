package io.legado.app.ui.book.read.config

import com.google.gson.JsonObject
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.help.readaloud.ReadAloudConfigChangeNotifier
import io.legado.app.reader.aloud.ReadAloudConfig
import io.legado.app.reader.config.ReadConfigEvent
import io.legado.app.reader.config.ReadConfigEvent.SystemBars
import io.legado.app.reader.config.ReadConfigEvent.Background
import io.legado.app.reader.config.ReadConfigEvent.HeaderFooterTips
import io.legado.app.reader.config.ReadConfigEvent.BackgroundAlpha
import io.legado.app.reader.config.ReadConfigEvent.PageTouchSlop
import io.legado.app.reader.config.ReadConfigEvent.Relayout
import io.legado.app.reader.config.ReadConfigEvent.TipStyle
import io.legado.app.reader.config.ReadConfigEvent.Typography
import io.legado.app.reader.config.ReadConfigEvent.InvalidateTextPage
import io.legado.app.reader.config.ReadConfigEvent.SubmitRender
import io.legado.app.reader.config.ReadConfigEvent.PageClick
import io.legado.app.reader.config.ReadConfigEvent.EpubEngineChanged
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.dns.DnsScope
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.ui.widget.compose.releaseComposeImage
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.appcompat.widget.AppCompatImageView
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.help.DefaultData
import io.legado.app.help.book.isImage
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.imageHttpClient
import io.legado.app.help.http.importHttpClient as okHttpClient
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplateStore
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppSlider
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFileReplace
import io.legado.app.utils.createFolderReplace
import io.legado.app.utils.delete
import io.legado.app.utils.externalCache
import io.legado.app.utils.externalFiles
import io.legado.app.utils.find
import io.legado.app.utils.getFile
import io.legado.app.utils.inputStream
import io.legado.app.utils.longToast
import io.legado.app.utils.observeEvent
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.outputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.readBytes
import io.legado.app.utils.readUri
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import androidx.lifecycle.lifecycleScope
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import splitties.init.appCtx
import kotlin.coroutines.CoroutineContext

internal class BgTextConfigDialog : V5DialogFragment() {

    /** 旧 BaseDialogFragment 的 BOTTOM 底部面板窗口行为 */
    override val dialogTheme: Int = R.style.Theme_Legado_ComposeDialog_Bottom
    override val dialogGravity: Int = Gravity.BOTTOM
    override val dialogWindowAnimations: Int = R.style.AnimDialogBottom

    companion object {
        const val TEXT_COLOR = 121
        const val BG_COLOR = 122
        const val TEXT_ACCENT_COLOR = 123
    }

    private val configFileName = "readConfig.zip"
    private val importFormNet = "网络导入"
    private var presetBgImages by mutableStateOf<List<String>>(emptyList())
    private var refreshTick by mutableIntStateOf(0)
    private var pendingSelfConfigEvents = 0

    private val selectBgImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> setBgFromUri(uri) }
    }
    private val selectExportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> exportConfig(uri) }
    }
    private val selectImportDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.path == "/$importFormNet") {
                importNetConfigAlert()
            } else {
                importConfig(uri)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            decorView.setPadding(0, 0, 0, 0)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeEvent<ArrayList<ReadConfigEvent>>(EventBus.READ_CONFIG_V2) {
            if (pendingSelfConfigEvents > 0) {
                pendingSelfConfigEvents--
            } else {
                refreshTick++
            }
        }
    }

    @Composable
    override fun Content() {
        BgTextConfigContent(palette = rememberReaderPagePalette())
        BgTextConfigDialogs()
    }

    /** 旧 BaseDialogFragment.execute() 的等价实现（基座退役后自带）。 */
    private fun <T> execute(
        scope: CoroutineScope = lifecycleScope,
        context: CoroutineContext = Dispatchers.IO,
        block: suspend CoroutineScope.() -> T
    ) = Coroutine.async(scope, context) { block() }

    // ── V5 弹窗状态 ─────────────────────────────────────────────
    internal var styleNameOpen by mutableStateOf(false)
    internal var styleNameText by mutableStateOf("")
    internal var presetOpen by mutableStateOf(false)
    internal var netUrlOpen by mutableStateOf(false)
    internal var netUrlText by mutableStateOf("")

    @Composable
    private fun BgTextConfigDialogs() {
        if (styleNameOpen) {
            AppInputDialog(
                title = getString(R.string.style_name),
                value = styleNameText,
                onValueChange = { styleNameText = it },
                placeholder = "name",
                confirmLabel = stringResource(R.string.ok),
                cancelLabel = stringResource(R.string.cancel),
                onConfirm = { text ->
                    styleNameOpen = false
                    ReadBookConfig.durConfig.name = text
                    refreshTick++
                },
                onDismiss = { styleNameOpen = false },
            )
        }
        if (presetOpen) {
            val layoutNames = DefaultData.readConfigs.map { it.name }
            AppMenuSheet(
                title = "选择预设布局",
                actions = layoutNames.mapIndexed { index, name ->
                    name to {
                        presetOpen = false
                        if (index >= 0) {
                            ReadBookConfig.durConfig = DefaultData.readConfigs[index].copy()
                            refreshTick++
                            postReadConfigChanged(Background, HeaderFooterTips, Relayout)
                        }
                    }
                },
                onDismiss = { presetOpen = false },
            )
        }
        if (netUrlOpen) {
            AppInputDialog(
                title = "输入地址",
                value = netUrlText,
                onValueChange = { netUrlText = it },
                placeholder = "https://...",
                confirmLabel = stringResource(R.string.ok),
                cancelLabel = stringResource(R.string.cancel),
                onConfirm = { url ->
                    netUrlOpen = false
                    if (url.isNotBlank()) {
                        importNetConfig(url.trim())
                    }
                },
                onDismiss = { netUrlOpen = false },
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (activity as ReadBookActivity).bottomDialog++
        presetBgImages = requireContext().assets.list("bg")?.toList().orEmpty()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
        (activity as ReadBookActivity).bottomDialog--
    }

    @Composable
    private fun SectionCard(
        palette: ReaderSheetStyle.Palette,
        title: String? = null,
        content: @Composable ColumnScope.() -> Unit
    ) {
        val visual = LocalVisualSpec.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(visual.shape(ShapeKind.BAR))
                .background(Color(palette.panel))
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            title?.let {
                Text(
                    text = it,
                    color = Color(palette.accentColor),
                    style = visual.type.label,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            content()
        }
    }

    @Composable
    private fun BgTextConfigContent(palette: ReaderSheetStyle.Palette) {
        refreshTick
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            color = Color(palette.surface),
            contentColor = Color(palette.textColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                HeaderSection(palette)
                ToggleSection(palette)
                if (ReadBook.book?.isImage != true) {
                    UnderlineSection(palette)
                }
                ColorSection(palette)
                ImportExportSection(palette)
                SliderSection(palette)
                BackgroundImageSection(palette)
            }
        }
    }

    @Composable
    private fun HeaderSection(palette: ReaderSheetStyle.Palette) {
        val visual = LocalVisualSpec.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(visual.shape(ShapeKind.BAR))
                .background(Color(palette.panel))
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.style_name),
                color = Color(palette.textColor),
                style = visual.type.body,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = ReadBookConfig.durConfig.name.ifBlank { "文字" },
                color = Color(palette.secondaryTextColor),
                style = visual.type.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                painter = painterResource(R.drawable.ic_edit),
                contentDescription = stringResource(R.string.edit),
                tint = Color(palette.secondaryTextColor),
                modifier = Modifier
                    .size(24.dp)
                    .clickable { editStyleName() }
                    .padding(2.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.restore),
                color = Color(palette.accentColor),
                style = visual.type.caption,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { restorePreset() }
            )
        }
    }

    @Composable
    private fun ToggleSection(palette: ReaderSheetStyle.Palette) {
        SectionCard(palette = palette) {
            var followTheme by rememberSaveable(refreshTick) {
                mutableStateOf(AppConfig.readerFollowTheme)
            }
            var darkStatusIcon by rememberSaveable(refreshTick) {
                mutableStateOf(ReadBookConfig.durConfig.curStatusIconDark())
            }
            var scrollFollowBg by rememberSaveable(refreshTick) {
                mutableStateOf(ReadBookConfig.durConfig.curReadScrollFollowBackground())
            }
            AppRowWithSwitch(
                title = stringResource(R.string.reader_follow_theme),
                checked = followTheme,
                card = false,
                onCheckedChange = {
                    followTheme = it
                    AppConfig.readerFollowTheme = it
                    postReadConfigChanged(Background, Relayout)
                    (activity as? ReadBookActivity)?.upSystemUiVisibility()
                }
            )
            AppRowWithSwitch(
                title = stringResource(R.string.dark_status_icon),
                checked = darkStatusIcon,
                card = false,
                onCheckedChange = {
                    darkStatusIcon = it
                    ReadBookConfig.durConfig.setCurStatusIconDark(it)
                    (activity as? ReadBookActivity)?.upSystemUiVisibility()
                }
            )
            AppRowWithSwitch(
                title = stringResource(R.string.read_scroll_follow_background),
                checked = scrollFollowBg,
                card = false,
                onCheckedChange = {
                    scrollFollowBg = it
                    ReadBookConfig.durConfig.setCurReadScrollFollowBackground(it)
                    postReadConfigChanged(Background, Relayout)
                }
            )
        }
    }

    @Composable
    private fun UnderlineSection(palette: ReaderSheetStyle.Palette) {
        val textColor = Color(palette.textColor)
        val accent = Color(palette.accentColor)
        SectionCard(palette = palette, title = stringResource(R.string.text_underline)) {
            var underlineMode by rememberSaveable(refreshTick) {
                mutableIntStateOf(ReadBookConfig.underlineMode)
            }
            var strokeWidthStep by rememberSaveable(refreshTick) {
                mutableIntStateOf((ReadBookConfig.underlineStrokeWidth * 2f).roundToInt())
            }
            var dashLength by rememberSaveable(refreshTick) {
                mutableIntStateOf(ReadBookConfig.underlineDashLength.roundToInt())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(0 to "关闭", 1 to "实线", 2 to "虚线").forEach { (value, label) ->
                    AppChip(
                        text = label,
                        selected = value == underlineMode,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (value == underlineMode) return@AppChip
                            underlineMode = value
                            ReadBookConfig.underlineMode = value
                            postReadConfigChanged(InvalidateTextPage, SubmitRender)
                        }
                    )
                }
            }
            if (underlineMode != 0) {
                SliderRow(
                    title = stringResource(R.string.underline_stroke_width),
                    value = strokeWidthStep,
                    range = 1..8,
                    textColor = textColor,
                    accent = accent,
                    valueText = "${strokeWidthStep / 2f} dp"
                ) {
                    strokeWidthStep = it
                    ReadBookConfig.underlineStrokeWidth = it / 2f
                    postReadConfigChanged(InvalidateTextPage, SubmitRender)
                }
            }
            if (underlineMode == 2) {
                SliderRow(
                    title = stringResource(R.string.underline_dash_length),
                    value = dashLength,
                    range = 2..30,
                    textColor = textColor,
                    accent = accent,
                    valueText = "$dashLength dp"
                ) {
                    dashLength = it
                    ReadBookConfig.underlineDashLength = it.toFloat()
                    postReadConfigChanged(InvalidateTextPage, SubmitRender)
                }
            }
        }
    }

    @Composable
    private fun ColorSection(palette: ReaderSheetStyle.Palette) {
        val panel = Color(palette.panel)
        val textColor = Color(palette.textColor)
        SectionCard(palette = palette) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                ColorAction(
                    text = stringResource(R.string.text_color),
                    color = Color(ReadBookConfig.durConfig.curTextColor()),
                    panel = panel,
                    textColor = textColor,
                    modifier = Modifier.weight(1f)
                ) { showTextColorPicker() }
                ColorAction(
                    text = stringResource(R.string.bg_color),
                    color = currentBackgroundSwatch(),
                    panel = panel,
                    textColor = textColor,
                    modifier = Modifier.weight(1f)
                ) { showBgColorPicker() }
                ColorAction(
                    text = stringResource(R.string.text_accent_color),
                    color = Color(ReadBookConfig.durConfig.curTextAccentColor()),
                    panel = panel,
                    textColor = textColor,
                    modifier = Modifier.weight(1f)
                ) { showTextAccentColorPicker() }
            }
        }
    }

    @Composable
    private fun ColorAction(
        text: String,
        color: Color,
        panel: Color,
        textColor: Color,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        val visual = LocalVisualSpec.current
        Row(
            modifier = modifier
                .heightIn(min = 42.dp)
                .clip(visual.shape(ShapeKind.BUTTON))
                .background(panel)
                .clickable(onClick = onClick)
                .padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(color)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = text,
                color = textColor,
                style = visual.type.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun ImportExportSection(palette: ReaderSheetStyle.Palette) {
        val panel = Color(palette.panel)
        val textColor = Color(palette.textColor)
        SectionCard(palette = palette) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SmallActionButton(
                    text = stringResource(R.string.import_str),
                    iconRes = R.drawable.ic_import,
                    panel = panel,
                    textColor = textColor,
                    modifier = Modifier.weight(1f),
                    onClick = ::launchImport
                )
                SmallActionButton(
                    text = stringResource(R.string.export_str),
                    iconRes = R.drawable.ic_export,
                    panel = panel,
                    textColor = textColor,
                    modifier = Modifier.weight(1f),
                    onClick = ::launchExport
                )
                SmallActionButton(
                    text = stringResource(R.string.delete),
                    iconRes = R.drawable.ic_clear_all,
                    panel = panel,
                    textColor = textColor,
                    modifier = Modifier.weight(1f),
                    danger = true,
                    onClick = ::deleteCurrentConfig
                )
            }
        }
    }

    @Composable
    private fun SmallActionButton(
        text: String,
        iconRes: Int,
        panel: Color,
        textColor: Color,
        modifier: Modifier = Modifier,
        danger: Boolean = false,
        onClick: () -> Unit
    ) {
        val visual = LocalVisualSpec.current
        val v5Colors = LocalSemanticColors.current
        val tint = if (danger) v5Colors.error else textColor
        Row(
            modifier = modifier
                .heightIn(min = 42.dp)
                .clip(visual.shape(ShapeKind.BUTTON))
                .background(if (danger) v5Colors.error.copy(alpha = 0.11f) else panel)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = text,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = tint,
                style = visual.type.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun SliderSection(palette: ReaderSheetStyle.Palette) {
        val textColor = Color(palette.textColor)
        val accent = Color(palette.accentColor)
        SectionCard(palette = palette) {
            var bgAlpha by rememberSaveable(refreshTick) { mutableIntStateOf(ReadBookConfig.bgAlpha) }
            var textShadow by rememberSaveable(refreshTick) { mutableIntStateOf(ReadBookConfig.paperInkStrength) }
            SliderRow(
                title = stringResource(R.string.bg_alpha),
                value = bgAlpha,
                range = 0..100,
                textColor = textColor,
                accent = accent
            ) {
                bgAlpha = it
                ReadBookConfig.bgAlpha = it
                postReadConfigChanged(BackgroundAlpha)
            }
            SliderRow(
                title = stringResource(R.string.text_shadow),
                value = textShadow,
                range = 0..100,
                textColor = textColor,
                accent = accent,
                valueText = if (textShadow == 0) stringResource(R.string.jf_convert_o) else "$textShadow%"
            ) {
                textShadow = it
                ReadBookConfig.paperInkStrength = it
                postReadConfigChanged(HeaderFooterTips, InvalidateTextPage, TipStyle)
            }
        }
    }

    @Composable
    private fun SliderRow(
        title: String,
        value: Int,
        range: IntRange,
        textColor: Color,
        accent: Color,
        valueText: String = "$value%",
        onValueChange: (Int) -> Unit
    ) {
        val visual = LocalVisualSpec.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = textColor,
                    style = visual.type.caption,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = valueText,
                    color = accent,
                    style = visual.type.caption,
                    fontWeight = FontWeight.SemiBold
                )
            }
            AppSlider(
                value = value.toFloat(),
                onValueChange = {
                    onValueChange(it.roundToInt().coerceIn(range.first, range.last))
                },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = (range.last - range.first - 1).coerceAtLeast(0)
            )
        }
    }

    @Composable
    private fun BackgroundImageSection(palette: ReaderSheetStyle.Palette) {
        SectionCard(palette = palette, title = stringResource(R.string.bg_image)) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "select_image") {
                    SelectImageTile(palette)
                }
                items(
                    items = presetBgImages,
                    key = { it }
                ) { imageName ->
                    PresetBackgroundTile(imageName = imageName, palette = palette)
                }
            }
        }
    }

    @Composable
    private fun SelectImageTile(palette: ReaderSheetStyle.Palette) {
        val visual = LocalVisualSpec.current
        Column(
            modifier = Modifier
                .width(82.dp)
                .height(82.dp)
                .clip(visual.shape(ShapeKind.BUTTON))
                .background(Color(palette.panel))
                .clickable {
                    selectBgImage.launch {
                        mode = HandleFileContract.IMAGE
                    }
                }
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_image),
                contentDescription = stringResource(R.string.select_image),
                tint = Color(palette.textColor),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.select_image),
                color = Color(palette.secondaryTextColor),
                style = visual.type.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun PresetBackgroundTile(imageName: String, palette: ReaderSheetStyle.Palette) {
        val visual = LocalVisualSpec.current
        Column(
            modifier = Modifier
                .width(82.dp)
                .height(82.dp)
                .clip(visual.shape(ShapeKind.BUTTON))
                .background(Color(palette.panel))
                .clickable {
                    ReadBookConfig.durConfig.setCurBg(1, imageName)
                    postReadConfigChanged(Background)
                    refreshTick++
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(
                        RoundedCornerShape(
                            topStart = 12.dp,
                            topEnd = 12.dp
                        )
                    )
                    .background(Color(palette.surface))
            ) {
                PresetBackgroundPreview(
                    imageName = imageName,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Text(
                text = imageName.substringBeforeLast("."),
                color = Color(palette.secondaryTextColor),
                style = visual.type.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }

    @Composable
    private fun PresetBackgroundPreview(imageName: String, modifier: Modifier = Modifier) {
        AndroidView(
            modifier = modifier,
            factory = { viewContext ->
                AppCompatImageView(viewContext).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
            },
            update = { imageView ->
                ImageLoader.load(imageView.context, "file:///android_asset/bg/$imageName")
                    .centerCrop()
                    .into(imageView)
            },
            onRelease = { it.releaseComposeImage() }
        )
    }

    private fun editStyleName() {
        styleNameText = ReadBookConfig.durConfig.name
        styleNameOpen = true
    }

    private fun restorePreset() {
        presetOpen = true
    }

    private fun showTextColorPicker() {
        ColorPickerDialog.newBuilder()
            .setColor(ReadBookConfig.durConfig.curTextColor())
            .setShowAlphaSlider(false)
            .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
            .setDialogId(TEXT_COLOR)
            .show(requireActivity())
    }

    private fun showTextAccentColorPicker() {
        ColorPickerDialog.newBuilder()
            .setColor(ReadBookConfig.durConfig.curTextAccentColor())
            .setShowAlphaSlider(false)
            .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
            .setDialogId(TEXT_ACCENT_COLOR)
            .show(requireActivity())
    }

    private fun showBgColorPicker() {
        val bgColor =
            if (ReadBookConfig.durConfig.curBgType() == 0) ReadBookConfig.durConfig.curBgStr().toColorInt()
            else "#015A86".toColorInt()
        ColorPickerDialog.newBuilder()
            .setColor(bgColor)
            .setShowAlphaSlider(false)
            .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
            .setDialogId(BG_COLOR)
            .show(requireActivity())
    }

    private fun currentBackgroundSwatch(): Color {
        return if (ReadBookConfig.durConfig.curBgType() == 0) {
            runCatching { Color(ReadBookConfig.durConfig.curBgStr().toColorInt()) }
                .getOrDefault(Color(0xFF015A86))
        } else {
            Color(0xFF015A86)
        }
    }

    private fun postReadConfigChanged(vararg events: ReadConfigEvent) {
        pendingSelfConfigEvents++
        ReadConfigEvent.post(*events)
    }

    private fun launchImport() {
        selectImportDoc.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.import_str)
            allowExtensions = arrayOf("zip")
            otherActions = arrayListOf(SelectItem(importFormNet, -1))
        }
    }

    private fun launchExport() {
        selectExportDir.launch {
            title = getString(R.string.export_str)
        }
    }

    private fun deleteCurrentConfig() {
        if (ReadBookConfig.deleteDur()) {
            postReadConfigChanged(Background, HeaderFooterTips, Relayout)
            dismissAllowingStateLoss()
        } else {
            toastOnUi("数量已是最少, 不能删除.")
        }
    }

    private fun exportConfig(uri: Uri) {
        val exportFileName = if (ReadBookConfig.config.name.isBlank()) {
            configFileName
        } else {
            "${ReadBookConfig.config.name}.zip"
        }
        execute {
            val exportFiles = arrayListOf<File>()
            val configDir = requireContext().externalCache.getFile("readConfig")
            configDir.createFolderReplace()
            val configFile = configDir.getFile("readConfig.json")
            configFile.createFileReplace()
            val config = ReadBookConfig.getExportConfig()
            // P3-b：朗读配置随阅读配置包导出
            val aloudFile = configDir.getFile("readAloudConfig.json")
            aloudFile.createFileReplace()
            aloudFile.writeText(ReadAloudConfig.toExportJson().toString(), Charsets.UTF_8)
            exportFiles.add(aloudFile)
            if (config.readerTemplateId.isNotEmpty()) {
                val templateFile = configDir.getFile(EpubReaderTemplateStore.singleTemplateFileName)
                templateFile.writeText(EpubReaderTemplateStore.exportJson(config.readerTemplateId), Charsets.UTF_8)
                exportFiles.add(templateFile)
            }
            val fontPath = ReadBookConfig.textFont
            if (fontPath.isNotEmpty()) {
                val fontDoc = FileDoc.fromFile(fontPath)
                val fontName = fontDoc.name
                val fontInputStream = fontDoc.openInputStream().getOrNull()
                fontInputStream?.use {
                    val fontExportFile = FileUtils.createFileIfNotExist(configDir, fontName)
                    fontExportFile.outputStream().use { out ->
                        it.copyTo(out)
                    }
                    config.textFont = fontName
                    exportFiles.add(fontExportFile)
                }
            }
            configFile.writeText(GSON.toJson(config))
            exportFiles.add(configFile)
            repeat(3) {
                val path = ReadBookConfig.durConfig.getBgPath(it) ?: return@repeat
                val bgExportFile = copyBgImage(path, configDir) ?: return@repeat
                exportFiles.add(bgExportFile)
            }
            val configZipPath = FileUtils.getPath(requireContext().externalCache, configFileName)
            if (ZipUtils.zipFiles(exportFiles, File(configZipPath))) {
                val exportDir = FileDoc.fromDir(uri)
                exportDir.find(exportFileName)?.delete()
                val exportFileDoc = exportDir.createFileIfNotExist(exportFileName)
                exportFileDoc.openOutputStream().getOrThrow().use { out ->
                    File(configZipPath).inputStream().use {
                        it.copyTo(out)
                    }
                }
            }
        }.onSuccess {
            toastOnUi("导出成功, 文件名为 $exportFileName")
        }.onError {
            it.printOnDebug()
            AppLog.put("导出失败:${it.localizedMessage}", it)
            longToast("导出失败:${it.localizedMessage}")
        }
    }

    private fun copyBgImage(path: String, configDir: File): File? {
        val bgName = FileUtils.getName(path)
        val bgFile = File(path)
        if (bgFile.exists()) {
            val bgExportFile = File(FileUtils.getPath(configDir, bgName))
            if (!bgExportFile.exists()) {
                bgFile.copyTo(bgExportFile)
                return bgExportFile
            }
        }
        return null
    }

    private fun importNetConfigAlert() {
        netUrlText = ""
        netUrlOpen = true
    }

    private fun importNetConfig(url: String) {
        execute {
            okHttpClient.newCallResponseBody {
                url(url)
            }.bytes().let {
                importConfig(it)
            }
        }.onError {
            longToast(it.stackTraceStr)
        }
    }

    private fun importConfig(uri: Uri) {
        execute {
            importConfig(uri.readBytes(requireContext()))
        }.onError {
            it.printOnDebug()
            longToast("导入失败:${it.localizedMessage}")
        }
    }

    private fun importConfig(byteArray: ByteArray) {
        execute {
            ReadBookConfig.import(byteArray).also {
                // P3-b：恢复随包携带的朗读配置（旧包无此文件则跳过）
                runCatching {
                java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(byteArray)).use { stream ->
                    var entry = stream.nextEntry
                    while (entry != null) {
                        if (entry.name == "readAloudConfig.json") {
                            val json = stream.readBytes().toString(Charsets.UTF_8)
                            ReadAloudConfig.applyExport(
                                GSON.fromJsonObject<JsonObject>(json).getOrThrow()
                            )
                            break
                        }
                        entry = stream.nextEntry
                    }
                }
                ReadAloudConfigChangeNotifier.notifyEngine()
            }
            }
        }.onSuccess {
            ReadBookConfig.durConfig = it
            refreshTick++
            postReadConfigChanged(Background, HeaderFooterTips, Relayout)
            toastOnUi("导入成功")
        }.onError {
            it.printOnDebug()
            longToast("导入失败:${it.localizedMessage}")
        }
    }

    private fun setBgFromUri(uri: Uri) {
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            lifecycleScope.launch {
                kotlin.runCatching {
                    appCtx.toastOnUi("下载图片中...")
                    val analyzeUrl = AnalyzeUrl(uri.toString(), dnsScope = DnsScope.IMAGE)
                    val url = analyzeUrl.urlNoQuery
                    var file = requireContext().externalFiles
                    val res = imageHttpClient.newCallResponse(0) {
                        addHeaders(analyzeUrl.headerMap)
                        url(url)
                    }
                    val contentType = res.header("Content-Type") ?: "image/jpeg"
                    val imageType = when {
                        contentType.contains("png", ignoreCase = true) -> "png"
                        contentType.contains("gif", ignoreCase = true) -> "gif"
                        contentType.contains("webp", ignoreCase = true) -> "webp"
                        else -> "jpg"
                    }
                    val suffix = if (url.contains(".9.png", true)) {
                        ".9.png"
                    } else {
                        ".$imageType"
                    }
                    val fileName = MD5Utils.md5Encode(url) + suffix
                    file = FileUtils.createFileIfNotExist(file, "bg", fileName)
                    res.body.byteStream().use { inputStream ->
                        FileOutputStream(file).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    ReadBookConfig.durConfig.setCurBg(2, fileName)
                    postReadConfigChanged(Background)
                    refreshTick++
                }.onSuccess {
                    appCtx.toastOnUi("设定成功")
                }.onFailure {
                    appCtx.toastOnUi(it.localizedMessage)
                }
            }
            return
        }
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                var file = requireContext().externalFiles
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = uri.inputStream(requireContext()).getOrThrow().use {
                    MD5Utils.md5Encode(it) + suffix
                }
                file = FileUtils.createFileIfNotExist(file, "bg", fileName)
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                ReadBookConfig.durConfig.setCurBg(2, fileName)
                postReadConfigChanged(Background)
                refreshTick++
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }
}
