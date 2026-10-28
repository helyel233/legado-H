package io.legado.app.ui.book.read.config

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookIllustration
import io.legado.app.databinding.DialogIllustrationEditBinding
import io.legado.app.databinding.ItemImageSimpleBinding
import io.legado.app.help.illustration.IllustrationAnchor
import io.legado.app.help.illustration.IllustrationHelp
import io.legado.app.help.illustration.imageSrcsToJson
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.utils.SelectImagesContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding
import org.json.JSONObject

/**
 * 插入媒体对话框：选择图片、视频或音频，设置显示高度、布局、独占一页、备注。
 * 备注默认统一填写（本次插入的所有记录共用）；取消"统一备注"后按媒体逐条填写，
 * 排版只管显示分组，不管备注条数，两者解构。
 */
class IllustrationEditDialog() : BaseDialogFragment(R.layout.dialog_illustration_edit, true) {

    constructor(anchor: IllustrationAnchor) : this() {
        arguments = Bundle().apply {
            putString("anchorType", anchor.anchorType)
            putInt("anchorPos", anchor.anchorPos)
            putString("frontParagraph", anchor.frontParagraph)
            putString("backParagraph", anchor.backParagraph)
        }
    }

    /** 成组单元：firstIndex 为单元内第一个媒体的下标，indexes 为单元包含的媒体下标 */
    private data class MediaUnit(
        val firstIndex: Int,
        val indexes: List<Int>,
        val isAudio: Boolean
    )

    private val binding by viewBinding(DialogIllustrationEditBinding::bind)
    private val anchor by lazy {
        IllustrationAnchor(
            anchorType = arguments?.getString("anchorType").orEmpty(),
            anchorPos = arguments?.getInt("anchorPos") ?: -1,
            frontParagraph = arguments?.getString("frontParagraph").orEmpty(),
            backParagraph = arguments?.getString("backParagraph").orEmpty()
        )
    }

    private val selectedUris = arrayListOf<Uri>()

    // 选择时统一读取字节并解析类型，保存与逐条备注分组共用这份结果
    private val parsedMedia = arrayListOf<Pair<ByteArray, String>>() // bytes to ext

    private val unitNoteEdits = arrayListOf<EditText>()
    private val unitKeys = arrayListOf<Int>()
    private val unitNotes = LinkedHashMap<Int, String>() // 媒体下标 -> 备注（排版只管显示分组，不管备注）

    /** 选择代际号：快速连续选择时丢弃过期结果 */
    private var selectRequestId = 0L

    private val selectImages = registerForActivityResult(SelectImagesContract()) { result ->
        if (result.uris.isNotEmpty()) {
            // 回调在主线程，先取可空 context：IO 里读大文件期间对话框可能已关闭，
            // IO 线程里 requireContext()/Fragment.toastOnUi(requireActivity()) 都会抛未捕获异常
            val context = context ?: return@registerForActivityResult
            // 代际号：快速连续两次选择时，完成顺序不定，过期结果不能覆盖最新选择
            val requestId = ++selectRequestId
            // 选择器回调在主线程：readBytes 会把选中的视频/音频整文件读进内存（可达数十~数百 MB），
            // 必须放 IO；读完回主线程再更新 UI 状态
            lifecycleScope.launch(Dispatchers.IO) {
                val parsed = arrayListOf<Pair<ByteArray, String>>()
                result.uris.forEach { uri ->
                    // 选择器放开 */* 后可能选到非媒体文件，先统一读取字节并解析类型
                    val bytes = kotlin.runCatching {
                        context.contentResolver.openInputStream(uri)?.use { s ->
                            s.readBytes()
                        }
                    }.getOrNull()
                    if (bytes == null || bytes.isEmpty()) {
                        appCtx.toastOnUi("读取媒体文件失败")
                        return@forEach
                    }
                    // 文件选择器返回的 MIME 可能不可靠（null/octet-stream/报成 image 类），
                    // 按文件名扩展名 → MIME → 文件头嗅探三级判断，确保视频/音频不会落成 jpg
                    val name = IllustrationHelp.queryDisplayName(context, uri)
                    val mime = context.contentResolver.getType(uri)
                    val ext = IllustrationHelp.resolveMediaExt(name, mime, bytes)
                    if (ext !in IllustrationHelp.VIDEO_EXTS &&
                        ext !in IllustrationHelp.AUDIO_EXTS &&
                        ext !in IllustrationHelp.IMAGE_EXTS
                    ) {
                        appCtx.toastOnUi("仅支持图片、视频、音频文件")
                        return@forEach
                    }
                    parsed.add(bytes to ext)
                }
                launch(Dispatchers.Main) {
                    if (requestId != selectRequestId) {
                        // 已经有更新的选择，丢弃本次过期结果
                        return@launch
                    }
                    if (parsed.size != result.uris.size) {
                        // 有媒体读取或解析失败：本次选择整体不生效，直接暴露问题
                        return@launch
                    }
                    selectedUris.clear()
                    selectedUris.addAll(result.uris)
                    parsedMedia.clear()
                    parsedMedia.addAll(parsed)
                    upSelected()
                    rebuildNoteInputs()
                }
            }
        }
    }

    private var thumbAdapter: ThumbAdapter? = null

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.rvSelected.layoutManager = LinearLayoutManager(
            requireContext(),
            LinearLayoutManager.HORIZONTAL,
            false
        )
        thumbAdapter = ThumbAdapter()
        binding.rvSelected.adapter = thumbAdapter
        binding.tvPickImages.setOnClickListener {
            selectImages.launch(0)
        }
        binding.tvCancel.setOnClickListener {
            dismissAllowingStateLoss()
        }
        binding.tvOk.setOnClickListener {
            save()
        }
        binding.rgLayout.check(binding.rbSingle.id)
        //排版只管显示分组，不管备注条数：切排版不重建备注输入
        binding.cbUnifiedNote.isChecked = false
        binding.cbUnifiedNote.setOnCheckedChangeListener { _, _ ->
            rebuildNoteInputs()
        }
        rebuildNoteInputs()
    }

    private fun upSelected() {
        thumbAdapter?.setItems(selectedUris)
        binding.rvSelected.visible(selectedUris.isNotEmpty())
    }

    private fun selectedLayout(): String {
        return when (binding.rgLayout.checkedRadioButtonId) {
            binding.rbDouble.id -> BookIllustration.LAYOUT_DOUBLE
            binding.rbTriple.id -> BookIllustration.LAYOUT_TRIPLE
            binding.rbQuad.id -> BookIllustration.LAYOUT_QUAD
            binding.rbQuadGrid.id -> BookIllustration.LAYOUT_QUAD_GRID
            else -> BookIllustration.LAYOUT_SINGLE
        }
    }

    private fun layoutCellCount(): Int {
        return when (selectedLayout()) {
            BookIllustration.LAYOUT_DOUBLE -> 2
            BookIllustration.LAYOUT_TRIPLE -> 3
            BookIllustration.LAYOUT_QUAD -> 4
            BookIllustration.LAYOUT_QUAD_GRID -> 4
            else -> 1
        }
    }

    /**
     * 成组单元与保存时的记录一一对应：图片/视频按所选布局分块，音频永不参与宫格单独成格；
     * 各块按原始选择顺序排序（音频夹在宫格区间内时排在宫格块之后）。
     */
    private fun computeUnits(): List<MediaUnit> {
        val cellCount = layoutCellCount()
        val units = arrayListOf<MediaUnit>()
        parsedMedia.mapIndexedNotNull { index, m ->
            if (m.second in IllustrationHelp.AUDIO_EXTS) null else index
        }.chunked(cellCount).forEach { chunk ->
            units.add(MediaUnit(chunk.first(), chunk, false))
        }
        parsedMedia.forEachIndexed { index, m ->
            if (m.second in IllustrationHelp.AUDIO_EXTS) {
                units.add(MediaUnit(index, listOf(index), true))
            }
        }
        units.sortBy { it.firstIndex }
        return units
    }

    private fun snapshotUnitNotes() {
        unitNoteEdits.forEachIndexed { i, edit ->
            unitKeys.getOrNull(i)?.let { key ->
                unitNotes[key] = edit.text.toString()
            }
        }
    }

    /** 备注输入区：统一备注勾选时单个输入框；取消勾选后按媒体逐条输入（与排版无关） */
    private fun rebuildNoteInputs() {
        snapshotUnitNotes()
        val unified = binding.cbUnifiedNote.isChecked
        binding.etNote.visibility = if (unified) View.VISIBLE else View.GONE
        binding.llNoteItems.visibility = if (unified) View.GONE else View.VISIBLE
        binding.llNoteItems.removeAllViews()
        unitNoteEdits.clear()
        unitKeys.clear()
        if (unified) {
            return
        }
        val context = requireContext()
        //一媒体一条：四宫格 4 张图就是 4 条备注，排版只决定显示分组，不合并备注
        parsedMedia.forEachIndexed { mediaIndex, m ->
            //每条备注默认收成一行：点预览展开输入，点标题收起
            val initial = unitNotes[mediaIndex].orEmpty()
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val rowParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                rowParams.topMargin = 12.dpToPx()
                layoutParams = rowParams
            }
            val label = TextView(context).apply {
                text = if (m.second in IllustrationHelp.AUDIO_EXTS) {
                    getString(R.string.illustration_note_audio_unit, mediaIndex + 1)
                } else {
                    getString(R.string.illustration_note_image_unit, mediaIndex + 1, 1)
                }
                setTextColor(context.getCompatColor(R.color.primaryText))
                textSize = 14f
            }
            val preview = TextView(context).apply {
                text = previewTextOf(initial)
                setTextColor(context.getCompatColor(R.color.secondaryText))
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isClickable = true
                isFocusable = true
            }
            val edit = EditText(context).apply {
                hint = getString(R.string.illustration_note_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                gravity = Gravity.TOP or Gravity.START
                background = null
                setTextColor(context.getCompatColor(R.color.primaryText))
                textSize = 14f
                setText(initial)
                visibility = View.GONE
            }
            preview.setOnClickListener { expandNoteRow(preview, edit) }
            label.setOnClickListener { toggleNoteRow(preview, edit) }
            row.addView(label)
            row.addView(preview)
            row.addView(edit)
            binding.llNoteItems.addView(row)
            unitNoteEdits.add(edit)
            unitKeys.add(mediaIndex)
        }
        binding.llNoteItems.applyUiBodyTypefaceDeep(context.uiTypeface())
    }

    /** 备注收起行：取首个非空行，空的就是无备注 */
    private fun previewTextOf(text: CharSequence): String {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return first.ifBlank { getString(R.string.illustration_note_empty) }
    }

    private fun expandNoteRow(preview: TextView, edit: EditText) {
        preview.visibility = View.GONE
        edit.visibility = View.VISIBLE
        edit.requestFocus()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun toggleNoteRow(preview: TextView, edit: EditText) {
        if (edit.visibility == View.VISIBLE) {
            preview.text = previewTextOf(edit.text ?: "")
            edit.visibility = View.GONE
            preview.visibility = View.VISIBLE
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(edit.windowToken, 0)
        } else {
            expandNoteRow(preview, edit)
        }
    }

    private fun save() {
        if (parsedMedia.isEmpty()) {
            toastOnUi(R.string.illustration_no_images)
            return
        }
        val book = ReadBook.book ?: return
        val chapter = ReadBook.curTextChapter?.chapter ?: return
        val heightText = binding.etHeight.text.toString().trim()
        val displayHeight = heightText.toIntOrNull() ?: 0
        val pageBreak = binding.cbPageBreak.isChecked
        snapshotUnitNotes()
        val unified = binding.cbUnifiedNote.isChecked
        val unifiedNote = binding.etNote.text.toString()
        val units = computeUnits()
        val parsed = parsedMedia.toList()
        // 媒体写盘必须离开主线程：选择器允许选大视频，整段 writeBytes 会 ANR。
        // 挂 viewLifecycleOwner.lifecycleScope：视图销毁即取消，协程内不再操作 binding
        viewLifecycleOwner.lifecycleScope.launch {
            // 保存媒体文件，选择顺序与单元下标一致
            val srcs = withContext(Dispatchers.IO) {
                parsed.map { (bytes, ext) ->
                    val src = IllustrationHelp.newSrc(ext)
                    IllustrationHelp.saveImage(book, src, bytes)
                    src
                }
            }
            val records = arrayListOf<BookIllustration>()
            units.forEach { unit ->
                val unitSrcs = unit.indexes.map { srcs[it] }
                //记录按排版分组存（宫格渲染靠它），备注按媒体各写各的：
                //统一备注/单媒体组走 note 字段；多媒体组 note 置空，每图备注进 srcNotes
                val (note, srcNotes) = if (unified) {
                    unifiedNote to "{}"
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
                        single = unit.isAudio
                    )
                )
            }
            if (records.isEmpty()) return@launch
            appDb.bookIllustrationDao.insert(*records.toTypedArray())
            dismissAllowingStateLoss()
            appCtx.toastOnUi(R.string.illustration_inserted)
            callback?.invoke()
        }
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
        single: Boolean = false
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
            layoutType = if (single) BookIllustration.LAYOUT_SINGLE else selectedLayout(),
            displayHeight = displayHeight,
            pageBreak = pageBreak,
            sortOrder = sortOrder,
            note = note,
            srcNotes = srcNotes
        )
    }

    private var callback: (() -> Unit)? = null

    fun setOnInserted(callback: () -> Unit) {
        this.callback = callback
    }

    private inner class ThumbAdapter :
        RecyclerAdapter<Uri, ItemImageSimpleBinding>(requireContext()) {

        override fun getViewBinding(parent: ViewGroup): ItemImageSimpleBinding {
            return ItemImageSimpleBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemImageSimpleBinding,
            item: Uri,
            payloads: MutableList<Any>
        ) {
            binding.ivImage.run {
                io.legado.app.help.glide.ImageLoader.load(context, item).into(this)
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemImageSimpleBinding) {
            // 缩略图无需点击
        }
    }
}
