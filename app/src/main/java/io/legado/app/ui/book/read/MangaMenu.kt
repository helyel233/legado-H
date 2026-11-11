package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.databinding.ViewMangaMenuBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.getSourceType
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.glass.applyLiquidGlassSpec
import io.legado.app.ui.glass.contentLiquidGlassSpec
import io.legado.app.ui.glass.glassPanelFillYielded
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.explore.ExploreGlassBackdrop
import io.legado.app.ui.main.explore.resolvedExploreGlassMode
import io.legado.app.ui.widget.StableLiquidGlassView
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.activity
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.loadAnimation
import io.legado.app.utils.openUrl
import io.legado.app.utils.startActivity
import io.legado.app.utils.visible

class MangaMenu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val binding = ViewMangaMenuBinding.inflate(LayoutInflater.from(context), this, true)
    private val callBack: CallBack get() = activity as CallBack
    var canShowMenu: Boolean = false
    private val menuTopIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_in)
    }
    private val menuTopOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_out)
    }
    private val menuBottomIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_bottom_in)
    }
    private val menuBottomOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_bottom_out)
    }
    private var isMenuOutAnimating = false

    /**
     * 底部控制条的液态玻璃层：与控制条同处 [ViewMangaMenuBinding.flMangaControlPill]，
     * 随 [ViewMangaMenuBinding.bottomMenu] 的平移动画一起移动，逐帧同形。
     *
     * 采样源是漫画正文 `webtoon_frame`（与本视图同级的兄弟子树）；玻璃层只负责
     * 背景，不参与触摸。顶部标题栏用的是同源的 [titleGlass]。
     */
    private val glass = StableLiquidGlassView(context).apply {
        visibility = GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * 顶部标题栏玻璃层：与 [glass] 同源（正文 `webtoon_frame`）、同材质，半径取 0——
     * TitleBar 本身是贴边全宽矩形，加圆角会在屏幕顶部两角留下缺口。
     * 作为标题栏的兄弟节点插在 `vw_menu_bg` 之上、`title_bar` 之下，随同一段
     * 平移动画移动，并让出 TitleBar 的主题主色底。
     */
    private val titleGlass = StableLiquidGlassView(context).apply {
        visibility = GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val titleGlassIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_in)
    }
    private val titleGlassOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_out)
    }
    private var glassActive = false
    private var pillBackground: Drawable? = null
    /** TitleBar 初始化后的原背景（主题主色 / 墨水屏描边），玻璃让出后按它还原。 */
    private var titleBarBackground: Drawable? = null

    private val menuOutListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            isMenuOutAnimating = true
            binding.vwMenuBg.setOnClickListener(null)
        }

        override fun onAnimationEnd(animation: Animation) {
            hideGlass()
            this@MangaMenu.invisible()
            binding.titleBar.invisible()
            binding.bottomMenu.invisible()
            isMenuOutAnimating = false
            canShowMenu = false
            callBack.upSystemUiVisibility(false)
        }

        override fun onAnimationRepeat(animation: Animation) = Unit
    }
    private val menuInListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            binding.tvSourceAction.text =
                ReadManga.bookSource?.bookSourceName ?: context.getString(R.string.book_source)
            callBack.upSystemUiVisibility(true)
            binding.tvSourceAction.isGone = false
        }

        @SuppressLint("RtlHardcoded")
        override fun onAnimationEnd(animation: Animation) {
            binding.run {
                vwMenuBg.setOnClickListener { runMenuOut() }
            }
        }

        override fun onAnimationRepeat(animation: Animation) = Unit
    }

    init {
        binding.root.applyUiBodyTypefaceDeep(context.uiTypeface())
        binding.flMangaControlPill.addView(
            glass, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        glass.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (glassActive) applyPillSurface()
        }
        binding.root.addView(titleGlass, 1, ConstraintLayout.LayoutParams(0, 0).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = R.id.title_bar
            bottomToBottom = R.id.title_bar
        })
        titleGlass.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (glassActive) applyTitleSurface()
        }
        initView()
        bindEvent()
    }

    private fun initView() = binding.run {
        initAnimation()
        val textColor = context.primaryTextColor
        val secondaryTextColor = context.secondaryTextColor
        tvChapterName.setTextColor(secondaryTextColor)
        tvChapterUrl.setTextColor(secondaryTextColor)
        tvPre.setTextColor(textColor)
        tvNext.setTextColor(textColor)
        pillBackground = llMangaControlPill.background
        if (AppConfig.isEInkMode) {
            titleBar.setBackgroundResource(R.drawable.bg_eink_border_bottom)
            bottomMenu.setBackgroundResource(R.drawable.bg_eink_border_top)
        } else {
            bottomMenu.setBackgroundColor(Color.TRANSPARENT)
        }
        titleBarBackground = titleBar.background
        if (AppConfig.showReadTitleBarAddition) {
            titleBarAddition.visible()
        } else {
            titleBarAddition.gone()
        }
        /**
         * 确保视图不被导航栏遮挡
         */
        bottomMenu.applyNavigationBarPadding()
    }

    private fun initAnimation() {
        menuTopIn.setAnimationListener(menuInListener)
        menuTopOut.setAnimationListener(menuOutListener)
    }

    fun runMenuOut(anim: Boolean = !AppConfig.isEInkMode) {
        if (isMenuOutAnimating) {
            return
        }
        if (this.isVisible) {
            if (anim) {
                binding.titleBar.startAnimation(menuTopOut)
                if (titleGlass.isVisible) titleGlass.startAnimation(titleGlassOut)
                binding.bottomMenu.startAnimation(menuBottomOut)
            } else {
                menuOutListener.onAnimationStart(menuBottomOut)
                menuOutListener.onAnimationEnd(menuBottomOut)
            }
        }
    }

    fun runMenuIn(anim: Boolean = !AppConfig.isEInkMode) {
        this.visible()
        binding.titleBar.visible()
        binding.bottomMenu.visible()
        refreshGlass()
        if (anim) {
            binding.titleBar.startAnimation(menuTopIn)
            if (titleGlass.isVisible) titleGlass.startAnimation(titleGlassIn)
            binding.bottomMenu.startAnimation(menuBottomIn)
        } else {
            menuInListener.onAnimationStart(menuBottomIn)
            menuInListener.onAnimationEnd(menuBottomIn)
        }
    }


    private fun bindEvent() = binding.run {
        vwMenuBg.setOnClickListener { runMenuOut() }
        titleBar.toolbar.setOnClickListener {
            callBack.openBookInfoActivity()
        }
        val chapterViewClickListener = OnClickListener {
            val url = tvChapterUrl.text.toString().trim()
            if (url.isBlank()) return@OnClickListener
            context.startActivity<WebViewActivity> {
                val bookSource = ReadBook.bookSource
                putExtra("title", tvChapterName.text)
                putExtra("url", url)
                putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                putExtra("sourceName", bookSource?.bookSourceName)
                putExtra("sourceType", bookSource?.getSourceType())
            }
        }
        val chapterViewLongClickListener = OnLongClickListener {
            val url = tvChapterUrl.text.toString().trim()
            if (url.isNotBlank()) {
                context.alert(R.string.open_fun) {
                    setMessage(R.string.use_browser_open)
                    okButton {
                        context.openUrl(url)
                    }
                    noButton()
                }
            }
            true
        }
        tvChapterName.setOnClickListener(chapterViewClickListener)
        tvChapterName.setOnLongClickListener(chapterViewLongClickListener)
        tvChapterUrl.setOnClickListener(chapterViewClickListener)
        tvChapterUrl.setOnLongClickListener(chapterViewLongClickListener)

        tvNext.setOnClickListener {
            ReadManga.moveToNextChapter(true)
        }
        tvPre.setOnClickListener {
            ReadManga.moveToPrevChapter(true)
        }
        seekReadPage.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    callBack.skipToPage(seekBar.progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                binding.vwMenuBg.setOnClickListener(null)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                binding.vwMenuBg.setOnClickListener { runMenuOut() }
            }
        })
    }

    fun upSeekBar(value: Int, count: Int) {
        binding.seekReadPage.apply {
            max = count.minus(1)
            progress = value
        }
    }

    /**
     * 按当前档位与设备能力决定底部控制条是否走液态玻璃。
     *
     * 采样源是漫画正文 `webtoon_frame`（与本视图同级的兄弟子树）；菜单打开期间
     * 全屏遮罩接管触摸，正文静止，不存在「滚动中逐帧录制大图列表」的场景。
     * solid 档 / API<33 / 低内存 / 墨水屏由 [contentLiquidGlassSpec] 统一否决。
     */
    private fun refreshGlass() {
        val source = activity?.findViewById<ViewGroup>(R.id.webtoon_frame)
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
        glass.setCornerRadius(
            resources.getDimensionPixelSize(R.dimen.manga_control_bar_radius).toFloat()
        )
        glass.applyLiquidGlassSpec(spec)
        glass.bind(source)
        glass.visibility = VISIBLE
        titleGlass.setCornerRadius(0f)
        titleGlass.applyLiquidGlassSpec(spec)
        titleGlass.bind(source)
        titleGlass.visibility = VISIBLE
        applyPillSurface()
        applyTitleSurface()
    }

    /** 菜单收起后解绑：不采样就没有每帧整树录制。 */
    private fun hideGlass() {
        glassActive = false
        glass.clearAnimation()
        glass.bind(null)
        glass.visibility = GONE
        titleGlass.clearAnimation()
        titleGlass.bind(null)
        titleGlass.visibility = GONE
        applyPillSurface()
        applyTitleSurface()
    }

    /** 玻璃层量到尺寸才让出控制条底色，否则保持实底，避免一帧透明空条。 */
    private fun applyPillSurface() {
        val target =
            if (glassPanelFillYielded(glassActive, glass.width > 0 && glass.height > 0)) null
            else pillBackground
        if (binding.llMangaControlPill.background !== target) {
            binding.llMangaControlPill.background = target
        }
    }

    /**
     * 标题栏同理：玻璃层量到尺寸才让出主题主色底，否则保留实底。
     * 墨水屏 / API<33 / 低内存时 glassActive 恒为 false，背景始终是
     * [titleBarBackground]（主色或墨水屏描边）。
     */
    private fun applyTitleSurface() {
        val target =
            if (glassPanelFillYielded(glassActive, titleGlass.width > 0 && titleGlass.height > 0)) null
            else titleBarBackground
        if (binding.titleBar.background !== target) {
            binding.titleBar.background = target
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE && glassActive) {
            hideGlass()
        }
    }

    interface CallBack {
        fun openBookInfoActivity()
        fun openMangaConfig()
        fun upSystemUiVisibility(menuIsVisible: Boolean)
        fun skipToPage(index: Int)
    }

}
