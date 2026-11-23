package io.legado.app.ui.book.read.page.provider

import android.app.Application
import android.graphics.Paint
import android.os.Looper
import android.text.TextPaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ZhLayoutTest {

    @Test
    fun `punctuation sets include expanded kinshoku chars without duplicates`() {
        val expectedPost = listOf("…", "—", "·", "～", "〉", "』", "〕", "〗")
        val expectedPre = listOf("〈", "『", "〔", "〖")
        assertTrue(ZhLayout.postPanc.containsAll(expectedPost))
        assertTrue(ZhLayout.prePanc.containsAll(expectedPre))
        assertTrue(ZhLayout.postPanc.contains("}"))
        assertTrue(ZhLayout.prePanc.contains("‘"))
    }

    @Test
    fun `ZhLayout treats extended punctuation with kinshoku rules`() {
        val paint = TextPaint().apply { textSize = 40f }
        val words = listOf("甲", "乙", "丙", "『", "丁", "』", "—")
        val widths = List(words.size) { 40f }
        val text = words.joinToString("")

        // Width accommodates 4 chars (160f); "『" at index 3 is pre-punctuation before "丁" at line break.
        val layout = ZhLayout(text, paint, 160, words, widths, 0)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(layout.lineCount >= 2)
        // "『" must be pushed down with "丁" rather than stranded at end of line 0.
        assertEquals(3, layout.getLineStart(1))
    }

    @Test
    fun `concurrent getCnCharWidth and ZhLayout construction remain thread-safe`() {
        val threadCount = 8
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            val tasks = (0 until threadCount).map { threadIdx ->
                Callable {
                    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 32f + threadIdx
                    }
                    val words = listOf("天", "地", "玄", "黄", "。", "”")
                    val widths = List(words.size) { paint.measureText(words[0]) }
                    val text = words.joinToString("")
                    repeat(20) {
                        val cnWidth = ZhLayout.getCnCharWidth(paint)
                        assertTrue(cnWidth > 0f)
                        ZhLayout(text, paint, 200, words, widths, 0)
                    }
                    true
                }
            }
            val futures = pool.invokeAll(tasks)
            futures.forEach { assertTrue(it.get()) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `8 threads sharing a single TextPaint instance compute consistent getCnCharWidth and ZhLayout`() {
        val threadCount = 8
        val pool = Executors.newFixedThreadPool(threadCount)
        val barrier = CyclicBarrier(threadCount)
        val sharedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 44f
        }
        val words = listOf("天", "地", "玄", "黄", "宇", "宙", "洪", "荒", "。", "”")
        val widths = List(words.size) { 44f }
        val text = words.joinToString("")
        try {
            val tasks = (0 until threadCount).map {
                Callable {
                    barrier.await()
                    val firstWidth = ZhLayout.getCnCharWidth(sharedPaint)
                    assertTrue(firstWidth > 0f)
                    repeat(25) {
                        val width = ZhLayout.getCnCharWidth(sharedPaint)
                        assertEquals(firstWidth, width, 1e-5f)
                        val layout = ZhLayout(text, sharedPaint, 220, words, widths, 0)
                        assertTrue(layout.lineCount >= 1)
                    }
                    firstWidth
                }
            }
            val results = pool.invokeAll(tasks).map { it.get() }
            val expected = results.first()
            results.forEach { assertEquals(expected, it, 1e-5f) }
        } finally {
            pool.shutdownNow()
        }
    }
}
