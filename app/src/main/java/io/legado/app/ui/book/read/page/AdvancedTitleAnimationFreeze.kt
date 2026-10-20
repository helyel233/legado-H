package io.legado.app.ui.book.read.page

import io.legado.app.help.config.AdvancedTipSlot

/**
 * Freeze state of the advanced title and tip animations during a page turn.
 *
 * A page turn re-runs the whole bind, so the animations that were playing are paused
 * first and resumed afterwards. Which slots were playing has to survive the bind, and
 * it has to be dropped again as soon as it has been used: a slot that was not resumed
 * (hidden in the meantime, or no longer ready) must not come back to life on a later
 * page turn.
 */
internal class AdvancedTitleAnimationFreeze {

    var isFrozen: Boolean = false
        private set

    private val titleToResume = BooleanArray(AdvancedTitleSlot.entries.size)
    private val tipToResume = BooleanArray(AdvancedTipSlot.entries.size)

    /**
     * Starts a freeze.
     *
     * Returns false when a freeze is already in progress: the caller must then leave
     * the animations alone instead of pausing them a second time.
     */
    fun beginFreeze(): Boolean {
        if (isFrozen) return false
        isFrozen = true
        return true
    }

    /** Ends the freeze. The resume plan is kept until it is used or dropped. */
    fun endFreeze() {
        isFrozen = false
    }

    /** Remembers that the title of this slot was playing and should play again. */
    fun markTitleToResume(slot: AdvancedTitleSlot) {
        titleToResume[slot.ordinal] = true
    }

    /** Remembers that the tip of this slot was playing and should play again. */
    fun markTipToResume(slot: AdvancedTipSlot) {
        tipToResume[slot.ordinal] = true
    }

    /** Forgets the resume plan of a tip slot, e.g. when the slot is torn down. */
    fun clearTipResume(slot: AdvancedTipSlot) {
        tipToResume[slot.ordinal] = false
    }

    /**
     * Takes the resume plan of a title slot.
     *
     * The plan is dropped whether or not the caller goes on to resume it: a title that
     * could not be resumed must not be resumed later by accident.
     */
    fun consumeTitleResume(slot: AdvancedTitleSlot): Boolean {
        val shouldResume = titleToResume[slot.ordinal]
        titleToResume[slot.ordinal] = false
        return shouldResume
    }

    /** Takes the resume plan of a tip slot, dropping it either way. */
    fun consumeTipResume(slot: AdvancedTipSlot): Boolean {
        val shouldResume = tipToResume[slot.ordinal]
        tipToResume[slot.ordinal] = false
        return shouldResume
    }

    /** Drops every pending resume plan. */
    fun clearResumePlan() {
        titleToResume.fill(false)
        tipToResume.fill(false)
    }

    /** Returns to the state of a fresh reader: nothing frozen, nothing pending. */
    fun reset() {
        isFrozen = false
        clearResumePlan()
    }
}
