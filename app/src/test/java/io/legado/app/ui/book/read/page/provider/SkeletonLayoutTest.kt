package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文加载占位骨架布局的回归网
 */
class SkeletonLayoutTest {

    @Test
    fun `generates lines inside the available area`() {
        val lines = SkeletonLayout.lines(
            top = 0f,
            bottom = 100f,
            left = 10f,
            right = 210f,
            lineHeight = 10f,
            lineGap = 5f
        )
        assertEquals(7, lines.size)
        assertEquals(0f, lines.first().top, 0.001f)
        assertEquals(10f, lines.first().left, 0.001f)
    }

    @Test
    fun `width ratios follow a fixed sequence`() {
        val lines = SkeletonLayout.lines(
            top = 0f,
            bottom = 500f,
            left = 0f,
            right = 200f,
            lineHeight = 10f,
            lineGap = 0f
        )
        // 首行满宽，之后按固定比例收窄，最后一行最短
        assertEquals(200f, lines[0].right, 0.001f)
        assertEquals(200f * 0.94f, lines[1].right, 0.001f)
        assertEquals(200f * 0.99f, lines[2].right, 0.001f)
        assertEquals(200f * 0.86f, lines[3].right, 0.001f)
        assertEquals(200f * 0.63f, lines[4].right, 0.001f)
        // 序列循环：第 6 项回到第一项（满宽）
        assertEquals(200f, lines[5].right, 0.001f)
        assertEquals(200f * 0.94f, lines[6].right, 0.001f)
    }

    @Test
    fun `the same input always produces the same shape`() {
        val first = SkeletonLayout.lines(0f, 300f, 0f, 200f, 12f, 4f)
        val second = SkeletonLayout.lines(0f, 300f, 0f, 200f, 12f, 4f)
        assertEquals(first, second)
    }

    @Test
    fun `does not exceed the bottom edge`() {
        val lines = SkeletonLayout.lines(
            top = 0f,
            bottom = 25f,
            left = 0f,
            right = 200f,
            lineHeight = 10f,
            lineGap = 5f
        )
        // 0-10、15-25 两行，第三行 30 超出区域
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.top + it.height <= 25f })
    }

    @Test
    fun `respects the max lines limit`() {
        val lines = SkeletonLayout.lines(
            top = 0f,
            bottom = 1000f,
            left = 0f,
            right = 200f,
            lineHeight = 10f,
            lineGap = 0f,
            maxLines = 3
        )
        assertEquals(3, lines.size)
    }

    @Test
    fun `invalid arguments produce no lines`() {
        assertTrue(
            SkeletonLayout.lines(0f, 100f, 0f, 200f, 10f, 5f, maxLines = 0).isEmpty()
        )
        assertTrue(
            SkeletonLayout.lines(0f, 100f, 100f, 100f, 10f, 5f).isEmpty()
        )
        assertTrue(
            SkeletonLayout.lines(0f, 100f, 0f, 200f, 0f, 5f).isEmpty()
        )
        assertTrue(
            SkeletonLayout.lines(100f, 100f, 0f, 200f, 10f, 5f).isEmpty()
        )
    }
}
