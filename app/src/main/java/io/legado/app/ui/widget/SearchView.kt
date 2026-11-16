package io.legado.app.ui.widget

import android.annotation.SuppressLint
import android.app.SearchableInfo
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import io.legado.app.R
import io.legado.app.lib.theme.TopBarSearchStyle
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.themeSearchFieldBackgroundColorOrDefault
import io.legado.app.ui.glass.GlassSamplingDomain
import io.legado.app.ui.glass.GlassSurface
import io.legado.app.ui.glass.glassSurfaceDrawableAllowed
import io.legado.app.ui.glass.wallpaperGlassSignature
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.explore.resolvedExploreGlassMode
import io.legado.app.utils.dpToPx
import io.legado.app.utils.printOnDebug


class SearchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SearchView(context, attrs) {
    private var mSearchHintIcon: Drawable? = null
    private var textView: TextView? = null
    private var glassDrawable: Drawable? = null
    private var glassSignature: String? = null
    private var fallbackDrawable: Drawable? = null
    private var fallbackSignature: String? = null

    /**
     * 玻璃采样域，由宿主显式声明；默认 [GlassSamplingDomain.PANEL] = 不接玻璃。
     *
     * 搜索栏是共享控件：同一份 `view_search` 既铺在壁纸背景的 Activity / Fragment 顶部，
     * 也出现在 `CacheChapterDialog` 这类弹窗里。运行期无法可靠判定背后是壁纸还是弹窗面板
     * （见 [GlassSamplingDomain] 的显式声明约定），猜错就会画出用户看不到的背景，
     * 因此采样域只能由每个宿主按自己的真实背景给出。
     */
    var glassDomain: GlassSamplingDomain = GlassSamplingDomain.PANEL
        set(value) {
            if (field == value) return
            field = value
            glassSignature = null
            glassDrawable = null
            updateSearchBackground()
        }

    @SuppressLint("UseCompatLoadingForDrawables")
    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        super.onLayout(changed, left, top, right, bottom)
        try {
            if (textView == null) {
                textView = findViewById(androidx.appcompat.R.id.search_src_text)
                mSearchHintIcon = this.context.getDrawable(R.drawable.ic_search_hint)
            }
            // 改变字体
            textView!!.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            textView!!.gravity = Gravity.CENTER_VERTICAL
            textView!!.setTextColor(context.primaryTextColor)
            textView!!.setHintTextColor(context.secondaryTextColor)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                textView!!.isLocalePreferredLineHeightForMinimumUsed = false
            }
            ensureTransparentSurfaces()
            updateSearchBackground()
            updateQueryHint()
        } catch (e: Exception) {
            e.printOnDebug()
        }
    }

    private fun ensureTransparentSurfaces() {
        textView?.setBackgroundColor(Color.TRANSPARENT)
        findViewById<android.view.View?>(androidx.appcompat.R.id.search_plate)
            ?.setBackgroundColor(Color.TRANSPARENT)
        findViewById<android.view.View?>(androidx.appcompat.R.id.search_edit_frame)
            ?.setBackgroundColor(Color.TRANSPARENT)
        findViewById<android.view.View?>(androidx.appcompat.R.id.submit_area)
            ?.setBackgroundColor(Color.TRANSPARENT)
    }

    /**
     * 每次布局都会走到这里，因此背景必须按签名缓存：签名不变时复用同一实例，
     * 既不会每次布局重建底图管线，也不会重复挂接位置追踪器。
     *
     * 两条缓存（玻璃 / 原背景）共用同一个签名，且**只在背景真的换了一份实例时**才
     * 调 `setBackground`：旧实现的无玻璃分支每次都 new 一个 `GradientDrawable` 贴上去，
     * 于是 `onLayout` 每帧都给这个 View 换新 drawable（无谓分配 + 丢按压态）。
     */
    private fun updateSearchBackground() {
        val signature = wallpaperGlassSignature(context)
        if (signature != glassSignature) {
            glassSignature = signature
            glassDrawable = resolveGlassBackground()
        }
        val target = glassDrawable ?: resolveFallbackBackground()
        if (background !== target) background = target
    }

    /**
     * 壁纸域玻璃背景；非壁纸域恒为 null，调用方回退 [TopBarSearchStyle] 的纯色药丸。
     *
     * 圆角取 [TopBarSearchStyle.radius]——与被替换的药丸逐像素同形，只换材质不换几何。
     *
     * **无玻璃档必须先判否、不要拿管线产物**：`GlassSurface.panelBackground` 在 solid 档
     * 交回的是**底栏底色 + 1dp 描边的不透明实底壳**，那是底栏的观感，不是搜索栏的
     * ——直接贴上来会让搜索栏与本页其它表面（主题卡片色）判若两物（H6）。
     * 墨水屏 / 低内存本来就由管线返回 null，这里一并提前判否，使三条降级路径
     * 全部回到「控件自己的原背景」这一条回退链。
     *
     * **这里不判 `showsWallpaperBehindContent` / `hasUsableBgImage`**：本控件是共享控件，
     * 采样域由宿主通过 [glassDomain] 显式声明（默认 [GlassSamplingDomain.PANEL] = 不接玻璃），
     * 只有 `applyGlassDomain(WALLPAPER)` 的宿主才会走到这里。也就是说「我背后真的是壁纸」
     * 这条前提由**宿主自己保证**，控件不回溯 `BaseActivity`。
     * 目前全部 10 个 `applyGlassDomain(WALLPAPER)` 宿主都是根背景透壁纸的页面，因此成立；
     * 新增调用点必须自己确认这一点，否则会采到用户看不见的壁纸
     * （要硬约束就把这里换成 `GlassSurface.pageGlassAllowed`，代价是它含宿主回溯、
     * 在 `setContentView` 之前调用会拿到 null host 而误判）。
     */
    private fun resolveGlassBackground(): Drawable? {
        // CLEAR（透明无玻璃）档：wallpaperGlassEnabled 对本档判否，但底栏 / 对话框 /
        // 卡片在本档都铺「主题表面色 × CLEAR alpha」的半透明壳（见
        // GlassMaterialPreset.clearSurfaceColor）。搜索栏若沿回退链回实底药丸，
        // 就成了同档里唯一的不透明表面——这里按同一份口径补齐半透明壳。
        // 壳是纯色静态 drawable，不挂位置跟踪，此前若挂过必须解绑（与判否同责）。
        if (
            glassSurfaceDrawableAllowed(glassDomain) &&
            resolvedExploreGlassMode() == GlassMaterialPreset.CLEAR
        ) {
            GlassSurface.releaseBackgroundTracking(this)
            return GlassMaterialPreset.createSolidShellDrawable(
                fillColor = GlassMaterialPreset.clearSurfaceColor(
                    context.themeSearchFieldBackgroundColorOrDefault()
                ),
                borderColor = UiCorner.borderLineColor(context),
                cornerRadiusPx = TopBarSearchStyle.radius(),
                oval = false,
                strokeWidthPx = 1.dpToPx()
            )
        }
        // 两条前置判定不能拆：采样域（面板域 / 内容域不可采壁纸）先否决，
        // 再由 wallpaperGlassEnabled 否决 solid / 墨水屏 / 低内存。
        val drawable = if (
            glassSurfaceDrawableAllowed(glassDomain) &&
            GlassSurface.wallpaperGlassEnabled(context)
        ) {
            GlassSurface.panelBackground(this, glassDomain, TopBarSearchStyle.radius())
        } else {
            null
        }
        // 本次没拿到玻璃（任一否决条件命中 / 管线构建失败）必须解绑上一份位置跟踪：
        // 它会继续挂在 layout / attach 回调上给一份不再绘制的 drawable 重采样。
        // 拿到玻璃时不解绑——panelBackground 内部已 attach 了一份新的。
        if (drawable == null) GlassSurface.releaseBackgroundTracking(this)
        return drawable
    }

    /**
     * 未接管玻璃时的原背景：控件自己的药丸（[TopBarSearchStyle]）。
     *
     * 这也是全 App 所有未声明壁纸域的宿主（弹窗等）今天的外观，因此本方法让
     * 「无玻璃 / 无背景图 / 墨水屏 / 低内存 / solid」五条路径共用一份回退值，
     * 不会出现两套无玻璃观感。
     *
     * **缓存键必须含药丸自身的输入**：[wallpaperGlassSignature] 只覆盖玻璃观感
     * （档位 / 强度 / 描边 / 壁纸可用性），不含 `themeSearchFieldBackgroundColor`
     * 与 `uiCornerSearchFollow` / `uiCornerScale`。只按玻璃签名缓存，会让
     * 「改搜索框背景色 / 圆角跟随 → 返回」停在旧值——而改动前这里每次 `onLayout`
     * 都新建 drawable，所以不存在该问题，是本次引入缓存后才出现的回归。
     */
    private fun resolveFallbackBackground(): Drawable {
        val key = "${TopBarSearchStyle.surfaceColor(context)}|${TopBarSearchStyle.radius()}"
        fallbackDrawable?.let { cached ->
            if (fallbackSignature == key) return cached
        }
        return TopBarSearchStyle.searchViewBackground(context).also {
            fallbackDrawable = it
            fallbackSignature = key
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // 从设置页返回时窗口重新可见：档位 / 强度 / 描边可能已变，按新签名重绑一次。
        if (visibility == VISIBLE) updateSearchBackground()
    }

    private fun getDecoratedHint(hintText: CharSequence): CharSequence {
        // If the field is always expanded or we don't have a search hint icon,
        // then don't add the search icon to the hint.
        if (mSearchHintIcon == null) {
            return hintText
        }
        val textSize = textView!!.textSize.toInt()
        mSearchHintIcon!!.setBounds(0, 0, textSize, textSize)
        val ssb = SpannableStringBuilder("   ")
        ssb.setSpan(CenteredImageSpan(mSearchHintIcon), 1, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.append(hintText)
        return ssb
    }

    private fun updateQueryHint() {
        textView?.let {
            it.hint = getDecoratedHint(queryHint ?: "")
        }
    }

    override fun setIconifiedByDefault(iconified: Boolean) {
        super.setIconifiedByDefault(iconified)
        updateQueryHint()
    }

    override fun setSearchableInfo(searchable: SearchableInfo?) {
        super.setSearchableInfo(searchable)
        searchable?.let {
            updateQueryHint()
        }
    }

    override fun setQueryHint(hint: CharSequence?) {
        super.setQueryHint(hint)
        updateQueryHint()
    }

    internal class CenteredImageSpan(drawable: Drawable?) : ImageSpan(drawable!!) {
        override fun draw(
            canvas: Canvas, text: CharSequence,
            start: Int, end: Int, x: Float,
            top: Int, y: Int, bottom: Int, paint: Paint
        ) {
            // image to draw
            val b = drawable
            // font metrics of text to be replaced
            val fm = paint.fontMetricsInt
            val transY = ((y + fm.descent + y + fm.ascent) / 2
                    - b.bounds.bottom / 2)
            canvas.save()
            canvas.translate(x, transY.toFloat())
            b.draw(canvas)
            canvas.restore()
        }
    }
}

/**
 * 共享搜索栏的采样域声明入口：宿主拿到的引用通常是 `androidx.appcompat.widget.SearchView`
 * （布局里声明的才是本类），这里做一次安全收窄；不是本控件时静默忽略、外观不变。
 *
 * 调用点必须显式写出 [GlassSamplingDomain]，把「宿主背后是什么」留在每个宿主自己的代码里。
 */
fun androidx.appcompat.widget.SearchView.applyGlassDomain(domain: GlassSamplingDomain) {
    (this as? io.legado.app.ui.widget.SearchView)?.glassDomain = domain
}
