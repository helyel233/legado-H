package io.legado.app.ui.main.homepage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class HomepageLoadSchedulerTest {

    @Test
    fun `respects global concurrency limit across different sources`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scheduler = HomepageLoadScheduler(scope = scope, maxConcurrency = 2)
            val activeCount = AtomicInteger(0)
            val maxSeen = AtomicInteger(0)
            val gates = List(4) { CompletableDeferred<Unit>() }
            val started = List(4) { CompletableDeferred<Unit>() }
            val jobs = mutableListOf<kotlinx.coroutines.Job>()

            for (i in 0 until 4) {
                jobs += scheduler.enqueue(
                    key = "k$i",
                    sourceUrl = "https://src$i.example",
                    priority = HomepageLoadScheduler.PRIORITY_VISIBLE,
                    order = i,
                ) {
                    val now = activeCount.incrementAndGet()
                    maxSeen.updateAndGet { prev -> maxOf(prev, now) }
                    started[i].complete(Unit)
                    gates[i].await()
                    activeCount.decrementAndGet()
                }
            }

            withTimeout(5_000) {
                started[0].await()
                started[1].await()
            }
            delay(30)
            assertEquals(2, maxSeen.get())
            assertEquals(2, activeCount.get())

            gates.forEach { it.complete(Unit) }
            withTimeout(5_000) { jobs.forEach { it.join() } }
            assertEquals(0, activeCount.get())
            assertEquals(2, maxSeen.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `serializes requests targeting the same sourceUrl`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scheduler = HomepageLoadScheduler(scope = scope, maxConcurrency = 3)
            val sameSourceActive = AtomicInteger(0)
            val sameSourceOverlap = AtomicInteger(0)
            val log = Collections.synchronizedList(mutableListOf<String>())
            val jobs = mutableListOf<kotlinx.coroutines.Job>()

            repeat(3) { idx ->
                jobs += scheduler.enqueue(
                    key = "same_$idx",
                    sourceUrl = "https://same.example",
                    priority = HomepageLoadScheduler.PRIORITY_VISIBLE,
                    order = idx,
                ) {
                    if (sameSourceActive.incrementAndGet() > 1) {
                        sameSourceOverlap.incrementAndGet()
                    }
                    delay(20)
                    log.add("same_$idx")
                    sameSourceActive.decrementAndGet()
                }
            }

            withTimeout(5_000) { jobs.forEach { it.join() } }
            assertEquals(0, sameSourceOverlap.get())
            assertEquals(listOf("same_0", "same_1", "same_2"), log.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `executes visible priority before preload and revalidate`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scheduler = HomepageLoadScheduler(scope = scope, maxConcurrency = 1)
            val gate = CompletableDeferred<Unit>()
            val blockerStarted = CompletableDeferred<Unit>()
            val executionOrder = Collections.synchronizedList(mutableListOf<String>())
            val jobs = mutableListOf<kotlinx.coroutines.Job>()

            jobs += scheduler.enqueue(
                key = "blocker",
                sourceUrl = "https://blocker.example",
                priority = HomepageLoadScheduler.PRIORITY_VISIBLE,
                order = 0,
            ) {
                blockerStarted.complete(Unit)
                gate.await()
                executionOrder.add("blocker")
            }
            withTimeout(5_000) { blockerStarted.await() }

            jobs += scheduler.enqueue(
                key = "revalidate",
                sourceUrl = "https://a.example",
                priority = HomepageLoadScheduler.PRIORITY_REVALIDATE,
                order = 1,
            ) {
                executionOrder.add("revalidate")
            }
            jobs += scheduler.enqueue(
                key = "preload",
                sourceUrl = "https://b.example",
                priority = HomepageLoadScheduler.PRIORITY_PRELOAD,
                order = 2,
            ) {
                executionOrder.add("preload")
            }
            jobs += scheduler.enqueue(
                key = "visible",
                sourceUrl = "https://c.example",
                priority = HomepageLoadScheduler.PRIORITY_VISIBLE,
                order = 3,
            ) {
                executionOrder.add("visible")
            }

            // 动态把 revalidate 提升为可见优先
            scheduler.updateVisibleKeys(setOf("revalidate"))
            gate.complete(Unit)
            withTimeout(5_000) { jobs.forEach { it.join() } }

            assertEquals("blocker", executionOrder.first())
            assertTrue(executionOrder.indexOf("revalidate") < executionOrder.indexOf("preload"))
            assertTrue(executionOrder.indexOf("visible") < executionOrder.indexOf("preload"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancelling running task releases source lock for next queued task`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scheduler = HomepageLoadScheduler(scope = scope, maxConcurrency = 1)
            val started = CompletableDeferred<Unit>()
            val nextRan = CompletableDeferred<Unit>()

            scheduler.enqueue(
                key = "first",
                sourceUrl = "https://src.example",
                priority = HomepageLoadScheduler.PRIORITY_VISIBLE,
                order = 0,
            ) {
                started.complete(Unit)
                delay(10_000)
            }
            val secondJob = scheduler.enqueue(
                key = "second",
                sourceUrl = "https://src.example",
                priority = HomepageLoadScheduler.PRIORITY_VISIBLE,
                order = 1,
            ) {
                nextRan.complete(Unit)
            }

            withTimeout(5_000) { started.await() }
            scheduler.cancel("first")
            withTimeout(5_000) {
                nextRan.await()
                secondJob.join()
            }
            assertTrue(nextRan.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `non-suspending immediate block on Unconfined does not leak running slot`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val scheduler = HomepageLoadScheduler(scope = scope, maxConcurrency = 1)
            val ran = mutableListOf<String>()

            val j1 = scheduler.enqueue(
                key = "sync_k",
                sourceUrl = "https://src.example",
                priority = HomepageLoadScheduler.PRIORITY_VISIBLE,
                order = 0,
            ) {
                ran.add("first")
            }
            j1.join()
            assertFalse("已完成的任务不得继续占用 running 槽位", scheduler.isActive("sync_k"))

            val j2 = scheduler.enqueue(
                key = "sync_k2",
                sourceUrl = "https://src.example",
                priority = HomepageLoadScheduler.PRIORITY_VISIBLE,
                order = 1,
            ) {
                ran.add("second")
            }
            j2.join()
            assertEquals(listOf("first", "second"), ran)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `replacing pending task cancels replaced job and runs onFinally once`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scheduler = HomepageLoadScheduler(scope = scope, maxConcurrency = 1)
            val gate = CompletableDeferred<Unit>()
            val blockerStarted = CompletableDeferred<Unit>()
            val oldFinallyCount = AtomicInteger(0)
            val newFinallyCount = AtomicInteger(0)

            val blocker = scheduler.enqueue(
                key = "blocker",
                sourceUrl = "https://blocker.example",
                priority = HomepageLoadScheduler.PRIORITY_VISIBLE,
                order = 0,
            ) {
                blockerStarted.complete(Unit)
                gate.await()
            }
            withTimeout(5_000) { blockerStarted.await() }

            val oldJob = scheduler.enqueue(
                key = "same_key",
                sourceUrl = "https://target.example",
                priority = HomepageLoadScheduler.PRIORITY_PRELOAD,
                order = 1,
                onFinally = { oldFinallyCount.incrementAndGet() },
            ) {
                // 不应执行
            }
            val newJob = scheduler.enqueue(
                key = "same_key",
                sourceUrl = "https://target.example",
                priority = HomepageLoadScheduler.PRIORITY_VISIBLE,
                order = 1,
                onFinally = { newFinallyCount.incrementAndGet() },
            ) {
                // 替换后的新任务正常执行
            }

            withTimeout(5_000) { oldJob.join() }
            assertTrue(oldJob.isCancelled)
            assertEquals(1, oldFinallyCount.get())

            gate.complete(Unit)
            withTimeout(5_000) {
                blocker.join()
                newJob.join()
            }
            assertEquals(1, newFinallyCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `updateVisibleKeys promotes visible tasks and demotes tasks scrolled out`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scheduler = HomepageLoadScheduler(scope = scope, maxConcurrency = 1)
            val gate = CompletableDeferred<Unit>()
            val blockerStarted = CompletableDeferred<Unit>()
            val executionOrder = Collections.synchronizedList(mutableListOf<String>())
            val jobs = mutableListOf<kotlinx.coroutines.Job>()

            jobs += scheduler.enqueue(
                key = "blocker",
                sourceUrl = "https://blocker.example",
                priority = HomepageLoadScheduler.PRIORITY_VISIBLE,
                order = 0,
            ) {
                blockerStarted.complete(Unit)
                gate.await()
            }
            withTimeout(5_000) { blockerStarted.await() }

            // mod_a 的基准优先级为 REVALIDATE，mod_b 为 PRELOAD
            jobs += scheduler.enqueue(
                key = "mod_a",
                sourceUrl = "https://a.example",
                priority = HomepageLoadScheduler.PRIORITY_REVALIDATE,
                order = 1,
            ) {
                executionOrder.add("mod_a")
            }
            jobs += scheduler.enqueue(
                key = "mod_b",
                sourceUrl = "https://b.example",
                priority = HomepageLoadScheduler.PRIORITY_PRELOAD,
                order = 2,
            ) {
                executionOrder.add("mod_b")
            }

            // 先滑入 mod_a，再滑出 mod_a 只保留空集：mod_a 必须被降回 REVALIDATE，晚于 PRELOAD 的 mod_b
            scheduler.updateVisibleKeys(setOf("mod_a"))
            scheduler.updateVisibleKeys(emptySet())

            gate.complete(Unit)
            withTimeout(5_000) { jobs.forEach { it.join() } }
            assertEquals(listOf("mod_b", "mod_a"), executionOrder)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `close cancels pending tasks synchronously and invokes onFinally`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scheduler = HomepageLoadScheduler(scope = scope, maxConcurrency = 1)
            val gate = CompletableDeferred<Unit>()
            val blockerStarted = CompletableDeferred<Unit>()
            val pendingFinally = AtomicInteger(0)

            scheduler.enqueue(
                key = "blocker",
                sourceUrl = "https://blocker.example",
                priority = HomepageLoadScheduler.PRIORITY_VISIBLE,
                order = 0,
            ) {
                blockerStarted.complete(Unit)
                gate.await()
            }
            withTimeout(5_000) { blockerStarted.await() }

            val pendingJob = scheduler.enqueue(
                key = "queued",
                sourceUrl = "https://q.example",
                priority = HomepageLoadScheduler.PRIORITY_PRELOAD,
                order = 1,
                onFinally = { pendingFinally.incrementAndGet() },
            ) {
                // 不应执行
            }

            scheduler.close()
            assertTrue(pendingJob.isCancelled)
            assertEquals(1, pendingFinally.get())
        } finally {
            scope.cancel()
        }
    }
}
