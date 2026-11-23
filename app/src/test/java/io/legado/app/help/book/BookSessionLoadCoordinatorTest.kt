package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BookSessionLoadCoordinatorTest {

    private fun testBook(
        bookUrl: String = "https://example.test/book/1",
        origin: String = "https://example.test",
        notShelf: Boolean = true
    ): Book {
        return Book(
            bookUrl = bookUrl,
            name = "测试小说",
            author = "测试作者",
            origin = origin,
            originName = "测试源",
            type = if (notShelf) BookType.text or BookType.notShelf else BookType.text
        )
    }

    private fun testChapters(bookUrl: String, count: Int): List<BookChapter> {
        return List(count) { idx ->
            BookChapter(
                url = "$bookUrl/ch/$idx",
                title = "第${idx + 1}章",
                bookUrl = bookUrl,
                index = idx
            )
        }
    }

    @Test
    fun `concurrent book info fetches reuse single in-flight deferred and clean up afterwards`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = BookSessionLoadCoordinator(scope = scope)
            val fetchCalls = AtomicInteger(0)
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<String>()

            val detailBook = testBook()
            val readerBook = testBook()

            val detailDeferred = async(Dispatchers.Default) {
                coordinator.fetchOrJoinBookInfo(detailBook) { working ->
                    fetchCalls.incrementAndGet()
                    started.complete(Unit)
                    working.tocUrl = gate.await()
                    working.intro = "简介内容"
                    working
                }
            }
            withTimeout(2_000) { started.await() }

            val readerDeferred = async(Dispatchers.Default) {
                coordinator.fetchOrJoinBookInfo(readerBook, preserveNameAuthorOnApply = true) { working ->
                    fetchCalls.incrementAndGet()
                    working.tocUrl = "https://duplicate.invalid"
                    working
                }
            }

            withTimeout(2_000) {
                while (coordinator.bookInfoWaiterCount(detailBook.bookUrl, detailBook.origin) < 2) {
                    kotlinx.coroutines.yield()
                }
            }
            assertEquals(1, fetchCalls.get())
            assertTrue(coordinator.isBookInfoInFlight(detailBook.bookUrl, detailBook.origin))

            gate.complete("https://example.test/toc/1")
            val detailResult = detailDeferred.await()
            val readerResult = readerDeferred.await()

            assertEquals("https://example.test/toc/1", detailResult.tocUrl)
            assertEquals("https://example.test/toc/1", readerResult.tocUrl)
            assertTrue("notShelf flag must be preserved", readerResult.isNotShelf)
            assertEquals(1, fetchCalls.get())
            assertFalse(coordinator.isBookInfoInFlight(detailBook.bookUrl, detailBook.origin))
            assertEquals(0, coordinator.inFlightBookInfoCount())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `concurrent chapter list fetches share deferred and forward progress to joined reader`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = BookSessionLoadCoordinator(scope = scope)
            val fetchCalls = AtomicInteger(0)
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val readerProgressUpdates = mutableListOf<Pair<Int, Int>>()

            val detailBook = testBook()
            val readerBook = testBook()
            val expectedChapters = testChapters(detailBook.bookUrl, 12)

            val detailJob = async(Dispatchers.Default) {
                coordinator.fetchOrJoinChapterList(detailBook, rememberHandoff = true) { working, onProgress ->
                    fetchCalls.incrementAndGet()
                    started.complete(Unit)
                    gate.await()
                    onProgress(6, 2)
                    onProgress(12, 2)
                    working.totalChapterNum = expectedChapters.size
                    TocSessionResult(working, expectedChapters, failedPages = 0)
                }
            }
            withTimeout(2_000) { started.await() }

            val readerJob = async(Dispatchers.Default) {
                coordinator.fetchOrJoinChapterList(
                    book = readerBook,
                    consumeHandoff = true,
                    onProgress = { loaded, total ->
                        synchronized(readerProgressUpdates) {
                            readerProgressUpdates.add(loaded to total)
                        }
                    }
                ) { working, _ ->
                    fetchCalls.incrementAndGet()
                    TocSessionResult(working, emptyList(), 0)
                }
            }

            withTimeout(2_000) {
                while (coordinator.chapterListWaiterCount(detailBook.bookUrl, detailBook.origin) < 2) {
                    kotlinx.coroutines.yield()
                }
            }
            gate.complete(Unit)

            val res1 = detailJob.await()
            val res2 = readerJob.await()

            assertEquals(12, res1.chapters.size)
            assertEquals(12, res2.chapters.size)
            assertEquals(12, readerBook.totalChapterNum)
            assertTrue(readerBook.isNotShelf)
            assertEquals(listOf(6 to 2, 12 to 2), readerProgressUpdates)
            assertEquals(1, fetchCalls.get())
            assertEquals(0, coordinator.inFlightChapterListCount())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `canceling detail waiter keeps shared chapter list fetch alive for reader`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = BookSessionLoadCoordinator(scope = scope)
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val canceledFlag = CompletableDeferred<Unit>()
            val book = testBook()

            val detailJob = launch(Dispatchers.Default) {
                coordinator.fetchOrJoinChapterList(book) { working, _ ->
                    started.complete(Unit)
                    try {
                        gate.await()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        canceledFlag.complete(Unit)
                        throw e
                    }
                    TocSessionResult(working, testChapters(book.bookUrl, 5), 0)
                }
            }
            withTimeout(2_000) { started.await() }

            val readerJob = async(Dispatchers.Default) {
                coordinator.fetchOrJoinChapterList(testBook()) { working, _ ->
                    TocSessionResult(working, emptyList(), 0)
                }
            }
            withTimeout(2_000) {
                while (coordinator.chapterListWaiterCount(book.bookUrl, book.origin) < 2) {
                    kotlinx.coroutines.yield()
                }
            }

            detailJob.cancelAndJoin()
            assertFalse(canceledFlag.isCompleted)
            assertTrue(coordinator.isChapterListInFlight(book.bookUrl, book.origin))

            gate.complete(Unit)
            val result = readerJob.await()
            assertEquals(5, result.chapters.size)
            assertFalse(canceledFlag.isCompleted)
            assertEquals(0, coordinator.inFlightChapterListCount())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `sampled book staged TOC handoff resolves immediately consumes once and cleans up after persist`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val persistCalls = AtomicInteger(0)
            val persistStarted = CompletableDeferred<Unit>()
            val persistGate = CompletableDeferred<Unit>()

            val coordinator = BookSessionLoadCoordinator(
                scope = scope,
                persistSampledBook = { _, _, _ ->
                    persistCalls.incrementAndGet()
                    persistStarted.complete(Unit)
                    persistGate.await()
                }
            )
            val book = testBook(bookUrl = "https://example.test/sampled/1", notShelf = true)
            val chapters = testChapters(book.bookUrl, 50)

            coordinator.stageSampledBookEntry(book, chapters)
            // Re-entrant duplicate stage while persistence is active must not launch a second write.
            coordinator.stageSampledBookEntry(book, chapters)

            withTimeout(2_000) { persistStarted.await() }
            assertEquals(1, persistCalls.get())
            assertEquals(50, coordinator.findStagedChapterCount(book.bookUrl))
            assertEquals("第1章", coordinator.findStagedChapter(book.bookUrl, 0)?.title)
            assertNotNull(coordinator.peekStagedBook(book.bookUrl))

            val consumed = coordinator.consumeStagedToc(book.bookUrl)
            assertNotNull(consumed)
            assertEquals(50, consumed?.size)
            // Second consume must return null (one-shot).
            assertNull(coordinator.consumeStagedToc(book.bookUrl))
            // While DB write is still in progress, chapter lookup remains available.
            assertEquals("第2章", coordinator.findStagedChapter(book.bookUrl, 1)?.title)

            persistGate.complete(Unit)
            coordinator.awaitPendingPersist(book.bookUrl)

            // Once both consumed and persisted, staged entry is cleaned up completely.
            assertEquals(0, coordinator.stagedEntryCount())
            assertNull(coordinator.findStagedChapter(book.bookUrl, 0))
            assertNull(coordinator.peekStagedBook(book.bookUrl))
        } finally {
            scope.cancel()
        }
    }
}
