package io.legado.app.ui.main.homepage

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemHomepageBookBannerBinding
import io.legado.app.databinding.ItemHomepageBookCardBinding
import io.legado.app.databinding.ItemHomepageBookGridBinding
import io.legado.app.databinding.ItemHomepageBookWaterfallBinding
import io.legado.app.databinding.ItemHomepageRankingRowBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.glass.applyWallpaperGlassBackground
import io.legado.app.utils.StringUtils
import io.legado.app.utils.dpToPx

/**
 * 主页模块内嵌书籍列表适配器，按展示模式加载不同行布局，排版对齐 Max 版：
 * 排行榜 = 序号+封面+文字三段行；网格 = 纯封面+双行书名；卡片 = 120dp 圆角卡；
 * 瀑布流 = 圆角卡（封面/书名/作者/简介）；横幅 = 纯封面列。
 */
class HomepageBooksAdapter(
    context: Context,
    private val mode: Int,
    private val callBack: CallBack,
) : RecyclerAdapter<HomepageBookItemUi, ViewBinding>(context) {

    interface CallBack {
        fun onBookClick(book: SearchBook)

        fun onBookLongClick(book: SearchBook)
    }

    override fun getViewBinding(parent: ViewGroup): ViewBinding {
        return when (mode) {
            MODE_BANNER -> ItemHomepageBookBannerBinding.inflate(inflater, parent, false)
            MODE_RANK -> ItemHomepageRankingRowBinding.inflate(inflater, parent, false)
            MODE_CARD -> ItemHomepageBookCardBinding.inflate(inflater, parent, false)
            MODE_WATERFALL -> ItemHomepageBookWaterfallBinding.inflate(inflater, parent, false)
            else -> ItemHomepageBookGridBinding.inflate(inflater, parent, false)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val holder = super.onCreateViewHolder(parent, viewType)
        holder.itemView.applyBookCardSurface()
        return holder
    }

    /**
     * 行再次挂到窗口时校准一次卡面。
     *
     * 卡面只在 [onCreateViewHolder] 里铺一次，复用中的 ViewHolder 不会重建；玻璃档位 /
     * 强度 / 描边色 / 夜间模式 / 是否开启背景图都可能在这期间变化，签名未变时
     * [applyWallpaperGlassBackground] 是零开销的空操作。
     */
    override fun onViewAttachedToWindow(holder: ItemViewHolder) {
        super.onViewAttachedToWindow(holder)
        holder.itemView.applyBookCardSurface()
    }

    /**
     * 按签名重铺 [recyclerView] 中**已显示**书卡的卡面。
     *
     * 供宿主在回到前台时调用（见 `HomepageFragment.onResume`）：书卡在窗口上常驻，
     * 从设置页改档 / 关背景图返回时不会产生 attach 事件，只靠 [onViewAttachedToWindow]
     * 会漏，卡面就停在旧材质上（solid 档仍显示玻璃）。签名未变时零开销。
     */
    fun refreshVisibleCardSurfaces(recyclerView: RecyclerView) {
        for (index in 0 until recyclerView.childCount) {
            recyclerView.getChildAt(index).applyBookCardSurface()
        }
    }

    /**
     * 书籍卡片的卡面：**只有自带卡面的两种模式**参与，其余模式的行底是
     * `?selectableItemBackground`（按压涟漪）或干脆透明，套玻璃会把「行」变成「卡」，
     * 改变原有排版观感，因此逐模式显式列出，不做「统一套一层」。
     *
     * - [MODE_CARD]：120dp 竖卡，原卡面 `bg_homepage_book_card`（圆角 4dp）；
     * - [MODE_WATERFALL]：瀑布卡，原卡面 `bg_explore_book_grid_card`（圆角 8dp）。
     *
     * 采样走壁纸域位图 + 位置跟随（`applyWallpaperGlassBackground`），**不录制自身或
     * 祖先子树**：这些书卡处在「模块 RecyclerView → ViewPager2 → 外层 RecyclerView」
     * 的多层嵌套里，任何子树录制都会重入绘制。横向列表的采样坐标按卡片在屏幕上的
     * 实时位置换算，横滑 / 竖向滚动都由 `GlassPositionTracker` 重采样，不会错位。
     */
    private fun View.applyBookCardSurface() {
        val (radiusDp, cardRes) = when (mode) {
            MODE_CARD -> CARD_SURFACE_RADIUS_DP to R.drawable.bg_homepage_book_card
            MODE_WATERFALL -> WATERFALL_SURFACE_RADIUS_DP to R.drawable.bg_explore_book_grid_card
            else -> return
        }
        applyWallpaperGlassBackground(cornerRadiusPx = radiusDp.dpToPx()) {
            ContextCompat.getDrawable(context, cardRes)
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ViewBinding,
        item: HomepageBookItemUi,
        payloads: MutableList<Any>
    ) {
        when (binding) {
            is ItemHomepageRankingRowBinding -> bindRank(binding, item, holder.layoutPosition)
            is ItemHomepageBookBannerBinding -> bindBanner(binding, item)
            is ItemHomepageBookCardBinding -> bindCard(binding, item)
            is ItemHomepageBookWaterfallBinding -> bindWaterfall(binding, item)
            is ItemHomepageBookGridBinding -> bindGrid(binding, item)
        }
    }

    private fun bindRank(binding: ItemHomepageRankingRowBinding, item: HomepageBookItemUi, position: Int) {
        binding.run {
            val rank = position + 1
            tvRank.text = rank.toString()
            if (rank <= 3) {
                tvRank.setTextColor(context.accentColor)
                tvRank.setTypeface(tvRank.typeface, Typeface.BOLD_ITALIC)
            } else {
                tvRank.setTextColor(context.secondaryTextColor)
                tvRank.setTypeface(tvRank.typeface, Typeface.BOLD)
            }
            tvName.text = item.book.name
            val stat = buildString {
                val wc = StringUtils.wordCountFormat(item.book.wordCount)
                if (wc.isNotEmpty()) append(wc)
                if (item.book.author.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(item.book.author)
                }
            }
            tvStat.text = stat
            tvStat.isVisible = stat.isNotBlank()
            val kind = item.book.kind?.split(",")?.firstOrNull()
            tvKind.text = kind
            tvKind.isVisible = !kind.isNullOrBlank()
            val intro = item.book.intro?.takeIf { it.isNotBlank() }?.replace("\\s+".toRegex(), " ")
            tvIntro.text = intro
            tvIntro.isVisible = !intro.isNullOrBlank()
            HomepageBookBadge.bind(flBadge, ivBadge, item)
            ivCover.load(item.book, AppConfig.loadCoverOnlyWifi)
        }
    }

    private fun bindBanner(binding: ItemHomepageBookBannerBinding, item: HomepageBookItemUi) {
        binding.run {
            HomepageBookBadge.bind(flBadge, ivBadge, item)
            ivCover.load(item.book, AppConfig.loadCoverOnlyWifi)
        }
    }

    private fun bindGrid(binding: ItemHomepageBookGridBinding, item: HomepageBookItemUi) {
        binding.run {
            tvName.text = item.book.name
            HomepageBookBadge.bind(flBadge, ivBadge, item)
            ivCover.load(item.book, AppConfig.loadCoverOnlyWifi)
        }
    }

    private fun bindCard(binding: ItemHomepageBookCardBinding, item: HomepageBookItemUi) {
        binding.run {
            tvName.text = item.book.name
            val intro = item.book.intro?.takeIf { it.isNotBlank() }?.replace("\\s+".toRegex(), " ")
            tvIntro.text = intro
            tvIntro.isVisible = !intro.isNullOrBlank()
            HomepageBookBadge.bind(flBadge, ivBadge, item)
            ivCover.load(item.book, AppConfig.loadCoverOnlyWifi)
        }
    }

    private fun bindWaterfall(binding: ItemHomepageBookWaterfallBinding, item: HomepageBookItemUi) {
        binding.run {
            tvName.text = item.book.name
            tvAuthor.text = item.book.author
            val intro = item.book.intro?.takeIf { it.isNotBlank() }?.replace("\\s+".toRegex(), " ")
            tvIntro.text = intro
            tvIntro.isVisible = !intro.isNullOrBlank()
            HomepageBookBadge.bind(flBadge, ivBadge, item)
            ivCover.load(item.book, AppConfig.loadCoverOnlyWifi)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ViewBinding) {
        holder.itemView.setOnClickListener {
            getItemByLayoutPosition(holder.layoutPosition)?.let { item ->
                callBack.onBookClick(item.book)
            }
        }
        holder.itemView.setOnLongClickListener {
            getItemByLayoutPosition(holder.layoutPosition)?.let { item ->
                callBack.onBookLongClick(item.book)
            }
            true
        }
    }

    companion object {
        const val MODE_GRID = 0
        const val MODE_RANK = 1
        const val MODE_BANNER = 2
        const val MODE_CARD = 3
        const val MODE_WATERFALL = 4

        /** 卡片模式原卡面 `bg_homepage_book_card` 的圆角（4dp）。 */
        private const val CARD_SURFACE_RADIUS_DP = 4f

        /** 瀑布模式原卡面 `bg_explore_book_grid_card` 的圆角（8dp）。 */
        private const val WATERFALL_SURFACE_RADIUS_DP = 8f

        /** 纵向布局管理器 */
        fun verticalLayoutManager(context: Context): LinearLayoutManager =
            LinearLayoutManager(context)

        /** 横向布局管理器 */
        fun horizontalLayoutManager(context: Context): LinearLayoutManager =
            LinearLayoutManager(context).apply { orientation = LinearLayoutManager.HORIZONTAL }

        /** 网格布局管理器 */
        fun gridLayoutManager(context: Context, spanCount: Int): RecyclerView.LayoutManager =
            GridLayoutManager(context, spanCount)
    }
}
