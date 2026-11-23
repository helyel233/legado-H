package io.legado.app.ui.main.bookshelf.style1

import android.os.Bundle
import android.view.View
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.dao.BookShelfDisplay
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBookshelf1Binding
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.style1.books.BooksFragment
import io.legado.app.ui.widget.MainTopBarView
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.RoundedTagBarView
import io.legado.app.ui.widget.compose.ComposeMultiChoiceDialog
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch

/**
 * 书架界面
 */
class BookshelfFragment1 : BaseBookshelfFragment(R.layout.fragment_bookshelf1) {

    private val binding by viewBinding(FragmentBookshelf1Binding::bind)
    private val adapter by lazy { TabFragmentPageAdapter(this) }
    private val bookGroups = mutableListOf<BookGroup>()
    private val tabRegistry = BookshelfTabRegistry()
    private val fragmentMap = hashMapOf<Long, BooksFragment>()
    private var groupMenuPopup: ModernActionPopup.Handle? = null
    private var bookTags = emptyList<String>()
    private var selectedBookTag = ""
    private val groupShelfCache = hashMapOf<Long, List<BookShelfDisplay>>()
    private var currentGroupIndex = 0
    private var hasPreservedGroupIndex = false
    private var topOverlaySpace = 0
    private var topOverlayEnabled = false
    private var structureVersion = 0
    private val appearanceRefreshRunnable = Runnable { refreshAppearanceNow() }
    override val groupId: Long get() = selectedGroup?.groupId ?: 0

    override val books: List<Book>
        get() {
            val fragment = findBooksFragment(groupId)
            return fragment?.getBooks() ?: emptyList()
        }

    override var onlyUpdateRead = false

    private val childFragmentLifecycleCallbacks =
        object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentCreated(
                fm: FragmentManager,
                f: Fragment,
                savedInstanceState: Bundle?
            ) {
                val fragment = f as? BooksFragment ?: return
                val id = fragment.arguments?.getLong("groupId", Long.MIN_VALUE)
                    ?.takeIf { it != Long.MIN_VALUE }
                    ?: return
                fragmentMap[id] = fragment
            }

            override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
                val fragment = f as? BooksFragment ?: return
                val id = fragment.arguments?.getLong("groupId", Long.MIN_VALUE)
                    ?.takeIf { it != Long.MIN_VALUE }
                    ?: fragment.groupId
                if (fragmentMap[id] === fragment) {
                    fragmentMap.remove(id)
                }
            }
        }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (position !in bookGroups.indices) return
            currentGroupIndex = position
            selectedBookTag = ""
            updateHeaderTitle()
            val group = bookGroups[position]
            findBooksFragment(group.groupId)?.setBookTagFilter("")
            renderBookTags(groupShelfCache[group.groupId].orEmpty())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        childFragmentManager.registerFragmentLifecycleCallbacks(childFragmentLifecycleCallbacks, false)
    }

    override fun onDestroy() {
        childFragmentManager.unregisterFragmentLifecycleCallbacks(childFragmentLifecycleCallbacks)
        super.onDestroy()
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_CURRENT_GROUP_INDEX)) {
            currentGroupIndex = savedInstanceState.getInt(STATE_CURRENT_GROUP_INDEX, 0)
            hasPreservedGroupIndex = true
        }
        initView()
        initBookGroupData()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val indexToSave = if (view != null && bookGroups.isNotEmpty()) {
            binding.viewPagerBookshelfPager.currentItem
        } else {
            currentGroupIndex
        }
        outState.putInt(STATE_CURRENT_GROUP_INDEX, indexToSave)
    }

    override fun onDestroyView() {
        view?.removeCallbacks(appearanceRefreshRunnable)
        binding.viewPagerBookshelfPager.unregisterOnPageChangeCallback(pageChangeCallback)
        binding.viewPagerBookshelfPager.adapter = null
        super.onDestroyView()
    }

    private val selectedGroup: BookGroup?
        get() {
            val index = if (view != null) {
                binding.viewPagerBookshelfPager.currentItem
            } else {
                currentGroupIndex
            }
            return bookGroups.getOrNull(index)
        }

    private fun findBooksFragment(groupId: Long): BooksFragment? {
        val cached = fragmentMap[groupId]
        if (cached != null && cached.isAdded) {
            return cached
        }
        val itemId = adapter.itemIdForGroup(groupId)
        if (itemId != null) {
            val fromManager = childFragmentManager.findFragmentByTag("f$itemId") as? BooksFragment
            if (fromManager != null) {
                fragmentMap[groupId] = fromManager
                return fromManager
            }
        }
        return cached
    }

    private fun initView() {
        binding.topBar.applyStatusBarPadding(withInitialPadding = true)
        (binding.viewPagerBookshelfPager.getChildAt(0) as? RecyclerView)
            ?.setEdgeEffectColor(primaryColor)
        binding.topBar.setMode(MainTopBarView.Mode.BOOKSHELF)
        binding.topBar.moreButton.setOnClickListener {
            showModernBookshelfMenu(it)
        }
        binding.topBar.searchButton.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), SearchActivity::class.java))
        }
        binding.topBar.setSearchHint(getString(R.string.search_book_key))
        binding.topBar.searchEntry.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), SearchActivity::class.java))
        }
        binding.topBar.setOnHeightChangedListener {
            updateTopBarOverlay()
        }
        binding.topBar.titleSelect.setOnClickListener {
            showGroupSwitchMenu(it)
        }
        binding.topBar.primaryBar.setOnTagClickListener { index ->
            selectedBookTag = ""
            switchToGroup(index)
        }
        binding.topBar.showTags(true)
        binding.topBar.tagsBar.setOnTagClickListener { index ->
            val tag = bookTags.getOrNull(index).orEmpty()
            if (tag == selectedBookTag) {
                selectedGroup?.let { group ->
                    findBooksFragment(group.groupId)?.let { fragment ->
                        val label = tag.ifBlank { getString(R.string.bookshelf_tag_all) }
                        toastOnUi("${group.groupName} · $label(${fragment.getBooksCount()})")
                    }
                }
            } else {
                selectedBookTag = tag
                binding.topBar.tagsBar.setSelectedIndex(index, smooth = true)
                findBooksFragment(groupId)?.setBookTagFilter(tag)
            }
        }
        binding.topBar.tagsBar.setOnTagLongClickListener { index ->
            selectedBookTag = bookTags.getOrNull(index).orEmpty()
            findBooksFragment(groupId)?.setBookTagFilter(selectedBookTag)
            true
        }
        binding.viewPagerBookshelf.disallowParentInterceptDownEvent(false)
        binding.viewPagerBookshelfPager.offscreenPageLimit = 1
        binding.viewPagerBookshelfPager.isUserInputEnabled = AppConfig.bottomBarLayoutMode != "sidebar"
        binding.viewPagerBookshelfPager.adapter = adapter
        if (currentGroupIndex in bookGroups.indices) {
            binding.viewPagerBookshelfPager.setCurrentItem(currentGroupIndex, false)
        }
        binding.viewPagerBookshelfPager.registerOnPageChangeCallback(pageChangeCallback)
        binding.topBar.doOnLayout {
            updateTopBarOverlay()
        }
        binding.root.post {
            updateTopBarOverlay()
        }
        updateHeaderTitle()
    }

    override fun onResume() {
        super.onResume()
        binding.viewPagerBookshelfPager.isUserInputEnabled = AppConfig.bottomBarLayoutMode != "sidebar"
        binding.root.post {
            updateTopBarOverlay()
        }
    }

    private fun updateTopBarOverlay() {
        if (!isAdded) return
        // 仅 API>=33 走覆盖式(顶栏背景毛玻璃);低版本降级为非覆盖布局,避免透明顶栏透出滚动列表。
        val overlay = binding.topBar.isOverlayMode() && binding.topBar.supportsBackdropBlur()
        val contentMargin = resources.getDimensionPixelSize(R.dimen.bookshelf_content_margin_top)
        val newSpace = if (overlay) binding.topBar.height else 0
        if (topOverlayEnabled != overlay) {
            ConstraintSet().apply {
                clone(binding.root)
                clear(R.id.view_pager_bookshelf, ConstraintSet.TOP)
                if (overlay) {
                    connect(
                        R.id.view_pager_bookshelf,
                        ConstraintSet.TOP,
                        ConstraintSet.PARENT_ID,
                        ConstraintSet.TOP
                    )
                    setMargin(R.id.view_pager_bookshelf, ConstraintSet.TOP, 0)
                } else {
                    connect(
                        R.id.view_pager_bookshelf,
                        ConstraintSet.TOP,
                        R.id.top_bar,
                        ConstraintSet.BOTTOM
                    )
                    setMargin(R.id.view_pager_bookshelf, ConstraintSet.TOP, contentMargin)
                }
                applyTo(binding.root)
            }
        }
        topOverlayEnabled = overlay
        topOverlaySpace = newSpace
        bookGroups.forEach { group ->
            findBooksFragment(group.groupId)?.setTopOverlaySpace(topOverlaySpace, topOverlayEnabled)
        }
        // 1.25.10 顶栏液态玻璃：采样源取 view_pager_bookshelf —— 与 top_bar 在同一个
        // ConstraintLayout 下**平级**，是唯一合法的「兄弟子树」形态。
        // 绝不能传 Activity 的 content_container（那是顶栏的祖先），否则底层库在录制中
        // 会重入绘制顶栏自身 → RenderNode 重入录制 → hwui abort（1.25.8 同类崩溃）。
        // 非覆盖式布局（overlay=false）顶栏在内容上方，背后没有内容 → 保持 null。
        binding.topBar.setBackdropBlur(if (overlay) binding.viewPagerBookshelf else null)
        binding.topBar.bringToFront()
    }

    private fun scheduleTopBarOverlayUpdate() {
        if (!isAdded) return
        binding.topBar.post {
            updateTopBarOverlay()
        }
    }

    @Synchronized
    override fun upGroup(data: List<BookGroup>) {
        if (data.isEmpty()) {
            appDb.bookGroupDao.enableGroup(BookGroup.IdAll)
        } else if (data != bookGroups) {
            val hadGroups = bookGroups.isNotEmpty()
            val previousGroupId = if (hadGroups) selectedGroup?.groupId else null
            bookGroups.clear()
            bookGroups.addAll(data)
            val newItems = tabRegistry.buildItems(structureVersion, bookGroups)
            adapter.submitItems(newItems)
            bookGroups.forEachIndexed { index, group ->
                findBooksFragment(group.groupId)?.let { fragment ->
                    fragment.updatePosition(index)
                    fragment.setEnableRefresh(group.enableRefresh)
                    val sort = group.getRealBookSort()
                    if (fragment.bookSort != sort) {
                        fragment.upBookSort(sort)
                    }
                    fragment.setTopOverlaySpace(topOverlaySpace, topOverlayEnabled)
                }
            }
            renderGroupSelector()
            val preservedIndex = when {
                hadGroups && previousGroupId != null ->
                    bookGroups.indexOfFirst { it.groupId == previousGroupId }
                hasPreservedGroupIndex && currentGroupIndex in bookGroups.indices ->
                    currentGroupIndex
                else -> -1
            }
            hasPreservedGroupIndex = false
            if (preservedIndex >= 0) {
                currentGroupIndex = preservedIndex
                if (binding.viewPagerBookshelfPager.currentItem != preservedIndex) {
                    binding.viewPagerBookshelfPager.setCurrentItem(preservedIndex, false)
                }
                AppConfig.saveTabPosition = preservedIndex
            } else {
                selectSavedGroup()
            }
        } else {
            renderGroupSelector()
            renderGroupTags()
        }
        updateHeaderTitle()
    }

    override fun upSort() {
        // 与 BookshelfFragment2 同源：View 已销毁时 adapter 已经跟着 RecyclerView 走掉，
        // 保持同一套「先问一句」的调用约定
        if (!isViewReady()) return
        bookGroups.forEach { group ->
            val sort = group.getRealBookSort()
            findBooksFragment(group.groupId)?.let { fragment ->
                fragment.setEnableRefresh(group.enableRefresh)
                if (fragment.bookSort != sort) {
                    fragment.upBookSort(sort)
                }
            }
        }
    }

    private fun selectSavedGroup() {
        if (view == null) return
        binding.viewPagerBookshelfPager.post {
            if (!isAdded || view == null) return@post
            if (bookGroups.isEmpty()) {
                binding.topBar.setPrimaryItems(emptyList(), -1)
                binding.topBar.tagsBar.submitItems(emptyList(), -1)
                updateHeaderTitle()
                return@post
            }
            val target = AppConfig.saveTabPosition.coerceIn(0, bookGroups.lastIndex)
            switchToGroup(target)
        }
    }

    override fun gotoTop() {
        findBooksFragment(groupId)?.gotoTop()
    }

    private fun renderGroupTags() {
        renderBookTags(emptyList())
    }

    private fun updateHeaderTitle() {
        binding.topBar.setTitle(selectedGroup?.groupName ?: getString(R.string.bookshelf))
        renderGroupSelector()
    }

    private fun renderGroupSelector() {
        binding.topBar.setPrimaryItems(
            bookGroups.map { RoundedTagBarView.Item(it.groupName) },
            currentGroupIndex.coerceIn(-1, bookGroups.lastIndex)
        )
        scheduleTopBarOverlayUpdate()
    }

    fun onBooksChanged(groupId: Long, books: List<Book>) {
        onShelfDisplaysChanged(groupId, books.map { it.toShelfDisplay() })
    }

    fun onShelfDisplaysChanged(groupId: Long, books: List<BookShelfDisplay>) {
        groupShelfCache[groupId] = books
        if (groupId != this.groupId) return
        renderBookTags(books)
    }

    private fun renderBookTags(books: List<BookShelfDisplay>) {
        if (!isAdded) return
        val allText = getString(R.string.bookshelf_tag_all)
        val storedTags = AppConfig.bookshelfGroupTags[groupId].orEmpty()
        val tags = storedTags.ifEmpty {
            val migratedTags = books.asSequence()
                .flatMap { BookTagHelper.parse(it.customTag).asSequence() }
                .distinct()
                .sorted()
                .toList()
            if (migratedTags.isNotEmpty()) {
                val map = AppConfig.bookshelfGroupTags.toMutableMap()
                map[groupId] = migratedTags
                AppConfig.bookshelfGroupTags = map
            }
            migratedTags
        }
            .filterNot { it in AppConfig.bookshelfHiddenTags[groupId].orEmpty() }
        bookTags = listOf("") + tags
        if (selectedBookTag.isNotBlank() && selectedBookTag !in tags) {
            selectedBookTag = ""
            findBooksFragment(groupId)?.setBookTagFilter("")
        }
        binding.topBar.tagsBar.submitItems(
            bookTags.map { tag ->
                val label = tag.ifBlank { allText }
                val count = if (tag.isBlank()) books.size
                else books.count { BookTagHelper.has(it.customTag, tag) }
                RoundedTagBarView.Item("$label ($count)")
            },
            bookTags.indexOf(selectedBookTag).takeIf { it >= 0 } ?: 0
        )
        scheduleTopBarOverlayUpdate()
    }

    private fun showGroupSwitchMenu(anchor: View) {
        if (bookGroups.isEmpty()) return
        groupMenuPopup?.dismiss()
        val selectedId = selectedGroup?.groupId
        val actions = bookGroups.mapIndexed { index, group ->
            val prefix = if (group.groupId == selectedId) "✓ " else ""
            ModernActionPopup.Action(prefix + group.groupName) {
                selectedBookTag = ""
                switchToGroup(index)
            }
        }
        groupMenuPopup = ModernActionPopup.show(anchor, actions, groupMenuPopup)
    }

    private fun switchToGroup(index: Int) {
        if (index !in bookGroups.indices) return
        currentGroupIndex = index
        binding.viewPagerBookshelfPager.setCurrentItem(index, false)
        AppConfig.saveTabPosition = index
        selectedBookTag = ""
        findBooksFragment(groupId)?.setBookTagFilter("")
        renderBookTags(groupShelfCache[groupId].orEmpty())
        updateHeaderTitle()
    }

    fun switchToGroupId(groupId: Long) {
        val index = bookGroups.indexOfFirst { it.groupId == groupId }
        if (index >= 0) {
            switchToGroup(index)
        }
    }

    override fun showBookTagManageAlert() {
        val group = selectedGroup ?: return
        val targetBooks = groupShelfCache[group.groupId].orEmpty()
        val tags = targetBooks
            .flatMap { BookTagHelper.parse(it.customTag) }
            .distinct()
            .sorted()
        if (tags.isEmpty()) {
            toastOnUi(R.string.bookshelf_tag_none)
            return
        }
        val checked = BooleanArray(tags.size) { true }
        val labels = tags.map { tag ->
            "$tag (${targetBooks.count { BookTagHelper.has(it.customTag, tag) }})"
        }
        showDialogFragment(
            ComposeMultiChoiceDialog.create(
                title = "${group.groupName} · ${getString(R.string.bookshelf_tag_manage)}",
                labels = labels,
                checked = checked,
                message = getString(R.string.bookshelf_tag_manage_hint),
                positiveText = getString(android.R.string.ok),
                negativeText = getString(android.R.string.cancel),
                onPositive = { result ->
                    val keepTags = tags.filterIndexed { index, _ -> result[index] }.toSet()
                lifecycleScope.launch(IO) {
                    val fullBooks = appDb.bookDao.getBooksSafe(targetBooks.map { it.bookUrl })
                    fullBooks.forEach { book ->
                        val normalized = BookTagHelper.join(
                            BookTagHelper.parse(book.customTag).filter { it in keepTags }
                        )
                        if (normalized != book.customTag) {
                            book.customTag = normalized
                            appDb.bookDao.update(book)
                        }
                    }
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                }
                }
            )
        )
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            renderBookTags(groupShelfCache[groupId].orEmpty())
        }
        observeEvent<String>(EventBus.BOOKSHELF_STRUCTURE_CHANGED) {
            rebuildBookshelfContent()
        }
        observeEvent<Boolean>(EventBus.MAIN_APPEARANCE_KIT_CHANGED) {
            scheduleAppearanceRefresh()
        }
        observeEvent<Boolean>(EventBus.MAIN_THEME_BACKGROUND_CHANGED) {
            scheduleAppearanceRefresh()
        }
    }

    private fun scheduleAppearanceRefresh() {
        val rootView = view ?: return
        rootView.removeCallbacks(appearanceRefreshRunnable)
        rootView.post(appearanceRefreshRunnable)
    }

    private fun refreshAppearanceNow() {
        if (!isAdded || view == null) return
        val ctx = requireContext()
        (binding.viewPagerBookshelfPager.getChildAt(0) as? RecyclerView)
            ?.setEdgeEffectColor(primaryColor)
        binding.topBar.refreshStyle()
        binding.topBar.titleText.applyUiTitleTypeface(ctx)
        binding.topBar.applyUiBodyTypefaceDeep(ctx.uiTypeface())
        binding.root.applyUiBodyTypefaceDeep(ctx.uiTypeface())
        updateTopBarOverlay()
        val activeFragments = LinkedHashSet<BooksFragment>()
        bookGroups.forEach { group ->
            findBooksFragment(group.groupId)?.let(activeFragments::add)
        }
        activeFragments.addAll(fragmentMap.values)
        activeFragments.forEach { it.refreshAppearance() }
    }

    private fun rebuildBookshelfContent() {
        if (!isAdded || view == null) return
        val targetGroupId = groupId
        dismissBookshelfTransientUi()
        groupMenuPopup?.dismiss()
        groupMenuPopup = null
        structureVersion++
        fragmentMap.clear()
        selectedBookTag = ""
        binding.viewPagerBookshelfPager.post {
            if (!isAdded || view == null) return@post
            adapter.submitItems(tabRegistry.buildItems(structureVersion, bookGroups))
            if (bookGroups.any { it.groupId == targetGroupId }) {
                switchToGroupId(targetGroupId)
            } else {
                selectSavedGroup()
            }
            renderBookTags(groupShelfCache[groupId].orEmpty())
            updateTopBarOverlay()
        }
    }

    private inner class TabFragmentPageAdapter(fragment: Fragment) :
        FragmentStateAdapter(fragment) {

        private var adapterItems: List<BookshelfTabItem> = emptyList()

        fun itemIdForGroup(groupId: Long): Long? {
            return adapterItems.firstOrNull { it.group.groupId == groupId }?.itemId
        }

        fun submitItems(newItems: List<BookshelfTabItem>) {
            val oldItems = adapterItems
            if (oldItems == newItems) return
            val diffResult = DiffUtil.calculateDiff(BookshelfTabDiffCallback(oldItems, newItems))
            adapterItems = newItems
            diffResult.dispatchUpdatesTo(this)
        }

        override fun getItemCount(): Int = adapterItems.size

        override fun getItemId(position: Int): Long = adapterItems[position].itemId

        override fun containsItem(itemId: Long): Boolean =
            adapterItems.any { it.itemId == itemId }

        override fun createFragment(position: Int): Fragment {
            val item = adapterItems[position]
            val group = item.group
            onlyUpdateRead = group.onlyUpdateRead
            return BooksFragment(position, group).also { fragment ->
                fragment.structureVersion = structureVersion
                fragment.setTopOverlaySpace(topOverlaySpace, topOverlayEnabled)
                fragmentMap[group.groupId] = fragment
            }
        }

    }

    private fun Book.toShelfDisplay(): BookShelfDisplay {
        return BookShelfDisplay(
            bookUrl = bookUrl,
            origin = origin,
            originName = originName,
            name = name,
            author = author,
            intro = intro,
            customIntro = customIntro,
            customTag = customTag,
            coverUrl = coverUrl,
            customCoverUrl = customCoverUrl,
            type = type,
            group = group,
            latestChapterTitle = latestChapterTitle,
            latestChapterTime = latestChapterTime,
            lastCheckCount = lastCheckCount,
            totalChapterNum = totalChapterNum,
            durChapterTitle = durChapterTitle,
            durChapterIndex = durChapterIndex,
            durChapterTime = durChapterTime,
            canUpdate = canUpdate,
            order = order,
            readConfig = readConfig
        )
    }

    companion object {
        private const val STATE_CURRENT_GROUP_INDEX = "currentGroupIndex"
    }
}
