package io.legado.app.ui.book.explore

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import java.util.concurrent.ConcurrentHashMap
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemSearchWaterfallBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.widget.WaterfallCardMetrics
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.gone
import io.legado.app.utils.visible

class ExploreShowWaterfallAdapter(
    context: Context,
    private val callBack: ExploreShowBookCallback,
    private val columns: Int
) : RecyclerAdapter<SearchBook, ItemSearchWaterfallBinding>(context) {

    private data class BookItemKey(val bookUrl: String, val origin: String)

    // 记录上一次列表提交/绑定时各条目的书架命中状态，避免外部书架状态变化后 setItems 被 DiffUtil 误判为内容未变
    private val boundBookshelfState = ConcurrentHashMap<BookItemKey, Boolean>()

    override val useDiffUtil: Boolean get() = true

    private fun itemKey(item: SearchBook): BookItemKey = BookItemKey(item.bookUrl, item.origin)

    private fun areItemFieldsEqual(oldItem: SearchBook, newItem: SearchBook): Boolean {
        // SearchBook.equals() 仅比较 bookUrl；显式核对所有渲染字段
        return oldItem.name == newItem.name &&
            oldItem.author == newItem.author &&
            oldItem.latestChapterTitle == newItem.latestChapterTitle &&
            oldItem.intro == newItem.intro &&
            oldItem.kind == newItem.kind &&
            oldItem.coverUrl == newItem.coverUrl
    }

    override val diffItemCallback: DiffUtil.ItemCallback<SearchBook> =
        object : DiffUtil.ItemCallback<SearchBook>() {
            override fun areItemsTheSame(oldItem: SearchBook, newItem: SearchBook): Boolean {
                return oldItem.bookUrl == newItem.bookUrl && oldItem.origin == newItem.origin
            }

            override fun areContentsTheSame(oldItem: SearchBook, newItem: SearchBook): Boolean {
                val previousInBookshelf = boundBookshelfState[itemKey(oldItem)]
                val currentInBookshelf = callBack.isInBookshelf(newItem)
                return areItemFieldsEqual(oldItem, newItem) &&
                    (previousInBookshelf == null || previousInBookshelf == currentInBookshelf)
            }

            override fun getChangePayload(oldItem: SearchBook, newItem: SearchBook): Any? {
                val previousInBookshelf = boundBookshelfState[itemKey(oldItem)]
                val currentInBookshelf = callBack.isInBookshelf(newItem)
                return if (
                    areItemFieldsEqual(oldItem, newItem) &&
                    previousInBookshelf != null &&
                    previousInBookshelf != currentInBookshelf
                ) {
                    bundleOf("isInBookshelf" to currentInBookshelf)
                } else {
                    null
                }
            }
        }

    override fun getViewBinding(parent: ViewGroup): ItemSearchWaterfallBinding {
        return ItemSearchWaterfallBinding.inflate(inflater, parent, false).apply {
            root.applyUiBodyTypefaceDeep(context.uiTypeface())
            val metrics = WaterfallCardMetrics.resolve(parent, columns)
            root.layoutParams = (root.layoutParams ?: ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )).apply {
                height = metrics.cardHeight
            }
            ivCover.layoutParams = ivCover.layoutParams.apply {
                height = metrics.coverHeight
            }
            root.background = UiCorner.panelRounded(
                root.context,
                root.context.themeCardColorOrDefault(),
                UiCorner.panelRadius(root.context)
            )
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemSearchWaterfallBinding,
        item: SearchBook,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty()) {
            payloads.forEach { payload ->
                val bundle = payload as? Bundle ?: return@forEach
                if (bundle.containsKey("isInBookshelf")) {
                    bindKindLabels(binding, item)
                }
            }
            return
        }
        binding.run {
            tvName.text = item.name
            tvAuthor.text = context.getString(R.string.author_show, item.author)
            if (item.latestChapterTitle.isNullOrEmpty()) {
                tvLasted.gone()
            } else {
                tvLasted.text = context.getString(R.string.lasted_show, item.latestChapterTitle)
                tvLasted.visible()
            }
            tvIntroduce.text = item.exploreListIntro(context)
            bindKindLabels(this, item)
            ivCover.setCoverStyle(CoverImageView.CoverStyle.GRID)
            ivCover.load(item, AppConfig.loadCoverOnlyWifi)
        }
    }

    private fun bindKindLabels(binding: ItemSearchWaterfallBinding, item: SearchBook) {
        val inBookshelf = callBack.isInBookshelf(item)
        boundBookshelfState[itemKey(item)] = inBookshelf
        val labels = buildList {
            if (inBookshelf) add(IN_BOOKSHELF_LABEL)
            addAll(item.getKindList())
        }.take(4)
        if (labels.isEmpty()) {
            binding.llKind.gone()
        } else {
            binding.llKind.visible()
            binding.llKind.setLabels(labels)
        }
    }

    override fun onCurrentListChanged() {
        val current = getItems()
        val activeKeys = HashSet<BookItemKey>(current.size)
        for (item in current) {
            val key = itemKey(item)
            activeKeys.add(key)
            boundBookshelfState[key] = callBack.isInBookshelf(item)
        }
        boundBookshelfState.keys.retainAll(activeKeys)
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemSearchWaterfallBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.bindingAdapterPosition - getHeaderCount())?.let {
                callBack.showBookInfo(it)
            }
        }
    }

    override fun onViewAttachedToWindow(holder: ItemViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder.itemViewType < 0 || holder.itemViewType >= TYPE_FOOTER_VIEW) {
            (holder.itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams)?.isFullSpan = true
        }
    }
}

private const val IN_BOOKSHELF_LABEL = "已在书架"
