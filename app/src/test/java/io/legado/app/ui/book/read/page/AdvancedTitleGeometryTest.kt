package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Test

class AdvancedTitleGeometryTest {

    @Test
    fun viewSizeNeverCollapses() {
        assertEquals(Pair(300, 120), AdvancedTitleGeometry.viewSize(300f, 120f))
        assertEquals(Pair(1, 1), AdvancedTitleGeometry.viewSize(0f, 0f))
        assertEquals(Pair(1, 1), AdvancedTitleGeometry.viewSize(-10f, -20f))
        // 小于 1 像素的尺寸会被取整成 0，必须兜底成 1，否则 Lottie 视图没有尺寸。
        assertEquals(Pair(1, 40), AdvancedTitleGeometry.viewSize(0.4f, 40f))
    }

    @Test
    fun translationXUsesTheRawOffsetWithoutAContentWidth() {
        assertEquals(
            120f,
            AdvancedTitleGeometry.translationX(
                blockOffsetX = 100f,
                targetWidth = 300,
                contentWidth = 0,
                pageOffsetX = 20f
            ),
            0.001f
        )
    }

    @Test
    fun translationXTakesTheCentringOutAgain() {
        // 内容宽 1000、标题宽 400：居中偏移 300，块自身偏移 100 → 100 - 300 = -200。
        assertEquals(
            -200f,
            AdvancedTitleGeometry.translationX(
                blockOffsetX = 100f,
                targetWidth = 400,
                contentWidth = 1000,
                pageOffsetX = 0f
            ),
            0.001f
        )
    }

    @Test
    fun translationXAddsThePageOffsetOfTheSecondPage() {
        // 双页时右半页要再往右挪半页宽度。
        assertEquals(
            300f,
            AdvancedTitleGeometry.translationX(
                blockOffsetX = 100f,
                targetWidth = 400,
                contentWidth = 1000,
                pageOffsetX = 500f
            ),
            0.001f
        )
    }

    @Test
    fun translationYClampsIntoTheContent() {
        // 内容高 500、标题高 100：标题最多下移到 400。
        assertEquals(
            400f,
            AdvancedTitleGeometry.translationY(
                blockOffsetY = 900f,
                targetHeight = 100,
                contentHeight = 500
            ),
            0.001f
        )
        assertEquals(
            0f,
            AdvancedTitleGeometry.translationY(
                blockOffsetY = -50f,
                targetHeight = 100,
                contentHeight = 500
            ),
            0.001f
        )
    }

    @Test
    fun translationYUsesTheRawOffsetWithoutAContentHeight() {
        assertEquals(
            80f,
            AdvancedTitleGeometry.translationY(
                blockOffsetY = 80f,
                targetHeight = 100,
                contentHeight = 0
            ),
            0.001f
        )
    }

    @Test
    fun translationYDoesNotGoNegativeWhenTheTitleIsTallerThanTheContent() {
        // 标题比内容还高时没有可移动的空间，只能停在 0。
        assertEquals(
            0f,
            AdvancedTitleGeometry.translationY(
                blockOffsetY = 50f,
                targetHeight = 800,
                contentHeight = 500
            ),
            0.001f
        )
    }

    @Test
    fun scrollTranslationYFollowsTheContent() {
        assertEquals(
            1250f,
            AdvancedTitleGeometry.scrollTranslationY(blockOffsetY = 250f, scrollBaseY = 1000f),
            0.001f
        )
    }
}
