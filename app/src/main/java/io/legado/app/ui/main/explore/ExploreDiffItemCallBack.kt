package io.legado.app.ui.main.explore

import androidx.recyclerview.widget.DiffUtil
import io.legado.app.data.entities.BookSourcePart


class ExploreDiffItemCallBack : DiffUtil.ItemCallback<BookSourcePart>() {

    override fun areItemsTheSame(oldItem: BookSourcePart, newItem: BookSourcePart): Boolean {
        return oldItem.bookSourceUrl == newItem.bookSourceUrl
    }

    override fun areContentsTheSame(oldItem: BookSourcePart, newItem: BookSourcePart): Boolean {
        return contentSignature(oldItem) == contentSignature(newItem)
    }

    /** 行渲染只看这些字段：任何一项变化都需要重绑；其余字段变化不触发无谓 rebind。 */
    private fun contentSignature(item: BookSourcePart): String {
        return buildString {
            append(item.bookSourceName).append('\u001F')
            append(item.bookSourceGroup ?: "").append('\u001F')
            append(item.enabled).append('\u001F')
            append(item.enabledExplore).append('\u001F')
            append(item.hasLoginUrl).append('\u001F')
            append(item.hasExploreUrl).append('\u001F')
            append(item.bookSourceType)
        }
    }

}