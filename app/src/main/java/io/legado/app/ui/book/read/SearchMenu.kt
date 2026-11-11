package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.widget.FrameLayout
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.databinding.ViewSearchMenuBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.themeMutedColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.ui.glass.applyLiquidGlassSpec
import io.legado.app.ui.glass.contentLiquidGlassSpec
import io.legado.app.ui.glass.glassPanelFillYielded
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.explore.ExploreGlassBackdrop
import io.legado.app.ui.main.explore.resolvedExploreGlassMode
import io.legado.app.ui.widget.StableLiquidGlassView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.activity
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.invisible
import io.legado.app.utils.loadAnimation
import io.legado.app.utils.visible

/**
 * 搜索界面菜单
 */
class SearchMenu @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val callBack: CallBack get() = activity as CallBack
    private val binding = ViewSearchMenuBinding.inflate(LayoutInflater.from(context), this, true)

    private val menuBottomIn: Animation = loadAnimation(context, R.anim.anim_readbook_bottom_in)
    private val menuBottomOut: Animation = loadAnimation(context, R.anim.anim_readbook_bottom_out)
    private val accentColor: Int = context.accentColor
    private val bgColor: Int = context.bottomBackground
    private val textColor: Int = context.primaryTextColor
    private var bottomBackgroundList: ColorStateList = Selector.colorBuild()
        .setDefaultColor(context.themeCardColorOrDefault())
        .setPressedColor(ColorUtils.darkenColor(context.themeCardColorOrDefault()))
        .create()
    private var onMenuOutEnd: (() -> Unit)? = null
    private var isMenuOutAnimating = false

    /**
     * 底部面板的液态玻璃层：与面板同处 [ViewSearchMenuBinding.flBottomMenu]，尺寸由
     * 布局给出，随该容器一起做滑入 / 滑出平移动画，与面板逐帧同形。
     *
     * 采样源是与本视图同级的正文 `read_view`；玻璃层只负责背景，不参与触摸。
     */
    private val glass = StableLiquidGlassView(context).apply {
        visibility = GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var glassActive = false
    private var panelBackground: GradientDrawable? = null

    /** 底部面板是否由液态玻璃铺底（宿主据此把导航栏改成同一份着色）。 */
    val isBottomGlassActive: Boolean get() = glassActive

    /** 当前生效的玻璃着色透明度（0 = 玻璃未激活），导航栏同色用同一份参数。 */
    var glassTintAlpha: Float = 0f
        private set

    private val searchResultList: MutableList<SearchResult> = mutableListOf()
    private var currentSearchResultIndex: Int = -1
    private var lastSearchResultIndex: Int = -1
    private val hasSearchResult: Boolean
        get() = searchResultList.isNotEmpty()
    val selectedSearchResult: SearchResult?
        get() = searchResultList.getOrNull(currentSearchResultIndex)
    val previousSearchResult: SearchResult?
        get() = searchResultList.getOrNull(lastSearchResultIndex)
    val bottomMenuVisible get() = isVisible && binding.llBottomMenu.isVisible

    init {
        binding.root.applyUiBodyTypefaceDeep(context.uiTypeface())
        binding.flBottomMenu.addView(
            glass, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        glass.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (glassActive) applyPanelSurface()
        }
        initAnimation()
        initView()
        bindEvent()
        updateSearchInfo()
    }

    fun upSearchResultList(resultList: List<SearchResult>) {
        searchResultList.clear()
        searchResultList.addAll(resultList)
        updateSearchInfo()
    }

    private fun initView() = binding.run {
        val isBgLight = ColorUtils.isColorLight(bgColor)
        val themeSurfaceColor = context.themeMutedColorOrDefault()
        val themeCardColor = context.themeCardColorOrDefault()
        val dividerColor = context.getCompatColor(R.color.divider)
        val panelBaseColor = ColorUtils.blendColors(
            bgColor,
            themeSurfaceColor,
            if (isBgLight) 0.90f else 0.82f
        )
        val panelInnerColor = ColorUtils.blendColors(
            bgColor,
            themeCardColor,
            if (isBgLight) 0.80f else 0.70f
        )
        bottomBackgroundList = Selector.colorBuild()
            .setDefaultColor(panelInnerColor)
            .setPressedColor(ColorUtils.darkenColor(panelInnerColor))
            .create()
        val panelStrokeColor = ColorUtils.adjustAlpha(
            if (isBgLight) dividerColor else textColor,
            if (isBgLight) 1f else 0.14f
        )
        val menuOpacity = (AppConfig.readMenuAlpha / 100f).coerceIn(0.35f, 1f)
        panelBackground = GradientDrawable().apply {
            cornerRadius = UiCorner.scaledDp(18F)
            setColor(ColorUtils.withAlpha(panelBaseColor, menuOpacity))
            setStroke(1.dpToPx(), panelStrokeColor)
        }
        llBottomMenu.background = panelBackground
        llSearchBaseInfo.background = GradientDrawable().apply {
            cornerRadius = UiCorner.searchRadius(14F)
            setColor(ColorUtils.withAlpha(panelInnerColor, menuOpacity))
            setStroke(1.dpToPx(), panelStrokeColor)
        }
        tvCurrentSearchInfo.setTextColor(textColor)
        llBottomBg.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        fabLeft.backgroundTintList = bottomBackgroundList
        fabLeft.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
        fabRight.backgroundTintList = bottomBackgroundList
        fabRight.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
        tvMainMenu.setTextColor(textColor)
        tvSearchResults.setTextColor(textColor)
        tvSearchExit.setTextColor(textColor)
        ivMainMenu.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        ivSearchResults.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        ivSearchExit.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        ivSearchContentUp.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
        ivSearchContentDown.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
        applyNavigationBarPadding()
    }


    fun runMenuIn() {
        callBack.onMenuShow()
        this.visible()
        binding.llBottomMenu.visible()
        binding.vwMenuBg.visible()
        refreshGlass()
        binding.flBottomMenu.startAnimation(menuBottomIn)
    }

    fun runMenuOut(onMenuOutEnd: (() -> Unit)? = null) {
        if (isMenuOutAnimating || !isVisible) {
            return
        }
        this.onMenuOutEnd = onMenuOutEnd
        callBack.onMenuHide()
        binding.flBottomMenu.startAnimation(menuBottomOut)
    }

    @SuppressLint("SetTextI18n")
    fun updateSearchInfo() {
        ReadBook.curTextChapter?.let {
            binding.tvCurrentSearchInfo.text =
                """${context.getString(R.string.search_content_size)}: ${searchResultList.size} / 当前章节: ${it.title}"""
        }
    }

    fun updateSearchResultIndex(updateIndex: Int) {
        lastSearchResultIndex = currentSearchResultIndex
        currentSearchResultIndex = when {
            updateIndex < 0 -> 0
            updateIndex >= searchResultList.size -> searchResultList.size - 1
            else -> updateIndex
        }
    }

    private fun bindEvent() = binding.run {
        //搜索结果
        llSearchResults.setOnClickListener {
            runMenuOut {
                callBack.openSearchActivity(selectedSearchResult?.query)
            }
        }

        //主菜单
        llMainMenu.setOnClickListener {
            runMenuOut {
                callBack.cancelSelect()
                callBack.showMenuBar()
                this@SearchMenu.invisible()
            }
        }

        //退出
        llSearchExit.setOnClickListener {
            runMenuOut {
                callBack.exitSearchMenu()
            }
        }

        fabLeft.setOnClickListener {
            updateSearchResultIndex(currentSearchResultIndex - 1)
            callBack.navigateToSearch(
                searchResultList[currentSearchResultIndex],
                currentSearchResultIndex
            )
        }

        ivSearchContentUp.setOnClickListener {
            updateSearchResultIndex(currentSearchResultIndex - 1)
            callBack.navigateToSearch(
                searchResultList[currentSearchResultIndex],
                currentSearchResultIndex
            )
        }

        ivSearchContentDown.setOnClickListener {
            updateSearchResultIndex(currentSearchResultIndex + 1)
            callBack.navigateToSearch(
                searchResultList[currentSearchResultIndex],
                currentSearchResultIndex
            )
        }

        fabRight.setOnClickListener {
            updateSearchResultIndex(currentSearchResultIndex + 1)
            callBack.navigateToSearch(
                searchResultList[currentSearchResultIndex],
                currentSearchResultIndex
            )
        }
    }

    private fun initAnimation() {
        //显示菜单
        menuBottomIn.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {
                callBack.upSystemUiVisibility()
                binding.fabLeft.visible(hasSearchResult)
                binding.fabRight.visible(hasSearchResult)
            }

            @SuppressLint("RtlHardcoded")
            override fun onAnimationEnd(animation: Animation) {
                binding.vwMenuBg.setOnClickListener { runMenuOut() }
                callBack.upSystemUiVisibility()
            }

            override fun onAnimationRepeat(animation: Animation) = Unit
        })

        //隐藏菜单
        menuBottomOut.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {
                isMenuOutAnimating = true
                binding.vwMenuBg.setOnClickListener(null)
            }

            override fun onAnimationEnd(animation: Animation) {
                isMenuOutAnimating = false
                hideGlass()
                binding.llBottomMenu.invisible()
                binding.vwMenuBg.invisible()
                this@SearchMenu.invisible()
                binding.vwMenuBg.setOnClickListener { runMenuOut() }

                callBack.onMenuHidden()
                onMenuOutEnd?.invoke()
                callBack.upSystemUiVisibility()
            }

            override fun onAnimationRepeat(animation: Animation) = Unit
        })
    }

    /**
     * 按当前档位与设备能力决定搜索菜单是否走液态玻璃。
     *
     * 采样源是正文 `read_view`（与本视图同级的兄弟子树）：EPUB 直读时它 `GONE`、
     * 不可见即视为无源；solid 档 / API<33 / 低内存 / 墨水屏由
     * [contentLiquidGlassSpec] 统一否决。任一条命中都保持原有静态面板外观。
     */
    private fun refreshGlass() {
        val source = activity?.findViewById<ViewGroup>(R.id.read_view)
            ?.takeIf { it.isShown && it.width > 0 && it.height > 0 }
        val spec = contentLiquidGlassSpec(
            params = GlassMaterialPreset.current(resolvedExploreGlassMode()),
            density = resources.displayMetrics.density,
            sdkInt = Build.VERSION.SDK_INT,
            sourceAvailable = source != null,
            isLowRam = ExploreGlassBackdrop.isLowRamDevice(context),
            isEInk = AppConfig.isEInkMode
        )
        if (spec == null || source == null) {
            hideGlass()
            return
        }
        glassActive = true
        glassTintAlpha = spec.tintAlpha
        glass.setCornerRadius(UiCorner.scaledDp(18F))
        glass.applyLiquidGlassSpec(spec)
        glass.bind(source)
        glass.visibility = VISIBLE
        applyPanelSurface()
    }

    /** 菜单收起后解绑：不采样就没有每帧整树录制。 */
    private fun hideGlass() {
        glassActive = false
        glassTintAlpha = 0f
        glass.clearAnimation()
        glass.bind(null)
        glass.visibility = GONE
        applyPanelSurface()
    }

    /** 玻璃层量到尺寸才让出面板底色，否则保持静态面板，避免一帧透明空面板。 */
    private fun applyPanelSurface() {
        val target =
            if (glassPanelFillYielded(glassActive, glass.width > 0 && glass.height > 0)) null
            else panelBackground
        if (binding.llBottomMenu.background !== target) {
            binding.llBottomMenu.background = target
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE && glassActive) {
            hideGlass()
        }
    }

    interface CallBack {
        var isShowingSearchResult: Boolean
        fun openSearchActivity(searchWord: String?)
        fun showSearchSetting()
        fun upSystemUiVisibility()
        fun exitSearchMenu()
        fun showMenuBar()
        fun navigateToSearch(searchResult: SearchResult, index: Int)
        fun onMenuShow()
        fun onMenuHide()
        /** Called after the exit animation has made the complete menu invisible. */
        fun onMenuHidden() = Unit
        fun cancelSelect()
    }

}
