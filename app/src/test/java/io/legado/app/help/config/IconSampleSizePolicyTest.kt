package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 位图降采样倍率计算的回归网
 */
class IconSampleSizePolicyTest {

    @Test
    fun `small image is not downsampled`() {
        assertEquals(1, IconSampleSizePolicy.inSampleSize(48, 48, 256, 256))
    }

    @Test
    fun `large image is downsampled by power of two`() {
        // 2048 -> 1024 -> 512 -> 256，倍率 8
        assertEquals(8, IconSampleSizePolicy.inSampleSize(2048, 2048, 256, 256))
    }

    @Test
    fun `sample size keeps the requested edge length`() {
        val sampleSize = IconSampleSizePolicy.inSampleSize(1000, 1000, 256, 256)
        assertEquals(2, sampleSize)
        // 解码后仍不小于要求尺寸
        assertEquals(true, 1000 / sampleSize >= 256)
    }

    @Test
    fun `a narrow image is not downsampled even when it is tall`() {
        // 宽 100（半宽 50 已经小于 256），条件要求两边都满足，因此不降采样
        assertEquals(1, IconSampleSizePolicy.inSampleSize(100, 4000, 256, 256))
    }

    @Test
    fun `invalid inputs fall back to no downsampling`() {
        assertEquals(1, IconSampleSizePolicy.inSampleSize(0, 100, 256, 256))
        assertEquals(1, IconSampleSizePolicy.inSampleSize(100, 0, 256, 256))
        assertEquals(1, IconSampleSizePolicy.inSampleSize(100, 100, 0, 256))
        assertEquals(1, IconSampleSizePolicy.inSampleSize(100, 100, 256, 0))
    }
}
