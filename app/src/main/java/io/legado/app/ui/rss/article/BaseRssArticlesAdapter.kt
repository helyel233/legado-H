package io.legado.app.ui.rss.article

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import androidx.viewbinding.ViewBinding
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.RssArticle
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.themeUiPalette
import io.legado.app.ui.glass.GlassSurface
import io.legado.app.ui.glass.applyWallpaperGlassBackground


abstract class BaseRssArticlesAdapter<VB : ViewBinding>(context: Context, val callBack: CallBack) :
    RecyclerAdapter<RssArticle, VB>(context) {

    /**
     * 行是否自带卡面绘制。
     *
     * 文章样式 3 的水瀑布卡片把**卡面**铺在子 View 上（见
     * [RssArticlesAdapter3.getViewBinding]：`root` 是 `CardView` 且透明，真正带圆角的
     * 表面是它的第一个子 View），行级背景铺上去只会被卡面整块盖住。
     * 因此该样式显式声明跳过本类的行背景管线，改由子类在卡面 View 上自行接入
     * 同一份 [io.legado.app.ui.glass.applyWallpaperGlassBackground]；其余样式共用本类。
     */
    protected open val rowDrawsOwnCardSurface: Boolean get() = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val holder = super.onCreateViewHolder(parent, viewType)
        // 头 / 尾视图（如共享的 LoadMoreView）不走文章行背景：它们的 viewType 取
        // TYPE_HEADER_VIEW / TYPE_FOOTER_VIEW 两个极值，真实文章行的 viewType 是小整数
        if (isArticleRowViewType(viewType)) {
            holder.itemView.applyRowBackground()
        }
        return holder
    }

    /**
     * 行再次挂到窗口时校准一次背景。
     *
     * 玻璃观感随档位 / 强度 / 描边色 / 夜间模式变化（[GlassSurface.configSignature]）。
     * 复用中的 ViewHolder 不会重建，切档后返回列表若只靠 [onCreateViewHolder]，
     * 已复用的行会停在旧材质上（真实路径：主界面订阅标签 → 文章列表 → 我的 → 设置里
     * 改玻璃档 → 回到订阅标签）。按签名比较后只重绑真正变化的那一次，
     * 同档滚动重复挂载是零开销的。
     */
    override fun onViewAttachedToWindow(holder: ItemViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (isArticleRowViewType(holder.itemViewType)) {
            holder.itemView.applyRowBackground()
        }
    }

    /** 真实文章行的 viewType：头部为 `Int.MIN_VALUE + i`（负数），尾部为 `Int.MAX_VALUE - 999 + i`。 */
    protected fun isArticleRowViewType(viewType: Int): Boolean =
        viewType >= 0 && viewType < RecyclerAdapter.TYPE_FOOTER_VIEW

    /**
     * 文章行背景：壁纸域玻璃 → 主题半透明行底 → 透明（铺在页面底色上）。
     *
     * 订阅源卡片（[io.legado.app.ui.main.rss.RssAdapter]）早已接入壁纸域玻璃，文章行却
     * 一直只铺 `UiCorner.surfaceColor(cardColor)` 的平涂行底——平涂色把壁纸整块盖住，
     * 文章列表因此与其余玻璃界面割裂（用户反馈的「订阅源进去的文章列表没玻璃化」）。
     *
     * 采样方式与发现页书源名行、订阅源卡片**同一份**：玻璃 drawable 按行在屏幕上的
     * 实时位置采样进程级缓存的壁纸位图（[io.legado.app.ui.glass.applyWallpaperGlassBackground]
     * → `GlassSurface.pagePanelBackground`），每行只做一次位图着色绘制，不录制任何
     * 祖先 / 自身子树，滚动性能与复用安全都与既有列表行一致；`GlassPositionTracker`
     * 负责位移后重采样。
     *
     * 圆角取 0：样式 0/1 的文章行是通栏行（无外边距、上下无缝相连，靠 1dp 分隔线分界），
     * 只有圆角 0 才能保持原有行几何——与目录页章节行「无缝相连取圆角 0」同一口径。
     *
     * 无壁纸（普通主题）时保持改动前的透明行；solid 档 / 墨水屏 / 低内存由
     * `pagePanelBackground` 统一回退到原来的半透明行底。
     */
    private fun View.applyRowBackground() {
        if (rowDrawsOwnCardSurface) return
        applyWallpaperGlassBackground(cornerRadiusPx = 0f, tagKey = ROW_GLASS_SIGNATURE_TAG) {
            if (ThemeConfig.hasUsableBgImage(context)) {
                ColorDrawable(UiCorner.surfaceColor(context.themeUiPalette().cardColor))
            } else {
                // 无背景图时行保持透明（与改动前一致），玻璃也不会铺不透明实底壳
                null
            }
        }
    }

    interface CallBack {
        val isGridLayout: Boolean
        fun readRss(rssArticle: RssArticle)
    }
}

/**
 * 文章行背景所用配置签名（玻璃观感 + 壁纸可用性）的 View tag key。
 *
 * `setTag(int, Any)` 只挂在本行的 keyed tags 里（与 `GlassPositionTracker` 用同一个
 * 约定），不占用资源 id、不参与视图层级。独立于
 * [io.legado.app.ui.glass.GLASS_SIGNATURE_TAG]：文章行有自己的回归锁测试在读这个 key，
 * 且同一 View 上将来若有其它玻璃表面也不会互相顶掉。
 *
 * 对同模块测试可见（而不是 private + 测试里复制字面量）：值一旦改动测试会跟着变，
 * 而不是静默锁住一个实现里已经不用的 key。
 */
internal const val ROW_GLASS_SIGNATURE_TAG = 0x5F_5253_47
