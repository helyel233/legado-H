package io.legado.app.ui.main.homepage

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.ItemHomepageModuleBinding
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.lib.theme.accentColor
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.glass.GLASS_SIGNATURE_TAG
import io.legado.app.ui.glass.GlassSurface
import io.legado.app.ui.glass.applyWallpaperGlassBackground
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.visible

/**
 * 主页模块列表适配器：一个模块一个 item，内部按模块类型装配内嵌书籍列表、
 * 分类按钮组或排行榜多分类 Tab，并处理加载中/失败/加载更多等状态。
 * 排版对齐 Max 版：排行榜为序号卡片列表，网格排行榜为横向翻页卡片，
 * 按钮组为等宽卡片网格，加载中显示骨架屏。
 *
 * VH 缓存内嵌适配器，模块内容变化时只更新数据，不重建视图，保留滚动位置。
 */
class HomepageAdapter(
    context: Context,
    private val callBack: CallBack,
) : RecyclerView.Adapter<HomepageAdapter.ModuleViewHolder>() {

    interface CallBack {
        fun onBookClick(book: SearchBook)

        fun onBookLongClick(book: SearchBook)

        fun onModuleHeaderClick(module: HomepageModuleUi, exploreUrl: String?)

        fun onKindClick(module: HomepageModuleUi, kind: ExploreKind)

        fun onRetry(module: HomepageModuleUi)

        fun onLoadMore(module: HomepageModuleUi)

        fun onLoadMoreRankingTab(module: HomepageModuleUi, tabIndex: Int)

        fun onSelectRankingTab(module: HomepageModuleUi, index: Int)
    }

    private val inflater = LayoutInflater.from(context)
    private val appContext = context.applicationContext
    private val modules = mutableListOf<HomepageModuleUi>()

    /** 网格模块点过“加载更多”后展示全部已加载书籍，不再只取前 6 条 */
    private val gridMoreShown = mutableSetOf<String>()

    /** 无限网格随外层下滑自动加载：手势触发，ViewModel 侧防重入 */
    private val outerScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy <= 0) return
            val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
            val pos = lm.findLastVisibleItemPosition()
            if (pos == RecyclerView.NO_POSITION) return
            val module = modules.getOrNull(pos) ?: return
            if (module.type != HomepageModuleType.InfiniteGrid) return
            when (val state = module.state) {
                is ModuleLoadState.Loaded -> {
                    if (state.hasMore && !state.isLoadingMore) {
                        callBack.onLoadMore(module)
                    }
                }
                is ModuleLoadState.RankingTabs -> {
                    val tab = state.tabs.getOrNull(state.selectedIndex) ?: return
                    // 严格按 hasMore（空页即停），防自动加载死循环
                    if (tab.hasMore && !tab.isLoadingMore) {
                        callBack.onLoadMoreRankingTab(module, state.selectedIndex)
                    }
                }
                else -> return
            }
        }
    }

    /** 仅"加载中"状态翻转（书籍列表不变）时走 payload 轻量刷新，避免整块重绑 */
    private fun moduleDiffCallback(
        oldModules: List<HomepageModuleUi>,
        newModules: List<HomepageModuleUi>
    ): DiffUtil.Callback = object : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldModules.size
        override fun getNewListSize(): Int = newModules.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val old = oldModules.getOrNull(oldItemPosition) ?: return true
            val new = newModules.getOrNull(newItemPosition) ?: return true
            return old.globalId == new.globalId
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val old = oldModules.getOrNull(oldItemPosition) ?: return true
            val new = newModules.getOrNull(newItemPosition) ?: return true
            return old == new
        }

        override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? {
            val oldItem = oldModules.getOrNull(oldItemPosition) ?: return null
            val newItem = newModules.getOrNull(newItemPosition) ?: return null
            val oldLoaded = oldItem.state as? ModuleLoadState.Loaded
            val newLoaded = newItem.state as? ModuleLoadState.Loaded
            if (oldLoaded != null && newLoaded != null &&
                oldLoaded.books == newLoaded.books && oldLoaded.hasMore == newLoaded.hasMore &&
                oldLoaded.page == newLoaded.page &&
                oldLoaded.isLoadingMore != newLoaded.isLoadingMore
            ) {
                return LoadMorePayload(
                    module = newItem,
                    hasMore = newLoaded.hasMore,
                    isLoadingMore = newLoaded.isLoadingMore
                )
            }
            val oldTabs = oldItem.state as? ModuleLoadState.RankingTabs
            val newTabs = newItem.state as? ModuleLoadState.RankingTabs
            if (oldTabs != null && newTabs != null &&
                oldTabs.tabs == newTabs.tabs && oldTabs.selectedIndex == newTabs.selectedIndex
            ) {
                val oldTab = oldTabs.tabs.getOrNull(oldTabs.selectedIndex)
                val newTab = newTabs.tabs.getOrNull(newTabs.selectedIndex)
                if (oldTab != null && newTab != null &&
                    oldTab.books == newTab.books && oldTab.hasMore == newTab.hasMore &&
                    oldTab.isLoadingMore != newTab.isLoadingMore
                ) {
                    return LoadMorePayload(
                        module = newItem,
                        hasMore = newTab.hasMore,
                        isLoadingMore = newTab.isLoadingMore
                    )
                }
            }
            return null
        }
    }

    /** payload：只有加载更多状态变化，书籍列表内容未变 */
    private data class LoadMorePayload(
        val module: HomepageModuleUi,
        val hasMore: Boolean,
        val isLoadingMore: Boolean,
    )

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recyclerView.addOnScrollListener(outerScrollListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnScrollListener(outerScrollListener)
    }

    fun submitModules(list: List<HomepageModuleUi>) {
        if (list == modules) return
        val oldModules = modules.toList()
        val newModules = list.toList()
        modules.clear()
        modules.addAll(list)
        if (oldModules.isEmpty() || newModules.isEmpty()) {
            notifyDataSetChanged()
        } else {
            // detectMoves 保持 false：与原行为一致，模块重排走 remove+insert，不做位移动画
            DiffUtil.calculateDiff(moduleDiffCallback(oldModules, newModules))
                .dispatchUpdatesTo(this)
        }
    }

    override fun getItemCount(): Int = modules.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModuleViewHolder {
        val binding = ItemHomepageModuleBinding.inflate(inflater, parent, false)
        return ModuleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ModuleViewHolder, position: Int) {
        holder.bind(modules[position])
    }

    override fun onBindViewHolder(
        holder: ModuleViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        val payload = payloads.firstOrNull()
        if (payload != null && holder.handlePayload(payload)) {
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewAttachedToWindow(holder: ModuleViewHolder) {
        super.onViewAttachedToWindow(holder)
        // 模块 holder 是常驻的：设置里改玻璃档 / 关背景图后返回首页不会重新 bind
        // （boundKey 会拦掉同模块重绑，`submitModules` 的 diff 也可能认为内容没变），
        // 所以挂载时按签名重铺一次**当前已显示**的卡面。同档重复挂载零开销。
        holder.refreshCardSurfaces()
    }

    /**
     * 按签名重铺 [recyclerView] 中所有**当前已显示**模块的卡面。
     *
     * 供宿主在回到前台时调用（见 `HomepageFragment.onResume`）：从设置页返回时 View
     * 一直挂在窗口上，不会产生 attach 事件，只靠 [onViewAttachedToWindow] 会漏。
     * 不重绑内容、不重新请求数据、不碰内嵌适配器——只重铺卡面背景。
     */
    fun refreshVisibleCardSurfaces(recyclerView: RecyclerView) {
        for (index in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(index))
            (holder as? ModuleViewHolder)?.refreshCardSurfaces()
        }
    }

    inner class ModuleViewHolder(
        private val binding: ItemHomepageModuleBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var booksAdapter: HomepageBooksAdapter? = null
        private var tabsAdapter: HomepageRankingTabAdapter? = null
        private var gridRankAdapter: HomepageGridRankingPagerAdapter? = null
        private var boundKey: String? = null
        private var currentModule: HomepageModuleUi? = null
        private var expandedForModule: String? = null
        private var currentPagerKey: String? = null
        private var pagerLoadAction: (() -> Unit)? = null
        private val pagerPositions = mutableMapOf<String, Int>()

        /**
         * `llRankCard` 当前以哪种形态显示：true = 榜单卡（`rankStyle = true`，有卡面），
         * false = 其它形态复用同一容器（无卡面），null = 容器隐藏。
         *
         * 改档后重铺（[refreshCardSurfaces]）要靠它区分「该铺卡面」与「该保持无背景」，
         * 不能只看 View 可见性——`rankStyle = false` 时容器同样是可见的。
         */
        private var rankCardStyled: Boolean? = null

        /** 横幅/卡片横滑到最右时的自动加载动作，其他类型保持 null */
        private var hEndAction: (() -> Unit)? = null

        private val pagerCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val key = currentPagerKey ?: return
                pagerPositions[key] = position
                val pageCount = gridRankAdapter?.itemCount ?: 0
                if (pageCount > 1 && position >= pageCount - 1) {
                    pagerLoadAction?.invoke()
                }
            }
        }

        init {
            binding.vpGridRanking.registerOnPageChangeCallback(pagerCallback)
            // 横滑列表右滑到头且是用户手势时触发自动加载（dx > 0 防绑定后连锁自加载）
            binding.rvContent.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dx > 0 && !recyclerView.canScrollHorizontally(1)) {
                        hEndAction?.invoke()
                    }
                }
            })
        }

        fun bind(module: HomepageModuleUi) {
            if (currentModule?.globalId != module.globalId) {
                expandedForModule = null
            }
            currentModule = module
            binding.tvTitle.text = module.title
            // 模块标题区无卡片底：背景图模式下加自适应投影保证可读，普通主题无投影
            if (ThemeConfig.hasUsableBgImage(appContext)) {
                binding.tvTitle.setShadowLayer(
                    4f, 1f, 1f,
                    if (ColorUtils.calculateLuminance(appContext.primaryTextColor) > 0.5) {
                        0xB3000000.toInt()
                    } else {
                        0xB3FFFFFF.toInt()
                    }
                )
            }
            binding.tvRetry.setTextColor(appContext.accentColor)
            if (module.type == HomepageModuleType.ButtonGroup) {
                binding.llTitle.isClickable = false
                binding.llTitle.setOnClickListener(null)
            } else {
                binding.llTitle.setOnClickListener {
                    val target = currentModule ?: return@setOnClickListener
                    callBack.onModuleHeaderClick(target, headerExploreUrl(target))
                }
            }
            // Tab 行默认隐藏，仅由 bindRankingTabs 显示，避免各内容绑定路径误伤；
            // 横滑到底自动加载动作同样按次绑定，入口先清掉防 holder 复用残留；
            // 标题行默认显示，多 tab 时由 bindRankingTabs 藏掉只留 tab
            binding.llTabs.gone()
            binding.llTitle.visible()
            hEndAction = null
            when (val state = module.state) {
                is ModuleLoadState.Loading -> bindLoading(module)
                is ModuleLoadState.Error -> bindError(module)
                is ModuleLoadState.Buttons -> bindButtons(module, state)
                is ModuleLoadState.Loaded -> bindLoaded(module, state)
                is ModuleLoadState.RankingTabs -> bindRankingTabs(module, state)
            }
        }

        /** payload 轻量刷新：书籍列表不变，仅"加载更多"状态翻转；非本类 payload 返回 false 走全量绑定 */
        fun handlePayload(payload: Any): Boolean {
            if (payload !is LoadMorePayload) return false
            updateLoadMore(payload)
            return true
        }

        private fun updateLoadMore(payload: LoadMorePayload) {
            val module = payload.module
            val onLoadMore = {
                val state = module.state
                if (state is ModuleLoadState.RankingTabs) {
                    callBack.onLoadMoreRankingTab(module, state.selectedIndex)
                } else {
                    callBack.onLoadMore(module)
                }
            }
            when (module.type) {
                // 横向翻页排行榜：只更新自动加载动作的可用性
                HomepageModuleType.GridRanking -> {
                    pagerLoadAction =
                        if (payload.hasMore && !payload.isLoadingMore) onLoadMore else null
                    binding.pbLoadMore.isVisible = false
                }
                // 网格/排行榜等带底部加载条的形态：只刷新加载条
                HomepageModuleType.Grid,
                HomepageModuleType.Waterfall,
                HomepageModuleType.Ranking,
                -> bindLoadMore(payload.hasMore, payload.isLoadingMore, onLoadMore)
                // 横幅/卡片/无限网格：无底部加载条，自动加载动作不随 isLoadingMore 变化
                else -> Unit
            }
        }

        /** 标题跳转 URL：排行榜多 Tab 模块取当前 Tab 的发现地址 */
        private fun headerExploreUrl(module: HomepageModuleUi): String? {
            val tabs = module.state as? ModuleLoadState.RankingTabs
            return tabs?.tabs?.getOrNull(tabs.selectedIndex)?.exploreUrl ?: module.exploreUrl
        }

        private fun hideAll() {
            binding.llTabs.gone()
            binding.flButtons.gone()
            binding.llRankCard.gone()
            rankCardStyled = null
            binding.vpGridRanking.gone()
            binding.llExpand.gone()
            binding.flSkeleton.gone()
            binding.llError.gone()
            binding.llLoadMore.gone()
            // 这里不解绑位置跟踪：llRankCard/llError 的玻璃背景与签名 tag 都还在，
            // 下次形态切换会经 clearRankCardSurface（解绑+清 tag）或带签名的重铺自愈；
            // 只解绑不清 tag 会让重绑早退，玻璃背景在场却没人驱动采样
        }

        private fun bindLoading(module: HomepageModuleUi) {
            hideAll()
            binding.flSkeleton.visible()
            buildSkeleton(module.type, binding.flSkeleton)
        }

        private fun bindError(module: HomepageModuleUi) {
            hideAll()
            binding.llError.visible()
            applyErrorCardSurface()
            binding.llError.setOnClickListener { callBack.onRetry(module) }
        }

        /**
         * 错误卡卡面：原卡面 `bg_homepage_card_12`（圆角 12dp），壁纸可见时让位给玻璃，
         * 否则铺回同一张实底 drawable（solid / 墨水屏 / 低内存 / 无壁纸）。
         *
         * 抽成方法是因为它在两条路径上都要跑：首次 bind，以及改档 / 关背景图后回到首页
         * 的重铺（[refreshCardSurfaces]）。
         */
        private fun applyErrorCardSurface() {
            binding.llError.applyWallpaperGlassBackground(
                cornerRadiusPx = ERROR_CARD_RADIUS_DP.dpToPx()
            ) {
                ContextCompat.getDrawable(appContext, R.drawable.bg_homepage_card_12)
            }
        }

        /**
         * 榜单卡卡面：原卡面 `bg_homepage_card_16`（圆角 16dp），壁纸可见时让位给玻璃，
         * 卡内的排行行本身是透明的，玻璃直接透到行文字后面。
         */
        private fun applyRankCardSurface() {
            binding.llRankCard.applyWallpaperGlassBackground(
                cornerRadiusPx = RANK_CARD_RADIUS_DP.dpToPx()
            ) {
                ContextCompat.getDrawable(appContext, R.drawable.bg_homepage_card_16)
            }
            binding.llRankCard.setPadding(0, 12.dpToPx(), 0, 0)
        }

        /**
         * 同一 ViewHolder 会在「排行榜 / 网格 / 瀑布」等形态间复用：清掉背景的同时必须
         * 清掉玻璃签名 tag，否则切回榜单形态时签名未变会被判成「已经铺过」而跳过铺底。
         */
        private fun clearRankCardSurface() {
            GlassSurface.releaseBackgroundTracking(binding.llRankCard)
            binding.llRankCard.background = null
            binding.llRankCard.setTag(GLASS_SIGNATURE_TAG, null)
            binding.llRankCard.setPadding(0, 0, 0, 0)
        }

        /**
         * 只重铺「此刻已显示」的卡面：不重绑内容、不重新请求数据、不动内嵌适配器。
         *
         * 依据是绑定期记下的 UI 事实（[rankCardStyled] + 可见性），而不是再跑一遍状态机
         * ——重跑状态机会和 holder 里已有的内容状态分叉。签名未变时
         * [applyWallpaperGlassBackground] 直接返回，所以重复调用（每次 attach + 每次
         * onResume）是零开销的。
         */
        internal fun refreshCardSurfaces() {
            when (rankCardStyled) {
                // 榜单卡形态：按签名重铺卡面（solid / 无壁纸时逐值回退到原实底）
                true -> applyRankCardSurface()
                // 其它形态复用同一容器：只保证背景与签名 tag 被清掉，
                // 不能把已经隐藏的卡面重新铺回来
                false -> clearRankCardSurface()
                null -> Unit
            }
            if (binding.llError.isVisible) applyErrorCardSurface()
            // 内嵌的卡片在各自适配器里（多层 RecyclerView / ViewPager2）：它们同样是常驻的，
            // 回到前台不会重新 attach，所以从宿主这一层一起推下去
            if (binding.rvContent.isVisible) {
                booksAdapter?.refreshVisibleCardSurfaces(binding.rvContent)
            }
            if (binding.vpGridRanking.isVisible) {
                (binding.vpGridRanking.getChildAt(0) as? RecyclerView)?.let { pages ->
                    gridRankAdapter?.refreshVisibleCardSurfaces(pages)
                }
            }
        }

        private fun bindButtons(module: HomepageModuleUi, state: ModuleLoadState.Buttons) {
            hideAll()
            binding.flButtons.visible()
            // 多选（多个按钮）时隐藏顶部静态标题，按钮本身即头
            binding.llTitle.isVisible = state.kinds.size < 2
            binding.flButtons.removeAllViews()
            val kinds = state.kinds
            if (kinds.isEmpty()) return
            val maxColumns = 5
            val numRows = (kinds.size + maxColumns - 1) / maxColumns
            val actualColumns = (kinds.size + numRows - 1) / numRows
            kinds.forEach { kind ->
                val cell = TextView(appContext).apply {
                    text = kind.title
                    gravity = Gravity.CENTER
                    textSize = 11f
                    setTextColor(appContext.primaryTextColor)
                    setBackgroundResource(R.drawable.bg_explore_book_grid_card)
                    setPadding(4.dpToPx(), 12.dpToPx(), 4.dpToPx(), 12.dpToPx())
                    setOnClickListener { callBack.onKindClick(module, kind) }
                }
                val lp = FlexboxLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                // 横向等宽：basis 必须先扣除横向 margin，否则 basis+margin 超出行宽被迫换行；
                // 容器宽 = 屏幕宽 - 模块左右 padding（16dp×2），每格 basis = W/N - 左右 margin，
                // 再减 1px 防浮点取整溢出，余量由 flexGrow 均分吃掉，保证同行等宽占满
                val marginH = 4.dpToPx()
                val containerPx =
                    appContext.resources.displayMetrics.widthPixels - 32.dpToPx()
                lp.flexBasisPercent =
                    (containerPx / actualColumns - marginH * 2 - 1).toFloat() / containerPx
                lp.flexGrow = 1f
                lp.setMargins(marginH, 4.dpToPx(), marginH, 4.dpToPx())
                binding.flButtons.addView(cell, lp)
            }
        }

        private fun bindLoaded(module: HomepageModuleUi, state: ModuleLoadState.Loaded) {
            if (module.type == HomepageModuleType.GridRanking) {
                bindGridRanking(
                    books = state.books,
                    hasMore = state.hasMore,
                    isLoadingMore = state.isLoadingMore,
                    tabKey = null,
                    onLoadMore = { callBack.onLoadMore(module) },
                )
                return
            }
            if (module.type == HomepageModuleType.Ranking) {
                showRankCard(rankStyle = true)
                ensureBooksAdapter(HomepageModuleType.Ranking)
                booksAdapter?.setItems(state.books.take(rankLimit(module)))
                bindExpandFooter(module, state.books)
                bindLoadMore(state.hasMore, state.isLoadingMore) {
                    callBack.onLoadMore(module)
                }
                return
            }
            bindTypeContent(
                module = module,
                books = state.books,
                hasMore = state.hasMore,
                isLoadingMore = state.isLoadingMore,
                moreKey = module.globalId,
                onLoadMore = { callBack.onLoadMore(module) },
            )
        }

        /**
         * 排行榜/网格排行以外的内容形态（网格/瀑布流/卡片/横幅/无限网格）：
         * 单 URL（Loaded）与多分类 tab 共用，加载动作由调用方传入单页或 tab 版。
         */
        private fun bindTypeContent(
            module: HomepageModuleUi,
            books: List<HomepageBookItemUi>,
            hasMore: Boolean,
            isLoadingMore: Boolean,
            moreKey: String,
            onLoadMore: () -> Unit,
        ) {
            val isHorizontal = module.type == HomepageModuleType.Card ||
                    module.type == HomepageModuleType.Banner
            val isInfinite = module.type == HomepageModuleType.InfiniteGrid
            showRankCard(rankStyle = false)
            ensureBooksAdapter(module.type)
            // 网格首屏只取 6 条，点过加载更多后展示全部已加载（否则按钮形同虚设）
            val shown = if (module.type == HomepageModuleType.Grid &&
                !gridMoreShown.contains(moreKey)
            ) {
                books.take(GRID_MAX_ITEMS)
            } else {
                books
            }
            booksAdapter?.setItems(shown)
            binding.llExpand.gone()
            when {
                // 横幅/卡片横滑到头自动加载，无限网格随下滑自动加载，都不要底部按钮
                isHorizontal || isInfinite -> binding.llLoadMore.gone()
                else -> bindLoadMore(hasMore, isLoadingMore) {
                    if (module.type == HomepageModuleType.Grid) {
                        gridMoreShown.add(moreKey)
                    }
                    onLoadMore()
                }
            }
            if (isHorizontal) {
                hEndAction = { onLoadMore() }
            }
        }

        private fun bindRankingTabs(module: HomepageModuleUi, state: ModuleLoadState.RankingTabs) {
            if (tabsAdapter == null || binding.rvTabs.adapter == null) {
                tabsAdapter = HomepageRankingTabAdapter(appContext) { index ->
                    currentModule?.let { callBack.onSelectRankingTab(it, index) }
                }
                binding.rvTabs.adapter = tabsAdapter
                binding.rvTabs.layoutManager =
                    HomepageBooksAdapter.horizontalLayoutManager(appContext)
            }
            tabsAdapter?.submitItems(state.tabs, state.selectedIndex)

            val currentTab = state.tabs.getOrNull(state.selectedIndex)
            val tabBooks = currentTab?.books ?: emptyList()
            val tabHasMore = currentTab?.hasMore == true
            val tabLoading = currentTab?.isLoadingMore == true
            val onTabLoadMore = { callBack.onLoadMoreRankingTab(module, state.selectedIndex) }

            if (module.type == HomepageModuleType.GridRanking) {
                bindGridRanking(
                    books = tabBooks,
                    hasMore = tabHasMore,
                    isLoadingMore = tabLoading,
                    tabKey = currentTab?.title,
                    onLoadMore = onTabLoadMore,
                )
            } else if (module.type == HomepageModuleType.Ranking) {
                showRankCard(rankStyle = true)
                ensureBooksAdapter(HomepageModuleType.Ranking)
                booksAdapter?.setItems(tabBooks.take(rankLimit(module)))
                bindExpandFooter(module, tabBooks)
                binding.llLoadMore.gone()
            } else {
                // 网格/瀑布流/卡片/横幅/无限网格的多分类：当前 tab 内容按本类型形态渲染
                bindTypeContent(
                    module = module,
                    books = tabBooks,
                    hasMore = tabHasMore,
                    isLoadingMore = tabLoading,
                    moreKey = "${module.globalId}#${state.selectedIndex}",
                    onLoadMore = onTabLoadMore,
                )
            }

            // Tab 行在内容绑定之后显示：显隐只由本方法管理；
            // 多分类时隐藏顶部静态标题只留 tab 行（跳转走 tab 行右侧箭头），
            // 单分类时反过来：显示静态标题、隐藏 tab 行（跳转走标题行）
            binding.llTabs.isVisible = state.tabs.size >= 2
            binding.llTitle.isVisible = state.tabs.size < 2
            binding.ivTabArrow.isVisible = currentTab?.exploreUrl != null
            binding.ivTabArrow.setOnClickListener {
                currentTab?.let { tab -> callBack.onModuleHeaderClick(module, tab.exploreUrl) }
            }
        }

        private fun rankLimit(module: HomepageModuleUi): Int {
            return if (expandedForModule == module.globalId) RANK_MAX_COUNT else RANK_INITIAL_COUNT
        }

        /** 排行榜展开/收起 footer：超过 5 条显示，最多展开到 20 条 */
        private fun bindExpandFooter(module: HomepageModuleUi, all: List<HomepageBookItemUi>) {
            if (all.size <= RANK_INITIAL_COUNT) {
                binding.llExpand.gone()
                return
            }
            binding.llExpand.visible()
            val expanded = expandedForModule == module.globalId
            binding.tvExpand.setText(
                if (expanded) R.string.homepage_collapse else R.string.homepage_expand_all
            )
            val color = if (expanded) {
                appContext.secondaryTextColor
            } else {
                appContext.accentColor
            }
            binding.tvExpand.setTextColor(color)
            binding.ivExpand.setColorFilter(color)
            binding.ivExpand.rotation = if (expanded) 180f else 0f
            binding.llExpand.setOnClickListener {
                expandedForModule = if (expanded) null else module.globalId
                val nowExpanded = expandedForModule == module.globalId
                booksAdapter?.setItems(
                    all.take(if (nowExpanded) RANK_MAX_COUNT else RANK_INITIAL_COUNT)
                )
                bindExpandFooter(module, all)
            }
        }

        /** 网格排行榜：横向翻页卡片，右缘露出下一页，翻到最后一页自动加载更多 */
        private fun bindGridRanking(
            books: List<HomepageBookItemUi>,
            hasMore: Boolean,
            isLoadingMore: Boolean,
            tabKey: String?,
            onLoadMore: () -> Unit,
        ) {
            binding.llRankCard.gone()
            rankCardStyled = null
            binding.flSkeleton.gone()
            binding.llError.gone()
            binding.flButtons.gone()
            binding.llExpand.gone()
            binding.llLoadMore.gone()
            binding.vpGridRanking.visible()

            if (gridRankAdapter == null || binding.vpGridRanking.adapter == null) {
                gridRankAdapter = HomepageGridRankingPagerAdapter(
                    // 同上：页卡面要玻璃化，context 必须是 Activity（能回溯到 BaseActivity）
                    binding.vpGridRanking.context,
                    { item -> callBack.onBookClick(item.book) },
                    { item -> callBack.onBookLongClick(item.book) },
                )
                binding.vpGridRanking.adapter = gridRankAdapter
                binding.vpGridRanking.offscreenPageLimit = 1
                binding.vpGridRanking.layoutParams.height =
                    HomepageGridRankingPagerAdapter.PAGE_HEIGHT_DP.dpToPx()
                binding.vpGridRanking.requestLayout()
                binding.vpGridRanking.setPageTransformer(
                    MarginPageTransformer(PAGE_SPACING_DP.dpToPx())
                )
                (binding.vpGridRanking.getChildAt(0) as? RecyclerView)?.let { rv ->
                    rv.clipToPadding = false
                    rv.setPadding(0, 0, PEEK_DP.dpToPx(), 0)
                }
            }
            currentPagerKey = tabKey ?: KEY_SINGLE
            pagerLoadAction = if (hasMore && !isLoadingMore) onLoadMore else null
            gridRankAdapter?.submitPages(
                books.chunked(HomepageGridRankingPagerAdapter.ROWS_PER_PAGE)
            )
            binding.vpGridRanking.setCurrentItem(pagerPositions[currentPagerKey] ?: 0, false)
        }

        private fun showRankCard(rankStyle: Boolean) {
            binding.flSkeleton.gone()
            binding.llError.gone()
            binding.vpGridRanking.gone()
            binding.flButtons.gone()
            binding.llExpand.gone()
            binding.llRankCard.visible()
            rankCardStyled = rankStyle
            if (rankStyle) {
                applyRankCardSurface()
            } else {
                clearRankCardSurface()
            }
        }

        private fun bindLoadMore(hasMore: Boolean, isLoadingMore: Boolean, action: () -> Unit) {
            binding.llLoadMore.isVisible = hasMore || isLoadingMore
            binding.pbLoadMore.isVisible = isLoadingMore
            binding.tvLoadMore.setText(
                if (isLoadingMore) R.string.homepage_loading else R.string.homepage_load_more
            )
            binding.ivLoadMore.isVisible = !isLoadingMore
            if (hasMore && !isLoadingMore) {
                binding.llLoadMore.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6.dpToPx().toFloat()
                    setColor(ColorUtils.setAlphaComponent(appContext.accentColor, 20))
                    setStroke(1.dpToPx(), ColorUtils.setAlphaComponent(appContext.accentColor, 38))
                }
                binding.tvLoadMore.setTextColor(appContext.accentColor)
                binding.ivLoadMore.setColorFilter(appContext.accentColor)
            } else {
                binding.llLoadMore.background = null
                binding.tvLoadMore.setTextColor(appContext.secondaryTextColor)
                binding.ivLoadMore.setColorFilter(appContext.secondaryTextColor)
                // 无底态下文字直接压背景图：背景图模式加自适应投影
                if (ThemeConfig.hasUsableBgImage(appContext)) {
                    binding.tvLoadMore.setShadowLayer(
                        4f, 1f, 1f,
                        if (ColorUtils.calculateLuminance(appContext.secondaryTextColor) > 0.5) {
                            0xB3000000.toInt()
                        } else {
                            0xB3FFFFFF.toInt()
                        }
                    )
                }
            }
            binding.llLoadMore.setOnClickListener { if (!isLoadingMore && hasMore) action() }
        }

        /** 按模块类型生成加载占位骨架屏 */
        private fun buildSkeleton(type: HomepageModuleType, parent: FrameLayout) {
            parent.removeAllViews()
            val ctx = parent.context
            fun box(): View = View(ctx).apply {
                setBackgroundResource(R.drawable.bg_homepage_skeleton)
            }
            fun vlp(w: Int, h: Int): LinearLayout.LayoutParams =
                LinearLayout.LayoutParams(w, h)

            when (type) {
                HomepageModuleType.Banner -> {
                    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    repeat(4) {
                        row.addView(box(), vlp(96.dpToPx(), 128.dpToPx()).apply {
                            marginEnd = 12.dpToPx()
                        })
                    }
                    parent.addView(row)
                }

                HomepageModuleType.Card -> {
                    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    repeat(3) {
                        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                        col.addView(box(), vlp(120.dpToPx(), 160.dpToPx()))
                        col.addView(
                            box(),
                            vlp(120.dpToPx(), 14.dpToPx()).apply { topMargin = 8.dpToPx() }
                        )
                        row.addView(
                            col,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { marginEnd = 12.dpToPx() }
                        )
                    }
                    parent.addView(row)
                }

                HomepageModuleType.Grid, HomepageModuleType.InfiniteGrid -> {
                    val outer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                    repeat(2) {
                        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                        repeat(3) {
                            val cell = LinearLayout(ctx).apply {
                                orientation = LinearLayout.VERTICAL
                            }
                            cell.addView(
                                box(),
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, 110.dpToPx()
                                )
                            )
                            cell.addView(
                                box(),
                                vlp(
                                    ViewGroup.LayoutParams.MATCH_PARENT, 12.dpToPx()
                                ).apply { topMargin = 4.dpToPx() }
                            )
                            row.addView(
                                cell,
                                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                    .apply { marginEnd = 12.dpToPx() }
                            )
                        }
                        outer.addView(
                            row,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = 12.dpToPx() }
                        )
                    }
                    parent.addView(outer)
                }

                HomepageModuleType.Ranking -> {
                    val card = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundResource(R.drawable.bg_homepage_card_16)
                        setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 4.dpToPx())
                    }
                    repeat(5) {
                        val row = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        row.addView(
                            box(),
                            vlp(18.dpToPx(), 18.dpToPx()).apply { marginEnd = 14.dpToPx() }
                        )
                        row.addView(
                            box(),
                            vlp(52.dpToPx(), 69.dpToPx()).apply { marginEnd = 8.dpToPx() }
                        )
                        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                        texts.addView(
                            box(),
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, 14.dpToPx()
                            )
                        )
                        texts.addView(
                            box(),
                            vlp(120.dpToPx(), 10.dpToPx()).apply { topMargin = 4.dpToPx() }
                        )
                        row.addView(
                            texts,
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        )
                        card.addView(
                            row,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = 8.dpToPx() }
                        )
                    }
                    parent.addView(card)
                }

                HomepageModuleType.GridRanking -> {
                    val card = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundResource(R.drawable.bg_homepage_card_20)
                        setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 4.dpToPx())
                    }
                    repeat(4) {
                        val row = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        row.addView(
                            box(),
                            vlp(48.dpToPx(), 64.dpToPx()).apply { marginEnd = 8.dpToPx() }
                        )
                        row.addView(
                            box(),
                            vlp(18.dpToPx(), 18.dpToPx()).apply { marginEnd = 4.dpToPx() }
                        )
                        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                        texts.addView(
                            box(),
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, 14.dpToPx()
                            )
                        )
                        texts.addView(
                            box(),
                            vlp(140.dpToPx(), 10.dpToPx()).apply { topMargin = 4.dpToPx() }
                        )
                        row.addView(
                            texts,
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        )
                        card.addView(
                            row,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = 8.dpToPx() }
                        )
                    }
                    parent.addView(card)
                }

                HomepageModuleType.Waterfall -> {
                    val outer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                    repeat(2) {
                        val card = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            setBackgroundResource(R.drawable.bg_explore_book_grid_card)
                            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
                        }
                        card.addView(
                            box(),
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, 180.dpToPx()
                            )
                        )
                        card.addView(
                            box(),
                            vlp(
                                ViewGroup.LayoutParams.MATCH_PARENT, 14.dpToPx()
                            ).apply { topMargin = 8.dpToPx() }
                        )
                        card.addView(
                            box(),
                            vlp(150.dpToPx(), 10.dpToPx()).apply { topMargin = 6.dpToPx() }
                        )
                        outer.addView(
                            card,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = 8.dpToPx() }
                        )
                    }
                    parent.addView(outer)
                }

                HomepageModuleType.ButtonGroup, HomepageModuleType.Unknown -> Unit
            }
        }

        private fun ensureBooksAdapter(type: HomepageModuleType) {
            val key = moduleKey(type)
            if (booksAdapter != null && binding.rvContent.adapter != null && boundKey == key) {
                return
            }
            boundKey = key
            booksAdapter = HomepageBooksAdapter(
                // 用承载内嵌列表的视图 context（Activity）而不是 appContext：
                // 玻璃采样要沿 ContextWrapper 链找到 BaseActivity 才能判「背后有没有壁纸」，
                // Application context 链上没有 Activity，玻璃会恒判不可用
                binding.rvContent.context,
                modeOf(type),
                object :
                    HomepageBooksAdapter.CallBack {
                    override fun onBookClick(book: SearchBook) {
                        callBack.onBookClick(book)
                    }

                    override fun onBookLongClick(book: SearchBook) {
                        callBack.onBookLongClick(book)
                    }
                }).also { adapter ->
                binding.rvContent.adapter = adapter
                binding.rvContent.layoutManager = layoutManagerOf(type)
            }
        }

        private fun moduleKey(type: HomepageModuleType): String = when (type) {
            HomepageModuleType.Grid -> "grid"
            HomepageModuleType.InfiniteGrid -> "grid"
            HomepageModuleType.Waterfall -> "waterfall"
            HomepageModuleType.Card -> "card"
            HomepageModuleType.Banner -> "banner"
            else -> "rank"
        }

        private fun modeOf(type: HomepageModuleType): Int = when (type) {
            HomepageModuleType.Grid, HomepageModuleType.InfiniteGrid -> HomepageBooksAdapter.MODE_GRID
            HomepageModuleType.Waterfall -> HomepageBooksAdapter.MODE_WATERFALL
            HomepageModuleType.Card -> HomepageBooksAdapter.MODE_CARD
            HomepageModuleType.Banner -> HomepageBooksAdapter.MODE_BANNER
            else -> HomepageBooksAdapter.MODE_RANK
        }

        private fun layoutManagerOf(type: HomepageModuleType): RecyclerView.LayoutManager =
            when (type) {
                HomepageModuleType.Grid, HomepageModuleType.InfiniteGrid ->
                    HomepageBooksAdapter.gridLayoutManager(appContext, GRID_SPAN)
                HomepageModuleType.Waterfall ->
                    androidx.recyclerview.widget.StaggeredGridLayoutManager(
                        WATERFALL_SPAN,
                        androidx.recyclerview.widget.StaggeredGridLayoutManager.VERTICAL
                    )
                HomepageModuleType.Card, HomepageModuleType.Banner ->
                    HomepageBooksAdapter.horizontalLayoutManager(appContext)
                else -> HomepageBooksAdapter.verticalLayoutManager(appContext)
            }
    }

    companion object {
        private const val GRID_SPAN = 3
        private const val WATERFALL_SPAN = 2
        private const val GRID_MAX_ITEMS = 6
        private const val RANK_INITIAL_COUNT = 5
        private const val RANK_MAX_COUNT = 20

        /** 榜单卡原卡面 `bg_homepage_card_16` 的圆角（16dp）。 */
        private const val RANK_CARD_RADIUS_DP = 16f

        /** 错误卡原卡面 `bg_homepage_card_12` 的圆角（12dp）。 */
        private const val ERROR_CARD_RADIUS_DP = 12f

        /** 网格排行榜翻页：页间距与右缘露出宽度（dp） */
        private const val PAGE_SPACING_DP = 12
        private const val PEEK_DP = 100
        private const val KEY_SINGLE = "single"
    }
}
