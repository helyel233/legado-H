package io.legado.app.help.review

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ReviewWindowPrefetcherPolicyTest {

    @Before
    fun setUp() {
        ReviewWindowPrefetcher.clearAllCooldown()
    }

    @After
    fun tearDown() {
        ReviewWindowPrefetcher.clearAllCooldown()
    }

    @Test
    fun windowRangeCoversFirstLastZeroCappedAndSingleChapter() {
        assertEquals(0..2, ReviewWindowPrefetcher.windowRange(centerIndex = 0, preDownloadNum = 2, chapterSize = 10))
        assertEquals(9..9, ReviewWindowPrefetcher.windowRange(centerIndex = 9, preDownloadNum = 2, chapterSize = 10))
        assertEquals(3..3, ReviewWindowPrefetcher.windowRange(centerIndex = 3, preDownloadNum = 0, chapterSize = 10))
        assertEquals(2..5, ReviewWindowPrefetcher.windowRange(centerIndex = 2, preDownloadNum = 10, chapterSize = 20))
        assertEquals(0..0, ReviewWindowPrefetcher.windowRange(centerIndex = 0, preDownloadNum = 2, chapterSize = 1))
        assertTrue(ReviewWindowPrefetcher.windowRange(centerIndex = 0, preDownloadNum = 2, chapterSize = 0).isEmpty())
        assertTrue(ReviewWindowPrefetcher.windowRange(centerIndex = -1, preDownloadNum = 2, chapterSize = 5).isEmpty())
        assertTrue(ReviewWindowPrefetcher.windowRange(centerIndex = 5, preDownloadNum = 2, chapterSize = 5).isEmpty())
    }

    @Test
    fun isStaleRespectsTtlDaysAndSavedAtBoundaries() {
        val oneDayMs = 24 * 60 * 60 * 1000L
        val now = 10_000_000_000L

        assertFalse(ReviewWindowPrefetcher.isStale(savedAt = 0L, now = now, ttlDays = 0))
        assertFalse(ReviewWindowPrefetcher.isStale(savedAt = 1L, now = now, ttlDays = -3))

        assertTrue(ReviewWindowPrefetcher.isStale(savedAt = 0L, now = now, ttlDays = 7))
        assertTrue(ReviewWindowPrefetcher.isStale(savedAt = -1L, now = now, ttlDays = 1))

        val exactBoundarySavedAt = now - 3 * oneDayMs
        assertFalse(ReviewWindowPrefetcher.isStale(savedAt = exactBoundarySavedAt, now = now, ttlDays = 3))
        assertTrue(ReviewWindowPrefetcher.isStale(savedAt = exactBoundarySavedAt - 1L, now = now, ttlDays = 3))
    }

    @Test
    fun singleFlightDeduplicatesConcurrentSameKeyAndAllowsSubsequentRun() = runBlocking {
        val key = "book-sf|ch-1|btn-same"
        val executions = AtomicInteger(0)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = async(Dispatchers.Default) {
            ReviewWindowPrefetcher.singleFlight(key) {
                executions.incrementAndGet()
                entered.complete(Unit)
                release.await()
            }
        }

        withTimeout(5_000L) { entered.await() }

        val secondReady = CompletableDeferred<Unit>()
        val thirdReady = CompletableDeferred<Unit>()
        val second = async(Dispatchers.Default) {
            secondReady.complete(Unit)
            ReviewWindowPrefetcher.singleFlight(key) {
                executions.incrementAndGet()
            }
        }
        val third = async(Dispatchers.Default) {
            thirdReady.complete(Unit)
            ReviewWindowPrefetcher.singleFlight(key) {
                executions.incrementAndGet()
            }
        }

        withTimeout(5_000L) {
            secondReady.await()
            thirdReady.await()
        }
        delay(50L)

        release.complete(Unit)
        withTimeout(5_000L) {
            awaitAll(first, second, third)
        }
        assertEquals(1, executions.get())

        ReviewWindowPrefetcher.singleFlight(key) {
            executions.incrementAndGet()
        }
        assertEquals(2, executions.get())
    }

    @Test
    fun singleFlightRunsDifferentKeysConcurrently() = runBlocking {
        val keyA = "book-sf|ch-1|btn-a"
        val keyB = "book-sf|ch-1|btn-b"
        val aEntered = CompletableDeferred<Unit>()
        val bEntered = CompletableDeferred<Unit>()
        val executions = AtomicInteger(0)

        withTimeout(5_000L) {
            val jobA = async(Dispatchers.Default) {
                ReviewWindowPrefetcher.singleFlight(keyA) {
                    executions.incrementAndGet()
                    aEntered.complete(Unit)
                    bEntered.await()
                }
            }
            val jobB = async(Dispatchers.Default) {
                ReviewWindowPrefetcher.singleFlight(keyB) {
                    executions.incrementAndGet()
                    bEntered.complete(Unit)
                    aEntered.await()
                }
            }
            awaitAll(jobA, jobB)
        }

        assertEquals(2, executions.get())
    }

    @Test
    fun cooldownTracksExpiryPerBookClearAndClearAll() {
        val cooldownMs = 5 * 60 * 1000L
        val t0 = 1_000_000L
        val bookAKey1 = "bookA|ch1|btn1"
        val bookAKey2 = "bookA|ch2|btn2"
        val bookABKey = "bookAB|ch1|btn1"
        val bookBKey = "bookB|ch1|btn1"

        assertFalse(ReviewWindowPrefetcher.isInCooldown(bookAKey1, t0))

        ReviewWindowPrefetcher.recordCooldown(bookAKey1, t0)
        assertTrue(ReviewWindowPrefetcher.isInCooldown(bookAKey1, t0))
        assertTrue(ReviewWindowPrefetcher.isInCooldown(bookAKey1, t0 + cooldownMs - 1L))
        assertFalse(ReviewWindowPrefetcher.isInCooldown(bookAKey1, t0 + cooldownMs))
        assertFalse(ReviewWindowPrefetcher.isInCooldown(bookAKey1, t0))

        ReviewWindowPrefetcher.recordCooldown(bookAKey1, t0)
        ReviewWindowPrefetcher.recordCooldown(bookAKey2, t0)
        ReviewWindowPrefetcher.recordCooldown(bookABKey, t0)
        ReviewWindowPrefetcher.recordCooldown(bookBKey, t0)

        ReviewWindowPrefetcher.clearCooldown("")
        ReviewWindowPrefetcher.clearCooldown("   ")
        assertTrue(ReviewWindowPrefetcher.isInCooldown(bookAKey1, t0 + 1L))

        ReviewWindowPrefetcher.clearCooldown("bookA")
        assertFalse(ReviewWindowPrefetcher.isInCooldown(bookAKey1, t0 + 1L))
        assertFalse(ReviewWindowPrefetcher.isInCooldown(bookAKey2, t0 + 1L))
        assertTrue(ReviewWindowPrefetcher.isInCooldown(bookABKey, t0 + 1L))
        assertTrue(ReviewWindowPrefetcher.isInCooldown(bookBKey, t0 + 1L))

        ReviewWindowPrefetcher.clearAllCooldown()
        assertFalse(ReviewWindowPrefetcher.isInCooldown(bookABKey, t0 + 1L))
        assertFalse(ReviewWindowPrefetcher.isInCooldown(bookBKey, t0 + 1L))
    }
}
