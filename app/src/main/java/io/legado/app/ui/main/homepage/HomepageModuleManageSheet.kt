package io.legado.app.ui.main.homepage

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.BaseBottomSheetDialogFragment
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.DialogHomepageManageBinding
import io.legado.app.databinding.ItemHomepageManageActionBinding
import io.legado.app.databinding.ItemHomepageManageFieldBinding
import io.legado.app.databinding.ItemHomepageManageKindPickerBinding
import io.legado.app.databinding.ItemHomepageManageModuleBinding
import io.legado.app.databinding.ItemHomepageManageSectionBinding
import io.legado.app.databinding.ItemHomepageManageSetBinding
import io.legado.app.databinding.ItemHomepageManageSourceBinding
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

/**
 * 主页模块管理底部弹窗：集列表 → 集详情 → 浏览书源/订阅源 → 源模块详情（已加入/发现 两 Tab），
 * 以及自定义集复制模块，所有层级共用一个列表，按页面栈切换。
 */
class HomepageModuleManageSheet : BaseBottomSheetDialogFragment(R.layout.dialog_homepage_manage) {

    private val binding by viewBinding(DialogHomepageManageBinding::bind)

    /**
     * 面板表面走壁纸域玻璃。
     *
     * 这个面板是**稀疏行列表**（标题 + 开关 + 图标，行底本身是透明的水波纹选择器），
     * 玻璃不会压住信息密度；目录 / 分类选择这类密集面板保持实底不变。
     * 面板内部各页（集详情 / 源详情 / 发现）共用同一个表面，因此一处 opt-in 即可。
     */
    override val sheetSurfaceGlass: Boolean get() = true

    internal val viewModel: HomepageViewModel
        get() = (parentFragment as HomepageFragment).viewModel

    private val adapter by lazy { ManageAdapter(requireContext()) }

    private val itemTouchHelper by lazy {
        ItemTouchHelper(ReorderCallback())
    }

    private var pageStack = mutableListOf<Page>(Page.SetList)

    /** 源模块详情页当前 Tab：0 = 已加入，1 = 发现 */
    private var sourceDetailTab = 0

    /** 搜索关键字（仅在浏览书源/订阅源/复制模块页生效） */
    private var searchQuery = ""

    /** 发现页状态：分类加载与内联勾选状态 */
    private var discoverState = DiscoverState()

    private data class DiscoverState(
        val loading: Boolean = false,
        val kinds: List<ExploreKind> = emptyList(),
        val selectedKindKeys: Set<String> = emptySet(),
        val selectedTypeKey: String = HomepageModuleType.Grid.key,
    )

    private sealed interface Page {
        data object SetList : Page

        data class SetDetail(val setUrl: String, val setName: String) : Page

        data object BrowseSources : Page

        data object BrowseRssSources : Page

        data class SourceDetail(
            val sourceUrl: String,
            val sourceName: String,
            val setId: String?,
            val sourceType: String,
        ) : Page

        data class CustomSetAdd(val setId: String, val setName: String) : Page
    }

    private sealed interface Row {
        data class Section(val title: String) : Row

        data class Set(val ui: HomepageSourceManageUi) : Row

        data class Module(val ui: HomepageModuleManageUi, val copyMode: Boolean = false) : Row

        data class Source(val ui: HomepageSourceManageUi, val sourceType: String) : Row

        data class Field(
            val label: String,
            val value: String,
            val hint: String? = null,
            val onClick: () -> Unit,
        ) : Row

        data class Action(val label: String, val action: () -> Unit) : Row

        data class KindPicker(val page: Page.SourceDetail) : Row
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.72f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.rvManage.layoutManager = LinearLayoutManager(requireContext())
        binding.rvManage.adapter = adapter
        itemTouchHelper.attachToRecyclerView(binding.rvManage)

        binding.ivClose.setOnClickListener { dismiss() }
        binding.ivBack.setOnClickListener { popPage() }
        binding.ivClearSearch.setOnClickListener {
            binding.etSearch.setText("")
        }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val next = s?.toString()?.trim().orEmpty()
                binding.ivClearSearch.isVisible = next.isNotEmpty()
                if (searchQuery != next) {
                    searchQuery = next
                    rebuildRows()
                }
            }
        })

        binding.tvActionAddSet.setOnClickListener { showCreateSetDialog() }
        binding.tvActionBrowseBook.setOnClickListener { pushPage(Page.BrowseSources) }
        binding.tvActionBrowseRss.setOnClickListener { pushPage(Page.BrowseRssSources) }

        binding.tabSourceDetail.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (sourceDetailTab != tab.position) {
                    sourceDetailTab = tab.position
                    rebuildRows()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.manageStateFlow.collect {
                rebuildRows()
            }
        }
        rebuildRows()
        upFooter()
        upHeader()
    }

    private fun pushPage(page: Page) {
        pageStack.add(page)
        resetSearchQuery()
        if (page is Page.SourceDetail) {
            val effectiveSetId = page.setId ?: when (page.sourceType) {
                "rss" -> "rss_${page.sourceUrl}"
                else -> "src_${page.sourceUrl}"
            }
            val joinedCount = adapter.manageState.allJoinedModules.count {
                it.sourceUrl == page.sourceUrl && it.customSetId == effectiveSetId
            }
            // 未加入任何模块的新源默认直接进「发现」页签勾选添加，减少一次切 Tab
            sourceDetailTab = if (joinedCount > 0) 0 else 1
            discoverState = DiscoverState()
            loadDiscoverKinds(page)
        }
        rebuildRows()
        upFooter()
        upHeader()
    }

    private fun popPage() {
        if (pageStack.size > 1) {
            pageStack.removeAt(pageStack.lastIndex)
            resetSearchQuery()
            rebuildRows()
            upFooter()
            upHeader()
        } else {
            dismiss()
        }
    }

    private fun resetSearchQuery() {
        searchQuery = ""
        if (binding.etSearch.text?.isNotEmpty() == true) {
            binding.etSearch.setText("")
        }
    }

    private fun currentPage(): Page = pageStack.last()

    private fun upHeader() {
        val page = currentPage()
        binding.ivBack.isVisible = pageStack.size > 1
        binding.tvTitle.text = when (page) {
            Page.SetList -> getString(R.string.homepage_module_manage)
            is Page.SetDetail -> page.setName.ifBlank { getString(R.string.homepage_set_detail) }
            Page.BrowseSources -> getString(R.string.homepage_browse_book_sources)
            Page.BrowseRssSources -> getString(R.string.homepage_browse_rss_sources)
            is Page.SourceDetail -> page.sourceName
            is Page.CustomSetAdd -> page.setName
        }
        binding.llSearchBar.isVisible = page is Page.BrowseSources ||
            page is Page.BrowseRssSources ||
            page is Page.CustomSetAdd
        binding.tabSourceDetail.isVisible = page is Page.SourceDetail
        if (page is Page.SourceDetail && binding.tabSourceDetail.tabCount != 2) {
            binding.tabSourceDetail.removeAllTabs()
            binding.tabSourceDetail.addTab(
                binding.tabSourceDetail.newTab().setText(R.string.homepage_joined)
            )
            binding.tabSourceDetail.addTab(
                binding.tabSourceDetail.newTab().setText(R.string.homepage_discover)
            )
        }
        if (page is Page.SourceDetail) {
            binding.tabSourceDetail.getTabAt(sourceDetailTab)?.select()
        }
    }

    private fun upFooter() {
        val page = currentPage()
        binding.tvActionAddSet.isVisible = page is Page.SetList
        binding.tvActionBrowseBook.isVisible = page is Page.SetList
        binding.tvActionBrowseRss.isVisible = page is Page.SetList
    }

    private fun rebuildRows() {
        val rows = mutableListOf<Row>()
        val state = adapter.manageState
        when (val page = currentPage()) {
            Page.SetList -> {
                if (state.sets.isEmpty()) {
                    rows.add(Row.Section(getString(R.string.homepage_empty_title)))
                }
                state.sets.forEach { rows.add(Row.Set(it)) }
                rows.add(Row.Action(getString(R.string.homepage_auto_setup)) {
                    if (viewModel.isAutoAssembling()) {
                        viewModel.cancelAutoAssembleStarterHomepage()
                        return@Action
                    }
                    requireContext().toastOnUi(getString(R.string.homepage_auto_setup_running))
                    viewModel.autoAssembleStarterHomepage { result ->
                        if (!isAdded) return@autoAssembleStarterHomepage
                        val msg = when (result.status) {
                            AutoSetupStatus.ADDED ->
                                getString(R.string.homepage_auto_setup_done, result.addedCount)
                            AutoSetupStatus.NO_SOURCES ->
                                getString(R.string.homepage_auto_setup_empty)
                            AutoSetupStatus.NO_CATEGORIES ->
                                getString(R.string.homepage_auto_setup_no_categories)
                            AutoSetupStatus.CANCELLED ->
                                getString(R.string.homepage_auto_setup_cancelled)
                        }
                        requireContext().toastOnUi(msg)
                    }
                })
            }

            is Page.SetDetail -> {
                val setId = resolveSetId(page.setUrl)
                val modules = state.allJoinedModules.filter { it.customSetId == setId }
                if (modules.isEmpty()) {
                    rows.add(Row.Section(getString(R.string.homepage_empty_title)))
                }
                modules.forEach { rows.add(Row.Module(it)) }
                when {
                    page.setUrl.startsWith("src_") -> rows.add(
                        Row.Action(getString(R.string.homepage_add_module)) {
                            pushPage(
                                Page.SourceDetail(
                                    sourceUrl = page.setUrl.removePrefix("src_"),
                                    sourceName = page.setName,
                                    setId = page.setUrl,
                                    sourceType = "book",
                                )
                            )
                        }
                    )

                    page.setUrl.startsWith("rss_") -> rows.add(
                        Row.Action(getString(R.string.homepage_add_module)) {
                            pushPage(
                                Page.SourceDetail(
                                    sourceUrl = page.setUrl.removePrefix("rss_"),
                                    sourceName = page.setName,
                                    setId = page.setUrl,
                                    sourceType = "rss",
                                )
                            )
                        }
                    )

                    else -> rows.add(
                        Row.Action(getString(R.string.homepage_copy_from_other_sets)) {
                            pushPage(Page.CustomSetAdd(setId, page.setName))
                        }
                    )
                }
            }

            Page.BrowseSources -> {
                val filtered = filterSources(state.browseSources, searchQuery)
                filtered.forEach {
                    rows.add(Row.Source(it, "book"))
                }
                if (filtered.isEmpty()) {
                    rows.add(Row.Section(getString(R.string.homepage_empty_title)))
                }
            }

            Page.BrowseRssSources -> {
                val filtered = filterSources(state.rssSources, searchQuery)
                filtered.forEach { rows.add(Row.Source(it, "rss")) }
                if (filtered.isEmpty()) {
                    rows.add(Row.Section(getString(R.string.homepage_empty_title)))
                }
            }

            is Page.SourceDetail -> {
                if (sourceDetailTab == 0) {
                    buildJoinedRows(rows, state, page)
                } else {
                    buildDiscoverRows(rows, page)
                }
            }

            is Page.CustomSetAdd -> {
                val q = searchQuery.lowercase()
                val others = state.allJoinedModules.filter { mod ->
                    mod.customSetId != page.setId && (
                        q.isEmpty() ||
                            mod.title.lowercase().contains(q) ||
                            mod.sourceName.lowercase().contains(q)
                        )
                }
                rows.add(Row.Section(getString(R.string.homepage_copy_from_other_sets)))
                others.forEach { rows.add(Row.Module(it, copyMode = true)) }
                if (others.isEmpty()) {
                    rows.add(Row.Section(getString(R.string.homepage_empty_title)))
                }
            }
        }
        adapter.submitRows(rows)
    }

    private fun filterSources(
        sources: List<HomepageSourceManageUi>,
        query: String,
    ): List<HomepageSourceManageUi> {
        if (query.isBlank()) return sources
        val q = query.lowercase()
        return sources.filter {
            it.sourceName.lowercase().contains(q) ||
                (it.sourceGroup?.lowercase()?.contains(q) == true)
        }
    }

    /** 已加入 Tab：该源在当前集的模块列表，附同步入口 */
    private fun buildJoinedRows(
        rows: MutableList<Row>,
        state: HomepageManageUiState,
        page: Page.SourceDetail,
    ) {
        val effectiveSetId = page.setId ?: when (page.sourceType) {
            "rss" -> "rss_${page.sourceUrl}"
            else -> "src_${page.sourceUrl}"
        }
        val modules = state.allJoinedModules.filter {
            it.sourceUrl == page.sourceUrl && it.customSetId == effectiveSetId
        }
        if (modules.isEmpty()) {
            rows.add(Row.Section(getString(R.string.homepage_empty_title)))
        }
        modules.forEach { rows.add(Row.Module(it)) }
        if (page.sourceType == "book") {
            rows.add(Row.Action(getString(R.string.homepage_sync)) {
                viewModel.syncSourceModules(page.sourceUrl) { result ->
                    if (!isAdded) return@syncSourceModules
                    val msg = if (!result.hasConfig) {
                        getString(R.string.homepage_sync_empty)
                    } else {
                        getString(R.string.homepage_sync_count, result.moduleCount)
                    }
                    requireContext().toastOnUi(msg)
                }
            })
        }
    }

    /** 发现 Tab：内联多选分类 + 一键选样式添加到首页，免去多层弹窗；底部保留高级手动添加入口 */
    private fun buildDiscoverRows(rows: MutableList<Row>, page: Page.SourceDetail) {
        when {
            discoverState.loading -> {
                rows.add(Row.Section(getString(R.string.homepage_loading_categories)))
            }
            discoverState.kinds.isEmpty() -> {
                rows.add(Row.Section(getString(R.string.homepage_no_categories)))
            }
            else -> {
                rows.add(Row.KindPicker(page))
            }
        }
        rows.add(
            Row.Field(
                label = getString(R.string.homepage_manual_add_module),
                value = "",
            ) {
                showEditDialog(
                    HomepageModuleManageUi(
                        id = "",
                        sourceUrl = page.sourceUrl,
                        sourceName = page.sourceName,
                        moduleKey = "",
                        title = "",
                        type = "",
                        originalTitle = "",
                        sourceType = page.sourceType,
                    ),
                    isCreate = true,
                    setId = page.setId,
                )
            }
        )
    }

    private fun loadDiscoverKinds(page: Page.SourceDetail) {
        viewLifecycleOwner.lifecycleScope.launch {
            discoverState = discoverState.copy(
                loading = true,
                kinds = emptyList(),
                selectedKindKeys = emptySet(),
            )
            rebuildRows()
            val kinds = if (page.sourceType == "rss") {
                viewModel.getRssKinds(page.sourceUrl).map { (title, url) ->
                    ExploreKind(title = title, url = url)
                }
            } else {
                viewModel.getExploreKinds(page.sourceUrl)
            }.filter { !it.title.startsWith("ERROR:") && !it.url.isNullOrBlank() }
            discoverState = discoverState.copy(
                loading = false,
                kinds = kinds,
                selectedKindKeys = emptySet(),
            )
            rebuildRows()
        }
    }

    private fun resolveSetId(setUrl: String): String {
        return if (HomepageViewModel.isCustomSetUrl(setUrl)) {
            HomepageViewModel.customSetIdFromUrl(setUrl)
        } else {
            setUrl
        }
    }

    private fun showCreateSetDialog() {
        requireContext().alert(getString(R.string.homepage_new_custom_set)) {
            val editText = EditText(requireContext())
            editText.hint = getString(R.string.homepage_set_name)
            editText.setPadding(48.dpToPx(), 16.dpToPx(), 48.dpToPx(), 8.dpToPx())
            setCustomView(editText)
            positiveButton(R.string.ok) {
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.createCustomSet(name)
                    requireContext().toastOnUi(getString(R.string.homepage_set_created))
                }
            }
            negativeButton(R.string.cancel)
        }
    }

    // ==================== 行事件 ====================

    private fun onSetRowClick(ui: HomepageSourceManageUi) {
        pushPage(Page.SetDetail(ui.sourceUrl, ui.sourceName))
    }

    private fun onSetToggle(ui: HomepageSourceManageUi, visible: Boolean) {
        viewModel.toggleSet(ui.sourceUrl, visible)
    }

    private fun showRenameSetDialog(ui: HomepageSourceManageUi) {
        val setId = resolveSetId(ui.sourceUrl)
        requireContext().alert(getString(R.string.homepage_rename_set)) {
            val editText = EditText(requireContext())
            editText.setText(ui.sourceName)
            editText.setPadding(48.dpToPx(), 16.dpToPx(), 48.dpToPx(), 8.dpToPx())
            setCustomView(editText)
            positiveButton(R.string.ok) {
                val name = editText.text.toString().trim()
                if (name.isNotEmpty() && name != ui.sourceName) {
                    viewModel.renameCustomSet(setId, name)
                }
            }
            negativeButton(R.string.cancel)
        }
    }

    private fun showDeleteSetDialog(ui: HomepageSourceManageUi) {
        requireContext().alert(
            getString(R.string.homepage_delete_set),
            getString(R.string.homepage_delete_set_confirm, ui.sourceName)
        ) {
            positiveButton(R.string.ok) {
                viewModel.deleteCustomSet(resolveSetId(ui.sourceUrl))
                val page = pageStack.lastOrNull()
                if (page is Page.SetDetail && page.setUrl == ui.sourceUrl) {
                    popPage()
                }
            }
            negativeButton(R.string.cancel)
        }
    }

    private fun onModuleToggle(ui: HomepageModuleManageUi, visible: Boolean) {
        viewModel.toggleModule(ui.id, visible)
    }

    private fun showDeleteModuleDialog(ui: HomepageModuleManageUi) {
        requireContext().alert(
            getString(R.string.homepage_delete_module),
            getString(R.string.homepage_delete_module_confirm, ui.title)
        ) {
            positiveButton(R.string.ok) {
                viewModel.deleteModule(ui.id)
            }
            negativeButton(R.string.cancel)
        }
    }

    private fun onSourceRowClick(ui: HomepageSourceManageUi, sourceType: String) {
        val page = currentPage()
        val setId = if (page is Page.SetDetail) {
            page.setUrl.takeIf { it.startsWith("src_") || it.startsWith("rss_") }
        } else {
            null
        }
        pushPage(
            Page.SourceDetail(
                sourceUrl = ui.sourceUrl,
                sourceName = ui.sourceName,
                setId = setId,
                sourceType = sourceType,
            )
        )
    }

    private fun onCopyModule(ui: HomepageModuleManageUi) {
        val page = currentPage()
        if (page !is Page.CustomSetAdd) return
        viewModel.assignModuleToCustomSet(ui.id, resolveSetId(page.setId))
        requireContext().toastOnUi(getString(R.string.homepage_copied))
    }

    private fun showEditDialog(
        ui: HomepageModuleManageUi,
        isCreate: Boolean,
        setId: String? = null,
    ) {
        val dialog = HomepageModuleEditDialog()
        dialog.arguments = Bundle().apply {
            putString("id", ui.id)
            putString("sourceUrl", ui.sourceUrl)
            putString("sourceName", ui.sourceName)
            putString("moduleKey", ui.moduleKey)
            putString("title", ui.title)
            putString("type", ui.type)
            putString("url", ui.url)
            putString("args", ui.args)
            putString("layoutConfig", ui.layoutConfig)
            putString("setId", setId)
            putString("sourceType", ui.sourceType)
            putBoolean("isCreate", isCreate)
        }
        dialog.show(childFragmentManager, "homepageModuleEdit")
    }

    // ==================== 适配器 ====================

    /** 拖动柄触摸即启动排序拖拽 */
    private fun bindDragHandle(iv: View, holder: RecyclerView.ViewHolder) {
        iv.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION && adapter.isDraggable(position)) {
                    itemTouchHelper.startDrag(holder)
                    return@setOnTouchListener true
                }
            }
            false
        }
    }

    private inner class ReorderCallback : ItemTouchHelper.Callback() {

        override fun isLongPressDragEnabled(): Boolean = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
        ): Int {
            val position = viewHolder.bindingAdapterPosition
            val flags = if (position != RecyclerView.NO_POSITION && adapter.isDraggable(position)) {
                ItemTouchHelper.UP or ItemTouchHelper.DOWN
            } else {
                0
            }
            return makeMovementFlags(flags, 0)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            return adapter.moveRow(
                viewHolder.bindingAdapterPosition,
                target.bindingAdapterPosition
            )
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            commitReorder()
        }
    }

    /** 拖拽结束后按页面类型持久化新顺序 */
    private fun commitReorder() {
        val rows = adapter.currentRows()
        when (val page = currentPage()) {
            is Page.SetList -> {
                val urls = rows.filterIsInstance<Row.Set>().map { it.ui.sourceUrl }
                if (urls.isNotEmpty() && urls != adapter.manageState.sets.map { it.sourceUrl }) {
                    viewModel.reorderCustomSets(urls)
                }
            }

            is Page.SetDetail ->
                commitModuleOrder(rows, resolveSetId(page.setUrl))

            is Page.SourceDetail -> {
                if (sourceDetailTab != 0) return
                val effectiveSetId = page.setId ?: when (page.sourceType) {
                    "rss" -> "rss_${page.sourceUrl}"
                    else -> "src_${page.sourceUrl}"
                }
                commitModuleOrder(rows, effectiveSetId)
            }

            else -> Unit
        }
    }

    private fun commitModuleOrder(rows: List<Row>, setId: String) {
        val ids = rows.filterIsInstance<Row.Module>().map { it.ui.id }
        if (ids.isEmpty()) return
        val current = adapter.manageState.allJoinedModules
            .filter { it.customSetId == setId }
            .map { it.id }
        if (ids != current) {
            viewModel.reorderModules(ids)
        }
    }

    private inner class ManageAdapter(
        context: Context
    ) : RecyclerView.Adapter<ManageViewHolder>() {

        val manageState get() = viewModel.manageStateFlow.value

        private val inflater = LayoutInflater.from(context)
        private val rows = mutableListOf<Row>()

        fun submitRows(newRows: List<Row>) {
            rows.clear()
            rows.addAll(newRows)
            notifyDataSetChanged()
        }

        fun currentRows(): List<Row> = rows.toList()

        fun isDraggable(position: Int): Boolean {
            if (position < 0 || position >= rows.size) return false
            return when (val row = rows[position]) {
                is Row.Set -> true
                is Row.Module -> !row.copyMode
                else -> false
            }
        }

        fun moveRow(from: Int, to: Int): Boolean {
            if (from == to || from < 0 || to < 0 || from >= rows.size || to >= rows.size) {
                return false
            }
            if (!isDraggable(from) || !isDraggable(to)) return false
            if (rows[from]::class != rows[to]::class) return false
            val tmp = rows[from]
            rows[from] = rows[to]
            rows[to] = tmp
            notifyItemMoved(from, to)
            return true
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Section -> TYPE_SECTION
            is Row.Set -> TYPE_SET
            is Row.Module -> TYPE_MODULE
            is Row.Source -> TYPE_SOURCE
            is Row.Field -> TYPE_FIELD
            is Row.Action -> TYPE_ACTION
            is Row.KindPicker -> TYPE_KIND_PICKER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ManageViewHolder {
            val binding: ViewBinding = when (viewType) {
                TYPE_SECTION -> ItemHomepageManageSectionBinding.inflate(inflater, parent, false)
                TYPE_SET -> ItemHomepageManageSetBinding.inflate(inflater, parent, false)
                TYPE_MODULE -> ItemHomepageManageModuleBinding.inflate(inflater, parent, false)
                TYPE_SOURCE -> ItemHomepageManageSourceBinding.inflate(inflater, parent, false)
                TYPE_FIELD -> ItemHomepageManageFieldBinding.inflate(inflater, parent, false)
                TYPE_KIND_PICKER -> ItemHomepageManageKindPickerBinding.inflate(inflater, parent, false)
                else -> ItemHomepageManageActionBinding.inflate(inflater, parent, false)
            }
            return ManageViewHolder(binding)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: ManageViewHolder, position: Int) {
            val context = holder.binding.root.context
            when (val row = rows[position]) {
                is Row.Section -> holder.sectionBinding?.tvSection?.text = row.title

                is Row.Set -> holder.setBinding?.run {
                    bindDragHandle(ivDrag, holder)
                    tvName.text = row.ui.sourceName
                    tvDesc.text = context.getString(R.string.homepage_module_count, row.ui.moduleCount)
                    swVisible.setOnCheckedChangeListener(null)
                    swVisible.isChecked = row.ui.isSelected
                    swVisible.setOnCheckedChangeListener { _, isChecked ->
                        onSetToggle(row.ui, isChecked)
                    }
                    root.setOnClickListener { onSetRowClick(row.ui) }
                    ivEdit.setOnClickListener { showRenameSetDialog(row.ui) }
                    ivDelete.setOnClickListener { showDeleteSetDialog(row.ui) }
                }

                is Row.Module -> holder.moduleBinding?.run {
                    val editable = !row.copyMode
                    bindDragHandle(ivDrag, holder)
                    ivDrag.isVisible = editable
                    ivEdit.isVisible = editable
                    ivDelete.isVisible = editable
                    tvTitle.text = row.ui.title
                    tvDesc.text = moduleTypeLabel(row.ui.type)
                    swVisible.isVisible = editable
                    if (editable) {
                        swVisible.setOnCheckedChangeListener(null)
                        swVisible.isChecked = row.ui.isVisible
                        swVisible.setOnCheckedChangeListener { _, isChecked ->
                            onModuleToggle(row.ui, isChecked)
                        }
                        ivEdit.setOnClickListener { showEditDialog(row.ui, isCreate = false) }
                        ivDelete.setOnClickListener { showDeleteModuleDialog(row.ui) }
                    }
                    root.setOnClickListener {
                        if (row.copyMode) onCopyModule(row.ui)
                    }
                }

                is Row.Source -> holder.sourceBinding?.run {
                    tvName.text = row.ui.sourceName
                    tvDesc.text = buildString {
                        row.ui.sourceGroup?.takeIf { it.isNotBlank() }?.let {
                            append(it)
                            if (row.ui.moduleCount > 0) append(" · ")
                        }
                        if (row.ui.moduleCount > 0) {
                            append(context.getString(R.string.homepage_module_count, row.ui.moduleCount))
                        }
                    }
                    root.setOnClickListener { onSourceRowClick(row.ui, row.sourceType) }
                }

                is Row.Field -> holder.fieldBinding?.run {
                    tvFieldLabel.text = row.label
                    tvFieldValue.text = row.value
                    tvFieldValue.isVisible = row.value.isNotEmpty()
                    tvFieldHint.isVisible = !row.hint.isNullOrBlank()
                    tvFieldHint.text = row.hint ?: ""
                    root.setOnClickListener { row.onClick() }
                }

                is Row.Action -> holder.actionBinding?.root?.let { tv ->
                    tv.text = row.label
                    tv.setOnClickListener { row.action() }
                }

                is Row.KindPicker -> holder.kindPickerBinding?.let { pickerBinding ->
                    bindKindPicker(pickerBinding, row.page)
                }
            }
        }

        private fun bindKindPicker(
            picker: ItemHomepageManageKindPickerBinding,
            page: Page.SourceDetail,
        ) {
            val ctx = picker.root.context
            fun kindKey(kind: ExploreKind): String = kind.url ?: kind.title

            fun renderState() {
                val selectedKeys = discoverState.selectedKindKeys
                val selectedCount = selectedKeys.size

                picker.flKinds.removeAllViews()
                discoverState.kinds.forEach { kind ->
                    val key = kindKey(kind)
                    val selected = key in selectedKeys
                    val chip = createKindChip(ctx, kind.title) {
                        val next = selectedKeys.toMutableSet()
                        if (selected) next.remove(key) else next.add(key)
                        discoverState = discoverState.copy(selectedKindKeys = next)
                        renderState()
                    }
                    if (selected) {
                        chip.setBackgroundResource(R.drawable.bg_homepage_field)
                        chip.setTextColor(ctx.accentColor)
                    }
                    picker.flKinds.addView(chip)
                }

                picker.flStyles.removeAllViews()
                HomepageModuleBuilder.quickModuleTypes.forEach { type ->
                    val selected = discoverState.selectedTypeKey == type.key
                    val chip = createKindChip(ctx, ctx.getString(type.titleRes)) {
                        discoverState = discoverState.copy(selectedTypeKey = type.key)
                        renderState()
                    }
                    if (selected) {
                        chip.setBackgroundResource(R.drawable.bg_homepage_field)
                        chip.setTextColor(ctx.accentColor)
                    }
                    picker.flStyles.addView(chip)
                }

                picker.tvQuickAdd.text = ctx.getString(R.string.homepage_quick_add_selected, selectedCount)
                picker.tvQuickAdd.isEnabled = selectedCount > 0
                picker.tvQuickAdd.alpha = if (selectedCount > 0) 1f else 0.45f
                picker.tvQuickAdd.setTextColor(
                    if (selectedCount > 0) ctx.accentColor else ctx.getColor(R.color.secondaryText)
                )
            }

            picker.tvSelectAll.setTextColor(ctx.accentColor)
            picker.tvSelectAll.setOnClickListener {
                discoverState = discoverState.copy(
                    selectedKindKeys = discoverState.kinds.map(::kindKey).toSet()
                )
                renderState()
            }
            picker.tvClearAll.setOnClickListener {
                discoverState = discoverState.copy(selectedKindKeys = emptySet())
                renderState()
            }
            picker.tvQuickAdd.setOnClickListener {
                val selectedKinds = discoverState.kinds.filter {
                    kindKey(it) in discoverState.selectedKindKeys
                }
                if (selectedKinds.isEmpty()) return@setOnClickListener
                viewModel.quickAddExploreModule(
                    sourceUrl = page.sourceUrl,
                    sourceName = page.sourceName,
                    sourceType = page.sourceType,
                    setId = page.setId,
                    typeKey = discoverState.selectedTypeKey,
                    kinds = selectedKinds,
                )
                discoverState = discoverState.copy(selectedKindKeys = emptySet())
                ctx.toastOnUi(ctx.getString(R.string.homepage_module_added))
                sourceDetailTab = 0
                upHeader()
                rebuildRows()
            }
            renderState()
        }

        private fun moduleTypeLabel(type: String): String {
            val res = HomepageModuleType.fromKey(type).titleRes
            return requireContext().getString(res)
        }
    }

    class ManageViewHolder(val binding: ViewBinding) :
        RecyclerView.ViewHolder(binding.root) {
        val sectionBinding: ItemHomepageManageSectionBinding? =
            binding as? ItemHomepageManageSectionBinding

        val setBinding: ItemHomepageManageSetBinding? = binding as? ItemHomepageManageSetBinding

        val moduleBinding: ItemHomepageManageModuleBinding? =
            binding as? ItemHomepageManageModuleBinding

        val sourceBinding: ItemHomepageManageSourceBinding? =
            binding as? ItemHomepageManageSourceBinding

        val fieldBinding: ItemHomepageManageFieldBinding? =
            binding as? ItemHomepageManageFieldBinding

        val actionBinding: ItemHomepageManageActionBinding? =
            binding as? ItemHomepageManageActionBinding

        val kindPickerBinding: ItemHomepageManageKindPickerBinding? =
            binding as? ItemHomepageManageKindPickerBinding
    }

    companion object {
        private const val TYPE_SECTION = 0
        private const val TYPE_SET = 1
        private const val TYPE_MODULE = 2
        private const val TYPE_SOURCE = 3
        private const val TYPE_FIELD = 4
        private const val TYPE_ACTION = 5
        private const val TYPE_KIND_PICKER = 6

        internal fun createKindChip(
            context: Context,
            text: String,
            onClick: () -> Unit
        ): TextView {
            val chip = TextView(context)
            chip.text = text
            chip.textSize = 13f
            chip.setTextColor(context.getColor(R.color.primaryText))
            chip.setBackgroundResource(R.drawable.bg_homepage_chip)
            chip.setPadding(10.dpToPx(), 6.dpToPx(), 10.dpToPx(), 6.dpToPx())
            chip.setOnClickListener { onClick() }
            val lp = FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 10.dpToPx(), 10.dpToPx())
            chip.layoutParams = lp
            return chip
        }
    }
}
