package io.legado.app.ui.main.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行内液态玻璃「本次绘制是否可以走液态层」的判定测试。
 *
 * 与 [RowGlassLiquidSpecTest]（锁设备/偏好级闸门与 uniform 口径）互补：
 * 这里锁的是**绘制期那三条会随帧变化的事实**（硬件加速 Canvas / 底图可用 / 持久故障位），
 * 以及「设备闸门与绘制闸门组合起来仍然只会两态收敛」这条全局性质。
 *
 * 为什么值得单独锁：这三条一旦判错，后果是**画面级**而不是崩溃级——
 * - 软件 Canvas 上强行走 RenderNode 会画出黑块（部分 ROM 的离屏 layer）；
 * - 底图缺失时走液态会得到一块「没有任何内容的折射」，即整行透明；
 * - 故障位没生效会逐帧重试、逐帧抛异常，表现为滚动卡成幻灯片。
 * 三者都不会让编译或冒烟测试失败。
 *
 * 1.25.12 起强度不再是液态闸门（「无玻璃」档才是关闭开关），判定里没有 level 参数。
 */
class RowGlassLiquidDrawDecisionTest {

    // -------------------------------------------------------- 绘制期三条件

    /** 真值表：三条必须同时成立才算「当帧可画」。 */
    @Test
    fun drawableNowTruthTable() {
        assertTrue(rowLiquidDrawableNow(true, true, false))
        // 任一为假 → 不可画
        assertFalse(rowLiquidDrawableNow(false, true, false))
        assertFalse(rowLiquidDrawableNow(true, false, false))
        assertFalse(rowLiquidDrawableNow(true, true, true))
        // 全假
        assertFalse(rowLiquidDrawableNow(false, false, true))
    }

    /** 软件 Canvas 只是当帧状态：它不得反过来影响「设备是否支持」这条判定。 */
    @Test
    fun softwareCanvasIsPerFrameOnly() {
        val deviceOk = rowLiquidCapability(
            sdkInt = 33, rowLiquidEnabled = true, hardwareBlurPref = true,
            isEInk = false, isLowRam = false,
            canvasHardwareAccelerated = true, backdropAvailable = true, liquidFailed = false
        )
        assertEquals(RowLiquidCapability.LIQUID, deviceOk)
        // 同一台设备，这一帧是软件 Canvas → 降级
        val softFrame = rowLiquidCapability(
            sdkInt = 33, rowLiquidEnabled = true, hardwareBlurPref = true,
            isEInk = false, isLowRam = false,
            canvasHardwareAccelerated = false, backdropAvailable = true, liquidFailed = false
        )
        assertEquals(RowLiquidCapability.STATIC, softFrame)
        // 下一帧回到硬件 Canvas → 自动恢复（没有任何持久位被写）
        assertEquals(
            RowLiquidCapability.LIQUID,
            rowLiquidCapability(
                sdkInt = 33, rowLiquidEnabled = true, hardwareBlurPref = true,
                isEInk = false, isLowRam = false,
                canvasHardwareAccelerated = true, backdropAvailable = true, liquidFailed = false
            )
        )
    }

    /** 底图缺失（动图壁纸纯色底 / 构建失败 / 已回收）当帧降级，同样不置持久位。 */
    @Test
    fun missingBackdropIsPerFrameOnly() {
        assertEquals(
            RowLiquidCapability.STATIC,
            rowLiquidCapability(
                sdkInt = 34, rowLiquidEnabled = true, hardwareBlurPref = true,
                isEInk = false, isLowRam = false,
                canvasHardwareAccelerated = true, backdropAvailable = false, liquidFailed = false
            )
        )
    }

    /** 持久故障位一旦置位，无论其它条件多好都必须保持降级（不再逐帧重试）。 */
    @Test
    fun failedBitOverridesEverythingElse() {
        val aggressive = listOf(33, 34, 36).map { sdk ->
            rowLiquidCapability(
                sdkInt = sdk, rowLiquidEnabled = true, hardwareBlurPref = true,
                isEInk = false, isLowRam = false,
                canvasHardwareAccelerated = true, backdropAvailable = true,
                liquidFailed = true
            )
        }
        aggressive.forEach { assertEquals(RowLiquidCapability.STATIC, it) }
    }

    // -------------------------------------------------------- 组合闸门

    /**
     * 全组合性质：设备闸门 × 绘制闸门的笛卡尔积里，
     * **只有「全绿」那一格**能得到 LIQUID，其余全部 STATIC。
     * 这是「崩溃面为零 + 不留空白帧」的形式化表述。
     */
    @Test
    fun onlyAllGreenCombinationYieldsLiquid() {
        val sdks = listOf(21, 30, 31, 32, 33, 34)
        val bools = listOf(false, true)
        var liquidCount = 0
        var staticCount = 0
        for (sdk in sdks) {
            for (switch in bools) {
                for (pref in bools) {
                    for (eink in bools) {
                        for (lowRam in bools) {
                            for (canvas in bools) {
                                for (backdrop in bools) {
                                    for (failed in bools) {
                                        val route = rowLiquidCapability(
                                            sdk, switch, pref, eink, lowRam,
                                            canvas, backdrop, failed
                                        )
                                        val allGreen = sdk >= ROW_LIQUID_MIN_SDK &&
                                            switch && pref && !eink && !lowRam &&
                                            canvas && backdrop && !failed
                                        if (allGreen) {
                                            assertEquals(
                                                "全绿必须走液态", RowLiquidCapability.LIQUID, route
                                            )
                                            liquidCount++
                                        } else {
                                            assertEquals(
                                                "存在否决闸门时必须降级：" +
                                                    "sdk=$sdk switch=$switch pref=$pref " +
                                                    "eink=$eink lowRam=$lowRam " +
                                                    "canvas=$canvas backdrop=$backdrop " +
                                                    "failed=$failed",
                                                RowLiquidCapability.STATIC, route
                                            )
                                            staticCount++
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 组合数自证：6 个 SDK × 2^7 个布尔闸 = 768 种输入，
        // 其中只有 2 种「全绿」（sdk 33 与 34，其余闸门全部通过）。
        assertEquals(2, liquidCount)
        assertEquals(768 - 2, staticCount)
    }

    // -------------------------------------------------------- 编译期总闸

    /** 总闸默认必须为 true；误改成 false 会让线上设备悄悄少一层观感，编译不会报错。 */
    @Test
    fun liquidCompileTimeSwitchDefaultsToTrue() {
        assertTrue(ROW_LIQUID_ENABLED)
    }

    /**
     * 进程级故障位默认未失败（静态初始化若把它置 true，会把所有设备的液态一次性关掉）。
     *
     * 该位是**进程级单例**而不是每渲染器一份 —— 这是「跨 rebind 不重编译、只打一次日志」的前提。
     * 跨实例的粘性由 getter 结构保证：`RowGlassLiquidRenderer.liquidFailed` 是
     * `RowLiquidRuntimeState.shaderFailed` 的计算属性，`markFailed()` 直接写同一个布尔值，
     * 因此之后新建的渲染器读到的必然是同一个已置位的布尔值。
     *
     * 本用例只锁**默认值**（`false`），不主动置位（置位会污染同 JVM 的其它用例）。
     */
    @Test
    fun processWideFailureFlagDefaultsOffAndIsSharedByAllRendererInstances() {
        assertFalse(RowLiquidRuntimeState.shaderFailed)
    }
}
