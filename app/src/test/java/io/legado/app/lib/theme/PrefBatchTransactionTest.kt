package io.legado.app.lib.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefBatchTransactionTest {

    @Test
    fun nestedBatches_invokeRecomputeExactlyOnceOnOuterExit() {
        var recomputeCount = 0
        var inBatchInsideOuter = false
        var inBatchInsideInner = false

        PrefBatchTransaction.runWithCallback(onOuterExit = { recomputeCount++ }) {
            inBatchInsideOuter = PrefBatchTransaction.isInBatch
            PrefBatchTransaction.runWithCallback(onOuterExit = { recomputeCount++ }) {
                inBatchInsideInner = PrefBatchTransaction.isInBatch
            }
            assertTrue(PrefBatchTransaction.isInBatch)
            assertEquals(0, recomputeCount)
        }

        assertTrue(inBatchInsideOuter)
        assertTrue(inBatchInsideInner)
        assertFalse(PrefBatchTransaction.isInBatch)
        assertEquals(1, recomputeCount)
    }

    @Test
    fun exceptionInsideBatch_stillResetsIsInBatchAndRunsExitCallback() {
        var exitCalled = false
        try {
            PrefBatchTransaction.runWithCallback(onOuterExit = { exitCalled = true }) {
                assertTrue(PrefBatchTransaction.isInBatch)
                throw IllegalStateException("boom")
            }
        } catch (_: IllegalStateException) {
            // expected
        }
        assertFalse(PrefBatchTransaction.isInBatch)
        assertTrue(exitCalled)
    }

    @Test
    fun batchRevisionBump_incrementsComposeObservableRevisionExactlyOnceOnOuterExit() {
        val before = currentThemeUiBatchRevision()
        PrefBatchTransaction.runWithCallback(onOuterExit = ::bumpThemeUiBatchRevision) {
            assertEquals(before, currentThemeUiBatchRevision())
            PrefBatchTransaction.runWithCallback(onOuterExit = ::bumpThemeUiBatchRevision) {
                assertEquals(before, currentThemeUiBatchRevision())
            }
            assertEquals(before, currentThemeUiBatchRevision())
        }
        assertEquals(before + 1, currentThemeUiBatchRevision())
    }
}
