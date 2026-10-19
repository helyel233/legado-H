package io.legado.app.ui.rss.article

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.RssArticle
import io.legado.app.databinding.ItemRssArticle1Binding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.gone
import io.legado.app.utils.visible


/**
 * 订阅文章列表样式 1（大图）。
 *
 * ⚠️ Adapter / Adapter1 / Adapter2 / Adapter4 四个类的 convert 逻辑**完全相同**，
 * 但它们对应的 5 个 item 布局是用户可选的文章样式（图片高度/字号/间距各不相同），
 * ViewBinding 类型因此互不相同，无法用泛型合并——除非把所有取 view 的地方改成
 * findViewById 或再加一层接口包装，得不偿失（2026-10-19 评估过，结论：维持现状）。
 *
 * 所以：**改这四个类里任何一个的绑定/加载逻辑，必须同步检查另外三个**。
 * Adapter3 的实现有实质差异（圆角裁剪 + Glide 加载监听），不在同步范围。
 */
class RssArticlesAdapter1(context: Context, callBack: CallBack) :
    BaseRssArticlesAdapter<ItemRssArticle1Binding>(context, callBack) {

    override fun getViewBinding(parent: ViewGroup): ItemRssArticle1Binding {
        return ItemRssArticle1Binding.inflate(inflater, parent, false)
    }

    @SuppressLint("CheckResult")
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssArticle1Binding,
        item: RssArticle,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty()) {
            payloads.forEach { payload ->
                when (payload) {
                    "read" -> {
                        if (item.read) {
                            binding.tvTitle.setTextColor(context.secondaryTextColor)
                        } else {
                            binding.tvTitle.setTextColor(context.primaryTextColor)
                        }
                    }
                    "title" -> {
                        binding.tvTitle.text = item.title
                    }
                }
            }
            return
        }
        binding.run {
            tvTitle.text = item.title
            if (item.read) {
                tvTitle.setTextColor(context.secondaryTextColor)
            } else {
                tvTitle.setTextColor(context.primaryTextColor)
            }
            tvPubDate.text = item.pubDate
            if (item.image.isNullOrBlank() && !callBack.isGridLayout) {
                imageView.gone()
            } else {
                val options =
                    RequestOptions().set(OkHttpModelLoader.sourceOriginOption, item.origin)
                ImageLoader.load(context, item.image).apply(options).apply {
                    if (callBack.isGridLayout) {
                        placeholder(R.drawable.image_rss_article)
                    } else {
                        addListener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(
                                e: GlideException?,
                                model: Any?,
                                target: Target<Drawable>,
                                isFirstResource: Boolean
                            ): Boolean {
                                imageView.gone()
                                return false
                            }

                            override fun onResourceReady(
                                resource: Drawable,
                                model: Any,
                                target: Target<Drawable>?,
                                dataSource: DataSource,
                                isFirstResource: Boolean
                            ): Boolean {
                                imageView.visible()
                                return false
                            }

                        })
                    }
                }.into(imageView)
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssArticle1Binding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.readRss(it)
            }
        }
    }

}
