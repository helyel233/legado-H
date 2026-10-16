package io.legado.app.ui.rss.article

import android.content.Context
import android.view.ViewGroup
import androidx.viewbinding.ViewBinding
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.RssArticle
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.themeUiPalette


abstract class BaseRssArticlesAdapter<VB : ViewBinding>(context: Context, val callBack: CallBack) :
    RecyclerAdapter<RssArticle, VB>(context) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val holder = super.onCreateViewHolder(parent, viewType)
        // 自定义背景图下文章行原本透明，标题/摘要文字直接压图难分辨：
        // 铺与主题一致的半透明行底（随界面不透明度走）；普通主题不加
        if (ThemeConfig.hasUsableBgImage(context)) {
            holder.itemView.setBackgroundColor(
                UiCorner.surfaceColor(context.themeUiPalette().cardColor)
            )
        }
        return holder
    }

    interface CallBack {
        val isGridLayout: Boolean
        fun readRss(rssArticle: RssArticle)
    }
}
