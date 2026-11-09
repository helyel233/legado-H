package io.legado.app.ui.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设置页「实时预览」管线判定（纯函数）的确定性单测。
 *
 * 预览必须与发现页行真实走的那条管线一致：如果判定写错，用户在设置页看到的是
 * 一条**并不存在**的管线（例如墨水屏设备上预览显示液态玻璃，回到发现页却是实底），
 * 于是「实时预览」变成误导。因此这里逐条锁住判定顺序：
 * 无玻璃 > 墨水屏/低内存关闭 > 液态 > 硬件高斯 > 软件模糊。
 */
class ExploreGlassPreviewPipelineTest {

    private fun pipeline(
        mode: String = "frosted",
        sdkInt: Int = 34,
        rowLiquidEnabled: Boolean = true,
        hardwareBlurPref: Boolean = true,
        isEInk: Boolean = false,
        isLowRam: Boolean = false,
        liquidFailed: Boolean = false
    ): ExploreGlassPreviewPipeline = exploreGlassPreviewPipeline(
        mode = mode,
        sdkInt = sdkInt,
        rowLiquidEnabled = rowLiquidEnabled,
        hardwareBlurPref = hardwareBlurPref,
        isEInk = isEInk,
        isLowRam = isLowRam,
        liquidFailed = liquidFailed
    )

    /** 无玻璃档优先于任何设备降级：它是不透明的实底，墨水屏上也照常显示。 */
    @Test
    fun solidAlwaysWinsEvenOnDegradedDevices() {
        listOf(21, 31, 33, 36).forEach { sdk ->
            assertEquals(
                ExploreGlassPreviewPipeline.SOLID,
                pipeline(mode = "solid", sdkInt = sdk, isEInk = true, isLowRam = true)
            )
            assertEquals(
                ExploreGlassPreviewPipeline.SOLID,
                pipeline(mode = "solid", sdkInt = sdk, hardwareBlurPref = false)
            )
        }
    }

    /** 墨水屏 / 低内存：玻璃背景返回 null（发现页用原透明背景），预览必须如实显示关闭。 */
    @Test
    fun eInkOrLowRamDisablesGlass() {
        assertEquals(ExploreGlassPreviewPipeline.DISABLED, pipeline(isEInk = true))
        assertEquals(ExploreGlassPreviewPipeline.DISABLED, pipeline(isLowRam = true))
        assertEquals(
            ExploreGlassPreviewPipeline.DISABLED,
            pipeline(sdkInt = 21, isEInk = true, hardwareBlurPref = false)
        )
    }

    /** API 33+ 且所有闸门打开：走行内液态（AGSL 真折射 + GPU 高斯）。 */
    @Test
    fun liquidWinsOnApi33PlusWhenAllGatesOpen() {
        listOf(33, 34, 36).forEach { sdk ->
            listOf("glass", "frosted").forEach { mode ->
                assertEquals(
                    ExploreGlassPreviewPipeline.LIQUID,
                    pipeline(mode = mode, sdkInt = sdk)
                )
            }
        }
        // 未到 API 33 没有 RuntimeShader，不能显示液态
        assertEquals(ExploreGlassPreviewPipeline.HARDWARE, pipeline(sdkInt = 32))
    }

    /**
     * 液态持久故障位一旦置位，发现页该行永久降级；预览也必须同步降级，
     * 否则用户看到的预览比真机更「高级」。
     */
    @Test
    fun liquidFailureFallsBackLikeProduction() {
        assertEquals(ExploreGlassPreviewPipeline.HARDWARE, pipeline(sdkInt = 34, liquidFailed = true))
        assertEquals(ExploreGlassPreviewPipeline.HARDWARE, pipeline(sdkInt = 33, liquidFailed = true))
        // API 30 没有硬件高斯，液态失败后直接落软件管线
        assertEquals(ExploreGlassPreviewPipeline.SOFTWARE, pipeline(sdkInt = 30, liquidFailed = true))
    }

    /** 「硬件模糊」开关同时是液态与硬件的门槛：关掉后两条 GPU 管线都不启用。 */
    @Test
    fun hardwareBlurPrefGatesBothGpuPipelines() {
        assertEquals(
            ExploreGlassPreviewPipeline.SOFTWARE,
            pipeline(sdkInt = 34, hardwareBlurPref = false, rowLiquidEnabled = false)
        )
        assertEquals(
            ExploreGlassPreviewPipeline.SOFTWARE,
            pipeline(sdkInt = 36, hardwareBlurPref = false)
        )
        assertEquals(
            ExploreGlassPreviewPipeline.SOFTWARE,
            pipeline(sdkInt = 31, hardwareBlurPref = false)
        )
    }

    /** API 21-30：没有 RenderEffect，只能走已验证的软件预模糊位图。 */
    @Test
    fun preApi31UsesSoftwareBlur() {
        listOf(21, 26, 30).forEach { sdk ->
            assertEquals(ExploreGlassPreviewPipeline.SOFTWARE, pipeline(sdkInt = sdk))
        }
    }

    /** 编译期总闸关闭时的对照：液态不出现，但仍保留底栏同源的硬件高斯（API 31+）。 */
    @Test
    fun liquidSwitchOffLeavesHardwarePipeline() {
        assertEquals(
            ExploreGlassPreviewPipeline.HARDWARE,
            pipeline(sdkInt = 34, rowLiquidEnabled = false)
        )
        assertEquals(
            ExploreGlassPreviewPipeline.SOFTWARE,
            pipeline(sdkInt = 30, rowLiquidEnabled = false)
        )
    }

    /**
     * 全组合：3 档 × 6 个 SDK × 5 个布尔闸（2^5 = 32）= 576 种输入。
     * 逐值断言 total 与「液态只在全绿时出现」，其中液态组合数可独立算出：
     * 2 个支持液态的 SDK（33/34）× 2 个非实底档 × 其余 4 闸固定为通过 = 4。
     * 实底档 6 × 32 = 192，墨水屏/低内存关闭 6 × 2 档 × 24 = 288。
     */
    @Test
    fun fullMatrixOnlyYieldsLiquidWhenEveryGateIsOpen() {
        val sdks = listOf(21, 30, 31, 32, 33, 34)
        val modes = listOf("solid", "glass", "frosted")
        val bools = listOf(false, true)
        var total = 0
        var liquid = 0
        var solid = 0
        var disabled = 0
        for (sdk in sdks) {
            for (mode in modes) {
                for (liquidEnabled in bools) {
                    for (hardwareBlur in bools) {
                        for (eInk in bools) {
                            for (lowRam in bools) {
                                for (failed in bools) {
                                    val route = pipeline(
                                        mode = mode,
                                        sdkInt = sdk,
                                        rowLiquidEnabled = liquidEnabled,
                                        hardwareBlurPref = hardwareBlur,
                                        isEInk = eInk,
                                        isLowRam = lowRam,
                                        liquidFailed = failed
                                    )
                                    total++
                                    when (route) {
                                        ExploreGlassPreviewPipeline.LIQUID -> {
                                            liquid++
                                            assertEquals("solid" != mode, true)
                                            assertEquals(true, liquidEnabled)
                                            assertEquals(true, hardwareBlur)
                                            assertEquals(false, eInk)
                                            assertEquals(false, lowRam)
                                            assertEquals(false, failed)
                                            assertEquals(true, sdk >= 33)
                                        }
                                        ExploreGlassPreviewPipeline.SOLID -> {
                                            solid++
                                            assertEquals("solid", mode)
                                        }
                                        ExploreGlassPreviewPipeline.DISABLED -> {
                                            disabled++
                                            assertEquals(false, mode == "solid")
                                            assertEquals(true, eInk || lowRam)
                                        }
                                        ExploreGlassPreviewPipeline.HARDWARE,
                                        ExploreGlassPreviewPipeline.SOFTWARE -> {
                                            assertEquals(false, mode == "solid")
                                            assertEquals(false, eInk || lowRam)
                                            // 非液态的静态路径：要么 SDK 不够，要么某个闸门关闭
                                            assertEquals(
                                                false,
                                                sdk >= 33 && liquidEnabled && hardwareBlur && !failed
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(576, total)
        assertEquals(4, liquid)
        assertEquals(192, solid)
        assertEquals(288, disabled)
    }
}
