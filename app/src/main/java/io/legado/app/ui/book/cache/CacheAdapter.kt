package io.legado.app.ui.book.cache

import android.content.Context
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemDownloadBinding
import io.legado.app.help.book.isLocal
import io.legado.app.utils.gone
import io.legado.app.utils.visible

class CacheAdapter(context: Context, private val callBack: CallBack) :
    DiffRecyclerAdapter<Book, ItemDownloadBinding>(context) {

    override val diffItemCallback: DiffUtil.ItemCallback<Book>
        get() = object : DiffUtil.ItemCallback<Book>() {
            override fun areItemsTheSame(oldItem: Book, newItem: Book): Boolean {
                return oldItem.bookUrl == newItem.bookUrl
            }

            override fun areContentsTheSame(oldItem: Book, newItem: Book): Boolean {
                return oldItem.name == newItem.name
                        && oldItem.author == newItem.author
            }

        }

    override fun getViewBinding(parent: ViewGroup): ItemDownloadBinding {
        return ItemDownloadBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemDownloadBinding,
        item: Book,
        payloads: MutableList<Any>
    ) {
        binding.run {
            if (payloads.isEmpty()) {
                tvName.text = item.name
                tvAuthor.text = context.getString(R.string.author_show, item.getRealAuthor())
            }
            bindDownloadProgress(tvDownload, item, allowLoadingPlaceholder = payloads.isEmpty())
            upDownloadIv(ivDownload, item)
            upExportInfo(tvMsg, progressExport, item)
        }
    }

    private fun bindDownloadProgress(
        tvDownload: TextView,
        item: Book,
        allowLoadingPlaceholder: Boolean
    ) {
        if (item.isLocal) {
            tvDownload.setText(R.string.local_book)
            return
        }
        val cs = callBack.cacheChapters[item.bookUrl]
        if (cs == null && allowLoadingPlaceholder) {
            tvDownload.setText(R.string.loading)
            return
        }
        val bodyCount = cs?.size ?: 0
        val totalCount = item.totalChapterNum
        val reviewCount = callBack.reviewChapters[item.bookUrl]?.size ?: 0
        tvDownload.text = formatCacheProgressText(context, bodyCount, totalCount, reviewCount)
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemDownloadBinding) {
        binding.run {
            ivDownload.setOnClickListener {
                getItem(holder.layoutPosition)?.let { book ->
                    callBack.toggleBookDownload(book)
                }
            }
            tvExport.setOnClickListener {
                callBack.export(holder.layoutPosition)
            }
        }
    }

    private fun upDownloadIv(iv: ImageView, book: Book) {
        if (book.isLocal) {
            iv.gone()
        } else {
            iv.visible()
            if (callBack.isBookDownloading(book.bookUrl)) {
                iv.setImageResource(R.drawable.ic_stop_black_24dp)
            } else {
                iv.setImageResource(R.drawable.ic_play_24dp)
            }
        }
    }

    private fun upExportInfo(msgView: TextView, progressView: ProgressBar, book: Book) {
        val msg = callBack.exportMsg(book.bookUrl)
        if (msg != null) {
            msgView.text = msg
            msgView.visible()
            progressView.gone()
            return
        }
        msgView.gone()
        val progress = callBack.exportProgress(book.bookUrl)
        if (progress != null) {
            progressView.max = book.totalChapterNum
            progressView.progress = progress
            progressView.visible()
            return
        }
        progressView.gone()
    }

    interface CallBack {
        val cacheChapters: HashMap<String, HashSet<String>>
        val reviewChapters: HashMap<String, HashSet<String>>
        fun isBookDownloading(bookUrl: String): Boolean
        fun toggleBookDownload(book: Book)
        fun export(position: Int)
        fun exportProgress(bookUrl: String): Int?
        fun exportMsg(bookUrl: String): String?
    }
}

internal fun formatCacheProgressText(
    context: Context,
    bodyCount: Int,
    totalCount: Int,
    reviewCount: Int
): String {
    if (reviewCount <= 0) {
        return context.getString(R.string.download_count, bodyCount, totalCount)
    }
    val reviewTotal = if (totalCount > 0) totalCount else reviewCount
    val clampedReviewCount = reviewCount.coerceAtMost(reviewTotal)
    return context.getString(
        R.string.download_count_with_review,
        bodyCount,
        totalCount,
        clampedReviewCount,
        reviewTotal
    )
}