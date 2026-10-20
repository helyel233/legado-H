package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AdvancedTitleScrollGeometryTest {

    @Test
    fun idCombinesChapterPageAndTitle() {
        assertEquals("3:7:序", AdvancedTitleScrollGeometry.id(3, 7, "序"))
        assertEquals(
            "3:7:序",
            AdvancedTitleScrollGeometry.id(chapterIndex = 3, pageIndex = 7, title = "序")
        )
    }

    @Test
    fun placementUsesTheRelativeOffsetAndTakesTheCentringOut() {
        // 页面偏移 1000 + 页内偏移 50 → y = 1050；
        // 内容宽 800、标题宽 300，居中偏移 250，块内偏移 40 → x = 40 - 250 = -210。
        val placement = AdvancedTitleScrollGeometry.place(
            blockOffsetX = 40f,
            blockOffsetY = 50f,
            blockWidth = 300f,
            blockHeight = 120f,
            relativeOffsetY = 1000f,
            contentWidth = 800f,
            contentHeight = 2000f
        )

        assertNotNull(placement)
        assertEquals(-210f, placement!!.x, 0.001f)
        assertEquals(1050f, placement.y, 0.001f)
        assertEquals(300, placement.width)
        assertEquals(120, placement.height)
    }

    @Test
    fun sizeNeverCollapses() {
        val placement = AdvancedTitleScrollGeometry.place(
            blockOffsetX = 0f,
            blockOffsetY = 0f,
            blockWidth = 0f,
            blockHeight = 0f,
            relativeOffsetY = 0f,
            contentWidth = 800f,
            contentHeight = 2000f
        )

        assertNotNull(placement)
        assertEquals(1, placement!!.width)
        assertEquals(1, placement.height)
    }

    @Test
    fun aTitleAboveTheViewportIsNotShown() {
        // 标题整块在视口上方：y + height <= 0。
        assertNull(
            AdvancedTitleScrollGeometry.place(
                blockOffsetX = 0f,
                blockOffsetY = 0f,
                blockWidth = 300f,
                blockHeight = 120f,
                relativeOffsetY = -200f,
                contentWidth = 800f,
                contentHeight = 2000f
            )
        )
    }

    @Test
    fun aTitleBelowTheViewportIsNotShown() {
        // 标题整块在视口下方：y >= contentHeight。
        assertNull(
            AdvancedTitleScrollGeometry.place(
                blockOffsetX = 0f,
                blockOffsetY = 0f,
                blockWidth = 300f,
                blockHeight = 120f,
                relativeOffsetY = 2000f,
                contentWidth = 800f,
                contentHeight = 2000f
            )
        )
    }

    @Test
    fun aTitlePartlyInsideTheViewportIsShown() {
        // 只露出一像素也要显示：y + height > 0 且 y < contentHeight。
        val placement = AdvancedTitleScrollGeometry.place(
            blockOffsetX = 0f,
            blockOffsetY = 0f,
            blockWidth = 300f,
            blockHeight = 120f,
            relativeOffsetY = -119f,
            contentWidth = 800f,
            contentHeight = 2000f
        )
        assertNotNull(placement)

        val bottom = AdvancedTitleScrollGeometry.place(
            blockOffsetX = 0f,
            blockOffsetY = 0f,
            blockWidth = 300f,
            blockHeight = 120f,
            relativeOffsetY = 1999f,
            contentWidth = 800f,
            contentHeight = 2000f
        )
        assertNotNull(bottom)
    }
}
