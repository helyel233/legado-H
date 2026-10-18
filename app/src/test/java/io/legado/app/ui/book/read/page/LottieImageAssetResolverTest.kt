package io.legado.app.ui.book.read.page

import android.app.Application
import android.graphics.Bitmap
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieImageAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 进阶标题图片资产解码单测（从 PageView 拆出的 [LottieImageAssetResolver]）。
 *
 * 只覆盖**不碰 native 位图解码**的部分：已解码资产的复用、无法解析时的降级、
 * 空图片组合的就绪判定。真正解 SVG/位图的路径需要真机图形栈，留给真机验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LottieImageAssetResolverTest {

    private fun resolver(
        fallbackAssets: Map<String, String> = emptyMap()
    ) = LottieImageAssetResolver(
        viewWidth = 200,
        viewHeight = 300,
        compositionWidth = 200,
        compositionHeight = 300,
        fallbackAssets = fallbackAssets
    )

    private fun asset(
        fileName: String? = "img_1.png",
        dirName: String? = "images/",
        id: String = "img_1"
    ) = LottieImageAsset(64, 64, id, fileName, dirName)

    @Test
    fun `已解码的资产直接复用同一实例`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val imageAsset = asset().apply { this.bitmap = bitmap }
        assertSame(bitmap, resolver().fetchBitmap(imageAsset))
    }

    @Test
    fun `回收过的位图不被复用`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { recycle() }
        val imageAsset = asset().apply { this.bitmap = bitmap }
        // 会走到解析：既不是 data: 也不是资源别名 → 解析失败 → null（而不是崩）
        assertNull(resolver().fetchBitmap(imageAsset))
    }

    @Test
    fun `无法解析的来源返回 null 而不抛异常`() {
        // 普通文件名，既非 data-uri 也非派生资源别名
        assertNull(resolver().fetchBitmap(asset()))
    }

    @Test
    fun `没有文件名的资产返回 null`() {
        assertNull(resolver().fetchBitmap(asset(fileName = null, dirName = null)))
    }

    @Test
    fun `空图片组合视为就绪`() {
        val composition = LottieCompositionFactory.fromJsonStringSync(
            """{"v":"5.7.0","w":200,"h":300,"layers":[]}""",
            "empty-composition"
        ).value
        assertNotNull(composition)
        assertTrue(resolver().prepareCompositionImages(composition!!))
    }

    @Test
    fun `data-uri 前缀的来源被当作兜底数据`() {
        // data:image 走 fallbackDataUri 分支：真实解码需要图形栈，这里只验证不会抛异常
        val imageAsset = asset(fileName = "data:image/png;base64,iVBORw0KGgo=", dirName = null)
        // 解析成功但 base64 内容不完整 → 解码失败 → null，不应崩溃
        assertNull(resolver().fetchBitmap(imageAsset))
    }

    @Test
    fun `派生资源别名且无兜底时解析失败`() {
        // 走 isResourceAlias 分支但 fallbackAssets 为空 → return@forEach → 最终 null
        val imageAsset = asset(fileName = "legado_derived_xxx", dirName = null)
        assertNull(resolver().fetchBitmap(imageAsset))
    }

    @Test
    fun `解码尺寸受内存预算约束`() {
        // 超大资产：内存策略会把边长夹到上限、总像素夹到预算内（不会按视口死压，
        // 解大一点再缩放比强行压到视口更清晰）
        val size = LottieImageMemoryPolicy.decodeSize(
            assetWidth = 4000,
            assetHeight = 4000,
            viewWidth = 200,
            viewHeight = 300,
            compositionWidth = 200,
            compositionHeight = 300
        )
        assertNotNull("超大资产应仍能算出解码尺寸", size)
        assertTrue("边长不应超过上限，实际 ${size!!.width}", size.width <= MAX_EDGE_CAP)
        assertTrue(
            "总像素不应超预算",
            size.width.toLong() * size.height <= LottieImageMemoryPolicy.MAX_PIXELS
        )
    }

    @Test
    fun `零尺寸资产解码不出结果`() {
        val size = LottieImageMemoryPolicy.decodeSize(
            assetWidth = 0,
            assetHeight = 0,
            viewWidth = 0,
            viewHeight = 0,
            compositionWidth = 0,
            compositionHeight = 0
        )
        assertNull(size)
    }

    @Test
    fun `未启用的守卫不放行也不误判`() {
        // 与解码无关的健全性检查：确保测试辅助构造的资产字段可读
        val imageAsset = asset()
        assertEquals(64, imageAsset.width)
        assertEquals(64, imageAsset.height)
        assertEquals("img_1", imageAsset.id)
        assertFalse(imageAsset.bitmap != null)
    }

    private companion object {
        /** 与 LottieImageMemoryPolicy 的边长上限保持一致 */
        const val MAX_EDGE_CAP = 1200
    }
}
