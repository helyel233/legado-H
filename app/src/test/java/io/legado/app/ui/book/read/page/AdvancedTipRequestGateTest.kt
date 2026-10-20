package io.legado.app.ui.book.read.page

import io.legado.app.help.config.AdvancedTipSlot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedTipRequestGateTest {

    @Test
    fun freshGateHasNoCurrentRequest() {
        val gate = AdvancedTipRequestGate()

        assertFalse(gate.hasCurrent(AdvancedTipSlot.HEADER, 1L, "key"))
        assertFalse(gate.accepts(
            AdvancedTipRequestToken(1L, 1L, AdvancedTipSlot.HEADER, "key"),
            contentGeneration = 1L
        ))
    }

    @Test
    fun begunRequestIsAccepted() {
        val gate = AdvancedTipRequestGate()

        val token = gate.begin(AdvancedTipSlot.HEADER, contentGeneration = 7L, compositionKey = "key")

        assertTrue(gate.accepts(token, contentGeneration = 7L))
        assertTrue(gate.hasCurrent(AdvancedTipSlot.HEADER, 7L, "key"))
    }

    @Test
    fun requestFromAnOlderContentGenerationIsRejected() {
        val gate = AdvancedTipRequestGate()

        val token = gate.begin(AdvancedTipSlot.HEADER, contentGeneration = 7L, compositionKey = "key")

        // 翻页后世代号变了：即便槽位里还留着同一个令牌，它也已经过期。
        assertFalse(gate.accepts(token, contentGeneration = 8L))
        assertFalse(gate.hasCurrent(AdvancedTipSlot.HEADER, 8L, "key"))
        assertTrue(gate.hasCurrent(AdvancedTipSlot.HEADER, 7L, "key"))
    }

    @Test
    fun aReplacedRequestIsNotAccepted() {
        val gate = AdvancedTipRequestGate()

        val first = gate.begin(AdvancedTipSlot.HEADER, contentGeneration = 1L, compositionKey = "key1")
        val second = gate.begin(AdvancedTipSlot.HEADER, contentGeneration = 1L, compositionKey = "key2")

        assertFalse(gate.accepts(first, contentGeneration = 1L))
        assertTrue(gate.accepts(second, contentGeneration = 1L))
        assertFalse(gate.hasCurrent(AdvancedTipSlot.HEADER, 1L, "key1"))
        assertTrue(gate.hasCurrent(AdvancedTipSlot.HEADER, 1L, "key2"))
    }

    @Test
    fun slotsAreTrackedIndependently() {
        val gate = AdvancedTipRequestGate()

        val header = gate.begin(AdvancedTipSlot.HEADER, contentGeneration = 1L, compositionKey = "key")
        gate.begin(AdvancedTipSlot.FOOTER, contentGeneration = 1L, compositionKey = "key")

        assertTrue(gate.accepts(header, contentGeneration = 1L))
        assertTrue(gate.hasCurrent(AdvancedTipSlot.FOOTER, 1L, "key"))

        gate.clear(AdvancedTipSlot.HEADER)

        assertFalse(gate.accepts(header, contentGeneration = 1L))
        assertTrue(gate.hasCurrent(AdvancedTipSlot.FOOTER, 1L, "key"))
    }

    @Test
    fun clearAllDropsEverySlot() {
        val gate = AdvancedTipRequestGate()

        val header = gate.begin(AdvancedTipSlot.HEADER, contentGeneration = 1L, compositionKey = "key")
        gate.begin(AdvancedTipSlot.FOOTER, contentGeneration = 1L, compositionKey = "key")

        gate.clearAll()

        assertFalse(gate.accepts(header, contentGeneration = 1L))
        assertFalse(gate.hasCurrent(AdvancedTipSlot.HEADER, 1L, "key"))
        assertFalse(gate.hasCurrent(AdvancedTipSlot.FOOTER, 1L, "key"))
    }

    @Test
    fun everyRequestGetsItsOwnGeneration() {
        val gate = AdvancedTipRequestGate()

        val first = gate.begin(AdvancedTipSlot.HEADER, contentGeneration = 1L, compositionKey = "key")
        val second = gate.begin(AdvancedTipSlot.FOOTER, contentGeneration = 1L, compositionKey = "key")

        // 请求号必须递增，否则两个不同槽位的请求可能被当成同一个。
        assertTrue(second.requestGeneration > first.requestGeneration)
        assertFalse(first == second)
    }
}
