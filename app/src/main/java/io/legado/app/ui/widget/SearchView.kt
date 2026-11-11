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
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.glass.GlassSamplingDomain
import io.legado.app.ui.glass.GlassSurface
import io.legado.app.ui.glass.glassSurfaceDrawableAllowed
import io.legado.app.utils.printOnDebug


class SearchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SearchView(context, attrs) {
    private var mSearchHintIcon: Drawable? = null
    private var textView: TextView? = null
    private var glassDrawable: Drawable? = null
    private var glassSignature: String? = null

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
     * 每次布局都会走到这里，因此玻璃背景必须按配置签名缓存：签名不变时复用同一实例，
     * 既不会每次布局重建底图管线，也不会重复挂接位置追踪器。
     */
    private fun updateSearchBackground() {
        val glass = resolveGlassBackground()
        if (glass != null) {
            if (background !== glass) background = glass
        } else {
            background = TopBarSearchStyle.searchViewBackground(context)
        }
    }

    /**
     * 壁纸域玻璃背景；非壁纸域恒为 null，调用方回退 [TopBarSearchStyle] 的纯色药丸。
     *
     * 圆角取 [TopBarSearchStyle.radius]——与被替换的药丸逐像素同形，只换材质不换几何。
     * 墨水屏 / 低内存 / 无玻璃档由管线内部决定：前两者返回 null 保留原药丸，
     * 无玻璃档返回与全 App 行、底栏同档的实底壳。
     */
    private fun resolveGlassBackground(): Drawable? {
        if (!glassSurfaceDrawableAllowed(glassDomain)) return null
        val signature = GlassSurface.configSignature(context)
        if (signature != glassSignature) {
            glassSignature = signature
            glassDrawable = GlassSurface.panelBackground(
                this, glassDomain, TopBarSearchStyle.radius()
            )
        }
        return glassDrawable
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
