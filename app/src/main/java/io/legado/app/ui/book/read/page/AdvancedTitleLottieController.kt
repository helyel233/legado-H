package io.legado.app.ui.book.read.page

import android.content.Context
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.RenderMode
import com.airbnb.lottie.model.LottieCompositionCache
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.help.config.AdvancedTitleFontAssetDelegate
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import kotlinx.coroutines.CoroutineStart

/**
 * What the title controller needs from the view that hosts it.
 *
 * Everything here is a view the titles draw into, a piece of reader state they depend
 * on, or a service of the page view (bind scheduling, snap dirtying). Keeping it behind
 * an interface is what lets the loading logic live on its own.
 */
internal interface AdvancedTitleHost {

    /** The Lottie view and the plain-text fallback the slot draws into. */
    fun titleViews(slot: AdvancedTitleSlot): Pair<LottieAnimationView, TextView>

    fun contentViewWidth(): Int

    fun contentViewHeight(): Int

    /**
     * Height of the band the scroll-mode title may travel in; a title fully outside it
     * is unmounted like a painted one.
     */
    fun scrollOverlayHeight(): Float

    val isScroll: Boolean

    val isMainView: Boolean

    fun currentTextPage(): TextPage?

    fun pairedTextPage(): TextPage?

    fun isHorizontalPageTurnActive(): Boolean

    fun markSnapDirty()

    fun clearAdvancedTitleLoadingState(view: LottieAnimationView)

    fun defaultFontAssetDelegate(): AdvancedTitleFontAssetDelegate

    fun scheduleOverlayBind()

    fun bookUrl(): String

    fun androidContext(): Context

    fun hasScrollRelativePage(pos: Int): Boolean

    fun scrollRelativePage(pos: Int): TextPage

    fun scrollRelativeOffset(pos: Int): Float
}

/**
 * Loads the advanced chapter titles (PageView F region).
 *
 * A title is the EPUB chapter heading rendered as a Lottie animation laid over the text.
 * The load is asynchronous and the reader moves on while it is in flight, so every step
 * has to prove it still belongs to what the reader sees: the composition key encodes the
 * styled JSON and the view size, the request gate drops stale tokens, and a page the
 * reader returns to finds its committed composition instead of flashing through a
 * rebuild. See [AdvancedTitleSlotState] for what each slot remembers.
 */
internal class AdvancedTitleLottieController(
    private val host: AdvancedTitleHost,
    maxTitleKeysByPage: Int,
    maxCommittedTitles: Int
) {

    private data class ScrollAdvTitle(
        val id: String,
        val textPage: TextPage,
        val block: TextPage.EpubEmbeddedBlock,
        val x: Float,
        val y: Float,
        val width: Int,
        val height: Int
    )

    private val state = AdvancedTitleSlotState(maxCommittedTitles)

    /**
     * Composition key last bound for a page, keyed by that page's position.
     *
     * Two things have to be true before a composition may go back up, and they need
     * separate mechanisms:
     *
     * The *lookup* is keyed by position — chapter index and page number — because that is
     * what stays stable when the reader turns away and back. Title text cannot be used:
     * chapter names repeat ("序", "第一章", "番外"), so two unrelated positions would
     * collide and one chapter could show another's composition.
     *
     * The *decision* stays with the recorded key, which encodes the styled JSON hash and
     * the view size. Changing the font, rotating, or swapping the title package therefore
     * produces a key that no longer resolves, and the page rebuilds instead of reusing a
     * composition built for different conditions — something position alone cannot detect.
     */
    private val keysByPage = AdvancedTitlePageKeyCache(maxTitleKeysByPage)

    private val requestGate = AdvancedTitleRequestGate()
    val animations = AdvancedTitleAnimationFreeze()

    private var scrollTitleLinger: List<ScrollAdvTitle> = emptyList()
    private var lastScrollPageOffsetForTitle: Int = Int.MIN_VALUE
    private var boundScrollTitleIds: Array<String?> = arrayOf(null, null)

    val contentGeneration: Long
        get() = requestGate.contentGeneration

    private fun titleViews(slot: AdvancedTitleSlot) = host.titleViews(slot)

    fun nextContent() {
        requestGate.nextContent()
    }

    fun clearInFlight() {
        state.clearPendingCompositions()
        AdvancedTitleSlot.entries.forEach { state.cancelImagePrepareJob(it) }
    }

    /** Tears a slot down and drops every request attached to it. */
    fun disposeSlot(slot: AdvancedTitleSlot) {
        hideSlot(slot)
        val (lottieView, _) = titleViews(slot)
        lottieView.setImageAssetDelegate(null)
        lottieView.setTextDelegate(null)
        state.clearApplied(slot)
    }

    fun isReady(slot: AdvancedTitleSlot): Boolean {
        val token = requestGate.readyToken(slot) ?: return false
        val (lottieView, _) = titleViews(slot)
        return requestGate.accepts(token) &&
            lottieView.tag == token.compositionKey &&
            state.appliedKey(slot) == token.compositionKey &&
            lottieView.composition != null
    }

    private fun isPresented(slot: AdvancedTitleSlot): Boolean {
        val token = requestGate.presentedToken(slot) ?: return false
        return requestGate.accepts(token) && isReady(slot)
    }

    private fun blockOf(textPage: TextPage?): TextPage.EpubEmbeddedBlock? {
        if (ReadBookConfig.titleMode != AdvancedTitleConfig.TITLE_MODE_ADVANCED) return null
        return textPage?.epubEmbeddedBlocks?.firstOrNull {
            it.role == AdvancedTitleConfig.LOTTIE_BLOCK_ROLE
        }
    }

    private fun pageKey(textPage: TextPage): String {
        return AdvancedTitlePageKeys.of(
            bookUrl = host.bookUrl(),
            chapterIndex = textPage.chapterIndex,
            pageIndex = textPage.index
        )
    }

    private fun resolveViewSize(block: TextPage.EpubEmbeddedBlock): Pair<Int, Int> {
        return AdvancedTitleGeometry.viewSize(block.width, block.height)
    }

    private fun resolveTranslationX(
        block: TextPage.EpubEmbeddedBlock,
        targetWidth: Int,
        pageOffsetX: Float
    ): Float {
        return AdvancedTitleGeometry.translationX(
            blockOffsetX = block.offsetX,
            targetWidth = targetWidth,
            contentWidth = host.contentViewWidth(),
            pageOffsetX = pageOffsetX
        )
    }

    private fun resolveTranslationY(
        block: TextPage.EpubEmbeddedBlock,
        targetHeight: Int,
        scrollBaseY: Float
    ): Float {
        if (host.isScroll) {
            return AdvancedTitleGeometry.scrollTranslationY(block.offsetY, scrollBaseY)
        }
        return AdvancedTitleGeometry.translationY(
            blockOffsetY = block.offsetY,
            targetHeight = targetHeight,
            contentHeight = host.contentViewHeight()
        )
    }

    /**
     * Lay out the plain-text title for [block]. [revealText] decides whether the text is
     * actually painted: while an advanced composition is still loading the slot only
     * reserves its geometry (INVISIBLE), so the reader never sees the plain title flash
     * before the advanced one. It is revealed only when the composition genuinely fails.
     */
    private fun showFallback(
        slot: AdvancedTitleSlot,
        textPage: TextPage,
        block: TextPage.EpubEmbeddedBlock,
        pageOffsetX: Float,
        scrollBaseY: Float = 0f,
        revealText: Boolean = false
    ) {
        hideSlot(slot)
        val (lottieView, fallbackView) = titleViews(slot)
        val (targetWidth, targetHeight) = resolveViewSize(block)
        val params = fallbackView.layoutParams as ViewGroup.LayoutParams
        if (params.width != targetWidth || params.height != targetHeight) {
            params.width = targetWidth
            params.height = targetHeight
            fallbackView.layoutParams = params
        }
        fallbackView.translationX = resolveTranslationX(block, targetWidth, pageOffsetX)
        fallbackView.translationY = resolveTranslationY(block, targetHeight, scrollBaseY)
        if (host.isScroll) {
            applyContentClip(
                fallbackView,
                fallbackView.translationY,
                targetWidth,
                targetHeight
            )
        } else {
            fallbackView.clipBounds = null
        }
        fallbackView.gravity = android.view.Gravity.CENTER
        fallbackView.text = textPage.title
        // Reserve geometry without painting text until the advanced title is known to fail.
        fallbackView.visibility = if (revealText) android.view.View.VISIBLE else android.view.View.INVISIBLE
        lottieView.visibility = android.view.View.GONE
        host.markSnapDirty()
    }

    fun showSynchronousFallbacks(
        textPage: TextPage,
        pairedTextPage: TextPage?,
        retainedSlots: Set<AdvancedTitleSlot> = emptySet()
    ) {
        if (ReadBookConfig.titleMode != AdvancedTitleConfig.TITLE_MODE_ADVANCED) {
            AdvancedTitleSlot.entries.forEach(::hideSlot)
            return
        }
        if (host.isScroll) {
            val titles = collectScrollTitles(
                host.contentViewWidth().toFloat().coerceAtLeast(1f)
            )
            AdvancedTitleSlot.entries.forEach { slot ->
                val title = titles.getOrNull(slot.ordinal)
                boundScrollTitleIds[slot.ordinal] = title?.id
                if (title == null) {
                    hideSlot(slot)
                } else {
                    showFallback(
                        slot = slot,
                        textPage = title.textPage,
                        block = title.block,
                        pageOffsetX = 0f,
                        scrollBaseY = title.y - title.block.offsetY
                    )
                    val (_, fallbackView) = titleViews(slot)
                    fallbackView.translationX = title.x
                    fallbackView.translationY = title.y
                    applyContentClip(fallbackView, title.y, title.width, title.height)
                }
            }
            return
        }

        val contentWidth = host.contentViewWidth()
        val useDoublePage = ChapterProvider.doublePage
        val pairOffsetX = if (useDoublePage) contentWidth / 2f else 0f
        val pages = arrayOf(textPage, pairedTextPage)
        AdvancedTitleSlot.entries.forEach { slot ->
            // A retained slot is already showing this page's title; touching it here would
            // hide the composition and reintroduce the flash this retention exists to avoid.
            if (slot in retainedSlots) return@forEach
            val page = pages[slot.ordinal]
            val block = blockOf(page)
            if (page == null || block == null) {
                hideSlot(slot)
            } else {
                showFallback(
                    slot = slot,
                    textPage = page,
                    block = block,
                    pageOffsetX = if (slot == AdvancedTitleSlot.PAIR) pairOffsetX else 0f
                )
            }
        }
    }

    /**
     * Slots whose composed title also belongs to the page being bound, so re-binding would
     * only rebuild what is already correct on screen.
     *
     * Deliberately strict: retention is only claimed when the slot is drawing a composition
     * for the identical block instance the incoming page carries. Retaining a slot wrongly
     * would leave one page's title sitting over another, which is worse than a flash.
     *
     * Must be called before the content generation is bumped: the proof it relies on is the
     * presented token, which the bump discards.
     */
    fun slotsAlreadyShowing(
        textPage: TextPage,
        pairedTextPage: TextPage?
    ): Set<AdvancedTitleSlot> {
        if (host.isScroll) return emptySet()
        if (ReadBookConfig.titleMode != AdvancedTitleConfig.TITLE_MODE_ADVANCED) return emptySet()
        val pages = arrayOf<TextPage?>(textPage, pairedTextPage)
        return AdvancedTitleSlot.entries.filterTo(mutableSetOf()) { slot ->
            val incoming = blockOf(pages[slot.ordinal]) ?: return@filterTo false
            val (lottieView, _) = titleViews(slot)
            AdvancedTitleRetentionPolicy.canRetain(
                boundBlock = state.boundBlock(slot),
                incomingBlock = incoming,
                isPresented = isPresented(slot),
                hasComposition = lottieView.composition != null,
                isVisible = lottieView.visibility == android.view.View.VISIBLE
            )
        }
    }

    /**
     * Put back the titles of pages being returned to, within the current frame.
     *
     * Must be called after the content generation is bumped, since the tokens it opens have
     * to belong to the generation now being bound.
     */
    fun restoreCommitted(
        textPage: TextPage,
        pairedTextPage: TextPage?,
        skip: Set<AdvancedTitleSlot>
    ): Set<AdvancedTitleSlot> {
        if (host.isScroll) return emptySet()
        if (ReadBookConfig.titleMode != AdvancedTitleConfig.TITLE_MODE_ADVANCED) return emptySet()
        val pages = arrayOf<TextPage?>(textPage, pairedTextPage)
        return AdvancedTitleSlot.entries.filterTo(mutableSetOf()) { slot ->
            if (slot in skip) return@filterTo false
            val block = blockOf(pages[slot.ordinal]) ?: return@filterTo false
            restoreCommittedTitle(slot, pages[slot.ordinal], block)
        }
    }

    /**
     * Re-show a title whose composition is still held, within the current frame.
     *
     * Returns true only once the slot is actually drawing it. Anything unproven is left to
     * the normal bind, which repaints from scratch: showing the wrong title would be a far
     * worse outcome than the flash this avoids.
     */
    private fun restoreCommittedTitle(
        slot: AdvancedTitleSlot,
        textPage: TextPage?,
        block: TextPage.EpubEmbeddedBlock
    ): Boolean {
        val page = textPage ?: return false
        val key = keysByPage[pageKey(page)] ?: return false
        val committed = state.committed(key) ?: return false
        val (lottieView, fallbackView) = titleViews(slot)
        // The size baked into the key has to still be the size this slot renders at, or the
        // composition would be drawn for bounds it was not built for.
        val (targetWidth, targetHeight) = resolveViewSize(block)
        if (!AdvancedTitleLottieKeys.matchesViewSize(key, targetWidth, targetHeight)) return false
        return runCatching {
            // Geometry first: committing only swaps the composition in, so without this the
            // title would be drawn wherever the previous page left the slot.
            val params = lottieView.layoutParams as ViewGroup.LayoutParams
            if (params.width != targetWidth || params.height != targetHeight) {
                params.width = targetWidth
                params.height = targetHeight
                lottieView.layoutParams = params
            }
            lottieView.scaleType = ImageView.ScaleType.FIT_CENTER
            val pageOffsetX = if (slot == AdvancedTitleSlot.PAIR && ChapterProvider.doublePage) {
                host.contentViewWidth() / 2f
            } else {
                0f
            }
            lottieView.translationX = resolveTranslationX(block, targetWidth, pageOffsetX)
            lottieView.translationY = resolveTranslationY(block, targetHeight, 0f)
            lottieView.clipBounds = null
            val token = requestGate.begin(
                slot,
                key,
                "content:${requestGate.contentGeneration}:${slot.name}"
            )
            lottieView.tag = key
            commit(
                AdvancedTitleSlotState.PendingComposition(
                    token,
                    committed.composition,
                    committed.renderConfig
                )
            )
            val restored = isReady(slot) && lottieView.visibility == android.view.View.VISIBLE
            if (restored) {
                state.setBoundBlock(slot, block)
                fallbackView.visibility = android.view.View.GONE
            }
            restored
        }.getOrDefault(false)
    }

    fun hideSlot(slot: AdvancedTitleSlot) {
        val (lottieView, fallbackView) = titleViews(slot)
        val pixelsChanged = lottieView.visibility != android.view.View.GONE ||
            fallbackView.visibility != android.view.View.GONE
        // This slot no longer shows anything, so it must not claim retention on the next bind.
        state.setBoundBlock(slot, null)
        requestGate.clear(slot)
        state.setPendingComposition(slot, null)
        state.cancelImagePrepareJob(slot)
        host.clearAdvancedTitleLoadingState(lottieView)
        lottieView.cancelAnimation()
        lottieView.renderMode = RenderMode.AUTOMATIC
        lottieView.visibility = android.view.View.GONE
        fallbackView.visibility = android.view.View.GONE
        if (pixelsChanged) host.markSnapDirty()
    }

    fun upLotties(
        textPage: TextPage,
        pairedTextPage: TextPage?,
        contentGeneration: Long
    ) {
        if (contentGeneration != requestGate.contentGeneration) return
        val contentWidth = host.contentViewWidth()
        if (host.isScroll) {
            val pageWidth = contentWidth.toFloat().coerceAtLeast(1f)
            val titles = collectScrollTitles(pageWidth)
            bindScrollTitle(
                title = titles.getOrNull(0),
                pageWidth = pageWidth,
                slot = AdvancedTitleSlot.PRIMARY,
                contentGeneration = contentGeneration
            )
            bindScrollTitle(
                title = titles.getOrNull(1),
                pageWidth = pageWidth,
                slot = AdvancedTitleSlot.PAIR,
                contentGeneration = contentGeneration
            )
            boundScrollTitleIds[0] = titles.getOrNull(0)?.id
            boundScrollTitleIds[1] = titles.getOrNull(1)?.id
            return
        }
        val useDoublePage = ChapterProvider.doublePage
        val pairOffsetX = if (useDoublePage) contentWidth / 2f else 0f
        val pageWidth = if (useDoublePage) {
            contentWidth / 2f
        } else {
            contentWidth.toFloat()
        }.coerceAtLeast(1f)
        bindSlotSafely(
            slot = AdvancedTitleSlot.PRIMARY,
            textPage = textPage,
            pageOffsetX = 0f,
            pageWidth = pageWidth,
            scrollBaseY = 0f,
            contentGeneration = contentGeneration
        )
        bindSlotSafely(
            slot = AdvancedTitleSlot.PAIR,
            textPage = pairedTextPage,
            pageOffsetX = pairOffsetX,
            pageWidth = pageWidth,
            scrollBaseY = 0f,
            contentGeneration = contentGeneration
        )
    }

    /**
     * Gather advanced titles still intersecting the reading viewport.
     * Titles stay mounted until fully outside (top or bottom), like normal painted titles.
     */
    private fun collectScrollTitles(pageWidth: Float): List<ScrollAdvTitle> {
        if (ReadBookConfig.titleMode != AdvancedTitleConfig.TITLE_MODE_ADVANCED) {
            scrollTitleLinger = emptyList()
            return scrollTitleLinger
        }
        // Overlay is content-sized: same coordinate space as painted text pages.
        val contentHeight = host.contentViewHeight().toFloat().coerceAtLeast(1f)
        val contentWidth = host.contentViewWidth().toFloat().coerceAtLeast(1f)
        val live = ArrayList<ScrollAdvTitle>(3)
        for (pos in 0..2) {
            if (!host.hasScrollRelativePage(pos)) continue
            val page = host.scrollRelativePage(pos)
            val block = page.epubEmbeddedBlocks.firstOrNull {
                it.role == AdvancedTitleConfig.LOTTIE_BLOCK_ROLE
            } ?: continue
            val placement = AdvancedTitleScrollGeometry.place(
                blockOffsetX = block.offsetX,
                blockOffsetY = block.offsetY,
                blockWidth = block.width,
                blockHeight = block.height,
                relativeOffsetY = host.scrollRelativeOffset(pos),
                contentWidth = contentWidth,
                contentHeight = contentHeight
            ) ?: continue
            val id = AdvancedTitleScrollGeometry.id(page.chapterIndex, page.index, page.title)
            live.add(
                ScrollAdvTitle(
                    id = id,
                    textPage = page,
                    block = block,
                    x = placement.x,
                    y = placement.y,
                    width = placement.width,
                    height = placement.height
                )
            )
        }
        live.sortBy { it.y }
        scrollTitleLinger = live.take(2)
        return scrollTitleLinger
    }

    /** Finger-scroll path: only move overlays. Reload happens in setContent via full bind. */
    fun syncScrollPositions() {
        if (!host.isScroll) return
        if (ReadBookConfig.titleMode != AdvancedTitleConfig.TITLE_MODE_ADVANCED) {
            boundScrollTitleIds.fill(null)
            AdvancedTitleSlot.entries.forEach(::hideSlot)
            return
        }
        val pageWidth = host.contentViewWidth().toFloat().coerceAtLeast(1f)
        val titles = collectScrollTitles(pageWidth)
        val id0 = titles.getOrNull(0)?.id
        val id1 = titles.getOrNull(1)?.id
        val sameBinding =
            boundScrollTitleIds[0] == id0 &&
                boundScrollTitleIds[1] == id1 &&
                (id0 == null || isReady(AdvancedTitleSlot.PRIMARY) ||
                    host.titleViews(AdvancedTitleSlot.PRIMARY).second.visibility ==
                    android.view.View.VISIBLE) &&
                (id1 == null || isReady(AdvancedTitleSlot.PAIR) ||
                    host.titleViews(AdvancedTitleSlot.PAIR).second.visibility ==
                    android.view.View.VISIBLE)
        if (!sameBinding) {
            // Keep JSON rewrite/parsing out of MotionEvent.MOVE. Publish the new page's
            // lightweight fallback now and let the posted idle bind load its composition.
            host.currentTextPage()?.let { textPage ->
                showSynchronousFallbacks(textPage, host.pairedTextPage())
                host.scheduleOverlayBind()
            }
            return
        }
        applyScrollTitlePosition(AdvancedTitleSlot.PRIMARY, titles.getOrNull(0))
        applyScrollTitlePosition(AdvancedTitleSlot.PAIR, titles.getOrNull(1))
    }

    fun resetScrollState() {
        scrollTitleLinger = emptyList()
        lastScrollPageOffsetForTitle = Int.MIN_VALUE
        boundScrollTitleIds[0] = null
        boundScrollTitleIds[1] = null
    }

    fun clearScrollBinding() {
        boundScrollTitleIds.fill(null)
    }

    private fun applyContentClip(view: android.view.View, y: Float, width: Int, height: Int) {
        if (y >= 0f) {
            view.clipBounds = null
            return
        }
        val top = (-y).toInt().coerceIn(0, height)
        view.clipBounds = if (top >= height) {
            android.graphics.Rect(0, 0, 0, 0)
        } else {
            android.graphics.Rect(0, top, width.coerceAtLeast(1), height.coerceAtLeast(1))
        }
    }

    private fun applyScrollTitlePosition(
        slot: AdvancedTitleSlot,
        title: ScrollAdvTitle?
    ) {
        val (lottieView, fallbackView) = titleViews(slot)
        if (title == null) {
            hideSlot(slot)
            return
        }
        arrayOf<android.view.View>(lottieView, fallbackView).forEach { view ->
            val params = view.layoutParams
            if (params.width != title.width || params.height != title.height) {
                params.width = title.width
                params.height = title.height
                view.layoutParams = params
            }
            view.translationX = title.x
            view.translationY = title.y
            applyContentClip(view, title.y, title.width, title.height)
        }
        if (isReady(slot)) {
            lottieView.visibility = android.view.View.VISIBLE
            lottieView.pauseAnimation()
            lottieView.progress = 0f
            fallbackView.visibility = if (isPresented(slot)) android.view.View.GONE
            else android.view.View.VISIBLE
        } else {
            lottieView.pauseAnimation()
            lottieView.visibility = android.view.View.GONE
            fallbackView.text = title.textPage.title
            // Blank while still loading; only a failed request may paint the plain title.
            fallbackView.visibility =
                if (requestGate.hasFailed(slot)) android.view.View.VISIBLE
                else android.view.View.INVISIBLE
        }
    }

    private fun bindScrollTitle(
        title: ScrollAdvTitle?,
        pageWidth: Float,
        slot: AdvancedTitleSlot,
        contentGeneration: Long
    ): String? {
        if (title == null) {
            boundScrollTitleIds[slot.ordinal] = null
            hideSlot(slot)
            return null
        }
        boundScrollTitleIds[slot.ordinal] = title.id
        // Absolute content coordinates: scrollBaseY carries full Y (contentOrigin=0 for content overlay).
        val key = bindSlotSafely(
            slot = slot,
            textPage = title.textPage,
            pageOffsetX = 0f,
            pageWidth = pageWidth,
            scrollBaseY = title.y - title.block.offsetY,
            forceVisibleInScroll = true,
            sourceId = title.id,
            contentGeneration = contentGeneration
        )
        // Force exact live position after bind (avoid recomputation drift).
        applyScrollTitlePosition(slot, title)
        return key
    }

    private fun bindSlotSafely(
        slot: AdvancedTitleSlot,
        textPage: TextPage?,
        pageOffsetX: Float,
        pageWidth: Float,
        scrollBaseY: Float,
        forceVisibleInScroll: Boolean = false,
        contentGeneration: Long,
        sourceId: String = "content:$contentGeneration:${slot.name}"
    ): String? {
        val (lottieView, fallbackView) = titleViews(slot)
        return runCatching {
            upLottie(
                textPage = textPage,
                lottieView = lottieView,
                fallbackView = fallbackView,
                pageOffsetX = pageOffsetX,
                pageWidth = pageWidth,
                scrollBaseY = scrollBaseY,
                forceVisibleInScroll = forceVisibleInScroll,
                slot = slot,
                contentGeneration = contentGeneration,
                sourceId = sourceId
            )
        }.getOrElse { error ->
            AppLog.put("PageView advanced title bind failed: $slot", error)
            val block = blockOf(textPage)
            if (textPage != null && block != null) {
                showFallback(slot, textPage, block, pageOffsetX, scrollBaseY)
            } else {
                hideSlot(slot)
            }
            null
        }
    }

    private fun upLottie(
        textPage: TextPage?,
        lottieView: LottieAnimationView,
        fallbackView: TextView,
        pageOffsetX: Float,
        pageWidth: Float,
        scrollBaseY: Float = 0f,
        forceVisibleInScroll: Boolean = false,
        slot: AdvancedTitleSlot,
        contentGeneration: Long,
        sourceId: String
    ): String? {
        if (contentGeneration != requestGate.contentGeneration) return null
        val page = textPage ?: run {
            hideSlot(slot)
            return null
        }
        val block = blockOf(page) ?: run {
            hideSlot(slot)
            return null
        }
        // Scroll: still show Lottie, but freeze on keyframe (progress=0) and follow pageOffset.
        val (targetWidth, targetHeight) = resolveViewSize(block)
        val params = lottieView.layoutParams as ViewGroup.LayoutParams
        if (params.width != targetWidth || params.height != targetHeight) {
            params.width = targetWidth
            params.height = targetHeight
            lottieView.layoutParams = params
        }
        lottieView.scaleType = ImageView.ScaleType.FIT_CENTER
        lottieView.translationX = resolveTranslationX(block, targetWidth, pageOffsetX)
        lottieView.translationY = resolveTranslationY(block, targetHeight, scrollBaseY)
        if (host.isScroll && !forceVisibleInScroll) {
            val overlayHeight = host.scrollOverlayHeight()
            val y = lottieView.translationY
            // Fully outside the reading overlay (top or bottom edge) — same as normal painted titles.
            val offScreen = y + targetHeight <= 0f || y >= overlayHeight
            if (offScreen) {
                hideSlot(slot)
                return null
            }
        }
        // Non-scroll may loop; scroll freezes on keyframe (progress 0).
        lottieView.repeatCount = if (host.isScroll) 0 else LottieDrawable.INFINITE
        lottieView.setFontAssetDelegate(host.defaultFontAssetDelegate())
        val json = block.payload?.takeIf { it.isNotBlank() }
        val textScale = textLayerScale(block, pageWidth)
        val styled = json?.let {
            LottieTitleJsonStyler.applyFallbackStyle(it, textScale, ReadBookConfig.textColor)
        }
        val resolvedJson = styled?.json
        val nextKey = resolvedJson?.let {
            AdvancedTitleLottieKeys.composition(it, targetWidth, targetHeight)
        } ?: AdvancedTitleLottieKeys.rawComposition(targetWidth, targetHeight)
        val compositionSize = styled?.compositionSize
        val renderConfig = AdvancedTitleSlotState.RenderConfig(
            imageAssetResolver = LottieImageAssetResolver(
                viewWidth = targetWidth,
                viewHeight = targetHeight,
                compositionWidth = compositionSize?.width ?: targetWidth,
                compositionHeight = compositionSize?.height ?: targetHeight,
                fallbackAssets = block.lottieFallbackAssets
            ),
            cacheComposition = resolvedJson == null
        )

        // Remember which title this slot is working on so a later page turn can tell that a
        // slot already holds the incoming page's title and leave it up.
        state.setBoundBlock(slot, block)
        // Record against the page's position too, so returning to it can find this key back
        // without restyling the JSON on the page-turn path.
        keysByPage.put(pageKey(page), nextKey)

        if (requestGate.hasCurrent(slot, nextKey, sourceId) && lottieView.tag == nextKey) {
            if (state.pendingComposition(slot) != null) return nextKey
            if (isReady(slot)) {
                lottieView.visibility = android.view.View.VISIBLE
                fallbackView.visibility = if (isPresented(slot)) android.view.View.GONE
                else android.view.View.VISIBLE
                if (host.isMainView && !host.isScroll && !animations.isFrozen &&
                    !lottieView.isAnimating
                ) {
                    lottieView.playAnimation()
                } else {
                    lottieView.pauseAnimation()
                    if (!host.isMainView || host.isScroll) lottieView.progress = 0f
                }
            }
            return nextKey
        }

        // The slot may already be drawing exactly this composition: a page turn bumps the
        // content generation, which retires the token above even though nothing about what
        // is on screen changed. Re-adopt it instead of composing again — going through
        // setComposition would blank the slot for a frame and read as a flash.
        val liveComposition = state.appliedComposition(slot)
        if (state.appliedKey(slot) == nextKey &&
            liveComposition != null &&
            lottieView.composition === liveComposition &&
            lottieView.visibility == android.view.View.VISIBLE &&
            state.appliedRenderConfig(slot) != null
        ) {
            val token = requestGate.begin(slot, nextKey, sourceId)
            lottieView.tag = nextKey
            if (requestGate.markReady(token)) {
                // Already on screen, so it has provably rendered; no pre-draw wait needed.
                requestGate.markPresented(token)
                fallbackView.visibility = android.view.View.GONE
                if (host.isMainView && !host.isScroll && !animations.isFrozen) {
                    if (!lottieView.isAnimating) lottieView.playAnimation()
                } else {
                    lottieView.pauseAnimation()
                    if (!host.isMainView || host.isScroll) lottieView.progress = 0f
                }
                return nextKey
            }
        }

        // Bind identity changed even when the package renders to the same composition key.
        // Publish the new page's text/geometry before reusing or loading any Lottie content.
        showFallback(slot, page, block, pageOffsetX, scrollBaseY)

        // A composition committed earlier can be put straight back: this is the returning
        // page's path, where the slot itself has since been handed to another page. Skipping
        // the reload keeps the JSON parse, the image decode and the async hop out of the
        // page turn — that round trip is what the reader sees as a flash.
        val reusable = state.appliedComposition(slot)
            ?.takeIf { state.appliedKey(slot) == nextKey }
            ?.let { composition ->
                state.appliedRenderConfig(slot)
                    ?.let { AdvancedTitleSlotState.CommittedComposition(composition, it) }
            }
            ?: state.committed(nextKey)
        if (reusable != null) {
            val token = requestGate.begin(slot, nextKey, sourceId)
            lottieView.tag = nextKey
            commitOrDefer(token, reusable.composition, reusable.renderConfig)
            return nextKey
        }

        val token = requestGate.begin(slot, nextKey, sourceId)
        lottieView.animate().cancel()
        lottieView.removeAllLottieOnCompositionLoadedListener()
        lottieView.setFailureListener(null)
        lottieView.tag = nextKey
        lottieView.alpha = 1f
        lottieView.visibility = android.view.View.GONE

        fun showFailureFallback() {
            if (!requestGate.fail(token)) return
            state.cancelImagePrepareJob(slot)
            state.setPendingComposition(slot, null)
            lottieView.cancelAnimation()
            lottieView.visibility = android.view.View.GONE
            // The fallback has followed any intervening scroll; never restore captured Y.
            fallbackView.visibility = android.view.View.VISIBLE
        }

        fun applyLoaded(composition: com.airbnb.lottie.LottieComposition) {
            state.cancelImagePrepareJob(slot)
            lateinit var prepareJob: Coroutine<Boolean>
            prepareJob = Coroutine.async(Coroutine.defaultScope, start = CoroutineStart.LAZY) {
                renderConfig.imageAssetResolver.prepareCompositionImages(composition)
            }.onSuccess { ready ->
                if (state.imagePrepareJob(slot) !== prepareJob) return@onSuccess
                state.setImagePrepareJob(slot, null)
                if (!requestGate.accepts(token)) return@onSuccess
                // The composition parsed, so it stays. Undecodable images are skipped by
                // Lottie; never drop a valid advanced title back to the plain-text one.
                if (!ready) {
                    AppLog.put("PageView advanced title images unavailable, rendering without them: $slot")
                }
                commitOrDefer(token, composition, renderConfig)
            }.onError { error ->
                if (state.imagePrepareJob(slot) !== prepareJob) return@onError
                state.setImagePrepareJob(slot, null)
                AppLog.put("PageView advanced title image preparation failed: $slot", error)
                // Images are best-effort; the parsed composition is still valid.
                if (requestGate.accepts(token)) {
                    commitOrDefer(token, composition, renderConfig)
                }
            }
            state.setImagePrepareJob(slot, prepareJob)
            prepareJob.start()
        }

        if (resolvedJson != null) {
            LottieCompositionCache.getInstance().get(nextKey)?.let { composition ->
                applyLoaded(composition)
                return nextKey
            }
            LottieCompositionFactory.fromJsonString(resolvedJson, nextKey)
                .addListener { composition -> composition?.let(::applyLoaded) }
                .addFailureListener { showFailureFallback() }
        } else {
            LottieCompositionFactory.fromRawRes(host.androidContext(), R.raw.advanced_title_lottie)
                .addListener { composition -> composition?.let(::applyLoaded) }
                .addFailureListener { showFailureFallback() }
        }
        return nextKey
    }

    private fun commitOrDefer(
        token: AdvancedTitleRequestToken,
        composition: com.airbnb.lottie.LottieComposition,
        renderConfig: AdvancedTitleSlotState.RenderConfig
    ) {
        if (!requestGate.accepts(token)) return
        if (!host.isScroll && host.isHorizontalPageTurnActive()) {
            state.setPendingComposition(
                token.slot,
                AdvancedTitleSlotState.PendingComposition(token, composition, renderConfig)
            )
            return
        }
        commit(
            AdvancedTitleSlotState.PendingComposition(token, composition, renderConfig)
        )
    }

    private fun commit(pending: AdvancedTitleSlotState.PendingComposition) {
        val token = pending.token
        if (!requestGate.accepts(token)) return
        val (lottieView, fallbackView) = titleViews(token.slot)
        if (lottieView.tag != token.compositionKey) return
        state.setPendingComposition(token.slot, null)
        runCatching {
            // Hardware/automatic rendering. Software rendering makes Lottie allocate a
            // view-sized backing bitmap per animation view and rasterize it on the main
            // thread; across three PageViews times title and tip slots that reproduced the
            // 2026-07-23 regression (heap exhausted, main thread hung, reader killed with a
            // black screen and no crash dialog) which the 2026-07-24 repair fixed by
            // removing software rendering.
            lottieView.renderMode = RenderMode.AUTOMATIC
            lottieView.setMaintainOriginalImageBounds(pending.renderConfig.maintainOriginalImageBounds)
            lottieView.setImageAssetDelegate(pending.renderConfig.imageAssetResolver)
            lottieView.setCacheComposition(pending.renderConfig.cacheComposition)
            lottieView.setComposition(pending.composition)
            state.setApplied(
                token.slot,
                token.compositionKey,
                pending.composition,
                pending.renderConfig
            )
            // Outlives the slot: the next page overwrites the entries above, and without a
            // copy here a page the reader returns to would have to be composed again.
            state.putCommitted(
                token.compositionKey,
                AdvancedTitleSlotState.CommittedComposition(
                    pending.composition,
                    pending.renderConfig
                )
            )
            lottieView.progress = 0f
            lottieView.alpha = 1f
            lottieView.visibility = android.view.View.VISIBLE
            if (host.isMainView && !host.isScroll && !animations.isFrozen) {
                lottieView.playAnimation()
            } else {
                lottieView.pauseAnimation()
                if (host.isMainView && !host.isScroll && animations.isFrozen) {
                    animations.markTitleToResume(token.slot)
                }
            }
            if (!requestGate.markReady(token)) {
                lottieView.cancelAnimation()
                lottieView.visibility = android.view.View.GONE
                state.clearApplied(token.slot)
                return@runCatching
            }
            // Composition success is not proof that a vendor renderer produced a frame. Keep
            // the correct-page fallback until this exact request participates in pre-draw.
            lottieView.doOnPreDraw {
                if (lottieView.tag == token.compositionKey &&
                    lottieView.visibility == android.view.View.VISIBLE &&
                    lottieView.composition != null &&
                    requestGate.markPresented(token)
                ) {
                    fallbackView.visibility = android.view.View.GONE
                    host.markSnapDirty()
                }
            }
            lottieView.invalidate()
        }.onFailure { error ->
            AppLog.put("PageView advanced title composition failed: ${token.slot}", error)
            state.clearApplied(token.slot)
            if (requestGate.fail(token)) {
                lottieView.cancelAnimation()
                lottieView.visibility = android.view.View.GONE
                fallbackView.visibility = android.view.View.VISIBLE
            }
        }
    }

    /** True when pending compositions may be committed right now. */
    fun canCommitPendingNow(): Boolean {
        if (host.isScroll) return true
        return !host.isHorizontalPageTurnActive()
    }

    fun commitPending() {
        state.pendingCompositions().forEach { pending ->
            commit(pending)
        }
    }

    /** Best-effort: parse title payload of an adjacent page into Lottie cache off the hot path. */
    fun prewarmFromPage(textPage: TextPage?) {
        if (ReadBookConfig.titleMode != AdvancedTitleConfig.TITLE_MODE_ADVANCED) return
        val block = textPage?.epubEmbeddedBlocks?.firstOrNull {
            it.role == AdvancedTitleConfig.LOTTIE_BLOCK_ROLE
        } ?: return
        val json = block.payload?.takeIf { it.isNotBlank() } ?: return
        val pageWidth = host.contentViewWidth().toFloat().coerceAtLeast(1f)
        val styled = LottieTitleJsonStyler.applyFallbackStyle(
            json,
            textLayerScale(block, pageWidth),
            ReadBookConfig.textColor
        )
        val tw = block.width.toInt().coerceAtLeast(1)
        val th = block.height.toInt().coerceAtLeast(1)
        val key = AdvancedTitleLottieKeys.composition(styled.json, tw, th)
        if (LottieCompositionCache.getInstance().get(key) != null) return
        LottieCompositionFactory.fromJsonString(styled.json, key)
    }

    private fun textLayerScale(block: TextPage.EpubEmbeddedBlock, pageWidth: Float): Float {
        // 纯计算已搬到 LottieTitleJsonStyler（可单测），这里只负责把阅读器配置喂进去
        return LottieTitleJsonStyler.textLayerScale(
            block = block,
            pageWidth = pageWidth,
            textSize = ReadBookConfig.textSize.toFloat(),
            titleSize = ReadBookConfig.titleSize.toFloat(),
            heightFactorRatio = AdvancedTitleConfig.heightFactor /
                AdvancedTitleConfig.DEFAULT_HEIGHT_FACTOR.toFloat()
        )
    }
}
