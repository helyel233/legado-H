package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPerfMetricsTest {

    @Test
    fun trackerComputesMinAvgP95MaxAndRingBufferEviction() {
        var nowNs = 1_000_000_000L
        val tracker = UiPerfTracker(maxSamplesPerMetric = 4, nanoClock = { nowNs })

        tracker.markStart("k1")
        nowNs += 10_000_000L // 10ms
        val e1 = tracker.markEnd("k1", "bg_apply", "miss")
        assertNotNull(e1)
        assertEquals(10.0, e1!!, 0.001)

        tracker.record("bg_apply", 2.0, "hit")
        tracker.record("bg_apply", 4.0, "hit")
        tracker.record("bg_apply", 20.0, "miss")
        tracker.record("bg_apply", 1.0, "hit") // evicts 10.0 from 4-slot window -> window=[2,4,20,1]

        val summary = tracker.snapshot("bg_apply")!!
        assertEquals(5L, summary.count)
        assertEquals(1.0, summary.minMs, 0.001)
        assertEquals(20.0, summary.maxMs, 0.001)
        assertEquals(7.4, summary.avgMs, 0.001)
        assertEquals(20.0, summary.p95Ms, 0.001)
        assertEquals(1.0, summary.lastMs, 0.001)
        assertEquals("hit", summary.lastTag)

        val dump = tracker.dumpSummary()
        assertTrue(dump.contains("bg_apply"))
        assertTrue(dump.contains("[hit]"))

        tracker.clear()
        assertNull(tracker.snapshot("bg_apply"))
    }
}
