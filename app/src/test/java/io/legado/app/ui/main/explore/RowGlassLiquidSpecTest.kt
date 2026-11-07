package io.legado.app.ui.main.explore

import io.legado.app.ui.main.GlassMaterialPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行内液态玻璃纯函数（[RowGlassLiquidSpec]）的确定性单测。
 *
 * 这些口径错了都**不会编译失败**，只会让真机观感悄悄跑偏或者让降级矩阵漏掉一条：
 * - 带宽没按行高钳制 → 整行落进 7-taps 折射带，行被拉花、滚动掉帧；
 * - 位移没按带宽钳制 → 取样跨过整行，退化成「平均色涂抹」；
 * - uniform 与底栏不逐值对齐 → 同一份 AGSL 在底栏与行内表现不同；
 * - 采样矩阵少乘一个 scale → 行背景与壁纸错位；
 * - 降级矩阵漏一条 → 某类设备上出现黑块 / 空白行 / 崩溃。
 *
 * 因此这里全部用具体数值与逐条分支断言，不使用「非空即通过」这类恒真写法。
 */
class RowGlassLiquidSpecTest {

    private val glass = GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.3f)
    private val frosted = GlassMaterialPreset.params(GlassMaterialPreset.FROSTED, 0.35f)

    // ---------------------------------------------------------------- 带宽标定

    /**
     * 1080×2400 / density 2.75 的真机口径：行高约 36dp = 99px，
     * 预设 25.6dp = 70.4px 大于 0.42×99 = 41.58px → 必须由行高钳制。
     */
    @Test
    fun refractionBandIsClampedByRowHeight() {
        val density = 2.75f
        val rowHeightPx = 36f * density // 99px
        val band = rowRefractionBandPx(glass.refractionHeightDp, rowHeightPx, density)
        assertEquals(0.42f * rowHeightPx, band, 0.001f)
        assertEquals(41.58f, band, 0.01f)
        // 预设本身（25.6dp × 2.75 = 70.4px）远大于钳制结果，证明限幅确实生效
        assertTrue(glass.refractionHeightDp * density > band)
    }

    /** 0.42 这个比例必须与 StableLiquidGlassView.effectiveRefractionHeight 的 pixelSafePill 口径一致。 */
    @Test
    fun bandRatioMatchesPixelSafePillConstant() {
        assertEquals(0.42f, ROW_REFRACTION_BAND_RATIO, 0.000001f)
    }

    /** 行足够高时不应被钳制，预设值原样生效（否则高行永远拿不到强折射）。 */
    @Test
    fun bandIsNotClampedWhenRowIsTallEnough() {
        val density = 2.75f
        val tallRowPx = 300f
        val band = rowRefractionBandPx(glass.refractionHeightDp, tallRowPx, density)
        assertEquals(glass.refractionHeightDp * density, band, 0.0001f)
        assertEquals(70.4f, band, 0.001f)
    }

    /** 多密度逐值锁：同 dp 行高在不同密度下都必须按像素 42% 钳制。 */
    @Test
    fun bandClampsAcrossDensities() {
        val rowHeightDp = 36f
        listOf(1f, 1.5f, 2f, 2.625f, 3f, 3.5f, 4f).forEach { density ->
            val rowHeightPx = rowHeightDp * density
            val band = rowRefractionBandPx(
                glass.refractionHeightDp, rowHeightPx, density
            )
            assertEquals(0.42f * rowHeightPx, band, 0.0001f)
            // 恒为正、且严格小于行高（不允许整行都成为折射带）
            assertTrue(band > 0f)
            assertTrue(band < rowHeightPx)
        }
    }

    /** 退化输入（行高 0 / 密度 0 / 预设 0）不得产出 0 或负数，否则 shader 内会除零。 */
    @Test
    fun bandNeverDegeneratesToZeroOrNegative() {
        assertEquals(1f, rowRefractionBandPx(0f, 0f, 0f), 0.000001f)
        assertEquals(1f, rowRefractionBandPx(0f, 99f, 3f), 0.000001f)
        assertTrue(rowRefractionBandPx(-5f, 99f, 3f) >= 1f)
        assertTrue(rowRefractionBandPx(20f, -99f, 3f) >= 1f)
    }

    /** 两档差异只来自预设自身：透明玻璃档的折射带宽必须大于磨砂档。 */
    @Test
    fun bandFollowsPresetDifferenceBetweenGlassAndFrosted() {
        val density = 2.75f
        val tallRowPx = 400f // 高到不被钳制，只比预设
        val glassBand = rowRefractionBandPx(glass.refractionHeightDp, tallRowPx, density)
        val frostedBand = rowRefractionBandPx(frosted.refractionHeightDp, tallRowPx, density)
        assertTrue(glassBand > frostedBand)
        assertEquals(25.6f * density, glassBand, 0.001f)
        assertEquals(16.8f * density, frostedBand, 0.001f)
    }

    // ---------------------------------------------------------------- 位移标定

    /** 位移量不得超过带宽的 1.6 倍（1080p 行口径下预设 135.3px 远大于 66.5px 上限）。 */
    @Test
    fun amountIsClampedByBand() {
        val density = 2.75f
        val band = rowRefractionBandPx(glass.refractionHeightDp, 99f, density)
        val amount = rowRefractionAmountPx(band, glass.refractionOffsetDp, density)
        assertEquals(band * ROW_REFRACTION_AMOUNT_RATIO, amount, 0.001f)
        assertEquals(1.6f * band, amount, 0.001f)
        assertTrue(amount < glass.refractionOffsetDp * density)
    }

    /** 带宽很小时按比例小幅度位移，不得为负。 */
    @Test
    fun amountNeverNegativeAndKeepsRatioRelation() {
        listOf(1f, 5f, 20f, 100f).forEach { band ->
            val amount = rowRefractionAmountPx(band, 49.2f, 2.75f)
            assertTrue(amount >= 0f)
            assertTrue(amount <= band * ROW_REFRACTION_AMOUNT_RATIO + 0.0001f)
        }
        // 带宽为 0 → 位移 0（不折射，但仍返回合法值）
        assertEquals(0f, rowRefractionAmountPx(0f, 49.2f, 3f), 0.000001f)
    }

    /** 预设若被外部写成负值（例如误传底栏已取负的 offset）必须先归一为正再限幅。 */
    @Test
    fun amountNormalizesNegativePreset() {
        val band = 40f
        val positive = rowRefractionAmountPx(band, 49.2f, 2.75f)
        val negative = rowRefractionAmountPx(band, -49.2f, 2.75f)
        assertEquals(positive, negative, 0.000001f)
        assertTrue(negative >= 0f)
    }

    /** 位移上限比例常量锁死：改小会让观感变钝，改大会让取样跨过整行。 */
    @Test
    fun amountRatioConstantIsLocked() {
        assertEquals(1.6f, ROW_REFRACTION_AMOUNT_RATIO, 0.000001f)
    }

    // ---------------------------------------------------------------- uniform 口径

    /** 与底栏 Config 默认值 / overrides() 逐值对齐的常量。 */
    @Test
    fun uniformConstantsMirrorBottomBarOverrides() {
        assertEquals(0.3f, ROW_LIQUID_DEPTH_EFFECT, 0.000001f)
        assertEquals(0f, ROW_LIQUID_CONTRAST, 0.000001f)
        assertEquals(0f, ROW_LIQUID_WHITE_POINT, 0.000001f)
        assertEquals(1f, ROW_LIQUID_CHROMA_MULTIPLIER, 0.000001f)
        // tint 由既有 tintPaint 负责，着色器必须传 0，否则双重上色
        assertEquals(0f, ROW_LIQUID_TINT_ALPHA, 0.000001f)
        assertEquals(1f, ROW_LIQUID_TINT_RED, 0.000001f)
        assertEquals(1f, ROW_LIQUID_TINT_GREEN, 0.000001f)
        assertEquals(1f, ROW_LIQUID_TINT_BLUE, 0.000001f)
    }

    /** 整组 uniform 逐字段断言（含负位移、色散取预设、偏移恒 0）。 */
    @Test
    fun uniformsAlignWithMaterialAndRowGeometry() {
        val density = 2.75f
        val widthPx = 992f
        val heightPx = 99f
        val radiusPx = 27.5f
        val u = rowLiquidUniforms(glass, widthPx, heightPx, radiusPx, density)

        assertEquals(widthPx, u.widthPx, 0.000001f)
        assertEquals(heightPx, u.heightPx, 0.000001f)
        // offset 恒 0：着色器坐标就是行局部坐标
        assertEquals(0f, u.offsetXPx, 0.000001f)
        assertEquals(0f, u.offsetYPx, 0.000001f)
        assertEquals(radiusPx, u.cornerRadiusPx, 0.000001f)
        // 带宽 = 0.42 × 99 = 41.58px
        assertEquals(41.58f, u.refractionHeightPx, 0.01f)
        // 位移 = -min(49.2dp×2.75, 1.6×41.58) = -66.528，负值 = 向内位移
        assertEquals(-66.528f, u.refractionAmountPx, 0.01f)
        assertTrue(u.refractionAmountPx <= 0f)
        assertEquals(0.3f, u.depthEffect, 0.000001f)
        // 色散与底栏同源：直接取该档预设的 dispersion
        assertEquals(glass.dispersion, u.chromaticAberration, 0.000001f)
        assertEquals(0.272f, u.chromaticAberration, 0.001f)
        assertEquals(0f, u.contrast, 0.000001f)
        assertEquals(0f, u.whitePoint, 0.000001f)
        assertEquals(1f, u.chromaMultiplier, 0.000001f)
    }

    /** uniform 签名必须随尺寸/色散变化而变化，否则渲染器会错误复用旧的 RenderEffect。 */
    @Test
    fun uniformsSignatureChangesWithGeometryAndDispersion() {
        val base = rowLiquidUniforms(glass, 992f, 99f, 27.5f, 2.75f)
        val same = rowLiquidUniforms(glass, 992f, 99f, 27.5f, 2.75f)
        val otherHeight = rowLiquidUniforms(glass, 992f, 108f, 27.5f, 2.75f)
        val otherMaterial = rowLiquidUniforms(frosted, 992f, 99f, 27.5f, 2.75f)
        assertEquals(base.signature(), same.signature())
        assertTrue(base.signature() != otherHeight.signature())
        assertTrue(base.signature() != otherMaterial.signature())
    }

    /** 磨砂档取值同样逐值可验：带宽/位移更小、色散更弱，差异全部来自预设。 */
    @Test
    fun frostedUniformsStayDistinctFromGlass() {
        val g = rowLiquidUniforms(glass, 992f, 99f, 27.5f, 2.75f)
        val f = rowLiquidUniforms(frosted, 992f, 99f, 27.5f, 2.75f)
        // 两档在行高钳制后带宽相同（都撞到 0.42 上限），但色散不同
        assertTrue(f.chromaticAberration < g.chromaticAberration)
        assertEquals(frosted.dispersion, f.chromaticAberration, 0.000001f)
    }

    // ---------------------------------------------------------------- 采样矩阵

    /** 与既有内联实现逐值一致：scale = 底图/根视图，translate = -(行位置 - 根位置) × scale。 */
    @Test
    fun sampleTransformMatchesInlineImplementation() {
        val t = rowSampleTransform(
            bitmapWidth = 360, bitmapHeight = 800,
            rootWidth = 1080, rootHeight = 2400,
            locationX = 0, locationY = 500,
            rootX = 0, rootY = 0
        )
        assertEquals(360f / 1080f, t.scaleX, 0.000001f)
        assertEquals(800f / 2400f, t.scaleY, 0.000001f)
        assertEquals(0f, t.translateX, 0.000001f)
        assertEquals(-500f * (800f / 2400f), t.translateY, 0.000001f)
    }

    /** 行不在根视图原点时（分屏 / 内嵌、状态栏偏移）平移必须带上行位置差。 */
    @Test
    fun sampleTransformHonoursRowOffsetFromRoot() {
        val t = rowSampleTransform(
            bitmapWidth = 400, bitmapHeight = 900,
            rootWidth = 1000, rootHeight = 2000,
            locationX = 40, locationY = 300,
            rootX = 20, rootY = 100
        )
        assertEquals(0.4f, t.scaleX, 0.000001f)
        assertEquals(0.45f, t.scaleY, 0.000001f)
        assertEquals(-(40 - 20) * 0.4f, t.translateX, 0.000001f)
        assertEquals(-(300 - 100) * 0.45f, t.translateY, 0.000001f)
    }

    /** 根视图尺寸未知（首次 draw）时返回单位变换，绝不产生 NaN/Inf 矩阵。 */
    @Test
    fun sampleTransformFallsBackToIdentityWhenRootUnknown() {
        listOf(0, -1).forEach { bad ->
            val t = rowSampleTransform(360, 800, bad, 2400, 0, 0, 0, 0)
            assertEquals(1f, t.scaleX, 0.000001f)
            assertEquals(1f, t.scaleY, 0.000001f)
            assertEquals(0f, t.translateX, 0.000001f)
            assertEquals(0f, t.translateY, 0.000001f)
        }
    }

    // ---------------------------------------------------------------- 降级矩阵

    private fun capability(
        sdkInt: Int = 33,
        rowLiquidEnabled: Boolean = true,
        hardwareBlurPref: Boolean = true,
        isEInk: Boolean = false,
        isLowRam: Boolean = false,
        level: Int = 60,
        canvasHardwareAccelerated: Boolean = true,
        backdropAvailable: Boolean = true,
        liquidFailed: Boolean = false
    ) = rowLiquidCapability(
        sdkInt, rowLiquidEnabled, hardwareBlurPref, isEInk, isLowRam, level,
        canvasHardwareAccelerated, backdropAvailable, liquidFailed
    )

    /** 全绿输入 → 走液态。 */
    @Test
    fun liquidWhenEverythingIsAvailable() {
        assertEquals(RowLiquidCapability.LIQUID, capability())
        assertEquals(RowLiquidCapability.LIQUID, capability(sdkInt = 34))
        assertEquals(RowLiquidCapability.LIQUID, capability(sdkInt = 36))
    }

    /** SDK 门槛：RuntimeShader 是 API 33，API 30/31/32 一律走静态管线（A1）。 */
    @Test
    fun sdkGateBlocksBelow33() {
        assertEquals(33, ROW_LIQUID_MIN_SDK)
        listOf(21, 26, 30, 31, 32).forEach { sdk ->
            assertEquals(
                "sdk=$sdk 必须降级",
                RowLiquidCapability.STATIC, capability(sdkInt = sdk)
            )
        }
    }

    /** 编译期总闸关闭 = 走静态管线（纯 A1）。 */
    @Test
    fun compileTimeSwitchFallsBackToStatic() {
        assertEquals(RowLiquidCapability.STATIC, capability(rowLiquidEnabled = false))
    }

    /** 用户关掉「硬件模糊」这个既有开关即关闭行内液态（不新增偏好键）。 */
    @Test
    fun hardwareBlurPreferenceActsAsRuntimeGate() {
        assertEquals(RowLiquidCapability.STATIC, capability(hardwareBlurPref = false))
    }

    /** 设备级降级：墨水屏 / 低内存与 rowBackground() 的既有判定一致。 */
    @Test
    fun deviceDegradationsFallBackToStatic() {
        assertEquals(RowLiquidCapability.STATIC, capability(isEInk = true))
        assertEquals(RowLiquidCapability.STATIC, capability(isLowRam = true))
    }

    /** 强度 0 表示用户已关掉观感档。 */
    @Test
    fun zeroLevelFallsBackToStatic() {
        assertEquals(RowLiquidCapability.STATIC, capability(level = 0))
        assertEquals(RowLiquidCapability.LIQUID, capability(level = 1))
    }

    /** 非硬件加速 Canvas（软件 layer / 截图）当帧降级。 */
    @Test
    fun softwareCanvasFallsBackToStatic() {
        assertEquals(
            RowLiquidCapability.STATIC, capability(canvasHardwareAccelerated = false)
        )
    }

    /** 没有可采样的底图（动图壁纸纯色底、构建失败、已回收）时降级。 */
    @Test
    fun missingBackdropFallsBackToStatic() {
        assertEquals(RowLiquidCapability.STATIC, capability(backdropAvailable = false))
    }

    /** AGSL 编译/绘制曾失败 → 持久位生效，本行不再重试。 */
    @Test
    fun liquidFailedBitIsSticky() {
        assertEquals(RowLiquidCapability.STATIC, capability(liquidFailed = true))
    }

    /** 判定必须是「全部满足才 LIQUID」：逐条单独置坏都只能得到 STATIC。 */
    @Test
    fun everySingleGateIsNecessary() {
        val gates: List<Pair<String, RowLiquidCapability>> = listOf(
            "sdk" to capability(sdkInt = 32),
            "switch" to capability(rowLiquidEnabled = false),
            "pref" to capability(hardwareBlurPref = false),
            "eink" to capability(isEInk = true),
            "lowram" to capability(isLowRam = true),
            "level" to capability(level = 0),
            "canvas" to capability(canvasHardwareAccelerated = false),
            "backdrop" to capability(backdropAvailable = false),
            "failed" to capability(liquidFailed = true)
        )
        gates.forEach { (label, route) ->
            assertEquals("闸门 $label 必须能单独否决", RowLiquidCapability.STATIC, route)
        }
    }
}
