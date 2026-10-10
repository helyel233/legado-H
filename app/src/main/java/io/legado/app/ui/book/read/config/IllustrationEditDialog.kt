package io.legado.app.ui.book.read.config

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookIllustration
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.illustration.IllustrationAnchor
import io.legado.app.help.illustration.IllustrationHelp
import io.legado.app.help.illustration.imageSrcsToJson
import io.legado.app.model.ReadBook
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.SelectImagesContract
import io.legado.app.utils.toastOnUi
import org.json.JSONObject

/**
 * 插入媒体面板（P3-d 面板化：原 dialog_illustration_edit.xml 迁移为 Compose）。
 * 选择图片、视频或音频，设置显示高度、布局、独占一页、备注。
 * 备注默认统一填写（本次插入的所有记录共用）；取消"统一备注"后按媒体逐条填写，
 * 排版只管显示分组，不管备注条数，两者解构。
 */
class IllustrationEditDialog : ComposeDialogFragment() {

    companion object {
        fun newInstance(anchor: IllustrationAnchor): IllustrationEditDialog {
            return IllustrationEditDialog().apply {
                arguments = Bundle().apply {
                    putString("anchorType", anchor.anchorType)
                    putInt("anchorPos", anchor.anchorPos)
                    putString("frontParagraph", anchor.frontParagraph)
                    putString("backParagraph", anchor.backParagraph)
                }
            }
        }
    }

    private val anchor by lazy {
        IllustrationAnchor(
            anchorType = arguments?.getString("anchorType").orEmpty(),
            anchorPos = arguments?.getInt("anchorPos") ?: -1,
            frontParagraph = arguments?.getString("frontParagraph").orEmpty(),
            backParagraph = arguments?.getString("backParagraph").orEmpty()
        )
    }

    private var insertedCallback: (() -> Unit)? = null

    fun setOnInserted(callback: () -> Unit) {
        insertedCallback = callback
    }

    override val dialogSize: AppDialogSize? = AppDialogSize.Form

    // 选择时统一读取字节并解析类型，保存与逐条备注分组共用这份结果
    private val mediaState = mutableStateListOf<Pair<ByteArray, String>>() // bytes to ext
    private val uriState = mutableStateListOf<Uri>()

    private val selectImages = registerForActivityResult(SelectImagesContract()) { result ->
        if (result.uris.isNotEmpty()) {
            val parsed = arrayListOf<Pair<ByteArray, String>>()
            result.uris.forEach { uri ->
                val bytes = kotlin.runCatching {
                    requireContext().contentResolver.openInputStream(uri)?.use { s ->
                        s.readBytes()
                    }
                }.getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    toastOnUi("读取媒体文件失败")
                    return@forEach
                }
                val name = IllustrationHelp.queryDisplayName(requireContext(), uri)
                val mime = requireContext().contentResolver.getType(uri)
                val ext = IllustrationHelp.resolveMediaExt(name, mime, bytes)
                if (ext !in IllustrationHelp.VIDEO_EXTS &&
                    ext !in IllustrationHelp.AUDIO_EXTS &&
                    ext !in IllustrationHelp.IMAGE_EXTS
                ) {
                    toastOnUi("仅支持图片、视频、音频文件")
                    return@forEach
                }
                parsed.add(bytes to ext)
            }
            if (parsed.size != result.uris.size) {
                return@registerForActivityResult
            }
            uriState.clear()
            uriState.addAll(result.uris)
            mediaState.clear()
            mediaState.addAll(parsed)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    IllustrationEditContent(style)
                }
            }
        }
    }

    @Composable
    private fun IllustrationEditContent(style: AppDialogStyle) {
        var selectedLayout by remember { mutableStateOf(BookIllustration.LAYOUT_SINGLE) }
        var heightText by remember { mutableStateOf("") }
        var pageBreak by remember { mutableStateOf(false) }
        var unifiedNote by remember { mutableStateOf(false) }
        var unifiedNoteText by remember { mutableStateOf("") }
        val unitNotes = remember { mutableStateMapOf<Int, String>() }
        val expandedNotes = remember { mutableStateMapOf<Int, Boolean>() }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.72f).dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.illustration_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = style.primaryText,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.illustration_pick_images),
                    color = style.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(style.actionRadius))
                        .clickable { selectImages.launch(0) }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
            if (uriState.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.height(72.dp)
                ) {
                    items(uriState.size) { index ->
                        ThumbCell(style, uriState[index])
                    }
                }
            }

            ReaderSectionCard(style = style, title = stringResource(R.string.illustration_layout)) {
                ReaderSegmentedOptions(
                    options = listOf(
                        ReaderOption(
                            BookIllustration.LAYOUT_SINGLE,
                            stringResource(R.string.illustration_layout_single)
                        ),
                        ReaderOption(
                            BookIllustration.LAYOUT_DOUBLE,
                            stringResource(R.string.illustration_layout_double)
                        ),
                        ReaderOption(
                            BookIllustration.LAYOUT_TRIPLE,
                            stringResource(R.string.illustration_layout_triple)
                        ),
                        ReaderOption(
                            BookIllustration.LAYOUT_QUAD,
                            stringResource(R.string.illustration_layout_quad)
                        ),
                        ReaderOption(
                            BookIllustration.LAYOUT_QUAD_GRID,
                            stringResource(R.string.illustration_layout_quad_grid)
                        )
                    ),
                    selectedValue = selectedLayout,
                    style = style,
                    scrollable = true,
                    onSelected = { selectedLayout = it }
                )
            }

            ReaderSectionCard(style = style) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.illustration_height),
                        color = style.secondaryText,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { heightText = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.illustration_height_hint)) },
                        colors = outlinedColors(style)
                    )
                }
                SwitchRow(
                    title = stringResource(R.string.illustration_page_break),
                    checked = pageBreak,
                    style = style,
                    onCheckedChange = { pageBreak = it }
                )
                SwitchRow(
                    title = stringResource(R.string.illustration_unified_note),
                    checked = unifiedNote,
                    style = style,
                    onCheckedChange = { unifiedNote = it }
                )
                if (unifiedNote) {
                    OutlinedTextField(
                        value = unifiedNoteText,
                        onValueChange = { unifiedNoteText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.illustration_note_hint)) },
                        colors = outlinedColors(style)
                    )
                } else {
                    mediaState.forEachIndexed { mediaIndex, media ->
                        NoteRow(
                            style = style,
                            label = if (media.second in IllustrationHelp.AUDIO_EXTS) {
                                stringResource(R.string.illustration_note_audio_unit, mediaIndex + 1)
                            } else {
                                stringResource(R.string.illustration_note_image_unit, mediaIndex + 1, 1)
                            },
                            text = unitNotes[mediaIndex].orEmpty(),
                            expanded = expandedNotes[mediaIndex] == true,
                            onTextChange = { unitNotes[mediaIndex] = it },
                            onToggle = {
                                expandedNotes[mediaIndex] = !(expandedNotes[mediaIndex] ?: false)
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                subtleButton(stringResource(R.string.cancel), style, Modifier.weight(1f)) {
                    dismissAllowingStateLoss()
                }
                subtleButton(stringResource(R.string.ok), style, Modifier.weight(1f)) {
                    save(
                        selectedLayout = selectedLayout,
                        heightText = heightText,
                        pageBreak = pageBreak,
                        unifiedNote = unifiedNote,
                        unifiedNoteText = unifiedNoteText,
                        unitNotes = unitNotes.toMap()
                    )
                }
            }
        }
    }

    @Composable
    private fun ThumbCell(style: AppDialogStyle, uri: Uri) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                }
            },
            update = { view ->
                ImageLoader.load(view.context, uri).into(view)
            },
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(style.actionRadius))
        )
    }

    @Composable
    private fun SwitchRow(
        title: String,
        checked: Boolean,
        style: AppDialogStyle,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = style.primaryText,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            LegadoMiuixSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                palette = style.toMiuixPalette()
            )
        }
    }

    @Composable
    private fun NoteRow(
        style: AppDialogStyle,
        label: String,
        text: String,
        expanded: Boolean,
        onTextChange: (String) -> Unit,
        onToggle: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(style.actionRadius))
                .background(style.fieldSurface)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = label,
                color = style.primaryText,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
            )
            if (expanded) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    placeholder = { Text(stringResource(R.string.illustration_note_hint)) },
                    colors = outlinedColors(style)
                )
            } else {
                Text(
                    text = previewTextOf(text),
                    color = style.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggle)
                        .padding(top = 2.dp)
                )
            }
        }
    }

    /** 备注收起行：取首个非空行，空的就是无备注 */
    private fun previewTextOf(text: String): String {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return first.ifBlank { "" }
    }

    @Composable
    private fun outlinedColors(style: AppDialogStyle) = OutlinedTextFieldDefaults.colors(
        focusedTextColor = style.primaryText,
        unfocusedTextColor = style.primaryText,
        cursorColor = style.accent,
        focusedBorderColor = style.accent,
        unfocusedBorderColor = style.stroke,
        focusedContainerColor = style.fieldSurface,
        unfocusedContainerColor = style.fieldSurface
    )

    @Composable
    private fun subtleButton(
        text: String,
        style: AppDialogStyle,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Text(
            text = text,
            color = style.primaryText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = modifier
                .clip(RoundedCornerShape(style.actionRadius))
                .background(style.fieldSurface, RoundedCornerShape(style.actionRadius))
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp)
        )
    }

    private fun layoutCellCount(layout: String): Int {
        return when (layout) {
            BookIllustration.LAYOUT_DOUBLE -> 2
            BookIllustration.LAYOUT_TRIPLE -> 3
            BookIllustration.LAYOUT_QUAD -> 4
            BookIllustration.LAYOUT_QUAD_GRID -> 4
            else -> 1
        }
    }

    /** 成组单元：firstIndex 为单元内第一个媒体的下标，indexes 为单元包含的媒体下标 */
    private data class MediaUnit(
        val firstIndex: Int,
        val indexes: List<Int>,
        val isAudio: Boolean
    )

    /**
     * 成组单元与保存时的记录一一对应：图片/视频按所选布局分块，音频永不参与宫格单独成格；
     * 各块按原始选择顺序排序（音频夹在宫格区间内时排在宫格块之后）。
     */
    private fun computeUnits(layout: String): List<MediaUnit> {
        val cellCount = layoutCellCount(layout)
        val units = arrayListOf<MediaUnit>()
        mediaState.mapIndexedNotNull { index, m ->
            if (m.second in IllustrationHelp.AUDIO_EXTS) null else index
        }.chunked(cellCount).forEach { chunk ->
            units.add(MediaUnit(chunk.first(), chunk, false))
        }
        mediaState.forEachIndexed { index, m ->
            if (m.second in IllustrationHelp.AUDIO_EXTS) {
                units.add(MediaUnit(index, listOf(index), true))
            }
        }
        units.sortBy { it.firstIndex }
        return units
    }

    private fun save(
        selectedLayout: String,
        heightText: String,
        pageBreak: Boolean,
        unifiedNote: Boolean,
        unifiedNoteText: String,
        unitNotes: Map<Int, String>
    ) {
        if (mediaState.isEmpty()) {
            toastOnUi(R.string.illustration_no_images)
            return
        }
        val book = ReadBook.book ?: return
        val chapter = ReadBook.curTextChapter?.chapter ?: return
        val displayHeight = heightText.trim().toIntOrNull() ?: 0
        val units = computeUnits(selectedLayout)
        // 保存媒体文件，选择顺序与单元下标一致
        val srcs = mediaState.map { (bytes, ext) ->
            val src = IllustrationHelp.newSrc(ext)
            IllustrationHelp.saveImage(book, src, bytes)
            src
        }
        val records = arrayListOf<BookIllustration>()
        units.forEach { unit ->
            val unitSrcs = unit.indexes.map { srcs[it] }
            // 记录按排版分组存（宫格渲染靠它），备注按媒体各写各的：
            // 统一备注/单媒体组走 note 字段；多媒体组 note 置空，每图备注进 srcNotes
            val (note, srcNotes) = if (unifiedNote) {
                unifiedNoteText to "{}"
            } else if (unit.indexes.size == 1) {
                unitNotes[unit.firstIndex].orEmpty() to "{}"
            } else {
                val map = unit.indexes.mapNotNull { mediaIndex ->
                    val text = unitNotes[mediaIndex].orEmpty()
                    if (text.isBlank()) null else srcs[mediaIndex] to text
                }.toMap()
                "" to JSONObject(map as Map<*, *>).toString()
            }
            records.add(
                newRecord(
                    book,
                    chapter,
                    unitSrcs,
                    displayHeight,
                    pageBreak,
                    records.size,
                    note = note,
                    srcNotes = srcNotes,
                    single = unit.isAudio,
                    layout = if (unit.isAudio) BookIllustration.LAYOUT_SINGLE else selectedLayout
                )
            )
        }
        if (records.isEmpty()) return
        appDb.bookIllustrationDao.insert(*records.toTypedArray())
        dismissAllowingStateLoss()
        toastOnUi(R.string.illustration_inserted)
        insertedCallback?.invoke()
    }

    private fun newRecord(
        book: Book,
        chapter: BookChapter,
        srcs: List<String>,
        displayHeight: Int,
        pageBreak: Boolean,
        sortOrder: Int,
        note: String,
        srcNotes: String = "{}",
        single: Boolean = false,
        layout: String = BookIllustration.LAYOUT_SINGLE
    ): BookIllustration {
        return BookIllustration(
            bookUrl = book.bookUrl,
            chapterIndex = chapter.index,
            chapterUrl = chapter.url,
            chapterName = chapter.title,
            anchorType = anchor.anchorType,
            anchorPos = anchor.anchorPos,
            frontParagraphText = anchor.frontParagraph,
            backParagraphText = anchor.backParagraph,
            frontFingerprint = IllustrationHelp.fingerprint(anchor.frontParagraph, false),
            backFingerprint = IllustrationHelp.fingerprint(anchor.backParagraph, true),
            imageSrcs = imageSrcsToJson(srcs),
            layoutType = layout,
            displayHeight = displayHeight,
            pageBreak = pageBreak,
            sortOrder = sortOrder,
            note = note,
            srcNotes = srcNotes
        )
    }
}
