package io.legado.app.help.book

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class ChapterFetchCoordinatorTest {

    @Test
    fun concurrentCallsForSameKeyExecuteFetcherOnceAndShareResult() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = ChapterFetchCoordinator(scope)
            val key = ChapterFetchKey("https://book/1", "https://book/1/ch1", 1)
            val fetchCalls = AtomicInteger(0)
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<String>()

            val first = async(Dispatchers.Default) {
                coordinator.fetchOrJoin(key) {
                    fetchCalls.incrementAndGet()
                    started.complete(Unit)
                    gate.await()
                }
            }
            withTimeout(2_000) { started.await() }

            val second = async(Dispatchers.Default) {
                coordinator.fetchOrJoin(key) {
                    fetchCalls.incrementAndGet()
                    "duplicate"
                }
            }
            val third = async(Dispatchers.Default) {
                coordinator.fetchOrJoin(key) {
                    fetchCalls.incrementAndGet()
                    "duplicate"
                }
            }

            // Wait until all three waiters are attached
            withTimeout(2_000) {
                while (coordinator.waiterCount(key) < 3) {
                    kotlinx.coroutines.yield()
                }
            }
            assertEquals(1, fetchCalls.get())
            assertTrue(coordinator.isInFlight(key))

            gate.complete("chapter-body-1")

            assertEquals("chapter-body-1", first.await())
            assertEquals("chapter-body-1", second.await())
            assertEquals("chapter-body-1", third.await())
            assertEquals(1, fetchCalls.get())
            assertFalse(coordinator.isInFlight(key))
            assertEquals(0, coordinator.inFlightCount())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancelingPrefetchWaiterKeepsSharedFetchAliveWhileLastWaiterCancelsIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = ChapterFetchCoordinator(scope)
            val key = ChapterFetchKey("https://book/2", "https://book/2/ch2", 2)
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<String>()
            val underlyingCanceled = CompletableDeferred<Unit>()

            val prefetchJob = launch(Dispatchers.Default) {
                coordinator.fetchOrJoin(key) {
                    started.complete(Unit)
                    try {
                        gate.await()
                    } catch (e: CancellationException) {
                        underlyingCanceled.complete(Unit)
                        throw e
                    }
                }
            }
            withTimeout(2_000) { started.await() }

            val readerJob = async(Dispatchers.Default) {
                coordinator.fetchOrJoin(key) {
                    "should-not-run"
                }
            }
            withTimeout(2_000) {
                while (coordinator.waiterCount(key) < 2) {
                    kotlinx.coroutines.yield()
                }
            }

            // Canceling prefetch drops waiter count to 1; fetch must stay alive for readerJob.
            prefetchJob.cancelAndJoin()
            assertFalse(underlyingCanceled.isCompleted)
            assertTrue(coordinator.isInFlight(key))
            assertEquals(1, coordinator.waiterCount(key))

            gate.complete("shared-content")
            assertEquals("shared-content", readerJob.await())
            assertFalse(underlyingCanceled.isCompleted)
            assertEquals(0, coordinator.inFlightCount())

            // Now verify canceling the LAST waiter DOES cancel the underlying fetch.
            val key2 = ChapterFetchKey("https://book/2", "https://book/2/ch3", 3)
            val started2 = CompletableDeferred<Unit>()
            val gate2 = CompletableDeferred<String>()
            val underlyingCanceled2 = CompletableDeferred<Unit>()

            val soloJob = launch(Dispatchers.Default) {
                coordinator.fetchOrJoin(key2) {
                    started2.complete(Unit)
                    try {
                        gate2.await()
                    } catch (e: CancellationException) {
                        underlyingCanceled2.complete(Unit)
                        throw e
                    }
                }
            }
            withTimeout(2_000) { started2.await() }
            soloJob.cancelAndJoin()
            withTimeout(2_000) { underlyingCanceled2.await() }
            assertFalse(coordinator.isInFlight(key2))
            assertEquals(0, coordinator.inFlightCount())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedFetchRemovesEntryAndAllowsSubsequentRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = ChapterFetchCoordinator(scope)
            val key = ChapterFetchKey("https://book/3", "https://book/3/ch1", 0)
            val attempts = AtomicInteger(0)

            var caught: Throwable? = null
            try {
                coordinator.fetchOrJoin(key) {
                    attempts.incrementAndGet()
                    throw IOException("network timeout")
                }
            } catch (e: IOException) {
                caught = e
            }

            assertTrue(caught is IOException)
            assertEquals(1, attempts.get())
            assertFalse(coordinator.isInFlight(key))
            assertEquals(0, coordinator.inFlightCount())

            val retryResult = coordinator.fetchOrJoin(key) {
                attempts.incrementAndGet()
                "recovered-content"
            }

            assertEquals("recovered-content", retryResult)
            assertEquals(2, attempts.get())
            assertFalse(coordinator.isInFlight(key))
            assertEquals(0, coordinator.inFlightCount())
        } finally {
            scope.cancel()
        }
    }
}
