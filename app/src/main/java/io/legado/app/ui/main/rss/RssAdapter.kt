package io.legado.app.ui.main.rss

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ItemRssBinding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.utils.dpToPx
import splitties.views.onLongClick

class RssAdapter(
    context: Context,
    private val fragment: Fragment,
    private val callBack: CallBack,
    private val lifecycle: Lifecycle
) : RecyclerAdapter<RssSource, ItemRssBinding>(context) {

    private var modernMenuPopup: ModernActionPopup.Handle? = null

    /**
     * 当前列数，多列时改用「图标在上、名称在下」的布局，保证名称有足够宽度显示全名
     */
    private var columns = 1
    private val listConstraints: ConstraintSet by lazy { buildListConstraints() }
    private val gridConstraints: ConstraintSet by lazy { buildGridConstraints() }

    @SuppressLint("NotifyDataSetChanged")
    fun setColumns(count: Int) {
        val normalized = count.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        if (columns == normalized) return
        columns = normalized
        notifyDataSetChanged()
    }

    override fun getViewBinding(parent: ViewGroup): ItemRssBinding {
        return ItemRssBinding.inflate(inflater, parent, false).apply {
            root.applyUiBodyTypefaceDeep(context.uiTypeface())
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssBinding,
        item: RssSource,
        payloads: MutableList<Any>
    ) {
        binding.apply {
            applyItemLayout()
            tvName.text = item.sourceName
            val options = RequestOptions()
                .set(OkHttpModelLoader.sourceOriginOption, item.sourceUrl)
            ImageLoader.load(fragment, lifecycle, item.sourceIcon)
                .apply(options)
                .centerCrop()
                .placeholder(R.drawable.image_rss)
                .error(R.drawable.image_rss)
                .into(ivIcon)
        }
    }

    /**
     * 单列时图标在左、名称在右；多列时图标在上、名称在下并占满整格宽度，避免名称被压缩后截断
     */
    private fun ItemRssBinding.applyItemLayout() {
        val multiColumn = columns > 1
        val style = if (multiColumn) STYLE_GRID else STYLE_LIST
        // 标签记录具体列数而非样式：列数在 2↔3 间切换时样式不变但文字大小不同，不能早退
        if ((root.getTag(R.id.rss_item_layout_style) as? Int) == columns) return
        root.setTag(R.id.rss_item_layout_style, columns)
        if (multiColumn) {
            gridConstraints.applyTo(root)
        } else {
            listConstraints.applyTo(root)
        }
        ivMore.isVisible = !multiColumn
        tvName.apply {
            gravity = if (multiColumn) Gravity.CENTER else Gravity.CENTER_VERTICAL
            maxLines = if (multiColumn) 3 else 2
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (columns > 2) 13f else 14f)
        }
        val hPadding: Int
        val vPadding: Int
        val hMargin: Int
        val vMargin: Int
        if (multiColumn) {
            hPadding = 6.dpToPx()
            vPadding = 10.dpToPx()
            hMargin = 5.dpToPx()
            vMargin = 5.dpToPx()
            root.minimumHeight = 0
        } else {
            hPadding = 14.dpToPx()
            vPadding = 12.dpToPx()
            hMargin = 12.dpToPx()
            vMargin = 6.dpToPx()
            root.minimumHeight = 72.dpToPx()
        }
        root.setPadding(hPadding, vPadding, hPadding, vPadding)
        (root.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
            setMarginStart(hMargin)
            setMarginEnd(hMargin)
            topMargin = vMargin
            bottomMargin = vMargin
        }
        root.requestLayout()
    }

    private fun buildListConstraints(): ConstraintSet = ConstraintSet().apply {
        val iconSize = 44.dpToPx()
        constrainWidth(R.id.iv_icon, iconSize)
        constrainHeight(R.id.iv_icon, iconSize)
        connect(R.id.iv_icon, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        connect(R.id.iv_icon, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        connect(R.id.iv_icon, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)

        constrainWidth(R.id.tv_name, ConstraintSet.MATCH_CONSTRAINT)
        constrainHeight(R.id.tv_name, ConstraintSet.WRAP_CONTENT)
        connect(R.id.tv_name, ConstraintSet.START, R.id.iv_icon, ConstraintSet.END, 14.dpToPx())
        connect(R.id.tv_name, ConstraintSet.END, R.id.iv_more, ConstraintSet.START)
        connect(R.id.tv_name, ConstraintSet.TOP, R.id.iv_icon, ConstraintSet.TOP)
        connect(R.id.tv_name, ConstraintSet.BOTTOM, R.id.iv_icon, ConstraintSet.BOTTOM)

        val moreSize = 18.dpToPx()
        constrainWidth(R.id.iv_more, moreSize)
        constrainHeight(R.id.iv_more, moreSize)
        connect(R.id.iv_more, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        connect(R.id.iv_more, ConstraintSet.TOP, R.id.iv_icon, ConstraintSet.TOP)
        connect(R.id.iv_more, ConstraintSet.BOTTOM, R.id.iv_icon, ConstraintSet.BOTTOM)
    }

    private fun buildGridConstraints(): ConstraintSet = ConstraintSet().apply {
        val iconSize = 40.dpToPx()
        constrainWidth(R.id.iv_icon, iconSize)
        constrainHeight(R.id.iv_icon, iconSize)
        connect(R.id.iv_icon, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        connect(R.id.iv_icon, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        connect(R.id.iv_icon, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)

        constrainWidth(R.id.tv_name, ConstraintSet.MATCH_CONSTRAINT)
        constrainHeight(R.id.tv_name, ConstraintSet.WRAP_CONTENT)
        connect(R.id.tv_name, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        connect(R.id.tv_name, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        connect(R.id.tv_name, ConstraintSet.TOP, R.id.iv_icon, ConstraintSet.BOTTOM, 8.dpToPx())
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssBinding) {
        binding.apply {
            root.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let {
                    callBack.openRss(it)
                }
            }
            root.onLongClick {
                getItemByLayoutPosition(holder.layoutPosition)?.let {
                    showMenu(ivIcon, it)
                }
            }
        }
    }

    private fun showMenu(view: View, rssSource: RssSource) {
        modernMenuPopup = ModernActionPopup.showFromMenu(
            view,
            R.menu.rss_main_item,
            modernMenuPopup,
            prepare = {
                findItem(R.id.menu_login).isVisible = !rssSource.loginUrl.isNullOrBlank()
            }
        ) {
            when (it.itemId) {
                R.id.menu_edit -> callBack.edit(rssSource)
                R.id.menu_top -> callBack.toTop(rssSource)
                R.id.menu_login -> callBack.login(rssSource)
                R.id.menu_del -> callBack.del(rssSource)
                R.id.menu_disable -> callBack.disable(rssSource)
            }
            true
        }
    }

    interface CallBack {
        fun openRss(rssSource: RssSource)
        fun edit(rssSource: RssSource)
        fun toTop(rssSource: RssSource)
        fun login(rssSource: RssSource)
        fun del(rssSource: RssSource)
        fun disable(rssSource: RssSource)
    }

    companion object {
        private const val MIN_COLUMNS = 1
        private const val MAX_COLUMNS = 3
        private const val STYLE_LIST = 0
        private const val STYLE_GRID = 1
    }
}
