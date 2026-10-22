package io.legado.app.ui.book.read.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import com.airbnb.lottie.ImageAssetDelegate
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.airbnb.lottie.LottieImageAsset
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.RenderMode
import com.airbnb.lottie.TextDelegate
import com.airbnb.lottie.model.LottieCompositionCache
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.withTranslation
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppConst.timeFormat
import io.legado.app.data.entities.Bookmark
import io.legado.app.databinding.ViewBookPageBinding
import io.legado.app.help.book.isEpub
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.help.config.LottieDerivedResourceCache
import io.legado.app.help.config.AdvancedTipConfig
import io.legado.app.help.config.AdvancedTipSlot
import io.legado.app.help.config.AdvancedTitleFontAssetDelegate
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.help.config.TipTemplateRenderer
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.entities.ReadSelectionPosition
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.widget.BatteryView
import io.legado.app.utils.activity
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.decodeBase64DataUrlBytes
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.setTextIfNotEqual
import splitties.views.backgroundColor
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Date
import kotlinx.coroutines.CoroutineStart
import org.json.JSONObject

/**
 * 页面视图
 */
class PageView(context: Context) : FrameLayout(context) {

    private val binding = ViewBookPageBinding.inflate(LayoutInflater.from(context), this, true)
    private val readBookActivity get() = activity as? ReadBookActivity
    private var battery = 100
    private var tvTitle: BatteryView? = null
    private var tvTime: BatteryView? = null
    private var tvBattery: BatteryView? = null
    private var tvBatteryP: BatteryView? = null
    private var tvPage: BatteryView? = null
    private var tvTotalProgress: BatteryView? = null
    private var tvTotalProgress1: BatteryView? = null
    private var tvPageAndTotal: BatteryView? = null
    private var tvBookName: BatteryView? = null
    private var tvTimeBattery: BatteryView? = null
    private var tvTimeBatteryP: BatteryView? = null
    private var isMainView = false
    /** Bumped when page-turn screenshot pixels may change (content / tip / lottie). */
    var snapRevision: Long = 1L
        private set
    private var overlayBindToken: Long = 0L
    private var overlayBindScheduledToken: Long = 0L
    private val overlayBindRunnable = Runnable {
        if (overlayBindScheduledToken != overlayBindToken) return@Runnable
        bindAdvancedOverlaysIdle()
    }
    private var currentTextPage: TextPage? = null
    private var pairedTextPage: TextPage? = null
    /**
     * 进阶章节标题装载（F 区），逻辑在 AdvancedTitleLottieController 里，
     * 这里只提供它需要的宿主环境。
     */
    private val title = AdvancedTitleLottieController(
        TitleHost(),
        MAX_TITLE_KEYS_BY_PAGE,
        MAX_COMMITTED_TITLES
    )

    private inner class TitleHost : AdvancedTitleHost {

        override fun titleViews(slot: AdvancedTitleSlot): Pair<LottieAnimationView, TextView> {
            return this@PageView.titleViews(slot)
        }

        override fun contentViewWidth(): Int = binding.contentTextView.width

        override fun contentViewHeight(): Int = binding.contentTextView.height

        override fun scrollOverlayHeight(): Float {
            return binding.advancedTitleOverlay.height.toFloat()
                .takeIf { it > 0f }
                ?: (binding.llHeader.height + binding.contentTextView.height).toFloat()
                    .coerceAtLeast(1f)
        }

        override val isScroll: Boolean
            get() = this@PageView.isScroll

        override val isMainView: Boolean
            get() = this@PageView.isMainView

        override fun currentTextPage(): TextPage? = this@PageView.currentTextPage

        override fun pairedTextPage(): TextPage? = this@PageView.pairedTextPage

        override fun isHorizontalPageTurnActive(): Boolean =
            (parent as? ReadView)?.isHorizontalPageTurnActive == true

        override fun markSnapDirty() = this@PageView.markSnapDirty()

        override fun clearAdvancedTitleLoadingState(view: LottieAnimationView) =
            this@PageView.clearAdvancedTitleLoadingState(view)

        override fun defaultFontAssetDelegate(): AdvancedTitleFontAssetDelegate =
            this@PageView.defaultFontAssetDelegate

        override fun scheduleOverlayBind() = this@PageView.scheduleOverlayBind()

        override fun bookUrl(): String = ReadBook.book?.bookUrl.orEmpty()

        override fun androidContext(): Context = context

        override fun hasScrollRelativePage(pos: Int): Boolean =
            binding.contentTextView.hasScrollRelativePage(pos)

        override fun scrollRelativePage(pos: Int): TextPage =
            binding.contentTextView.scrollRelativePage(pos)

        override fun scrollRelativeOffset(pos: Int): Float =
            binding.contentTextView.scrollRelativeOffset(pos)
    }

    /**
     * 页眉页脚要显示的阅读信息。经典文本 tip 与进阶动效包都从它渲染，
     * 所以这份状态放在 PageView，双方共用。
     */
    private var lastTipContext: AdvancedTipConfig.TipContext = AdvancedTipConfig.TipContext()
    /**
     * 页眉页脚进阶动效装载（G 区），逻辑在 AdvancedTipLottieController 里，
     * 这里只提供它需要的宿主环境。
     */
    private val advancedTip = AdvancedTipLottieController(TipHost())

    private inner class TipHost : AdvancedTipHost {

        override fun tipView(slot: AdvancedTipSlot): LottieAnimationView {
            return when (slot) {
                AdvancedTipSlot.HEADER -> binding.advancedHeaderLottie
                AdvancedTipSlot.FOOTER -> binding.advancedFooterLottie
            }
        }

        override fun contentWidth(): Int = binding.contentTextView.width

        override fun contentGeneration(): Long = title.contentGeneration

        override val isMainView: Boolean
            get() = this@PageView.isMainView

        override fun isEpubBook(): Boolean = this@PageView.isEpubBook()

        override val batteryLevel: Int
            get() = battery

        override fun currentTextPage(): TextPage? = this@PageView.currentTextPage

        override fun bookName(): String = ReadBook.book?.name.orEmpty()

        override fun bookAuthor(): String = ReadBook.book?.author.orEmpty()

        override fun isHorizontalPageTurnActive(): Boolean =
            (parent as? ReadView)?.isHorizontalPageTurnActive == true

        override var tipContext: AdvancedTipConfig.TipContext
            get() = lastTipContext
            set(value) {
                lastTipContext = value
            }

        override fun markSnapDirty() = this@PageView.markSnapDirty()

        override fun animationFreeze(): AdvancedTitleAnimationFreeze = title.animations

        override fun clearAdvancedTitleLoadingState(view: LottieAnimationView) =
            this@PageView.clearAdvancedTitleLoadingState(view)

        override fun setClassicTipFallbackVisible(slot: AdvancedTipSlot, visible: Boolean) {
            this@PageView.setClassicTipFallbackVisible(slot, visible)
        }

        override fun defaultFontAssetDelegate(): AdvancedTitleFontAssetDelegate =
            this@PageView.defaultFontAssetDelegate
    }
    var isScroll = false

    val headerHeight: Int
        get() {
            val h1 = if (binding.vwStatusBar.isGone) 0 else binding.vwStatusBar.height
            val h2 = if (binding.llHeader.isGone) 0 else binding.llHeader.height
            return h1 + h2 + binding.vwRoot.paddingTop
        }
    val imgBgPaddingStart: Int
        get() {
            return binding.vwRoot.paddingStart
        }

    init {
        if (!isInEditMode) {
            // Hard-clip title to content band so it cannot paint through the (transparent) header.
            binding.advancedTitleOverlay.clipChildren = true
            binding.advancedTitleOverlay.clipToPadding = true
            // Avoid clipToOutline/SOFTWARE Lottie at inflate: every book pays this cost and
            // HarmonyOS mid-range devices can fail to draw the first frame (black screen).
            binding.advancedTitleOverlay.elevation = 0f
            upStyle()
            binding.vwStatusBar.applyStatusBarPadding()
            binding.vwNavigationBar.applyNavigationBarPadding()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        upBg()
    }

    override fun onDetachedFromWindow() {
        disposeAdvancedTitleRequests()
        binding.contentTextView.setScrollFollowBackground(null, 255)
        binding.vwRoot.background = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // onDetachedFromWindow releases the potentially large wallpaper drawable. A same-size
        // reattach does not guarantee onSizeChanged, so restore it explicitly here.
        upBg()
        val textPage = currentTextPage ?: return
        title.showSynchronousFallbacks(textPage, pairedTextPage)
        scheduleOverlayBind()
    }

    fun upStyle() = binding.run {
        upTipStyle()
        ReadBookConfig.let {
            val textColor = it.textColor
            val tipColor = with(ReadTipConfig) {
                if (tipColor == 0) textColor else tipColor
            }
            val tipDividerColor = with(ReadTipConfig) {
                when (tipDividerColor) {
                    -1 -> ContextCompat.getColor(context, R.color.divider)
                    0 -> textColor
                    else -> tipDividerColor
                }
            }
            tvHeaderLeft.setColor(tipColor)
            tvHeaderMiddle.setColor(tipColor)
            tvHeaderRight.setColor(tipColor)
            tvFooterLeft.setColor(tipColor)
            tvFooterMiddle.setColor(tipColor)
            tvFooterRight.setColor(tipColor)
            advancedTitleFallback.setTextColor(textColor)
            advancedTitleFallbackPair.setTextColor(textColor)
            advancedTitleFallback.textSize =
                LottieTitleJsonStyler.textSizeSp(
                    ReadBookConfig.textSize.toFloat(),
                    ReadBookConfig.titleSize.toFloat()
                )
            advancedTitleFallbackPair.textSize =
                LottieTitleJsonStyler.textSizeSp(
                    ReadBookConfig.textSize.toFloat(),
                    ReadBookConfig.titleSize.toFloat()
                )
            val titleTypeface = ChapterProvider.titlePaint.typeface ?: ChapterProvider.typeface
            advancedTitleFallback.typeface = titleTypeface
            advancedTitleFallbackPair.typeface = titleTypeface
            vwTopDivider.backgroundColor = tipDividerColor
            vwBottomDivider.backgroundColor = tipDividerColor
            upStatusBar()
            upNavigationBar()
            upPaddingDisplayCutouts()
            llHeader.setPadding(
                it.headerPaddingLeft.dpToPx(),
                it.headerPaddingTop.dpToPx(),
                it.headerPaddingRight.dpToPx(),
                it.headerPaddingBottom.dpToPx()
            )
            llFooter.setPadding(
                it.footerPaddingLeft.dpToPx(),
                it.footerPaddingTop.dpToPx(),
                it.footerPaddingRight.dpToPx(),
                it.footerPaddingBottom.dpToPx()
            )
            vwTopDivider.gone(llHeader.isGone || !it.showHeaderLine)
            vwBottomDivider.gone(llFooter.isGone || !it.showFooterLine)
        }
        upTime()
        upBattery(battery)
        invalidateTextRenderCache()
    }

    fun invalidateTextRenderCache() {
        currentTextPage?.invalidateAll()
        pairedTextPage?.invalidateAll()
        ViewCompat.postInvalidateOnAnimation(binding.contentTextView)
    }

    /**
     * 显示状态栏时隐藏header
     */
    fun upStatusBar() = with(binding.vwStatusBar) {
//        setPadding(paddingLeft, context.statusBarHeight, paddingRight, paddingBottom)
        isGone = ReadBook.book?.isEpub == true ||
            ReadBookConfig.hideStatusBar ||
            readBookActivity?.isInMultiWindow == true
    }

    fun upNavigationBar() {
        binding.vwNavigationBar.isGone = ReadBook.book?.isEpub == true || ReadBookConfig.hideNavigationBar
    }

    fun upPaddingDisplayCutouts() {
        if (ReadBookConfig.isNineBgImg) {
            ViewCompat.setOnApplyWindowInsetsListener(binding.vwRoot, null)
            return
        }
        if (AppConfig.paddingDisplayCutouts) {
            binding.vwRoot.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                binding.vwRoot.setPadding(
                    insets.left,
                    if (binding.vwStatusBar.isGone) insets.top else 0,
                    insets.right,
                    insets.bottom
                )
                windowInsets
            }
        } else {
            ViewCompat.setOnApplyWindowInsetsListener(binding.vwRoot, null)
            binding.vwRoot.setPadding(0, 0, 0, 0)
        }
    }

    /**
     * 更新阅读信息
     */
    private fun upTipStyle(textPage: TextPage? = currentTextPage) = binding.run {
        val isEpub = ReadBook.book?.isEpub == true
        tvHeaderLeft.tag = null
        tvHeaderMiddle.tag = null
        tvHeaderRight.tag = null
        tvFooterLeft.tag = null
        tvFooterMiddle.tag = null
        tvFooterRight.tag = null
        llHeader.isGone = if (isEpub) {
            true
        } else {
            when (ReadTipConfig.headerMode) {
                ReadTipConfig.HEADER_MODE_SHOW,
                ReadTipConfig.HEADER_MODE_ADVANCED -> false
                ReadTipConfig.HEADER_MODE_HIDE -> true
                else -> !ReadBookConfig.hideStatusBar
            }
        }
        llFooter.isGone = if (isEpub) {
            true
        } else {
            when (ReadTipConfig.footerMode) {
                ReadTipConfig.FOOTER_MODE_HIDE -> true
                else -> false
            }
        }
        ReadTipConfig.apply {
            tvHeaderLeft.isGone = tipHeaderLeft == none
            tvHeaderRight.isGone = tipHeaderRight == none
            tvHeaderMiddle.isGone = tipHeaderMiddle == none
            tvFooterLeft.isInvisible = tipFooterLeft == none
            tvFooterRight.isGone = tipFooterRight == none
            tvFooterMiddle.isGone = tipFooterMiddle == none
        }
        tvTitle = getTipView(ReadTipConfig.chapterTitle)?.apply {
            tag = ReadTipConfig.chapterTitle
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 12f
        }
        tvTime = getTipView(ReadTipConfig.time)?.apply {
            tag = ReadTipConfig.time
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 12f
        }
        tvBattery = getTipView(ReadTipConfig.battery)?.apply {
            tag = ReadTipConfig.battery
            isBattery = true
            textSize = 11f
        }
        tvPage = getTipView(ReadTipConfig.page)?.apply {
            tag = ReadTipConfig.page
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 12f
        }
        tvTotalProgress = getTipView(ReadTipConfig.totalProgress)?.apply {
            tag = ReadTipConfig.totalProgress
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 12f
        }
        tvTotalProgress1 = getTipView(ReadTipConfig.totalProgress1)?.apply {
            tag = ReadTipConfig.totalProgress1
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 12f
        }
        tvPageAndTotal = getTipView(ReadTipConfig.pageAndTotal)?.apply {
            tag = ReadTipConfig.pageAndTotal
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 12f
        }
        tvBookName = getTipView(ReadTipConfig.bookName)?.apply {
            tag = ReadTipConfig.bookName
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 12f
        }
        tvTimeBattery = getTipView(ReadTipConfig.timeBattery)?.apply {
            tag = ReadTipConfig.timeBattery
            isBattery = true
            typeface = ChapterProvider.typeface
            textSize = 11f
        }
        tvBatteryP = getTipView(ReadTipConfig.batteryPercentage)?.apply {
            tag = ReadTipConfig.batteryPercentage
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 12f
        }
        tvTimeBatteryP = getTipView(ReadTipConfig.timeBatteryPercentage)?.apply {
            tag = ReadTipConfig.timeBatteryPercentage
            isBattery = false
            typeface = ChapterProvider.typeface
            textSize = 12f
        }
        applyAdvancedTipChromeVisibility()
    }

    /**
     * 获取信息视图
     * @param tip 信息类型
     */
    private fun getTipView(tip: Int): BatteryView? = binding.run {
        return when (tip) {
            ReadTipConfig.tipHeaderLeft -> tvHeaderLeft
            ReadTipConfig.tipHeaderMiddle -> tvHeaderMiddle
            ReadTipConfig.tipHeaderRight -> tvHeaderRight
            ReadTipConfig.tipFooterLeft -> tvFooterLeft
            ReadTipConfig.tipFooterMiddle -> tvFooterMiddle
            ReadTipConfig.tipFooterRight -> tvFooterRight
            else -> null
        }
    }

    /**
     * 更新背景
     */
    fun upBg() {
        val bgDrawable = ReadBookConfig.bg?.safePageBackgroundDrawable()
        val followScrollBackground =
            AppConfig.readScrollFollowBackground &&
                isScroll &&
                !ReadBookConfig.isNineBgImg &&
                bgDrawable is BitmapDrawable &&
                !bgDrawable.bitmap.isRecycled
        val bgAlpha = (ReadBookConfig.bgAlpha / 100f * 255).toInt()
        if (followScrollBackground) {
            // Draw scrolling wallpaper on the whole page so header/footer are not solid mean-color bars.
            // Content no longer paints its own copy (avoids double-darkening).
            binding.contentTextView.setScrollFollowBackground(null, bgAlpha)
            val follow = ScrollFollowBackgroundDrawable(
                bitmap = bgDrawable.bitmap,
                offsetProvider = { binding.contentTextView.getBackgroundOffset() },
                yBiasProvider = { binding.contentTextView.top.toFloat() }
            ).apply { alpha = bgAlpha }
            binding.vwRoot.background = LayerDrawable(
                arrayOf(
                    ReadBookConfig.bgMeanColor.toDrawable(),
                    follow
                )
            )
            binding.llHeader.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            binding.llFooter.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } else {
            binding.contentTextView.setScrollFollowBackground(null, bgAlpha)
            binding.vwRoot.background = bgDrawable?.let {
                LayerDrawable(
                    arrayOf(
                        ReadBookConfig.bgMeanColor.toDrawable(),
                        it
                    )
                )
            } ?: ReadBookConfig.bgMeanColor.toDrawable()
            binding.llHeader.background = null
            binding.llFooter.background = null
        }
        upBgAlpha()
    }

    /**
     * 更新背景透明度
     */
    fun upBgAlpha() {
        val bgAlpha = (ReadBookConfig.bgAlpha / 100f * 255).toInt()
        binding.contentTextView.setScrollFollowBackgroundAlpha(bgAlpha)
        val background = binding.vwRoot.background
        if (background is LayerDrawable && background.numberOfLayers > 1) {
            background.getDrawable(1).alpha = bgAlpha
        } else {
            background?.alpha = bgAlpha
        }
        binding.vwRoot.invalidate()
    }

    private fun Drawable.safePageBackgroundDrawable(): Drawable? {
        if (this is BitmapDrawable) {
            val source = bitmap ?: return null
            if (source.isRecycled) return null
            return BitmapDrawable(resources, source).apply {
                alpha = this@safePageBackgroundDrawable.alpha
            }
        }
        return constantState?.newDrawable(resources)?.mutate() ?: mutate()
    }

    /**
     * 更新时间信息
     */
    fun upTime() {
        tvTime?.text = timeFormat.format(Date(System.currentTimeMillis()))
        upTimeBattery()
        lastTipContext = lastTipContext.copy(time = AdvancedTipConfig.currentTimeText())
        if (ReadTipConfig.isHeaderAdvanced() || ReadTipConfig.isFooterAdvanced()) {
            refreshAdvancedTipFieldsIfBound()
            markSnapDirty()
        }
        renderCustomTipTemplates()
    }

    /**
     * 更新电池信息
     */
    @SuppressLint("SetTextI18n")
    fun upBattery(battery: Int) {
        this.battery = battery
        tvBattery?.setBattery(battery)
        tvBatteryP?.text = "$battery%"
        upTimeBattery()
        lastTipContext = lastTipContext.copy(battery = battery.toString())
        if (ReadTipConfig.isHeaderAdvanced() || ReadTipConfig.isFooterAdvanced()) {
            refreshAdvancedTipFieldsIfBound()
            markSnapDirty()
        }
        renderCustomTipTemplates()
    }

    /**
     * 更新电池信息
     */
    @SuppressLint("SetTextI18n")
    private fun upTimeBattery() {
        val time = timeFormat.format(Date(System.currentTimeMillis()))
        tvTimeBattery?.setBattery(battery, time)
        tvTimeBatteryP?.text = "$time $battery%"
    }

    /**
     * 设置内容
     */
    private fun markSnapDirty() {
        snapRevision++
    }

    private fun titleViews(slot: AdvancedTitleSlot): Pair<LottieAnimationView, TextView> {
        return when (slot) {
            AdvancedTitleSlot.PRIMARY -> binding.advancedTitleLottie to binding.advancedTitleFallback
            AdvancedTitleSlot.PAIR -> binding.advancedTitleLottiePair to binding.advancedTitleFallbackPair
        }
    }

    internal fun disposeAdvancedTitleRequests() {
        title.nextContent()
        overlayBindToken++
        overlayBindScheduledToken = overlayBindToken
        removeCallbacks(overlayBindRunnable)
        title.clearInFlight()
        advancedTip.dispose()
        title.animations.reset()
        AdvancedTitleSlot.entries.forEach { title.disposeSlot(it) }
        advancedTip.clearAllSlots()
    }

    internal fun freezeAdvancedTitleAnimationsForPageTurn() {
        if (isScroll) return
        if (!title.animations.beginFreeze()) return
        var pixelsWereChanging = false
        AdvancedTitleSlot.entries.forEach { slot ->
            val (lottieView, _) = titleViews(slot)
            if (lottieView.visibility == VISIBLE && lottieView.isAnimating) {
                title.animations.markTitleToResume(slot)
                lottieView.pauseAnimation()
                pixelsWereChanging = true
            }
        }
        AdvancedTipSlot.entries.forEach { slot ->
            val lottieView = advancedTip.viewOf(slot)
            if (lottieView.visibility == VISIBLE && lottieView.isAnimating) {
                title.animations.markTipToResume(slot)
                lottieView.pauseAnimation()
                pixelsWereChanging = true
            }
        }
        if (pixelsWereChanging) {
            // Lottie frames do not otherwise advance snapRevision. Capture this stable live
            // frame instead of reusing a recorder from an earlier animation frame.
            markSnapDirty()
        }
    }

    internal fun resumeAdvancedTitleAnimationsAfterPageTurn() {
        if (!title.animations.isFrozen) return
        title.animations.endFreeze()
        if (isMainView && !isScroll) {
            AdvancedTitleSlot.entries.forEach { slot ->
                val shouldResume = title.animations.consumeTitleResume(slot)
                val (lottieView, _) = titleViews(slot)
                if (!shouldResume || lottieView.visibility != VISIBLE ||
                    !title.isReady(slot)
                ) return@forEach
                runCatching {
                    if (!lottieView.isAnimating) lottieView.playAnimation()
                }.onFailure { error ->
                    AppLog.put("PageView advanced title resume failed: $slot", error)
                    lottieView.pauseAnimation()
                }
            }
            AdvancedTipSlot.entries.forEach { slot ->
                val shouldResume = title.animations.consumeTipResume(slot)
                val lottieView = advancedTip.viewOf(slot)
                if (shouldResume && lottieView.visibility == VISIBLE &&
                    advancedTip.hasKey(slot) && !lottieView.isAnimating
                ) {
                    runCatching {
                        lottieView.playAnimation()
                    }.onFailure { error ->
                        AppLog.put("PageView advanced tip resume failed: $slot", error)
                        lottieView.pauseAnimation()
                    }
                }
            }
        } else {
            title.animations.clearResumePlan()
        }
    }

    private fun schedulePageTurnPrewarm() {
        (parent as? ReadView)?.schedulePageTurnPrewarm()
    }

    /**
     * Critical path: body text + classic tip labels only.
     * Advanced title/header/footer Lottie always binds on the next frame (idle),
     * so first open / chapter switch never stalls on setComposition or Lottie draw.
     */
    fun setContent(
        textPage: TextPage,
        pairedTextPage: TextPage? = null,
        resetPageOffset: Boolean = true
    ) {
        // Note which slots are already drawing this page's title before the generation bump
        // retires the tokens that proves it.
        val alreadyShowing = title.slotsAlreadyShowing(textPage, pairedTextPage)
        title.nextContent()
        title.clearInFlight()
        advancedTip.dispose()
        title.clearScrollBinding()
        AdvancedTitleSlot.entries.forEach { slot ->
            if (slot !in alreadyShowing) title.hideSlot(slot)
        }
        // Now that tokens belong to this generation, put back any title whose composition is
        // still held. Doing it here rather than in the posted bind keeps the slot from
        // sitting blank for a frame, which is what shows as a flash when turning back and
        // forth quickly.
        val retainedSlots = alreadyShowing +
            title.restoreCommitted(textPage, pairedTextPage, alreadyShowing)
        currentTextPage = textPage
        this.pairedTextPage = pairedTextPage
        markSnapDirty()
        upTipStyle(textPage)
        if (resetPageOffset) {
            resetPageOffset()
        }
        // Body text first and only on the hot path.
        binding.contentTextView.setContent(textPage, pairedTextPage, resetPageOffset)
        // Never let a previous page's composition survive beside the new body. The cheap,
        // correct-page fallback stays visible until the matching composition is drawable.
        title.showSynchronousFallbacks(textPage, pairedTextPage, retainedSlots)
        // Classic tip TextViews are cheap; keep them in sync for non-advanced chrome.
        applyProgressTexts(textPage)
        // If tip Lottie already composed, only refresh TextDelegate variables (no re-parse).
        refreshAdvancedTipFieldsIfBound()
        // Advanced Lottie bind/parse only on next frame; never prewarm screenshots here.
        scheduleOverlayBind()
    }

    private fun scheduleOverlayBind() {
        overlayBindToken++
        overlayBindScheduledToken = overlayBindToken
        removeCallbacks(overlayBindRunnable)
        // One frame later: never compete with body text layout/draw of this frame.
        post(overlayBindRunnable)
    }

    private fun bindAdvancedOverlaysIdle() {
        val textPage = currentTextPage ?: return
        val contentGeneration = title.contentGeneration
        runCatching {
            title.upLotties(textPage, pairedTextPage, contentGeneration)
        }.onFailure { error ->
            AppLog.put("PageView advanced title bind failed", error)
            title.showSynchronousFallbacks(textPage, pairedTextPage)
        }
        runCatching {
            if (ReadTipConfig.isHeaderAdvanced() || ReadTipConfig.isFooterAdvanced()) {
                upAdvancedTipLotties(textPage, contentGeneration)
            }
        }.onFailure { error ->
            AppLog.put("PageView advanced tip bind failed", error)
        }
        // Warm the next page's title so the first flip does not parse it on the hot path.
        runCatching {
            title.prewarmFromPage(pairedTextPage)
        }.onFailure { error ->
            AppLog.put("PageView advanced title prewarm failed", error)
        }
        // Header/footer packages are shared across page views; warming them once here
        // keeps the first flip off the parse/decode path.
        runCatching {
            prewarmAdvancedTipCompositions()
        }.onFailure { error ->
            AppLog.put("PageView advanced tip prewarm failed", error)
        }
    }

    /** Update classic tip labels without touching Lottie. */
    @SuppressLint("SetTextI18n")
    private fun applyProgressTexts(textPage: TextPage) = textPage.apply {
        tvBookName?.setTextIfNotEqual(ReadBook.book?.name)
        tvTitle?.setTextIfNotEqual(textPage.title)
        val readProgress = readProgress
        tvTotalProgress?.setTextIfNotEqual(readProgress)
        tvTotalProgress1?.setTextIfNotEqual("${chapterIndex.plus(1)}/${chapterSize}")
        if (textChapter.isCompleted) {
            tvPageAndTotal?.setTextIfNotEqual("${index.plus(1)}/$pageSize  $readProgress")
            tvPage?.setTextIfNotEqual("${index.plus(1)}/$pageSize")
        } else {
            val pageSizeInt = pageSize
            val pageSizeText = if (pageSizeInt <= 0) "-" else "~$pageSizeInt"
            tvPageAndTotal?.setTextIfNotEqual("${index.plus(1)}/$pageSizeText  $readProgress")
            tvPage?.setTextIfNotEqual("${index.plus(1)}/$pageSizeText")
        }
        lastTipContext = AdvancedTipConfig.TipContext(
            book = ReadBook.book?.name.orEmpty(),
            title = textPage.title,
            page = (index + 1).toString(),
            pages = if (textChapter.isCompleted) pageSize.toString() else if (pageSize <= 0) "-" else "~${pageSize}",
            progress = readProgress,
            time = AdvancedTipConfig.currentTimeText(),
            battery = battery.toString(),
            author = ReadBook.book?.author.orEmpty()
        )
        renderCustomTipTemplates()
    }

    /**
     * 渲染槽位为「自定义模板」的页眉页脚文本
     */
    private fun renderCustomTipTemplates() {
        val context = lastTipContext
        fun render(tip: Int, template: String, view: BatteryView?) {
            if (tip == ReadTipConfig.customTemplate && view != null) {
                view.setTextIfNotEqual(TipTemplateRenderer.render(template, context))
            }
        }
        render(
            ReadTipConfig.tipHeaderLeft,
            ReadTipConfig.tipHeaderLeftTemplate,
            binding.tvHeaderLeft
        )
        render(
            ReadTipConfig.tipHeaderMiddle,
            ReadTipConfig.tipHeaderMiddleTemplate,
            binding.tvHeaderMiddle
        )
        render(
            ReadTipConfig.tipHeaderRight,
            ReadTipConfig.tipHeaderRightTemplate,
            binding.tvHeaderRight
        )
        render(
            ReadTipConfig.tipFooterLeft,
            ReadTipConfig.tipFooterLeftTemplate,
            binding.tvFooterLeft
        )
        render(
            ReadTipConfig.tipFooterMiddle,
            ReadTipConfig.tipFooterMiddleTemplate,
            binding.tvFooterMiddle
        )
        render(
            ReadTipConfig.tipFooterRight,
            ReadTipConfig.tipFooterRightTemplate,
            binding.tvFooterRight
        )
    }

    /**
     * Cheap path: tip Lottie already on-screen for this package — only swap text fields.
     * Avoids setComposition / parse on page turns and minute ticks.
     */
    private fun refreshAdvancedTipFieldsIfBound() = advancedTip.refreshFieldsIfBound()

    fun invalidateContentView() {
        binding.contentTextView.invalidate()
    }

    /**
     * 设置无障碍文本
     */
    fun setContentDescription(content: String) {
        binding.contentTextView.contentDescription = content
    }

    /**
     * 重置滚动位置
     */
    fun resetPageOffset() {
        binding.contentTextView.resetPageOffset()
    }

    /**
     * 设置进度
     */
    @SuppressLint("SetTextI18n")
    fun setProgress(textPage: TextPage) {
        applyProgressTexts(textPage)
        // Prefer field-only refresh; full tip bind stays on idle overlay path.
        refreshAdvancedTipFieldsIfBound()
        if ((ReadTipConfig.isHeaderAdvanced() || ReadTipConfig.isFooterAdvanced()) &&
            !advancedTipViewsBound()
        ) {
            scheduleOverlayBind()
        }
    }

    private fun advancedTipViewsBound(): Boolean = advancedTip.viewsBound()

    fun setAutoPager(autoPager: AutoPager?) {
        binding.contentTextView.setAutoPager(autoPager)
    }

    fun submitRenderTask() {
        binding.contentTextView.submitRenderTask()
    }

    fun setIsScroll(value: Boolean) {
        val changed = isScroll != value
        isScroll = value
        if (changed && !value) {
            title.resetScrollState()
        }
        binding.contentTextView.setIsScroll(value)
        if (value) {
            AdvancedTitleSlot.entries.forEach { slot ->
                titleViews(slot).first.pauseAnimation()
            }
        } else if (!title.animations.isFrozen && isMainView) {
            AdvancedTitleSlot.entries.forEach { slot ->
                val lottieView = titleViews(slot).first
                if (lottieView.visibility == VISIBLE && title.isReady(slot)) {
                    lottieView.playAnimation()
                }
            }
        }
        if (changed && AppConfig.readScrollFollowBackground) {
            upBg()
        }
    }

    /**
     * 滚动事件
     */
    fun scroll(offset: Int) {
        binding.contentTextView.scroll(offset)
        if (isScroll) {
            // Position-only sync: never reparse/reload Lottie during finger scroll (avoids jitter).
            title.syncScrollPositions()
            // Root wallpaper is driven by backgroundScrollOffset; invalidate for follow-bg.
            if (AppConfig.readScrollFollowBackground) {
                binding.vwRoot.invalidate()
            }
        }
    }

    /**
     * 更新是否开启选择功能
     */
    fun upSelectAble(selectAble: Boolean) {
        binding.contentTextView.selectAble = selectAble
    }

    /**
     * 优先处理页面内单击
     * @return true:已处理, false:未处理
     */
    fun onClick(x: Float, y: Float): Boolean {
        return binding.contentTextView.click(x - imgBgPaddingStart, y - headerHeight)
    }

    /**
     * 命中音频块进度条（坐标与 onClick 一致，未命中返回 null）
     */
    fun hitAudioTrack(x: Float, y: Float): ImageColumn? {
        return binding.contentTextView.hitAudioTrack(x - imgBgPaddingStart, y - headerHeight)
    }

    /**
     * 音频块进度条跳转（坐标与 onClick 一致）
     */
    fun audioTrackSeek(column: ImageColumn, x: Float) {
        binding.contentTextView.audioTrackSeek(column, x - imgBgPaddingStart)
    }

    /**
     * 长按事件
     */
    fun longPress(
        x: Float, y: Float,
        select: (textPos: TextPos) -> Unit,
    ): Boolean =
        binding.contentTextView.longPress(x - imgBgPaddingStart, y - headerHeight, select)

    /**
     * 选择文本
     */
    fun selectText(
        x: Float, y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        return binding.contentTextView.selectText(x - imgBgPaddingStart, y - headerHeight, select)
    }

    fun getCurVisiblePage(): TextPage {
        return binding.contentTextView.getCurVisiblePage()
    }

    fun getReadAloudPos(): Pair<Int, TextLine>? {
        return binding.contentTextView.getReadAloudPos()
    }

    fun markAsMainView() {
        isMainView = true
        binding.contentTextView.isMainView = true
    }

    fun selectStartMove(x: Float, y: Float) {
        binding.contentTextView.selectStartMove(x - imgBgPaddingStart, y - headerHeight)
    }

    fun refreshSelectionHandles() = binding.contentTextView.refreshSelectionHandles()

    val selectionTop: Float get() = headerHeight + ChapterProvider.visibleRect.top
    val selectionBottom: Float get() = headerHeight + ChapterProvider.visibleRect.bottom

    /**
     * 选择端点的锚点（本视图坐标）：选区边界 x + 端点所在行的中线 y
     * 放大镜按这个点取景，气泡里看到的选中状态才能和实际选区一致
     */
    fun getSelectEndpointAnchor(textPos: TextPos, startPoint: Boolean): PointF {
        val anchor = binding.contentTextView.getSelectEndpointAnchor(textPos, startPoint)
        anchor.x += imgBgPaddingStart
        anchor.y += headerHeight
        return anchor
    }

    /** 手柄拖动路径：读取最近一次拖动实际生效的端点锚点，无记录时返回 null */
    fun takeLastMovedEndpointAnchor(): PointF? {
        binding.contentTextView.takeLastMovedEndpointAnchor()?.let { anchor ->
            anchor.x += imgBgPaddingStart
            anchor.y += headerHeight
            return anchor
        }
        return null
    }

    /** 当前选区某一端的锚点（该端未选中时返回 null），供手柄拖动在端点未实际移动时回退取景 */
    fun getSelectionEndpointAnchor(startPoint: Boolean): PointF? {
        return binding.contentTextView.getSelectionEndpointAnchor(startPoint)?.let { anchor ->
            anchor.x += imgBgPaddingStart
            anchor.y += headerHeight
            anchor
        }
    }

    /**
     * 把正文内容画到画布上（不含页眉页脚与手柄），供自绘放大镜复用
     */
    fun drawContentText(canvas: Canvas) {
        val content = binding.contentTextView
        canvas.withTranslation(content.left.toFloat(), content.top.toFloat()) {
            content.draw(this)
        }
    }

    fun selectStartMoveIndex(
        relativePagePos: Int,
        lineIndex: Int,
        charIndex: Int
    ) {
        binding.contentTextView.selectStartMoveIndex(relativePagePos, lineIndex, charIndex)
    }

    fun selectStartMoveIndex(textPos: TextPos) {
        binding.contentTextView.selectStartMoveIndex(textPos)
    }

    fun selectEndMove(x: Float, y: Float) {
        binding.contentTextView.selectEndMove(x - imgBgPaddingStart, y - headerHeight)
    }

    fun selectEndMoveIndex(
        relativePagePos: Int,
        lineIndex: Int,
        charIndex: Int
    ) {
        binding.contentTextView.selectEndMoveIndex(relativePagePos, lineIndex, charIndex)
    }

    fun selectEndMoveIndex(textPos: TextPos) {
        binding.contentTextView.selectEndMoveIndex(textPos)
    }

    fun getReverseStartCursor(): Boolean {
        return binding.contentTextView.reverseStartCursor
    }

    fun getReverseEndCursor(): Boolean {
        return binding.contentTextView.reverseEndCursor
    }

    fun isLongScreenShot(): Boolean {
        return binding.contentTextView.longScreenshot
    }

    fun resetReverseCursor() {
        binding.contentTextView.resetReverseCursor()
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        binding.contentTextView.cancelSelect(clearSearchResult)
    }

    fun createBookmark(): Bookmark? {
        return binding.contentTextView.createBookmark()
    }

    fun relativePage(relativePagePos: Int): TextPage {
        return binding.contentTextView.relativePage(relativePagePos)
    }

    internal fun flushPendingAdvancedTitleCompositions() {
        if (!title.canCommitPendingNow()) return
        title.commitPending()
        advancedTip.commitPending()
        resumeAdvancedTitleAnimationsAfterPageTurn()
    }

    private fun setClassicTipFallbackVisible(slot: AdvancedTipSlot, visible: Boolean) = binding.run {
        when (slot) {
            AdvancedTipSlot.HEADER -> {
                tvHeaderLeft.isGone = !visible || ReadTipConfig.tipHeaderLeft == ReadTipConfig.none
                tvHeaderMiddle.isGone = !visible || ReadTipConfig.tipHeaderMiddle == ReadTipConfig.none
                tvHeaderRight.isGone = !visible || ReadTipConfig.tipHeaderRight == ReadTipConfig.none
            }
            AdvancedTipSlot.FOOTER -> {
                tvFooterLeft.isInvisible = !visible || ReadTipConfig.tipFooterLeft == ReadTipConfig.none
                tvFooterMiddle.isGone = !visible || ReadTipConfig.tipFooterMiddle == ReadTipConfig.none
                tvFooterRight.isGone = !visible || ReadTipConfig.tipFooterRight == ReadTipConfig.none
            }
        }
    }

    private fun applyAdvancedTipChromeVisibility() = advancedTip.applyChromeVisibility()

    private fun isEpubBook(): Boolean = ReadBook.book?.isEpub == true

    /**
     * Parse and decode the advanced header/footer packages once, off the UI thread,
     * before the first page turn needs them. Without this the first flip pays for
     * JSON parsing, derived-resource extraction and image decoding all at once.
     */
    internal fun prewarmAdvancedTipCompositions() = advancedTip.prewarm()

    private fun upAdvancedTipLotties(textPage: TextPage?, contentGeneration: Long) =
        advancedTip.up(textPage, contentGeneration)

    private fun clearAdvancedTitleLoadingState(view: LottieAnimationView) {
        view.animate().cancel()
        view.removeAllLottieOnCompositionLoadedListener()
        view.setFailureListener(null)
        view.tag = null
        view.alpha = 1f
    }

    private val defaultFontAssetDelegate = AdvancedTitleFontAssetDelegate {
        ChapterProvider.titlePaint.typeface ?: ChapterProvider.typeface ?: Typeface.DEFAULT
    }

    val textPage get() = binding.contentTextView.textPage

    val selectedText: String get() = binding.contentTextView.getSelectedText()

    fun hasSelection(): Boolean = binding.contentTextView.hasSelection()

    fun hasNativeSelection(): Boolean = binding.contentTextView.hasNativeSelection()

    fun getSelectedReadPosition(): ReadSelectionPosition? =
        binding.contentTextView.getSelectedReadPosition()

    val selectStartPos get() = binding.contentTextView.selectStart

    val selectEndPos get() = binding.contentTextView.selectEnd

    fun selectedStartPage(): TextPage? = binding.contentTextView.selectedStartPage()

    private companion object {
        /**
         * Positions worth remembering a composition key for. Only pages within reach of a
         * few turns can be returned to before the key would be rebuilt anyway, and each
         * entry is two short strings.
         */
        const val MAX_TITLE_KEYS_BY_PAGE = 24
        /**
         * Compositions kept per view. Three PageViews share the Lottie parse cache, so this
         * only bounds the committed render state; a handful covers turning back and forth
         * across a title page without holding rasterized bitmaps for a whole chapter.
         */
        const val MAX_COMMITTED_TITLES = 6
    }
}
