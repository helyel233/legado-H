package io.legado.app.ui.rss.article

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.RssArticle
import io.legado.app.databinding.ItemRssArticle3Binding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.ui.glass.applyWallpaperGlassBackground
import io.legado.app.ui.widget.WaterfallCardMetrics

class RssArticlesAdapter3(context: Context, callBack: CallBack) :
    BaseRssArticlesAdapter<ItemRssArticle3Binding>(context, callBack) {

    private val orientation = context.resources.configuration.orientation
    private val columnCount = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2

    /**
     * 本样式自带卡面：`root` 是透明 `CardView`，真正带圆角的表面是它的第一个子 View
     * （见 [getViewBinding]），行级背景铺上去会被卡面整块盖住。因此跳过基类的行背景
     * 管线，改在卡面 View 上走**同一份**壁纸域玻璃入口 [applyWaterfallCardSurface]。
     */
    override val rowDrawsOwnCardSurface: Boolean get() = true

    override fun getViewBinding(parent: ViewGroup): ItemRssArticle3Binding {
        return ItemRssArticle3Binding.inflate(inflater, parent, false).apply {
            val metrics = WaterfallCardMetrics.resolve(parent, columnCount)
            root.layoutParams = (root.layoutParams ?: ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )).apply {
                height = metrics.cardHeight
            }
            imageView.layoutParams = imageView.layoutParams.apply {
                height = metrics.coverHeight
            }
            root.setCardBackgroundColor(Color.TRANSPARENT)
            root.getChildAt(0)?.let { content ->
                content.layoutParams = content.layoutParams.apply {
                    height = metrics.cardHeight
                }
                content.applyWaterfallCardSurface()
            }
        }
    }

    /**
     * 卡面再次挂到窗口时校准一次。
     *
     * 卡面是在 [getViewBinding] 里铺好的，复用中的 ViewHolder 不会重建；玻璃档位 /
     * 强度 / 描边色 / 夜间模式 / 是否开启背景图都可能在这期间变化。签名未变时
     * [applyWallpaperGlassBackground] 是零开销的空操作，因此这里不需要额外判定。
     */
    override fun onViewAttachedToWindow(holder: ItemViewHolder) {
        super.onViewAttachedToWindow(holder)
        // 基类在本样式的行级背景是空操作（rowDrawsOwnCardSurface = true），
        // 卡面重铺由这里负责；row 是 CardView，卡面是它的第一个子 View。
        // footer/头视图的 itemView 是 LoadMoreView（第一个子 View 是 RotateLoading），
        // 必须与基类一样排除，否则加载圈会被铺上卡面
        if (isArticleRowViewType(holder.itemViewType)) {
            (holder.itemView as? ViewGroup)?.getChildAt(0)?.applyWaterfallCardSurface()
        }
    }

    /**
     * 水瀑布卡片卡面：壁纸可见时铺壁纸域玻璃，其余情况保持原来的
     * `UiCorner.panelRounded(cardColor, panelRadius)` 实底卡面。
     *
     * 四条纪律与其余玻璃表面一致：
     * - **圆角几何不变**：玻璃层圆角取 `UiCorner.panelRadius`，与 `panelRounded` 同一份
     *   取值（同样跟随 `uiCornerScale`），卡面轮廓逐值不变；
     * - **采样走壁纸域缓存 + 位置跟随**：`applyWallpaperGlassBackground` 只取进程级
     *   壁纸位图并按卡面在屏幕上的实时位置着色，**不录制自身或祖先子树**
     *   （这条管线里根本没有 `StableLiquidGlassView`），瀑布布局的滚动性能与
     *   ViewHolder 复用安全都与既有列表行一致；
     * - **solid / 墨水屏 / 低内存 / 无壁纸逐值回退**：由
     *   `GlassSurface.pageGlassAllowed` 统一判否后铺原卡面；
     * - **内容对比度**：卡面上的标题 / 摘要 / 时间仍按主题文字色绘制，玻璃 tint 与
     *   其余卡片同源，不额外叠加任何遮罩。
     */
    private fun View.applyWaterfallCardSurface() {
        val radius = UiCorner.panelRadius(context)
        val fillColor = context.themeCardColorOrDefault()
        applyWallpaperGlassBackground(cornerRadiusPx = radius) {
            UiCorner.panelRounded(context, fillColor, radius)
        }
    }

    @SuppressLint("CheckResult")
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssArticle3Binding,
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
            val radius = UiCorner.panelRadius(context)
            imageView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            imageView.clipToOutline = true
            imageView.adjustViewBounds = false
            val imageUrl = item.image
            if (imageUrl.isNullOrEmpty()) {
                imageView.setImageResource(R.drawable.image_rss_article)
                return
            }
            ImageLoader.load(context, imageUrl)
                .apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, item.origin))
                .placeholder(R.drawable.transparent_placeholder)
                .centerCrop()
                .into(imageView)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssArticle3Binding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.readRss(it)
            }
        }
    }
}
