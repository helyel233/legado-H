package io.legado.app.ui.main.explore

import io.legado.app.ui.main.GlassMaterialPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行内液态玻璃纯函数（[RowGlassLiquidSpec]）的确定性单测。
 *
 * 这些口径错了都**不会编译失败**，只会让真机观感悄悄跑偏或者让降级矩阵漏掉一条：
 * - 带宽没按行高钳制 → 整行落进 7-taps 折射带，行被拉花、滚动掉帧；
 * - 位移与底栏限幅口径不同 → 同一档参数下行与底栏的折射强度不同；
 * - uniform 与底栏不逐值对齐 → 同一份 AGSL 在底栏与行内表现不同；
 * - 采样矩阵少乘一个 scale → 行背景与壁纸错位；
 * - 降级矩阵漏一条 → 某类设备上出现黑块 / 空白行 / 崩溃。
 *
 * 因此这里全部用具体数值与逐条分支断言，不使用「非空即通过」这类恒真写法。
 * 1.25.12 起渲染器直接消费 [GlassMaterialPreset.Params]，不再有行级 `Style` 中间层。
 */
class RowGlassLiquidSpecTest {

    private val glass = GlassMaterialPreset.params(GlassMaterialPreset.GLASS, 0.3f)
    private val frosted = GlassMaterialPreset.params(GlassMaterialPreset.FROSTED, 0.35f)

    // ---------------------------------------------------------------- 带宽标定

    /**
     * 1080×2400 / density 2.75 的真机口径：行高约 36dp = 99px，
     * 预设 27.6dp = 75.9px 大于 0.42×99 = 41.58px → 必须由行高钳制。
     */
    @Test
    fun refractionBandIsClampedByRowHeight() {
        val density = 2.75f
        val rowHeightPx = 36f * density // 99px
        val band = rowRefractionBandPx(glass.refractionHeightDp, rowHeightPx, density)
        assertEquals(0.42f * rowHeightPx, band, 0.001f)
        assertEquals(41.58f, band, 0.01f)
        // 预设本身（27.6dp × 2.75 = 75.9px）远大于钳制结果，证明限幅确实生效
        assertTrue(glass.refractionHeightDp * density > band)
    }

    /** 0.42 这个比例必须与 StableLiquidGlassView.effectiveRefractionHeight 的 pixelSafePill 口径一致。 */
    @Test
    fun bandRatioMatchesPixelSafePillConstant() {
        assertEquals(0.42f, ROW_REFRACTION_BAND_RATIO, 0.000001f)
    }

    /** 行足够高时不应被行高钳制，预设值原样生效（否则高行永远拿不到强折射）。 */
    @Test
    fun bandIsNotClampedWhenRowIsTallEnough() {
        val density = 2.75f
        val tallRowPx = 300f
        val band = rowRefractionBandPx(glass.refractionHeightDp, tallRowPx, density)
        assertEquals(glass.refractionHeightDp * density, band, 0.0001f)
        assertEquals(75.9f, band, 0.001f)
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

    /**
     * 与底栏 `setRefractionHeight` 的 12..50dp 限幅逐值一致：
     * 预设再大也只能到 50dp，再小也至少 12dp（0 会让 shader 内除零）。
     */
    @Test
    fun bandIsClampedToBottomBarDpRange() {
        val density = 1f
        assertEquals(50f, rowRefractionBandPx(500f, 400f, density), 0.0001f)
        assertEquals(12f, rowRefractionBandPx(1f, 400f, density), 0.0001f)
    }

    /** 退化输入（行高 0 / 密度 0 / 预设 0）不得产出 0 或负数，否则 shader 内会除零。 */
    @Test
    fun bandNeverDegeneratesToZeroOrNegative() {
        // 三者全 0：没有任何可用的像素尺度，只能落到 1px 下限
        assertEquals(1f, rowRefractionBandPx(0f, 0f, 0f), 0.000001f)
        // 预设 0 但密度有效：与底栏一致先钳到 12dp 下限，再与行高 42% 取小 → 36px
        assertEquals(12f * 3f, rowRefractionBandPx(0f, 99f, 3f), 0.000001f)
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
        assertEquals(27.6f * density, glassBand, 0.001f)
        assertEquals(14.1f * density, frostedBand, 0.001f)
    }

    // ---------------------------------------------------------------- 位移标定

    /**
     * 位移与底栏 `setRefractionOffset` 同口径：`abs(offsetDp) × density` 限幅 20..120dp，
     * **不再**按折射带二次缩小——1.25.11 及以前行内多了一道 `band × 1.6` 上限，
     * 同一档参数下行与底栏的折射位移不同，正是观感不一致的来源之一。
     */
    @Test
    fun amountFollowsBottomBarDpClamp() {
        val density = 2.75f
        val amount = rowRefractionAmountPx(glass.refractionOffsetDp, density)
        assertEquals(53.8f * density, amount, 0.001f)
        assertEquals(147.95f, amount, 0.001f)

        // 上下限与底栏 setRefractionOffset 的 20..120dp 一致
        assertEquals(120f, rowRefractionAmountPx(500f, 1f), 0.0001f)
        assertEquals(20f, rowRefractionAmountPx(1f, 1f), 0.0001f)
    }

    /** 预设若被外部写成负值（例如误传底栏已取负的 offset）必须先归一为正。 */
    @Test
    fun amountNormalizesNegativePreset() {
        val positive = rowRefractionAmountPx(53.8f, 2.75f)
        val negative = rowRefractionAmountPx(-53.8f, 2.75f)
        assertEquals(positive, negative, 0.000001f)
        assertTrue(negative > 0f)
    }

    /** 位移恒为正（传给着色器时由 uniform 取负），0 输入也被下限抬到 20dp。 */
    @Test
    fun amountIsAlwaysPositiveBeforeNegation() {
        listOf(0f, 1f, 12f, 53.8f, 500f).forEach { presetDp ->
            assertTrue(rowRefractionAmountPx(presetDp, 2.75f) > 0f)
        }
    }

    // ---------------------------------------------------------------- uniform 口径

    /** 与底栏 Config 默认值 / overrides() 逐值对齐的常量。 */
    @Test
    fun uniformConstantsMirrorBottomBarOverrides() {
        assertEquals(0.3f, ROW_LIQUID_DEPTH_EFFECT, 0.000001f)
        assertEquals(0f, ROW_LIQUID_CONTRAST, 0.000001f)
        assertEquals(0f, ROW_LIQUID_WHITE_POINT, 0.000001f)
        assertEquals(1f, ROW_LIQUID_CHROMA_MULTIPLIER, 0.000001f)
        // tint 由着色器负责（底栏同口径），不再钉成 0 + 额外 tintPaint
        assertEquals(1f, ROW_LIQUID_TINT_RED, 0.000001f)
        assertEquals(1f, ROW_LIQUID_TINT_GREEN, 0.000001f)
        assertEquals(1f, ROW_LIQUID_TINT_BLUE, 0.000001f)
        // 描边上限只在 GlassMaterialPreset.scaled 一处生效
        assertEquals(0.42f, GlassMaterialPreset.MAX_STROKE_ALPHA, 0.000001f)
    }

    /** 整组 uniform 逐字段断言（含负位移、色散/着色取预设、偏移恒 0）。 */
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
        // 位移 = -53.8dp×2.75 = -147.95，负值 = 向内位移
        assertEquals(-147.95f, u.refractionAmountPx, 0.01f)
        assertTrue(u.refractionAmountPx <= 0f)
        assertEquals(0.3f, u.depthEffect, 0.000001f)
        // 色散与底栏同源：直接取该档预设的 dispersion
        assertEquals(glass.dispersion, u.chromaticAberration, 0.000001f)
        assertEquals(0.312f, u.chromaticAberration, 0.001f)
        // tint 与底栏同源：直接取该档预设的 tintAlpha（0.004 + 0.3*0.02 = 0.010）
        assertEquals(glass.tintAlpha, u.tintAlpha, 0.000001f)
        assertEquals(0.010f, u.tintAlpha, 0.000001f)
        assertEquals(0f, u.contrast, 0.000001f)
        assertEquals(0f, u.whitePoint, 0.000001f)
        assertEquals(1f, u.chromaMultiplier, 0.000001f)
    }

    /** 圆角超过行高一半时必须钳制（与 StableLiquidGlassView 的 effectiveShaderCornerRadius 同口径）。 */
    @Test
    fun cornerRadiusIsClampedToHalfRowHeight() {
        val u = rowLiquidUniforms(glass, 992f, 99f, 80f, 2.75f)
        assertEquals(49.5f, u.cornerRadiusPx, 0.000001f)
    }

    /** uniform 签名必须随尺寸/色散/着色变化而变化，否则渲染器会错误复用过期的 uniform。 */
    @Test
    fun uniformsSignatureChangesWithGeometryAndDispersion() {
        val base = rowLiquidUniforms(glass, 992f, 99f, 27.5f, 2.75f)
        val same = rowLiquidUniforms(glass, 992f, 99f, 27.5f, 2.75f)
        val otherHeight = rowLiquidUniforms(glass, 992f, 108f, 27.5f, 2.75f)
        val otherMaterial = rowLiquidUniforms(frosted, 992f, 99f, 27.5f, 2.75f)
        val otherTint = rowLiquidUniforms(
            GlassMaterialPreset.scaled(glass, tintPercent = 150), 992f, 99f, 27.5f, 2.75f
        )
        assertEquals(base.signature(), same.signature())
        assertTrue(base.signature() != otherHeight.signature())
        assertTrue(base.signature() != otherMaterial.signature())
        assertTrue(base.signature() != otherTint.signature())
    }

    /** 磨砂档取值同样逐值可验：带宽更窄、位移更小、色散更弱，差异全部来自预设。 */
    @Test
    fun frostedUniformsStayDistinctFromGlass() {
        val density = 2.75f
        val g = rowLiquidUniforms(glass, 992f, 99f, 27.5f, density)
        val f = rowLiquidUniforms(frosted, 992f, 99f, 27.5f, density)
        // 透明档撞到 0.42 行高上限（41.58），磨砂档预设 14.1dp×2.75 = 38.775 未触顶
        assertEquals(41.58f, g.refractionHeightPx, 0.01f)
        assertEquals(14.1f * density, f.refractionHeightPx, 0.001f)
        assertTrue(f.refractionHeightPx < g.refractionHeightPx)
        assertTrue(kotlin.math.abs(f.refractionAmountPx) < kotlin.math.abs(g.refractionAmountPx))
        assertTrue(f.chromaticAberration < g.chromaticAberration)
        assertTrue(f.tintAlpha > g.tintAlpha)
        assertEquals(-frosted.refractionOffsetDp * density, f.refractionAmountPx, 0.001f)
        assertEquals(frosted.dispersion, f.chromaticAberration, 0.000001f)
        assertEquals(frosted.tintAlpha, f.tintAlpha, 0.000001f)
    }

    // ---------------------------------------------------------------- 采样矩阵

    /**
     * 方向必须是「位图 → 局部」：降采样底图要放大回根视图尺寸，平移是未乘 scale 的局部偏移。
     *
     * 反过来（`位图/根视图` + `-(行偏移)×scale`）会把每行采样点推出位图范围，
     * 经 CLAMP 后所有行都显示位图最后一行附近的暗色——这正是真机截图里的暗条。
     */
    @Test
    fun sampleTransformMapsBitmapToLocalLikeBitmapShader() {
        val t = rowSampleTransform(
            bitmapWidth = 360, bitmapHeight = 800,
            rootWidth = 1080, rootHeight = 2400,
            locationX = 0, locationY = 500,
            rootX = 0, rootY = 0
        )
        assertEquals(1080f / 360f, t.scaleX, 0.000001f)
        assertEquals(2400f / 800f, t.scaleY, 0.000001f)
        assertEquals(0f, t.translateX, 0.000001f)
        assertEquals(-500f, t.translateY, 0.000001f)
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
        assertEquals(1000f / 400f, t.scaleX, 0.000001f)
        assertEquals(2000f / 900f, t.scaleY, 0.000001f)
        assertEquals(-20f, t.translateX, 0.000001f)
        assertEquals(-200f, t.translateY, 0.000001f)
    }

    /**
     * 对齐性：任意一行（含靠近屏幕底部的行）采样到的位图坐标都必须落在位图内，
     * 且等于「行在根视图中的偏移 × 降采样比例」。
     */
    @Test
    fun sampleTransformKeepsEveryRowInsideBackdrop() {
        val bitmapWidth = 360
        val bitmapHeight = 800
        val rootWidth = 1080
        val rootHeight = 2400
        val rowHeightPx = 119f
        listOf(0, 500, 1500, 2200).forEach { rowY ->
            val t = rowSampleTransform(
                bitmapWidth = bitmapWidth, bitmapHeight = bitmapHeight,
                rootWidth = rootWidth, rootHeight = rootHeight,
                locationX = 0, locationY = rowY,
                rootX = 0, rootY = 0
            )
            // 逆变换：位图坐标 = (局部坐标 - translate) / scale
            val top = (0f - t.translateY) / t.scaleY
            val bottom = (rowHeightPx - t.translateY) / t.scaleY
            assertEquals(
                "rowY=$rowY",
                rowY * (bitmapHeight / rootHeight.toFloat()),
                top,
                0.001f
            )
            assertTrue("rowY=$rowY top=$top", top >= 0f)
            assertTrue("rowY=$rowY bottom=$bottom", bottom <= bitmapHeight.toFloat())
        }
    }

    /** 尺寸未知（首次 draw）时返回单位变换，绝不产生除零 / NaN / Inf 矩阵。 */
    @Test
    fun sampleTransformFallsBackToIdentityWhenSizeUnknown() {
        listOf(0, -1).forEach { bad ->
            val rootBad = rowSampleTransform(360, 800, bad, 2400, 0, 0, 0, 0)
            assertEquals(1f, rootBad.scaleX, 0.000001f)
            assertEquals(1f, rootBad.scaleY, 0.000001f)
            assertEquals(0f, rootBad.translateX, 0.000001f)
            assertEquals(0f, rootBad.translateY, 0.000001f)

            val bitmapBad = rowSampleTransform(bad, 800, 1080, 2400, 0, 0, 0, 0)
            assertEquals(1f, bitmapBad.scaleX, 0.000001f)
            assertEquals(1f, bitmapBad.scaleY, 0.000001f)
            assertEquals(0f, bitmapBad.translateX, 0.000001f)
            assertEquals(0f, bitmapBad.translateY, 0.000001f)
        }
    }

    // ---------------------------------------------------------------- 降级矩阵

    private fun capability(
        sdkInt: Int = 33,
        rowLiquidEnabled: Boolean = true,
        hardwareBlurPref: Boolean = true,
        isEInk: Boolean = false,
        isLowRam: Boolean = false,
        canvasHardwareAccelerated: Boolean = true,
        backdropAvailable: Boolean = true,
        liquidFailed: Boolean = false
    ) = rowLiquidCapability(
        sdkInt, rowLiquidEnabled, hardwareBlurPref, isEInk, isLowRam,
        canvasHardwareAccelerated, backdropAvailable, liquidFailed
    )

    /** 全绿输入 → 走液态。 */
    @Test
    fun liquidWhenEverythingIsAvailable() {
        assertEquals(RowLiquidCapability.LIQUID, capability())
        assertEquals(RowLiquidCapability.LIQUID, capability(sdkInt = 34))
        assertEquals(RowLiquidCapability.LIQUID, capability(sdkInt = 36))
    }

    /** SDK 门槛：RuntimeShader 是 API 33，API 30/31/32 一律走静态管线。 */
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

    /** 编译期总闸关闭 = 走静态管线。 */
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

    /**
     * 强度不再作为液态闸门：0 只是「最小强度」，关玻璃由「无玻璃」档负责。
     * 因此液态判定里没有 level 参数，最小强度的材质仍有正的模糊/着色（不是空白玻璃）。
     */
    @Test
    fun strengthIsNotALiquidGate() {
        val minimum = GlassMaterialPreset.params(GlassMaterialPreset.FROSTED, 0f)
        assertTrue(minimum.blurDp > 0f)
        assertTrue(minimum.tintAlpha > 0f)
        assertEquals(RowLiquidCapability.LIQUID, capability())
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
            "canvas" to capability(canvasHardwareAccelerated = false),
            "backdrop" to capability(backdropAvailable = false),
            "failed" to capability(liquidFailed = true)
        )
        gates.forEach { (label, route) ->
            assertEquals("闸门 $label 必须能单独否决", RowLiquidCapability.STATIC, route)
        }
    }
}

/**
 * 行内 RenderNode 包围盒（[rowGlassNodeBounds]）。
 *
 * 这是 1.25.13 修复的回归锁：`beginRecording(w, h)` 只定录制画布大小、不定 node
 * 位置，位置为空时 `drawRenderNode` 静默不画却仍返回成功——tint 让位、行内只剩
 * 壳层与描边，透出清晰壁纸。修复点是录制前 `setPosition(0, 0, w, h)`（底栏库
 * `onSizeChanged` 同一步），因此这里断言「行尺寸 → 非空且锚在原点的包围盒」。
 */
class RowGlassNodeBoundsTest {

    @Test
    fun positiveSizeProducesNonEmptyBoundsAnchoredAtOrigin() {
        val bounds = rowGlassNodeBounds(320, 96)
        assertEquals(0, bounds.left)
        assertEquals(0, bounds.top)
        assertEquals(320, bounds.right)
        assertEquals(96, bounds.bottom)
        // 空包围盒正是“成功但不可见”的充分条件，必须显式排除
        assertFalse(bounds.isEmpty)
    }

    /** 逐尺寸抽样：任何正尺寸都必须非空（漏掉一档就会在对应行高上完全看不到玻璃）。 */
    @Test
    fun anyPositiveSizeIsNonEmpty() {
        listOf(1 to 1, 44 to 36, 1080 to 99, 2560 to 132).forEach { (w, h) ->
            assertFalse("${w}x$h 的包围盒不能为空", rowGlassNodeBounds(w, h).isEmpty)
        }
    }

    /** 非正尺寸是调用方的 bug 分支（draw 已在更早处返回 false），这里只做确定性钳制。 */
    @Test
    fun nonPositiveSizeClampsToEmptyBounds() {
        assertTrue(rowGlassNodeBounds(0, 96).isEmpty)
        assertTrue(rowGlassNodeBounds(-8, 96).isEmpty)
        assertTrue(rowGlassNodeBounds(320, -8).isEmpty)
    }
}
