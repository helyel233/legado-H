package io.legado.app.ui.book.read.page

import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.book.read.page.entities.TextPage

/**
 * Everything the advanced title slots remember between binds (PageView F region).
 *
 * There are two slots, and each of them carries a composition that was loaded for it,
 * the key that composition was built for, the block it belongs to and the render config
 * it needs. On top of that a few compositions are kept per key rather than per slot, so
 * a page the reader turns away from and back to finds its own again.
 *
 * Holding this in one place is what keeps the loading logic from reaching into a dozen
 * arrays by index: every access names the slot it belongs to.
 */
internal class AdvancedTitleSlotState(private val maxCommitted: Int) {

    internal data class RenderConfig(
        val imageAssetResolver: LottieImageAssetResolver,
        val cacheComposition: Boolean,
        val maintainOriginalImageBounds: Boolean = true
    )

    internal data class PendingComposition(
        val token: AdvancedTitleRequestToken,
        val composition: com.airbnb.lottie.LottieComposition,
        val renderConfig: RenderConfig
    )

    internal data class CommittedComposition(
        val composition: com.airbnb.lottie.LottieComposition,
        val renderConfig: RenderConfig
    )

    private val pendingCompositions =
        arrayOfNulls<PendingComposition>(AdvancedTitleSlot.entries.size)
    private val imagePrepareJobs =
        arrayOfNulls<Coroutine<Boolean>>(AdvancedTitleSlot.entries.size)
    private val appliedKeys = arrayOfNulls<String>(AdvancedTitleSlot.entries.size)
    private val appliedCompositions =
        arrayOfNulls<com.airbnb.lottie.LottieComposition>(AdvancedTitleSlot.entries.size)
    private val appliedRenderConfigs = arrayOfNulls<RenderConfig>(AdvancedTitleSlot.entries.size)
    private val boundBlocks = arrayOfNulls<TextPage.EpubEmbeddedBlock>(AdvancedTitleSlot.entries.size)
    private val committedCompositions = AccessOrderedLruCache<String, CommittedComposition>(
        maxCommitted
    )

    // --- compositions waiting for the page turn to finish -------------------------

    fun pendingComposition(slot: AdvancedTitleSlot): PendingComposition? {
        return pendingCompositions[slot.ordinal]
    }

    fun setPendingComposition(slot: AdvancedTitleSlot, value: PendingComposition?) {
        pendingCompositions[slot.ordinal] = value
    }

    fun pendingCompositions(): List<PendingComposition> = pendingCompositions.filterNotNull()

    fun clearPendingCompositions() {
        pendingCompositions.fill(null)
    }

    // --- image decoding jobs ------------------------------------------------------

    fun imagePrepareJob(slot: AdvancedTitleSlot): Coroutine<Boolean>? {
        return imagePrepareJobs[slot.ordinal]
    }

    fun setImagePrepareJob(slot: AdvancedTitleSlot, job: Coroutine<Boolean>?) {
        imagePrepareJobs[slot.ordinal] = job
    }

    fun cancelImagePrepareJob(slot: AdvancedTitleSlot) {
        imagePrepareJobs[slot.ordinal]?.cancel()
        imagePrepareJobs[slot.ordinal] = null
    }

    // --- what each slot is currently drawing --------------------------------------

    fun appliedKey(slot: AdvancedTitleSlot): String? = appliedKeys[slot.ordinal]

    fun appliedComposition(slot: AdvancedTitleSlot): com.airbnb.lottie.LottieComposition? {
        return appliedCompositions[slot.ordinal]
    }

    fun appliedRenderConfig(slot: AdvancedTitleSlot): RenderConfig? {
        return appliedRenderConfigs[slot.ordinal]
    }

    fun setApplied(
        slot: AdvancedTitleSlot,
        key: String,
        composition: com.airbnb.lottie.LottieComposition,
        renderConfig: RenderConfig
    ) {
        appliedKeys[slot.ordinal] = key
        appliedCompositions[slot.ordinal] = composition
        appliedRenderConfigs[slot.ordinal] = renderConfig
    }

    fun clearApplied(slot: AdvancedTitleSlot) {
        appliedKeys[slot.ordinal] = null
        appliedCompositions[slot.ordinal] = null
        appliedRenderConfigs[slot.ordinal] = null
        boundBlocks[slot.ordinal] = null
    }

    // --- which title each slot is bound to ----------------------------------------

    fun boundBlock(slot: AdvancedTitleSlot): TextPage.EpubEmbeddedBlock? {
        return boundBlocks[slot.ordinal]
    }

    fun setBoundBlock(slot: AdvancedTitleSlot, block: TextPage.EpubEmbeddedBlock?) {
        boundBlocks[slot.ordinal] = block
    }

    // --- compositions kept per key, so a returning page finds its own -------------

    fun committed(key: String): CommittedComposition? = committedCompositions[key]

    fun putCommitted(key: String, value: CommittedComposition) {
        committedCompositions.put(key, value)
    }
}
