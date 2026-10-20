package io.legado.app.ui.book.read.page

import io.legado.app.help.config.AdvancedTipSlot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedTitleAnimationFreezeTest {

    @Test
    fun freshControllerIsNeitherFrozenNorPending() {
        val freeze = AdvancedTitleAnimationFreeze()

        assertFalse(freeze.isFrozen)
        assertFalse(freeze.consumeTitleResume(AdvancedTitleSlot.PRIMARY))
        assertFalse(freeze.consumeTipResume(AdvancedTipSlot.HEADER))
    }

    @Test
    fun freezeIsStartedOnlyOnce() {
        val freeze = AdvancedTitleAnimationFreeze()

        assertTrue(freeze.beginFreeze())
        assertFalse(freeze.beginFreeze())
        assertTrue(freeze.isFrozen)
    }

    @Test
    fun frozenSlotsAreResumedAfterThePageTurn() {
        val freeze = AdvancedTitleAnimationFreeze()

        assertTrue(freeze.beginFreeze())
        freeze.markTitleToResume(AdvancedTitleSlot.PRIMARY)
        freeze.markTipToResume(AdvancedTipSlot.FOOTER)

        freeze.endFreeze()
        assertFalse(freeze.isFrozen)

        assertTrue(freeze.consumeTitleResume(AdvancedTitleSlot.PRIMARY))
        assertTrue(freeze.consumeTipResume(AdvancedTipSlot.FOOTER))
    }

    @Test
    fun resumePlanIsDroppedEvenWhenTheSlotCannotResume() {
        val freeze = AdvancedTitleAnimationFreeze()

        freeze.beginFreeze()
        freeze.markTitleToResume(AdvancedTitleSlot.PAIR)

        // 取计划时就把计划作废：这一次没能恢复的标题，不该在之后的某一页自己活过来。
        assertTrue(freeze.consumeTitleResume(AdvancedTitleSlot.PAIR))
        assertFalse(freeze.consumeTitleResume(AdvancedTitleSlot.PAIR))
    }

    @Test
    fun slotsAreTrackedIndependently() {
        val freeze = AdvancedTitleAnimationFreeze()

        freeze.beginFreeze()
        freeze.markTitleToResume(AdvancedTitleSlot.PRIMARY)
        freeze.markTipToResume(AdvancedTipSlot.HEADER)

        assertTrue(freeze.consumeTitleResume(AdvancedTitleSlot.PRIMARY))
        assertFalse(freeze.consumeTitleResume(AdvancedTitleSlot.PAIR))
        assertTrue(freeze.consumeTipResume(AdvancedTipSlot.HEADER))
        assertFalse(freeze.consumeTipResume(AdvancedTipSlot.FOOTER))
    }

    @Test
    fun tornDownTipSlotLosesItsResumePlan() {
        val freeze = AdvancedTitleAnimationFreeze()

        freeze.beginFreeze()
        freeze.markTipToResume(AdvancedTipSlot.HEADER)
        freeze.clearTipResume(AdvancedTipSlot.HEADER)

        assertFalse(freeze.consumeTipResume(AdvancedTipSlot.HEADER))
    }

    @Test
    fun clearResumePlanDropsEverything() {
        val freeze = AdvancedTitleAnimationFreeze()

        freeze.beginFreeze()
        freeze.markTitleToResume(AdvancedTitleSlot.PRIMARY)
        freeze.markTipToResume(AdvancedTipSlot.HEADER)

        freeze.clearResumePlan()

        assertFalse(freeze.consumeTitleResume(AdvancedTitleSlot.PRIMARY))
        assertFalse(freeze.consumeTipResume(AdvancedTipSlot.HEADER))
    }

    @Test
    fun resetReturnsToAFreshState() {
        val freeze = AdvancedTitleAnimationFreeze()

        freeze.beginFreeze()
        freeze.markTitleToResume(AdvancedTitleSlot.PRIMARY)
        freeze.markTipToResume(AdvancedTipSlot.FOOTER)

        freeze.reset()

        assertFalse(freeze.isFrozen)
        assertFalse(freeze.consumeTitleResume(AdvancedTitleSlot.PRIMARY))
        assertFalse(freeze.consumeTipResume(AdvancedTipSlot.FOOTER))
        // 重置后仍可再次冻结。
        assertTrue(freeze.beginFreeze())
    }
}
