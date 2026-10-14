package io.legado.app.ui.book.read.config

import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.SelectImagesContract
import io.legado.app.utils.toastOnUi
import org.json.JSONObject

/**
 * 插入媒体面板（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Form` 映射为 widthFraction 0.94f / maxWidthDp 660；
 * 内容高度上限 screenHeightDp*0.72 + verticalScroll 保留。
 * 组件替换：`ReaderSectionCard` → Box+shape+`colors.surfaceVariant`，`ReaderSegmentedOptions`（5 个布局）
 * → `LazyRow` + `AppChip(selected)`（等价单选且可横向滚动），自定义 `SwitchRow` → `AppRowWithSwitch(card = false)`，
 * 自定义 OutlinedTextField(outlinedColors) → AppTextField，自定义 subtleButton → AppGhostButton/AppFilledButton。
 * **业务逻辑逐行保留**：选中时统一读字节并解析类型（`IllustrationHelp.resolveMediaExt`）、
 * `computeUnits()` 按布局分块（音频永不参与宫格、单独成格并按原始顺序排序）、
 * 「统一备注 vs 逐条备注」双分支（多媒体组 note 置空、逐图备注进 srcNotes）、`save()` 落库与回调。
 */
internal class IllustrationEditDialog : V5DialogFragment() {

    companion object {
        internal fun newInstance(anchor: IllustrationAnchor): IllustrationEditDialog {
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

    internal fun setOnInserted(callback: () -> Unit) {
        insertedCallback = callback
    }

    /** 旧 AppDialogSize.Form(widthFraction = 0.94f, maxWidthDp = 660) */
    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int = 660

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

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density

        var selectedLayout by remember { mutableStateOf(BookIllustration.LAYOUT_SINGLE) }
        var heightText by remember { mutableStateOf("") }
        var pageBreak by remember { mutableStateOf(false) }
        var unifiedNote by remember { mutableStateOf(false) }
        var unifiedNoteText by remember { mutableStateOf("") }
        val unitNotes = remember { mutableStateMapOf<Int, String>() }
        val expandedNotes = remember { mutableStateMapOf<Int, Boolean>() }

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.72f).dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.illustration_title),
                        style = visual.type.title,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.illustration_pick_images),
                        style = visual.type.caption,
                        color = colors.primary,
                        modifier = Modifier
                            .clip(visual.shape(ShapeKind.BUTTON))
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
                            ThumbCell(uriState[index])
                        }
                    }
                }

                val layoutOptions = listOf(
                    BookIllustration.LAYOUT_SINGLE to stringResource(R.string.illustration_layout_single),
                    BookIllustration.LAYOUT_DOUBLE to stringResource(R.string.illustration_layout_double),
                    BookIllustration.LAYOUT_TRIPLE to stringResource(R.string.illustration_layout_triple),
                    BookIllustration.LAYOUT_QUAD to stringResource(R.string.illustration_layout_quad),
                    BookIllustration.LAYOUT_QUAD_GRID to stringResource(R.string.illustration_layout_quad_grid)
                )
                SectionCard(title = stringResource(R.string.illustration_layout)) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(layoutOptions.size) { index ->
                            val (value, label) = layoutOptions[index]
                            AppChip(
                                text = label,
                                selected = value == selectedLayout,
                                onClick = { selectedLayout = value },
                            )
                        }
                    }
                }

                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.illustration_height),
                            style = visual.type.body,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        AppTextField(
                            value = heightText,
                            onValueChange = { heightText = it.filter(Char::isDigit).take(5) },
                            placeholder = stringResource(R.string.illustration_height_hint),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    AppRowWithSwitch(
                        title = stringResource(R.string.illustration_page_break),
                        checked = pageBreak,
                        onCheckedChange = { pageBreak = it },
                        card = false,
                    )
                    AppRowWithSwitch(
                        title = stringResource(R.string.illustration_unified_note),
                        checked = unifiedNote,
                        onCheckedChange = { unifiedNote = it },
                        card = false,
                    )
                    if (unifiedNote) {
                        AppTextField(
                            value = unifiedNoteText,
                            onValueChange = { unifiedNoteText = it },
                            placeholder = stringResource(R.string.illustration_note_hint),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        mediaState.forEachIndexed { mediaIndex, media ->
                            NoteRow(
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
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { dismissAllowingStateLoss() },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.ok),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            save(
                                selectedLayout = selectedLayout,
                                heightText = heightText,
                                pageBreak = pageBreak,
                                unifiedNote = unifiedNote,
                                unifiedNoteText = unifiedNoteText,
                                unitNotes = unitNotes.toMap()
                            )
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun SectionCard(
        title: String? = null,
        content: @Composable () -> Unit
    ) {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(visual.shape(ShapeKind.BAR))
                .background(colors.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = visual.type.caption,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            content()
        }
    }

    @Composable
    private fun ThumbCell(uri: Uri) {
        val visual = LocalVisualSpec.current
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
                .then(Modifier.height(72.dp).width(72.dp))
                .clip(visual.shape(ShapeKind.TAG))
        )
    }

    @Composable
    private fun NoteRow(
        label: String,
        text: String,
        expanded: Boolean,
        onTextChange: (String) -> Unit,
        onToggle: () -> Unit
    ) {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(visual.shape(ShapeKind.BAR))
                .background(colors.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = label,
                style = visual.type.body,
                color = colors.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
            )
            if (expanded) {
                AppTextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = stringResource(R.string.illustration_note_hint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            } else {
                Text(
                    text = previewTextOf(text),
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
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
