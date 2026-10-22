package io.legado.app.model

import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ChapterLoadRegistryTest {

    private val registry = ChapterLoadRegistry()

    /** A disposable job that does not need Android's Dispatchers.Main. */
    private fun makeJob(): Coroutine<Unit> =
        Coroutine(CoroutineScope(SupervisorJob()), executeContext = Dispatchers.Unconfined) {}

    @Test
    fun beginMarksTheChapterLoadingAndReturnsAGeneration() {
        val generation = registry.begin(3, "layout-a")
        assertNotNull(generation)
        assertTrue(registry.isLoading(3))
        assertEquals("layout-a", registry.layoutKeyOf(3))
        assertEquals(generation, registry.generationOf(3))
    }

    @Test
    fun beginIsIdempotentWhileLoading() {
        val first = registry.begin(7, "k")!!
        assertNull("重复 begin 必须返回 null", registry.begin(7, "k"))
        // 仍未结束，仍是同一世代
        assertEquals(first, registry.generationOf(7))
        registry.finish(7, first)
    }

    @Test
    fun finishOnlyClosesItsOwnGeneration() {
        val generation = registry.begin(4, "k")!!
        registry.finish(4, generation + 1) // 错误世代：必须无操作
        assertTrue("错误世代的 finish 不能清掉加载", registry.isLoading(4))
        registry.finish(4, generation) // 正确世代
        assertFalse(registry.isLoading(4))
        assertNull(registry.generationOf(4))
    }

    @Test
    fun updateLayoutKeyIsGatedByGeneration() {
        val generation = registry.begin(9, "old")!!
        assertNull(registry.updateLayoutKey(9, generation + 5, "new"))
        assertEquals("old", registry.layoutKeyOf(9))
        assertEquals(generation, registry.updateLayoutKey(9, generation, "new"))
        assertEquals("new", registry.layoutKeyOf(9))
        registry.finish(9, generation)
    }

    @Test
    fun cancelDropsTheJobAndTheLoad() {
        val generation = registry.begin(11, "k")!!
        registry.setJob(11, makeJob())
        assertTrue("带 job 的 cancel 应返回 true", registry.cancel(11))
        assertFalse(registry.isLoading(11))
        assertNull(registry.generationOf(11))
    }

    @Test
    fun cancelWithoutJobReportsFalse() {
        registry.begin(12, "k")!!
        assertFalse("没有 job 时 cancel 应返回 false", registry.cancel(12))
        // 仍清掉了加载记录
        assertFalse(registry.isLoading(12))
    }

    @Test
    fun cancelRespectsTheExpectedGeneration() {
        val generation = registry.begin(13, "k")!!
        assertFalse(
            "世代已变则 cancel 不动",
            registry.cancel(13, expectedGeneration = generation + 1)
        )
        assertTrue(registry.isLoading(13))
        registry.cancel(13, expectedGeneration = generation)
        assertFalse("正确世代的 cancel 必须清掉加载", registry.isLoading(13))
    }

    @Test
    fun chapterLayoutKeysAreIndependentFromLoads() {
        registry.setChapterLayoutKey(20, "laid-out")
        assertEquals("laid-out", registry.chapterLayoutKey(20))
        registry.clearChapterLayoutKeys()
        assertNull(registry.chapterLayoutKey(20))
    }

    // --- the cross-lock race the review deferred (P2) ----------------------------

    /**
     * resetData/upData 调 clearLoads 与在途加载结果的 finish 原本都在 ReadBook 一把锁内；
     * 拆分后它们在两个锁上。安全不靠锁原子性，而靠世代号：clearLoads 抹掉世代后，
     * 带着旧世代飞回来的 finish 必须被丢弃，绝不能把加载记录"复活"。
     */
    @Test
    fun staleFinishAfterClearLoadsIsDropped() {
        val generation = registry.begin(5, "k")!!
        assertTrue(registry.isLoading(5))

        registry.clearLoads() // 模拟 resetData 离开书籍 / 清加载
        assertFalse(registry.isLoading(5))
        assertNull(registry.generationOf(5))

        registry.finish(5, generation) // 旧世代结果回来
        assertFalse("旧世代的 finish 不能复活加载记录", registry.isLoading(5))
        assertNull(registry.generationOf(5))
    }

    @Test
    fun staleCancelAfterClearLoadsIsANoOp() {
        val generation = registry.begin(6, "k")!!
        registry.clearLoads()
        assertFalse(registry.cancel(6, expectedGeneration = generation))
        assertNull(registry.generationOf(6))
    }

    /**
     * 多个线程交替 begin/finish/cancel，外加一个线程不停地 clearLoads，模拟"飞行中加载"
     * 与"离开书籍清加载"在真实调度下交错。注册表全方法 @Synchronized，且跨锁安全靠世代
     * 守卫，因此无论交错顺序如何，不变量都必须保持：isLoading(idx) <=> generationOf(idx) != null。
     */
    @Test
    fun concurrentBeginFinishAndClearLoadsStayConsistent() {
        val threadCount = 8
        val exceptions = Collections.synchronizedList(mutableListOf<Throwable>())
        val start = AtomicBoolean(true)
        val latch = CountDownLatch(1)

        val workers = List(threadCount) { id ->
            thread(start = false, name = "worker-$id") {
                try {
                    latch.await()
                    var counter = id * 1000L
                    while (start.get()) {
                        val index = (counter and 0xFF).toInt().also { counter++ }
                        val generation = registry.begin(index, "k$index")
                        if (generation != null) {
                            if ((counter and 1L) == 0L) registry.finish(index, generation)
                            else registry.cancel(index)
                        }
                    }
                } catch (e: Throwable) {
                    exceptions.add(e)
                }
            }
        }
        val clearer = thread(start = false, name = "clearer") {
            try {
                latch.await()
                while (start.get()) registry.clearLoads()
            } catch (e: Throwable) {
                exceptions.add(e)
            }
        }

        workers.forEach { it.start() }
        clearer.start()
        latch.countDown()
        Thread.sleep(400)
        start.set(false)
        workers.forEach { it.join() }
        clearer.join()

        assertTrue("并发过程中不得抛异常", exceptions.isEmpty())

        // 静默后断言不变量：loading 与 generation 必须一一对应
        for (index in 0..255) {
            assertEquals(
                "idx=$index 的 isLoading 与 generation 不一致",
                registry.isLoading(index),
                registry.generationOf(index) != null
            )
        }
    }

    @Test
    fun generationSeedKeepsGrowingAcrossClearLoads() {
        val first = registry.begin(1, "k")!!
        registry.clearLoads()
        val second = registry.begin(1, "k")!!
        assertTrue("清加载后新 begin 必须拿到更大的世代", second > first)
        registry.finish(1, second)
    }
}
