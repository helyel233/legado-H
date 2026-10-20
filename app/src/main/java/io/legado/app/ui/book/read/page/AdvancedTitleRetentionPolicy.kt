package io.legado.app.ui.book.read.page

/**
 * Decides whether a title slot may be kept as it is when the page is bound again.
 *
 * Retaining a slot is what keeps a title from flashing when the reader turns away and
 * back, but retaining the wrong slot would leave one page's title sitting over another
 * page — a far worse outcome than the flash. The rule is therefore deliberately strict:
 * the slot has to be drawing a composition for the *identical* block instance the
 * incoming page carries, that composition has to have been presented, and it has to be
 * on screen right now.
 */
internal object AdvancedTitleRetentionPolicy {

    /**
     * @param boundBlock the block the slot is currently bound to, if any
     * @param incomingBlock the block the incoming page carries, if any
     * @param isPresented the slot's composition has provably been drawn
     * @param hasComposition the slot currently holds a composition
     * @param isVisible the slot is on screen
     */
    fun canRetain(
        boundBlock: Any?,
        incomingBlock: Any?,
        isPresented: Boolean,
        hasComposition: Boolean,
        isVisible: Boolean
    ): Boolean {
        // Identity, not equality: two different block instances can carry the same
        // content, and only the very same instance proves the slot shows this title.
        if (boundBlock === null || incomingBlock === null) return false
        if (boundBlock !== incomingBlock) return false
        return isPresented && hasComposition && isVisible
    }
}
