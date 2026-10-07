package io.legado.app.ui.main.explore

import androidx.recyclerview.widget.DiffUtil
import io.legado.app.data.entities.BookSourcePart


class ExploreDiffItemCallBack : DiffUtil.ItemCallback<BookSourcePart>() {

    override fun areItemsTheSame(oldItem: BookSourcePart, newItem: BookSourcePart): Boolean {
        return oldItem.bookSourceUrl == newItem.bookSourceUrl
    }

    override fun areContentsTheSame(oldItem: BookSourcePart, newItem: BookSourcePart): Boolean {
        return oldItem.bookSourceName == newItem.bookSourceName &&
            oldItem.bookSourceGroup == newItem.bookSourceGroup &&
            oldItem.enabled == newItem.enabled &&
            oldItem.enabledExplore == newItem.enabledExplore &&
            oldItem.hasLoginUrl == newItem.hasLoginUrl &&
            oldItem.hasExploreUrl == newItem.hasExploreUrl &&
            oldItem.bookSourceType == newItem.bookSourceType
    }

}