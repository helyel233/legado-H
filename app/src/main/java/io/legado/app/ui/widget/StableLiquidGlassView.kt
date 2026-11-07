package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.qmdeve.liquidglass.Config
import com.qmdeve.liquidglass.LiquidGlass
import io.legado.app.utils.dpToPx

/**
 * Stable wrapper for com.qmdeve.liquidglass.LiquidGlass.
 *
 * The library's LiquidGlassView posts parameter updates that dereference an
 * internal glass field later. If the view detaches before that runnable runs,
 * the field becomes null and the app can crash or keep a stale bound state.
 *
 * ## 使用约束（违反会 native 崩溃，且 Java 侧无堆栈）
 *
 * - **禁止用于 `RecyclerView` / `ListView` / `ViewPager` 的 item 内部。** 底层库每帧
 *   按**采样源整树**录制进一颗 `RenderNode`；行内使用会让采样源（列表）与宿主互相
 *   嵌套绘制，导致 RenderNode 重入录制直接 abort，同时每帧 N 次全屏录制在性能上也
 *   不成立。发现页书源名行因此固定使用静态模糊管线
 *   （`ExploreGlassBackdrop.rowBackground`）。
 * - **采样源必须是与宿主平级的背景节点**（兄弟节点，或至少不是宿主的祖先）。
 *   底栏的 `contentContainer` 就是这种用法：[bind] 会拒绝把宿主祖先当作采样源，
 *   见 [isAncestorOfHost] 与 `LiquidGlassSourceGuard.selfOrAncestorContains`。
 */
class StableLiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var sampleSource: ViewGroup? = null
    private var boundSource: ViewGroup? = null
    private var glass: LiquidGlass? = null
    private var config: Config? = null
    // 单帧内重复 rebuild 只排队一次，避免配置持续失败时堆积大量 post 与反复 removeView
    private var rebuildPending = false

    private var cornerRadius = 40f.dpToPx()
    private var refractionHeight = 20f.dpToPx()
    private var refractionOffset = 70f.dpToPx()
    private var tintAlpha = 0f
    private var tintColorRed = 1f
    private var tintColorGreen = 1f
    private var tintColorBlue = 1f
    private var blurRadius = 0.01f
    private var dispersion = 0.5f
    private var pixelSafePill = false
    private val roundedOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
        }
    }

    init {
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        applyRoundedOutline()
    }

    /**
     * 绑定采样源（必须是与本视图平级的背景节点）。
     *
     * [source] 为 null 表示解绑并移除玻璃层。若 [source] 是本视图**自身或祖先**，
     * 直接拒绝绑定并解绑（不创建 LiquidGlass）：底栏库每帧 `beginRecording` 后调用
     * `source.draw(canvas)`，祖先会把本视图再画一遍，进而重入 `drawRenderNode`
     * → RenderNode 重入录制 → hwui abort（native crash，Java 无堆栈）。
     */
    fun bind(source: ViewGroup?) {
        sampleSource = source
        if (source == null) {
            removeGlass()
            return
        }
        if (rejectIfAncestorSource(source)) return
        ensureGlass()
    }

    /**
     * 采样源是否为本视图自身或祖先（纯逻辑委托给
     * `LiquidGlassSourceGuard.selfOrAncestorContains`，便于 JVM 单测）。
     * 返回 true 时该采样源不可用。
     */
    internal fun isAncestorOfHost(source: ViewGroup): Boolean =
        selfOrAncestorContains(
            self = this,
            target = source,
            parentOf = { (it as? View)?.parent }
        )

    /**
     * 采样源不合法时解绑并返回 true，调用方须立即 return。
     * 集中一处是为了让 [bind] / [ensureGlass] / [applyConfig] 三条入口共享同一判定，
     * 避免「先绑合法源、之后视图被移到源内部」这类绕开 [bind] 的路径漏防。
     */
    private fun rejectIfAncestorSource(source: ViewGroup): Boolean {
        if (!isAncestorOfHost(source)) return false
        sampleSource = null
        removeGlass()
        return true
    }

    fun setCornerRadius(value: Float) {
        cornerRadius = value.coerceIn(0f, (height.takeIf { it > 0 } ?: Int.MAX_VALUE).toFloat() / 2f)
        applyRoundedOutline()
        applyConfig()
    }

    fun setRefractionHeight(value: Float) {
        refractionHeight = value.coerceIn(12f.dpToPx(), 50f.dpToPx())
        applyConfig()
    }

    fun setRefractionOffset(value: Float) {
        refractionOffset = value.coerceIn(20f.dpToPx(), 120f.dpToPx())
        applyConfig()
    }

    fun setTintColorRed(value: Float) {
        tintColorRed = value
        applyConfig()
    }

    fun setTintColorGreen(value: Float) {
        tintColorGreen = value
        applyConfig()
    }

    fun setTintColorBlue(value: Float) {
        tintColorBlue = value
        applyConfig()
    }

    fun setTintAlpha(value: Float) {
        tintAlpha = value
        applyConfig()
    }

    fun setDispersion(value: Float) {
        dispersion = value.coerceIn(0f, 1f)
        applyConfig()
    }

    fun setBlurRadius(value: Float) {
        blurRadius = value.coerceIn(0.01f, 50f)
        applyConfig()
    }

    fun setPixelSafePillEnabled(enabled: Boolean) {
        if (pixelSafePill == enabled) return
        pixelSafePill = enabled
        applyConfig()
        invalidate()
    }

    fun setDraggableEnabled(@Suppress("UNUSED_PARAMETER") enabled: Boolean) = Unit

    fun setElasticEnabled(@Suppress("UNUSED_PARAMETER") enabled: Boolean) = Unit

    fun setTouchEffectEnabled(@Suppress("UNUSED_PARAMETER") enabled: Boolean) = Unit

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureGlass()
    }

    override fun onDetachedFromWindow() {
        removeGlass()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyRoundedOutline()
        if (w != oldw || h != oldh) {
            applyConfig()
        }
    }

    private fun applyRoundedOutline() {
        if (cornerRadius <= 0f) {
            outlineProvider = null
            clipToOutline = false
            return
        }
        outlineProvider = roundedOutlineProvider
        clipToOutline = true
        invalidateOutline()
    }

    private fun ensureGlass() {
        val source = sampleSource ?: return
        if (!isAttachedToWindow) return
        // 绑定之后视图可能被重新挂到采样源内部（或调用方绕过了 bind 的守卫）：
        // 一旦采样源变成祖先，再喂给 LiquidGlass 就会在下一帧 pre-draw 里重入录制，
        // 就地解绑，宁可不显示玻璃也不让进程 abort。
        if (rejectIfAncestorSource(source)) return
        if (glass == null) {
            val nextConfig = createConfig()
            val nextGlass = LiquidGlass(context, nextConfig)
            config = nextConfig
            glass = nextGlass
            addView(
                nextGlass,
                0,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
        }
        if (boundSource !== source) {
            runCatching {
                glass?.init(source)
                boundSource = source
            }.onFailure {
                rebuildGlass(source)
            }
        }
        applyConfig()
    }

    /** 同一时刻只允许一个待执行的玻璃重建任务，防止异常兜底被反复触发导致 post 队列堆积。 */
    private fun postRebuild(source: ViewGroup) {
        if (rebuildPending) return
        rebuildPending = true
        post {
            rebuildPending = false
            if (!isAttachedToWindow || sampleSource !== source) return@post
            ensureGlass()
        }
    }

    private fun applyConfig() {
        val source = sampleSource
        if (!isAttachedToWindow || source == null) return
        if (rejectIfAncestorSource(source)) return
        if (glass == null) {
            ensureGlass()
            return
        }
        runCatching {
            config?.configure(overrides())
            glass?.updateParameters()
            invalidate()
        }.onFailure {
            rebuildGlass(source)
        }
    }

    private fun rebuildGlass(source: ViewGroup) {
        removeGlass()
        sampleSource = source
        postRebuild(source)
    }

    private fun removeGlass() {
        glass?.let { runCatching { removeView(it) } }
        glass = null
        config = null
        boundSource = null
    }

    private fun createConfig(): Config {
        return Config().apply {
            configure(overrides())
        }
    }

    private fun overrides(): Config.Overrides {
        val width = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val shaderCornerRadius = effectiveShaderCornerRadius(width, height)
        val shaderRefractionHeight = effectiveRefractionHeight(width, height)
        return Config.Overrides()
            .noFilter()
            .contrast(0f)
            .whitePoint(0f)
            .chromaMultiplier(1f)
            .blurRadius(blurRadius)
            .cornerRadius(shaderCornerRadius)
            .refractionHeight(shaderRefractionHeight)
            .refractionOffset(-refractionOffset)
            .tintAlpha(tintAlpha)
            .tintColorRed(tintColorRed)
            .tintColorGreen(tintColorGreen)
            .tintColorBlue(tintColorBlue)
            .dispersion(dispersion)
            .size(width, height)
    }

    private fun effectiveShaderCornerRadius(width: Int, height: Int): Float {
        val maxRadius = height / 2f
        return cornerRadius.coerceIn(0f, maxRadius)
    }

    private fun effectiveRefractionHeight(width: Int, height: Int): Float {
        if (!pixelSafePill || width <= height * 2) {
            return refractionHeight
        }
        val maxSafeHeight = height * 0.42f
        return refractionHeight.coerceAtMost(maxSafeHeight.coerceAtLeast(1f))
    }
}
