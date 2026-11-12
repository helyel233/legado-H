package io.legado.app.ui.main.homepage

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.databinding.ItemHomepageGridRankingPageBinding
import io.legado.app.databinding.ItemHomepageGridRankingRowBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.glass.applyWallpaperGlassBackground
import io.legado.app.utils.dpToPx

/**
 * 网格排行榜分页适配器：书籍按每页 5 行分页，页为圆角卡片，不足一行用等高占位补齐。
 */
class HomepageGridRankingPagerAdapter(
    context: Context,
    private val onClick: (HomepageBookItemUi) -> Unit,
    private val onLongClick: (HomepageBookItemUi) -> Unit,
) : RecyclerView.Adapter<HomepageGridRankingPagerAdapter.PageViewHolder>() {

    private val inflater = LayoutInflater.from(context)
    private val pages = mutableListOf<List<HomepageBookItemUi>>()

    fun submitPages(newPages: List<List<HomepageBookItemUi>>) {
        if (newPages == pages) return
        pages.clear()
        pages.addAll(newPages)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemHomepageGridRankingPageBinding.inflate(inflater, parent, false)
        applyPageCardSurface(binding.root)
        return PageViewHolder(binding.root as LinearLayout)
    }

    /**
     * 页卡再次挂到窗口时按签名重铺一次。
     *
     * 页面存活期间 holder 不重建：设置里改玻璃档 / 关背景图后回到首页（分源 Tab 的页
     * 由 ViewPager2 常驻，`offscreenPageLimit = 1`）时，只在 `onCreateViewHolder` 铺一次的
     * 卡面会停在旧材质上——solid 档仍显示玻璃。签名未变时
     * [applyWallpaperGlassBackground] 直接返回，同档滚动 / 翻页是零开销。
     */
    override fun onViewAttachedToWindow(holder: PageViewHolder) {
        super.onViewAttachedToWindow(holder)
        applyPageCardSurface(holder.itemView)
    }

    /**
     * 按签名重铺 [recyclerView] 中**已显示**页的卡面。
     *
     * 供宿主在回到前台时调用：ViewPager2 的页常驻（`offscreenPageLimit = 1`），改档 /
     * 关背景图后返回不会产生 attach 事件。签名未变时零开销。
     */
    fun refreshVisibleCardSurfaces(recyclerView: RecyclerView) {
        for (index in 0 until recyclerView.childCount) {
            applyPageCardSurface(recyclerView.getChildAt(index))
        }
    }

    /**
     * 页卡面的玻璃化：原卡面 `bg_homepage_card_20`（圆角 20dp）。
     *
     * 每页是一个独立 ViewHolder（`offscreenPageLimit = 1`，同时最多两页存活），
     * 卡面在创建与每次挂载时按签名重铺（见 [onViewAttachedToWindow]）；采样走壁纸域位图
     * + 位置跟随，横向翻页 / 外层竖向滚动都由 `GlassPositionTracker` 按屏幕坐标重采样，
     * **不录制本页或祖先子树**（pager 页是自身，录制它就是 1.25.8 那类重入）。
     * solid / 墨水屏 / 低内存 / 无壁纸由 `applyWallpaperGlassBackground` 逐值回退到原实底，
     * 回退时同时解绑旧的位置跟踪。
     */
    private fun applyPageCardSurface(page: View) {
        page.applyWallpaperGlassBackground(cornerRadiusPx = PAGE_RADIUS_DP.dpToPx()) {
            androidx.core.content.ContextCompat.getDrawable(
                page.context,
                io.legado.app.R.drawable.bg_homepage_card_20
            )
        }
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val page = pages.getOrNull(position) ?: return
        holder.container.removeAllViews()
        page.forEachIndexed { rowIndex, item ->
            holder.container.addView(
                bindRow(item, position * ROWS_PER_PAGE + rowIndex, holder.container)
            )
        }
        repeat(ROWS_PER_PAGE - page.size) {
            holder.container.addView(
                Space(holder.container.context).apply {
                    layoutParams = LinearLayout.LayoutParams(1, ROW_HEIGHT_DP.dpToPx())
                }
            )
        }
    }

    private fun bindRow(
        item: HomepageBookItemUi,
        rank: Int,
        parent: ViewGroup,
    ): View {
        val binding = ItemHomepageGridRankingRowBinding.inflate(inflater, parent, false)
        val book = item.book
        binding.tvRank.text = rank.toString()
        if (rank <= 3) {
            binding.tvRank.setTextColor(parent.context.accentColor)
            binding.tvRank.setTypeface(binding.tvRank.typeface, Typeface.BOLD_ITALIC)
        } else {
            binding.tvRank.setTextColor(parent.context.secondaryTextColor)
            binding.tvRank.setTypeface(binding.tvRank.typeface, Typeface.BOLD)
        }
        binding.tvName.text = book.name
        val subTitle = buildString {
            append(book.kind?.split(",")?.firstOrNull() ?: "")
            if (book.author.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(book.author)
            }
        }
        binding.tvSub.text = subTitle
        binding.tvSub.isVisible = subTitle.isNotBlank()
        binding.tvSub.alpha = 0.8f
        HomepageBookBadge.bind(binding.flBadge, binding.ivBadge, item)
        binding.ivCover.load(book, AppConfig.loadCoverOnlyWifi)
        binding.root.setOnClickListener { onClick(item) }
        binding.root.setOnLongClickListener {
            onLongClick(item)
            true
        }
        return binding.root
    }

    class PageViewHolder(val container: LinearLayout) : RecyclerView.ViewHolder(container)

    companion object {
        const val ROWS_PER_PAGE = 5
        const val ROW_HEIGHT_DP = 72

        /** 页高 = 5 行 + 页内上下 padding 24dp */
        const val PAGE_HEIGHT_DP = ROWS_PER_PAGE * ROW_HEIGHT_DP + 24

        /** 原页卡面 `bg_homepage_card_20` 的圆角（20dp）。 */
        private const val PAGE_RADIUS_DP = 20f
    }
}
