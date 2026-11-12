package io.legado.app.utils

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `stackBlurSoftware` 的输出必须保持不透明。
 *
 * 这里锁的是一行**位运算**：原实现写的是
 * `pix[yi] = (0xff000000.toInt() shl 24) or ...`。`0xff000000.toInt()` 已经是
 * -16777216（0xFF000000），再左移 24 位会把仅存的低 8 位（此时全是 0）留在原处，
 * 结果恒为 0——整张模糊位图 alpha=0。
 *
 * 后果不是「糊得不够」，而是「完全没画」：`SurfaceDrawable` 把位图 draw 到 Canvas 上
 * 什么都不出现，只剩调用方的半透明着色层。真机表现为
 * ① 阅读页齿轮面板半透明、背后正文完全清晰；
 * ② 右上角菜单的玻璃层整块透明、面板 `color = Transparent` 时整个面板消失。
 *
 * 因此断言必须落在 alpha 上，而不是「有没有变糊」——后者在 alpha=0 时同样成立，
 * 正是这个缺陷能躲过之前所有检查的原因。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class StackBlurAlphaTest {

    private fun opaqueBitmap(size: Int, color: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun blurredBitmapStaysOpaque() {
        val src = opaqueBitmap(8, Color.RED)
        val out = src.stackBlurSoftware(4)

        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)

        assertEquals(255, Color.alpha(pixels[0]))
        assertEquals(255, Color.alpha(pixels[pixels.size / 2]))
        assertEquals(255, Color.alpha(pixels.last()))
    }

    @Test
    fun blurPreservesColorFamily() {
        // 纯色输入模糊后仍是同一色系（半径超过边长时边缘取样会拉暗，只校验通道关系，
        // 不锁具体值——这里要防的是「输出成了全黑或全透明」这种整体性错误）。
        val src = opaqueBitmap(8, Color.RED)
        val out = src.stackBlurSoftware(2)

        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)

        val center = pixels[pixels.size / 2]
        assertEquals(255, Color.alpha(center))
        assertEquals(255, Color.red(center))
        assertEquals(0, Color.green(center))
        assertEquals(0, Color.blue(center))
    }

    @Test
    fun transparentInputIsNotSilentlyOpaqueWashed() {
        // 反向约束：输入 alpha=0 的像素，RGB 也是 0，模糊后仍应是「全透明黑」。
        // 由于实现统一写 alpha=0xFF，这条只用于确认它不会把 RGB 也一起抹成别的值
        // （例如通道移位错误会产出非 0 的绿/蓝）。
        val src = opaqueBitmap(4, Color.TRANSPARENT)
        val out = src.stackBlurSoftware(2)

        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)

        assertEquals(0, Color.red(pixels[0]))
        assertEquals(0, Color.green(pixels[0]))
        assertEquals(0, Color.blue(pixels[0]))
    }
}
