package io.legado.app.ui.book.read.page

import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.widget.ImageView
import androidx.core.view.doOnPreDraw
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.RenderMode
import com.airbnb.lottie.TextDelegate
import com.airbnb.lottie.model.LottieCompositionCache
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AdvancedTipConfig
import io.legado.app.help.config.AdvancedTipSlot
import io.legado.app.help.config.AdvancedTitleFontAssetDelegate
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.book.read.page.entities.TextPage
import kotlinx.coroutines.CoroutineStart

/**
 * What the tip controller needs from the view that hosts it.
 *
 * Everything here is either a view the tip draws into or a piece of reader state the tip
 * depends on. Keeping it behind an interface is what lets the loading logic live on its
 * own instead of inside the page view.
 */
internal interface AdvancedTipHost {

    /** The Lottie view the slot draws into. */
    fun tipView(slot: AdvancedTipSlot): LottieAnimationView

    /** Width to fall back to when the tip view itself is not measured yet. */
    fun contentWidth(): Int

    /** Generation of the title requests: a tip bound to an older one is stale. */
    fun contentGeneration(): Long

    /** Whether this page view is the one the reader is looking at. */
    val isMainView: Boolean

    fun isEpubBook(): Boolean

    val batteryLevel: Int

    fun currentTextPage(): TextPage?

    fun bookName(): String

    fun bookAuthor(): String

    fun isHorizontalPageTurnActive(): Boolean

    /**
     * The reading information the tips show, and the last values pushed into them.
     *
     * Both the classic tip text views and the advanced packages render from it, so it
     * lives with the page view rather than with the controller.
     */
    var tipContext: AdvancedTipConfig.TipContext

    fun markSnapDirty()

    fun animationFreeze(): AdvancedTitleAnimationFreeze

    fun clearAdvancedTitleLoadingState(view: LottieAnimationView)

    fun setClassicTipFallbackVisible(slot: AdvancedTipSlot, visible: Boolean)

    fun defaultFontAssetDelegate(): AdvancedTitleFontAssetDelegate
}

/**
 * Loads the advanced header and footer packages (PageView G region).
 *
 * A package is a Lottie animation whose text fields carry the reading information — book
 * name, chapter, time, battery. It is keyed by package identity only: page, title, time
 * and battery updates are pushed into the text delegate instead of re-parsing the
 * animation, so turning a page costs a redraw rather than a composition load. Everything
 * that can go stale — a package swapped in the settings, a page turn that moved on, a
 * request overtaken by a newer one — has to drop the result instead of showing it.
 */
internal class AdvancedTipLottieController(private val host: AdvancedTipHost) {

    private data class PendingAdvancedTipComposition(
        val token: AdvancedTipRequestToken,
        val composition: com.airbnb.lottie.LottieComposition,
        val variables: Map<String, String>,
        val imageAssetResolver: LottieImageAssetResolver
    )

    private data class PreparedAdvancedTipComposition(
        val composition: com.airbnb.lottie.LottieComposition,
        val imageAssetResolver: LottieImageAssetResolver
    )

    private val requestGate = AdvancedTipRequestGate()
    private val pendingCompositions =
        arrayOfNulls<PendingAdvancedTipComposition>(AdvancedTipSlot.entries.size)
    private val prepareJobs =
        arrayOfNulls<Coroutine<PreparedAdvancedTipComposition?>>(AdvancedTipSlot.entries.size)
    private val prewarmKeys = arrayOfNulls<String>(AdvancedTipSlot.entries.size)
    private var headerKey: String? = null
    private var footerKey: String? = null
    private var headerDelegate: TipFieldTextDelegate? = null
    private var footerDelegate: TipFieldTextDelegate? = null

    private fun tipView(slot: AdvancedTipSlot): LottieAnimationView = host.tipView(slot)

    private fun key(slot: AdvancedTipSlot): String? {
        return when (slot) {
            AdvancedTipSlot.HEADER -> headerKey
            AdvancedTipSlot.FOOTER -> footerKey
        }
    }

    private fun setKey(slot: AdvancedTipSlot, key: String?) {
        when (slot) {
            AdvancedTipSlot.HEADER -> headerKey = key
            AdvancedTipSlot.FOOTER -> footerKey = key
        }
    }

    private fun textDelegate(slot: AdvancedTipSlot): TipFieldTextDelegate? {
        return when (slot) {
            AdvancedTipSlot.HEADER -> headerDelegate
            AdvancedTipSlot.FOOTER -> footerDelegate
        }
    }

    private fun setTextDelegate(slot: AdvancedTipSlot, delegate: TipFieldTextDelegate?) {
        when (slot) {
            AdvancedTipSlot.HEADER -> headerDelegate = delegate
            AdvancedTipSlot.FOOTER -> footerDelegate = delegate
        }
    }

    fun enabled(slot: AdvancedTipSlot): Boolean {
        if (host.isEpubBook()) return false
        return when (slot) {
            AdvancedTipSlot.HEADER -> ReadTipConfig.isHeaderAdvanced()
            AdvancedTipSlot.FOOTER -> ReadTipConfig.isFooterAdvanced()
        }
    }

    /**
     * True when every enabled slot already shows its package.
     *
     * A slot that is not enabled does not have to be bound, so it does not count against
     * the answer.
     */
    fun viewsBound(): Boolean {
        if (ReadTipConfig.isHeaderAdvanced()) {
            val expected = AdvancedTipConfig.compositionCacheKey(AdvancedTipSlot.HEADER)
            if (key(AdvancedTipSlot.HEADER) != expected ||
                tipView(AdvancedTipSlot.HEADER).composition == null
            ) {
                return false
            }
        }
        if (ReadTipConfig.isFooterAdvanced()) {
            val expected = AdvancedTipConfig.compositionCacheKey(AdvancedTipSlot.FOOTER)
            if (key(AdvancedTipSlot.FOOTER) != expected ||
                tipView(AdvancedTipSlot.FOOTER).composition == null
            ) {
                return false
            }
        }
        return true
    }

    /** Commits whatever was deferred while a page turn was in progress. */
    fun commitPending() {
        pendingCompositions.filterNotNull().forEach { commit(it) }
    }

    /** True when this slot currently holds a package. */
    fun hasKey(slot: AdvancedTipSlot): Boolean = key(slot) != null

    /** Tears down both slots: the reader is leaving this content. */
    fun clearAllSlots() {
        AdvancedTipSlot.entries.forEach(::clear)
    }

    /**
     * The view the slot draws into.
     *
     * The page-turn freeze logic pauses and resumes the tips along with the titles, so
     * it needs the views too.
     */
    fun viewOf(slot: AdvancedTipSlot): LottieAnimationView = tipView(slot)

    fun clear(slot: AdvancedTipSlot) {
        val lottieView = tipView(slot)
        val pixelsChanged = lottieView.visibility != GONE
        pendingCompositions[slot.ordinal] = null
        requestGate.clear(slot)
        cancelPreparation(slot)
        host.animationFreeze().clearTipResume(slot)
        host.clearAdvancedTitleLoadingState(lottieView)
        lottieView.cancelAnimation()
        lottieView.setTextDelegate(null)
        lottieView.setImageAssetDelegate(null)
        lottieView.visibility = GONE
        setKey(slot, null)
        setTextDelegate(slot, null)
        if (enabled(slot)) {
            host.setClassicTipFallbackVisible(slot, true)
        }
        if (pixelsChanged) host.markSnapDirty()
    }

    fun applyChromeVisibility() {
        val headerAdvanced = !host.isEpubBook() && ReadTipConfig.isHeaderAdvanced()
        val footerAdvanced = !host.isEpubBook() && ReadTipConfig.isFooterAdvanced()
        if (headerAdvanced) {
            val loaded = key(AdvancedTipSlot.HEADER) == AdvancedTipConfig.compositionCacheKey(
                AdvancedTipSlot.HEADER
            ) && tipView(AdvancedTipSlot.HEADER).composition != null &&
                tipView(AdvancedTipSlot.HEADER).visibility == VISIBLE
            host.setClassicTipFallbackVisible(AdvancedTipSlot.HEADER, !loaded)
        }
        if (footerAdvanced) {
            val loaded = key(AdvancedTipSlot.FOOTER) == AdvancedTipConfig.compositionCacheKey(
                AdvancedTipSlot.FOOTER
            ) && tipView(AdvancedTipSlot.FOOTER).composition != null &&
                tipView(AdvancedTipSlot.FOOTER).visibility == VISIBLE
            host.setClassicTipFallbackVisible(AdvancedTipSlot.FOOTER, !loaded)
        }
        if (!headerAdvanced) {
            clear(AdvancedTipSlot.HEADER)
        }
        if (!footerAdvanced) {
            clear(AdvancedTipSlot.FOOTER)
        }
    }

    /**
     * Parse and decode the advanced header/footer packages once, off the UI thread,
     * before the first page turn needs them. Without this the first flip pays for
     * JSON parsing, derived-resource extraction and image decoding all at once.
     */
    fun prewarm() {
        if (host.isEpubBook()) return
        AdvancedTipSlot.entries.forEach { slot ->
            if (!enabled(slot)) return@forEach
            val key = AdvancedTipConfig.compositionCacheKey(slot)
            if (LottieCompositionCache.getInstance().get(key) != null) return@forEach
            if (prewarmKeys[slot.ordinal] == key) return@forEach
            prewarmKeys[slot.ordinal] = key
            val lottieView = tipView(slot)
            val requestedWidth = lottieView.width.takeIf { it > 0 }
                ?: host.contentWidth().takeIf { it > 0 }
            val requestedHeight = lottieView.height.takeIf { it > 0 }
            Coroutine.async(Coroutine.defaultScope) {
                val document = AdvancedTipConfig.rawDocument(slot) ?: return@async
                val composition = LottieCompositionCache.getInstance().get(key)
                    ?: LottieCompositionFactory.fromJsonStringSync(document.json, key).value
                    ?: return@async
                val compositionWidth = composition.bounds.width().coerceAtLeast(1)
                val compositionHeight = composition.bounds.height().coerceAtLeast(1)
                LottieImageAssetResolver(
                    viewWidth = requestedWidth ?: compositionWidth,
                    viewHeight = requestedHeight ?: compositionHeight,
                    compositionWidth = compositionWidth,
                    compositionHeight = compositionHeight,
                    fallbackAssets = document.fallbackAssets
                ).prepareCompositionImages(composition)
            }.onError { error ->
                prewarmKeys[slot.ordinal] = null
                AppLog.put("PageView advanced tip prewarm failed: $slot", error)
            }
        }
    }

    fun up(textPage: TextPage?, contentGeneration: Long) {
        AdvancedTipSlot.entries.forEach { slot ->
            runCatching {
                upSlot(
                    slot = slot,
                    textPage = textPage,
                    contentGeneration = contentGeneration
                )
            }.onFailure { error ->
                AppLog.put("PageView advanced tip bind failed: $slot", error)
                clear(slot)
            }
        }
    }

    private fun upSlot(
        slot: AdvancedTipSlot,
        textPage: TextPage?,
        contentGeneration: Long
    ) {
        if (contentGeneration != host.contentGeneration()) return
        if (!enabled(slot)) {
            clear(slot)
            return
        }
        val lottieView = tipView(slot)
        val page = textPage ?: host.currentTextPage()
        val context = host.tipContext.copy(
            book = host.tipContext.book.ifBlank { host.bookName() },
            title = page?.title?.takeIf { it.isNotBlank() } ?: host.tipContext.title,
            time = AdvancedTipConfig.currentTimeText(),
            battery = host.batteryLevel.toString(),
            author = host.bookAuthor()
        )
        host.tipContext = context
        val vars = AdvancedTipConfig.variables(context)
        // Key by package identity only. Page/title/time updates use TextDelegate (no re-parse).
        val nextKey = AdvancedTipConfig.compositionCacheKey(slot)
        lottieView.scaleType = ImageView.ScaleType.FIT_CENTER
        lottieView.repeatCount = LottieDrawable.INFINITE
        lottieView.setFontAssetDelegate(host.defaultFontAssetDelegate())

        fun ensureDelegate() {
            val existing = textDelegate(slot)
            if (existing != null) {
                existing.variables = vars
                lottieView.invalidate()
                return
            }
            val created = TipFieldTextDelegate(lottieView).also { it.variables = vars }
            lottieView.setTextDelegate(created)
            setTextDelegate(slot, created)
        }

        if (key(slot) == nextKey && lottieView.composition != null) {
            pendingCompositions[slot.ordinal] = null
            requestGate.clear(slot)
            lottieView.tag = nextKey
            ensureDelegate()
            lottieView.visibility = VISIBLE
            host.setClassicTipFallbackVisible(slot, false)
            if (host.isMainView && !host.animationFreeze().isFrozen) {
                if (!lottieView.isAnimating) lottieView.playAnimation()
            } else {
                lottieView.pauseAnimation()
                if (host.isMainView && host.animationFreeze().isFrozen) {
                    host.animationFreeze().markTipToResume(slot)
                }
            }
            return
        }

        if (requestGate.hasCurrent(slot, contentGeneration, nextKey) &&
            lottieView.tag == nextKey
        ) {
            return
        }
        val token = requestGate.begin(slot, contentGeneration, nextKey)
        pendingCompositions[slot.ordinal] = null
        cancelPreparation(slot)

        lottieView.animate().cancel()
        lottieView.removeAllLottieOnCompositionLoadedListener()
        lottieView.setFailureListener(null)
        lottieView.tag = nextKey
        lottieView.alpha = 1f
        if (!(lottieView.composition != null && lottieView.visibility == VISIBLE)) {
            lottieView.visibility = INVISIBLE
        }

        fun showFailure() {
            if (!accepts(token)) return
            clear(slot)
        }

        val requestedWidth = lottieView.width.takeIf { it > 0 }
            ?: host.contentWidth().takeIf { it > 0 }
        val requestedHeight = lottieView.height.takeIf { it > 0 }
        lateinit var prepareJob: Coroutine<PreparedAdvancedTipComposition?>
        prepareJob = Coroutine.async(Coroutine.defaultScope, start = CoroutineStart.LAZY) {
            val document = AdvancedTipConfig.rawDocument(slot) ?: return@async null
            val composition = LottieCompositionCache.getInstance().get(nextKey)
                ?: LottieCompositionFactory.fromJsonStringSync(document.json, nextKey).value
                ?: return@async null
            val compositionWidth = composition.bounds.width().coerceAtLeast(1)
            val compositionHeight = composition.bounds.height().coerceAtLeast(1)
            val resolver = LottieImageAssetResolver(
                viewWidth = requestedWidth ?: compositionWidth,
                viewHeight = requestedHeight ?: compositionHeight,
                compositionWidth = compositionWidth,
                compositionHeight = compositionHeight,
                fallbackAssets = document.fallbackAssets
            )
            // Images are best-effort: a composition that parsed still renders.
            resolver.prepareCompositionImages(composition)
            PreparedAdvancedTipComposition(composition, resolver)
        }.onSuccess { prepared ->
            if (prepareJobs[slot.ordinal] !== prepareJob) return@onSuccess
            prepareJobs[slot.ordinal] = null
            if (!accepts(token)) return@onSuccess
            if (prepared == null) {
                showFailure()
            } else {
                commitOrDefer(token, prepared, vars)
            }
        }.onError { error ->
            if (prepareJobs[slot.ordinal] !== prepareJob) return@onError
            prepareJobs[slot.ordinal] = null
            AppLog.put("PageView advanced tip preparation failed: $slot", error)
            showFailure()
        }
        prepareJobs[slot.ordinal] = prepareJob
        prepareJob.start()
    }

    /**
     * Cheap path: tip Lottie already on-screen for this package — only swap text fields.
     * Avoids setComposition / parse on page turns and minute ticks.
     */
    fun refreshFieldsIfBound() {
        if (!(ReadTipConfig.isHeaderAdvanced() || ReadTipConfig.isFooterAdvanced())) return
        val vars = AdvancedTipConfig.variables(host.tipContext)
        if (ReadTipConfig.isHeaderAdvanced()) {
            val expected = AdvancedTipConfig.compositionCacheKey(AdvancedTipSlot.HEADER)
            if (key(AdvancedTipSlot.HEADER) == expected &&
                tipView(AdvancedTipSlot.HEADER).composition != null
            ) {
                textDelegate(AdvancedTipSlot.HEADER)?.let {
                    it.variables = vars
                    tipView(AdvancedTipSlot.HEADER).invalidate()
                }
            }
        }
        if (ReadTipConfig.isFooterAdvanced()) {
            val expected = AdvancedTipConfig.compositionCacheKey(AdvancedTipSlot.FOOTER)
            if (key(AdvancedTipSlot.FOOTER) == expected &&
                tipView(AdvancedTipSlot.FOOTER).composition != null
            ) {
                textDelegate(AdvancedTipSlot.FOOTER)?.let {
                    it.variables = vars
                    tipView(AdvancedTipSlot.FOOTER).invalidate()
                }
            }
        }
    }

    private fun accepts(token: AdvancedTipRequestToken): Boolean {
        if (!requestGate.accepts(token, host.contentGeneration())) {
            return false
        }
        return tipView(token.slot).tag == token.compositionKey &&
            enabled(token.slot) &&
            AdvancedTipConfig.compositionCacheKey(token.slot) == token.compositionKey
    }

    private fun commitOrDefer(
        token: AdvancedTipRequestToken,
        prepared: PreparedAdvancedTipComposition,
        variables: Map<String, String>
    ) {
        if (!accepts(token)) return
        if (host.isHorizontalPageTurnActive()) {
            pendingCompositions[token.slot.ordinal] =
                PendingAdvancedTipComposition(
                    token,
                    prepared.composition,
                    variables,
                    prepared.imageAssetResolver
                )
            return
        }
        commit(
            PendingAdvancedTipComposition(
                token,
                prepared.composition,
                variables,
                prepared.imageAssetResolver
            )
        )
    }

    private fun commit(pending: PendingAdvancedTipComposition) {
        val token = pending.token
        if (!accepts(token)) return
        pendingCompositions[token.slot.ordinal] = null
        val lottieView = tipView(token.slot)
        runCatching {
            lottieView.renderMode = RenderMode.AUTOMATIC
            lottieView.setMaintainOriginalImageBounds(true)
            lottieView.setImageAssetDelegate(pending.imageAssetResolver)
            lottieView.setComposition(pending.composition)
            val delegate = TipFieldTextDelegate(lottieView).also {
                it.variables = pending.variables
            }
            lottieView.setTextDelegate(delegate)
            setTextDelegate(token.slot, delegate)
            lottieView.alpha = 1f
            lottieView.visibility = VISIBLE
            if (host.isMainView && !host.animationFreeze().isFrozen) {
                if (!lottieView.isAnimating) lottieView.playAnimation()
            } else {
                lottieView.pauseAnimation()
                if (host.isMainView && host.animationFreeze().isFrozen) {
                    host.animationFreeze().markTipToResume(token.slot)
                }
            }
            setKey(token.slot, token.compositionKey)
            lottieView.doOnPreDraw {
                if (accepts(token) &&
                    key(token.slot) == token.compositionKey &&
                    lottieView.visibility == VISIBLE && lottieView.composition != null
                ) {
                    host.setClassicTipFallbackVisible(token.slot, false)
                    host.markSnapDirty()
                }
            }
            lottieView.invalidate()
        }.onFailure { error ->
            AppLog.put("PageView advanced tip composition failed: ${token.slot}", error)
            if (accepts(token)) clear(token.slot)
        }
    }

    private fun cancelPreparation(slot: AdvancedTipSlot) {
        prepareJobs[slot.ordinal]?.cancel()
        prepareJobs[slot.ordinal] = null
    }

    /** Drops everything in flight: the reader is leaving this content. */
    fun dispose() {
        pendingCompositions.fill(null)
        requestGate.clearAll()
        AdvancedTipSlot.entries.forEach(::cancelPreparation)
        prewarmKeys.fill(null)
    }

    private class TipFieldTextDelegate(
        animationView: LottieAnimationView
    ) : TextDelegate(animationView) {
        @Volatile
        var variables: Map<String, String> = emptyMap()

        init {
            setCacheText(false)
        }

        override fun getText(input: String): String {
            return AdvancedTipConfig.substituteText(input, variables)
        }
    }
}
