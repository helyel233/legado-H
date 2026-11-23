package io.legado.app.ui.book.read.page.delegate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTurnPrewarmPolicyTest {

    @Test
    fun `allows prewarm only when all conditions are satisfied`() {
        assertTrue(
            PageTurnPrewarmPolicy.canPrewarm(
                optimizeRender = true,
                prewarmEnabled = true,
                isEInkMode = false,
                isScroll = false,
                supportsSafePrewarm = true,
                isAnimating = false
            )
        )
    }

    @Test
    fun `blocks prewarm when optimizeRender is disabled`() {
        assertFalse(
            PageTurnPrewarmPolicy.canPrewarm(
                optimizeRender = false,
                prewarmEnabled = true,
                isEInkMode = false,
                isScroll = false,
                supportsSafePrewarm = true,
                isAnimating = false
            )
        )
    }

    @Test
    fun `blocks prewarm when kill switch prewarmEnabled is false`() {
        assertFalse(
            PageTurnPrewarmPolicy.canPrewarm(
                optimizeRender = true,
                prewarmEnabled = false,
                isEInkMode = false,
                isScroll = false,
                supportsSafePrewarm = true,
                isAnimating = false
            )
        )
    }

    @Test
    fun `blocks prewarm in E-Ink mode`() {
        assertFalse(
            PageTurnPrewarmPolicy.canPrewarm(
                optimizeRender = true,
                prewarmEnabled = true,
                isEInkMode = true,
                isScroll = false,
                supportsSafePrewarm = true,
                isAnimating = false
            )
        )
    }

    @Test
    fun `blocks prewarm in scroll mode`() {
        assertFalse(
            PageTurnPrewarmPolicy.canPrewarm(
                optimizeRender = true,
                prewarmEnabled = true,
                isEInkMode = false,
                isScroll = true,
                supportsSafePrewarm = true,
                isAnimating = false
            )
        )
    }

    @Test
    fun `blocks prewarm when delegate does not support safe prewarm`() {
        assertFalse(
            PageTurnPrewarmPolicy.canPrewarm(
                optimizeRender = true,
                prewarmEnabled = true,
                isEInkMode = false,
                isScroll = false,
                supportsSafePrewarm = false,
                isAnimating = false
            )
        )
    }

    @Test
    fun `blocks prewarm while animating`() {
        assertFalse(
            PageTurnPrewarmPolicy.canPrewarm(
                optimizeRender = true,
                prewarmEnabled = true,
                isEInkMode = false,
                isScroll = false,
                supportsSafePrewarm = true,
                isAnimating = true
            )
        )
    }
}
