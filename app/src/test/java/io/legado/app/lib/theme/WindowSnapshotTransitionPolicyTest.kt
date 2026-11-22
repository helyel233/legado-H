package io.legado.app.lib.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowSnapshotTransitionPolicyTest {

    @Test
    fun decideRevealMode_eInkAlwaysInstantCutWithZeroDurations() {
        assertEquals(
            SnapshotTransitionPolicy.RevealMode.INSTANT_CUT,
            SnapshotTransitionPolicy.decideRevealMode(isEInk = true, sdkInt = 34, hasSnapshotBitmap = true)
        )
        assertEquals(
            SnapshotTransitionPolicy.RevealMode.INSTANT_CUT,
            SnapshotTransitionPolicy.decideRevealMode(isEInk = true, sdkInt = 28, hasSnapshotBitmap = false)
        )
        assertEquals(0L, SnapshotTransitionPolicy.resolveRevealDurationMs(isEInk = true))
        assertEquals(0L, SnapshotTransitionPolicy.resolveFirstFrameTimeoutMs(isEInk = true))
    }

    @Test
    fun decideRevealMode_apiLevelsUseCircularOrAlphaFade() {
        assertEquals(
            SnapshotTransitionPolicy.RevealMode.CIRCULAR_REVEAL,
            SnapshotTransitionPolicy.decideRevealMode(isEInk = false, sdkInt = 30, hasSnapshotBitmap = true)
        )
        assertEquals(
            SnapshotTransitionPolicy.RevealMode.ALPHA_FADE,
            SnapshotTransitionPolicy.decideRevealMode(isEInk = false, sdkInt = 29, hasSnapshotBitmap = true)
        )
        assertEquals(
            SnapshotTransitionPolicy.RevealMode.ALPHA_FADE,
            SnapshotTransitionPolicy.decideRevealMode(isEInk = false, sdkInt = 34, hasSnapshotBitmap = false)
        )
        assertEquals(600L, SnapshotTransitionPolicy.resolveFirstFrameTimeoutMs(isEInk = false))
    }

    @Test
    fun shouldCommitReveal_requiresBothPreDrawAndFrameCommitUnlessTimedOut() {
        assertFalse(SnapshotTransitionPolicy.shouldCommitReveal(preDrawDone = false, frameCommitted = false, timedOut = false))
        assertFalse(SnapshotTransitionPolicy.shouldCommitReveal(preDrawDone = true, frameCommitted = false, timedOut = false))
        assertFalse(SnapshotTransitionPolicy.shouldCommitReveal(preDrawDone = false, frameCommitted = true, timedOut = false))
        assertTrue(SnapshotTransitionPolicy.shouldCommitReveal(preDrawDone = true, frameCommitted = true, timedOut = false))
        assertTrue(SnapshotTransitionPolicy.shouldCommitReveal(preDrawDone = false, frameCommitted = false, timedOut = true))
    }

    @Test
    fun isSnapshotFresh_rejectsExpiredOrNegativeTimestamp() {
        assertTrue(SnapshotTransitionPolicy.isSnapshotFresh(createdElapsedMs = 1000L, nowElapsedMs = 1200L))
        assertFalse(SnapshotTransitionPolicy.isSnapshotFresh(createdElapsedMs = 1000L, nowElapsedMs = 4500L))
        assertFalse(SnapshotTransitionPolicy.isSnapshotFresh(createdElapsedMs = 5000L, nowElapsedMs = 4000L))
    }
}
