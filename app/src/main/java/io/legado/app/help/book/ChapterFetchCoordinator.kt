package io.legado.app.help.book

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ChapterFetchKey(
    val bookUrl: String,
    val chapterUrl: String,
    val chapterIndex: Int
)

/**
 * Single-Flight coordinator for chapter content fetches.
 *
 * Concurrent callers ([ChapterPrefetcher] and [io.legado.app.model.CacheBook.CacheBookModel] via
 * `download`/`downloadAwait`) requesting the same [ChapterFetchKey] share a single in-flight
 * [Deferred]. Foreground reads through [io.legado.app.model.ReadBookContentLoader] check disk/memory
 * cache first and delegate network fetches through `CacheBookModel`, joining any active prefetch
 * here rather than awaiting [ChapterFetchCoordinator] directly. Reference-counted waiters ensure
 * canceling a background prefetch does not abort the underlying fetch while an active `CacheBookModel`
 * download is still awaiting it; only canceling the last waiter aborts the fetch.
 */
class ChapterFetchCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private class InFlightEntry(
        val deferred: Deferred<String>,
        val waiters: AtomicInteger = AtomicInteger(0)
    )

    private val lock = Any()
    private val inFlight = ConcurrentHashMap<ChapterFetchKey, InFlightEntry>()

    /**
     * Joins an existing in-flight fetch for [key] or starts a new one using [fetcher].
     */
    suspend fun fetchOrJoin(
        key: ChapterFetchKey,
        fetcher: suspend () -> String
    ): String {
        currentCoroutineContext().ensureActive()
        var shouldStart = false
        val entry = synchronized(lock) {
            val existing = inFlight[key]
            if (existing != null && !existing.deferred.isCompleted && !existing.deferred.isCancelled) {
                existing.waiters.incrementAndGet()
                existing
            } else {
                if (existing != null) {
                    inFlight.remove(key, existing)
                }
                val deferred = scope.async(start = CoroutineStart.LAZY) {
                    fetcher()
                }
                val created = InFlightEntry(deferred, AtomicInteger(1))
                inFlight[key] = created
                deferred.invokeOnCompletion {
                    synchronized(lock) {
                        inFlight.remove(key, created)
                    }
                }
                shouldStart = true
                created
            }
        }
        if (shouldStart) {
            entry.deferred.start()
        }
        var released = false
        try {
            return entry.deferred.await()
        } catch (e: CancellationException) {
            releaseWaiter(key, entry, cancelIfLast = true)
            released = true
            throw e
        } finally {
            if (!released) {
                releaseWaiter(key, entry, cancelIfLast = false)
            }
        }
    }

    fun isInFlight(key: ChapterFetchKey): Boolean = synchronized(lock) {
        val entry = inFlight[key] ?: return@synchronized false
        !entry.deferred.isCompleted && !entry.deferred.isCancelled
    }

    fun inFlightCount(): Int = synchronized(lock) {
        inFlight.size
    }

    fun waiterCount(key: ChapterFetchKey): Int = synchronized(lock) {
        inFlight[key]?.waiters?.get() ?: 0
    }

    private fun releaseWaiter(
        key: ChapterFetchKey,
        entry: InFlightEntry,
        cancelIfLast: Boolean
    ) {
        var deferredToCancel: Deferred<String>? = null
        synchronized(lock) {
            val remaining = entry.waiters.decrementAndGet()
            if (cancelIfLast && remaining <= 0 && !entry.deferred.isCompleted) {
                inFlight.remove(key, entry)
                deferredToCancel = entry.deferred
            }
        }
        deferredToCancel?.cancel()
    }

    companion object {
        val default = ChapterFetchCoordinator()

        suspend fun fetchOrJoin(
            key: ChapterFetchKey,
            fetcher: suspend () -> String
        ): String = default.fetchOrJoin(key, fetcher)

        fun isInFlight(key: ChapterFetchKey): Boolean = default.isInFlight(key)
    }
}
