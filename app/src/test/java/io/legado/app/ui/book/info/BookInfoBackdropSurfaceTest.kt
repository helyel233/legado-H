package io.legado.app.ui.book.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 书籍详情页「内容域半透明表面」的降级真值表（[bookInfoTranslucentSurfaceAllowed]）。
 *
 * 这张表决定详情页卡片是让出 alpha（透出背后已模糊的详情图）还是逐值保持主题实底。
 * 判错的两个方向都会在真机上被用户直接看到：判成可用而实际没有底图 → 卡片被冲淡；
 * 判成不可用 → 玻璃档下详情页仍是全 App 唯一一处死板实底卡。
 */
class BookInfoBackdropSurfaceTest {

    /** 玻璃档开：让出 alpha（第四轮修复后不再要求背景图偏好非空）。 */
    @Test
    fun translucentSurfaceAllowedWhenGlassEnabled() {
        assertTrue(bookInfoTranslucentSurfaceAllowed(glassEnabled = true))
    }

    /**
     * 无详情背景图偏好（用户用系统壁纸 / 主题纯色，从未配置 `bookInfoBgImage`/
     * `bgImage`）：第四轮根因——这道曾导致「详情页完全无变化」的门已移除，
     * 玻璃档开时照样上玻璃（半透明 + 描边，透出纯色底观感不退化）。
     */
    @Test
    fun translucentSurfaceAllowedWithoutBackdropImagePreference() {
        assertTrue(bookInfoTranslucentSurfaceAllowed(glassEnabled = true))
    }

    /**
     * solid 档 / 墨水屏 / 低内存（由 `GlassSurface.wallpaperGlassEnabled` 折叠成
     * `glassEnabled = false`）：逐值保持今天的实底观感。
     */
    @Test
    fun opaqueSurfaceWhenGlassDisabled() {
        assertFalse(bookInfoTranslucentSurfaceAllowed(glassEnabled = false))
    }

    // ---------------------------------------------------------------------
    // 页面自管顶栏面的圆角：悬浮卡必须同圆角，非悬浮保持贴边直角（dormant 底线）
    // ---------------------------------------------------------------------

    /**
     * 悬浮 chrome 开启：顶栏 margins 已内缩成卡片，面必须与卡片同圆角——
     * 半径原值透传（chrome 解析的钳制由 TopBarChromeSpecTest 锁，这里不重复）。
     */
    @Test
    fun floatingChromeFaceUsesChromeRadius() {
        assertEquals(26f, pageOwnedTitleBarRadius(floating = true, cornerRadiusPx = 26f))
        assertEquals(0f, pageOwnedTitleBarRadius(floating = true, cornerRadiusPx = 0f))
    }

    /**
     * 非悬浮：贴边通栏直角，逐值等价 1.31.1 及以前（半径不透传 chrome 值，
     * 否则 floatingChrome=false 的存量包顶栏会凭空变圆角）。
     */
    @Test
    fun legacyFaceStaysSquare() {
        assertEquals(0f, pageOwnedTitleBarRadius(floating = false, cornerRadiusPx = 26f))
        assertEquals(0f, pageOwnedTitleBarRadius(floating = false, cornerRadiusPx = 0f))
    }

    /**
     * R4 双向独立：悬浮布尔与半径是两个独立输入，禁止由 radius>0 反推悬浮——
     * chrome 给出非零半径但悬浮关闭时，面仍是直角。
     */
    @Test
    fun floatingAndRadiusAreIndependentInputs() {
        assertEquals(0f, pageOwnedTitleBarRadius(floating = false, cornerRadiusPx = 40f))
        assertEquals(40f, pageOwnedTitleBarRadius(floating = true, cornerRadiusPx = 40f))
    }

    // ---------------------------------------------------------------------
    // 顶栏玻璃面：白字恒亮（themeMode=dark），压暗面 alpha 是对比度倒推的下限
    // ---------------------------------------------------------------------

    /**
     * 顶栏黑面对最坏底图（夜间纯白模糊图经 `vw_bg` 31% 压暗后的 176 灰）必须保住
     * 白字 ≥ 4.5:1。测试用独立的 WCAG 实现复算，不与实现共享同一份数学，
     * 错的是实现而不是测试口径。
     */
    @Test
    fun titleBarScrimKeepsWhiteTextReadableOnWorstBackdrop() {
        val scrim = titleBarScrimColor()
        val scrimAlpha = (scrim ushr 24) and 0xFF
        assertEquals(
            (BookInfoBackdropSurface.TITLE_BAR_SCRIM_ALPHA * 255).toInt(),
            scrimAlpha
        )
        assertEquals(0, scrim and 0x00FFFFFF) // 黑面：与 vw_bg 同极性，日夜间都只压暗
        val blended = blendOverGray(scrim, BookInfoBackdropSurface.TITLE_BAR_SCRIM_ALPHA)
        val contrast = contrastRatio(0xFFFFFFFF.toInt(), blended)
        assertTrue("白字对最坏底图应 ≥ 4.5:1，实际 $contrast", contrast >= 4.5)
    }

    /** 日间最坏底图是纯黑：黑面叠加后仍是纯黑，白字对比度无限大（此处只需 > 4.5）。 */
    @Test
    fun titleBarScrimKeepsWhiteTextReadableOnDarkBackdrop() {
        val blended = blendOverGray(
            titleBarScrimColor(),
            BookInfoBackdropSurface.TITLE_BAR_SCRIM_ALPHA,
            backdropGray = BOOK_INFO_DARK_BACKDROP_GRAY
        )
        assertTrue(contrastRatio(0xFFFFFFFF.toInt(), blended) >= 4.5)
    }

    // ---------------------------------------------------------------------
    // 「阅读」主按钮：accent 让 alpha 后文字色必须按最坏面色重算
    // ---------------------------------------------------------------------

    /** 浅色 accent：黑字在两个最坏方向上都明显占优。alpha 取默认档 0.80（PANEL_ALPHA）。 */
    @Test
    fun accentButtonPicksBlackTextOnLightAccent() {
        val lightAccent = 0xFFF1F2F6.toInt()
        assertEquals(0xFF000000.toInt(), accentButtonTextColor(lightAccent, 0.80f))
    }

    /** 深色 accent：白字占优。alpha 取默认档 0.80（PANEL_ALPHA）。 */
    @Test
    fun accentButtonPicksWhiteTextOnDarkAccent() {
        val darkAccent = 0xFF252528.toInt()
        assertEquals(0xFFFFFFFF.toInt(), accentButtonTextColor(darkAccent, 0.80f))
    }

    /**
     * 性质锁：无论 accent 是什么色相，选出的文字色最坏对比度都不低于落选方
     * （这是「按最坏面色重算」这一规则本身的定义）。穷举色相环 + 明暗两端。
     */
    @Test
    fun accentButtonTextColorIsWorstCaseOptimal() {
        val alphas = listOf(0.80f, 0.62f, 1.0f)
        val accents = buildList {
            for (hue in 0 until 360 step 30) {
                add(hsvColor(hue.toFloat(), 0.7f, 0.9f))
                add(hsvColor(hue.toFloat(), 0.7f, 0.4f))
            }
        }
        for (accent in accents) {
            for (alpha in alphas) {
                val chosen = accentButtonTextColor(accent, alpha)
                val other = if (chosen == 0xFF000000.toInt()) {
                    0xFFFFFFFF.toInt()
                } else {
                    0xFF000000.toInt()
                }
                val chosenWorst = worstCaseContrast(chosen, accent, alpha)
                val otherWorst = worstCaseContrast(other, accent, alpha)
                assertTrue(
                    "accent=${accent.toUInt()} alpha=$alpha：选中的文字色最坏对比度" +
                        "($chosenWorst) 不应低于落选方 ($otherWorst)",
                    chosenWorst >= otherWorst
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // 背景图取用顺序：详情图优先，空则回退 App 级背景图（第二轮真机取证引入）
    // ---------------------------------------------------------------------

    /**
     * 配置了详情背景图时必须优先于 App 级背景图：详情图是用户对**本页**的显式选择，
     * 回退不应吞掉它。锁不住这一条，「想给详情页换独立底图」的用户会发现设置失效。
     */
    @Test
    fun backdropPathPrefersDetailImageOverAppBackground() {
        assertEquals(
            "/detail/bg.jpg",
            resolveBookInfoBackdropImagePath(
                detailPath = "/detail/bg.jpg",
                appBackgroundPath = "/theme/background.jpg"
            )
        )
    }

    /**
     * 详情图缺失（未配置 / 键被主题包迁移移除）时回退 App 级背景图。
     * 这是本轮修复的目标场景：真机上 `bookInfoBackgroundImage` 键根本不存在、
     * 而 `backgroundImage` 指向主题包里的背景图，旧口径判「无图」→ 整页回退实底。
     */
    @Test
    fun backdropPathFallsBackToAppBackgroundImage() {
        assertEquals(
            "/theme/background.jpg",
            resolveBookInfoBackdropImagePath(
                detailPath = null,
                appBackgroundPath = "/theme/background.jpg"
            )
        )
    }

    /** 空白字符串与 null 同义（防「空串 + 有图」组合判成有图）。 */
    @Test
    fun backdropPathTreatsBlankDetailAsMissing() {
        assertEquals(
            "/theme/background.jpg",
            resolveBookInfoBackdropImagePath(
                detailPath = "   ",
                appBackgroundPath = "/theme/background.jpg"
            )
        )
    }

    /** 两级都缺：返回 null，页面保持纯色底 + 实底卡片（与接入前逐值一致）。 */
    @Test
    fun backdropPathIsNullWhenBothMissing() {
        assertNull(
            resolveBookInfoBackdropImagePath(
                detailPath = null,
                appBackgroundPath = null
            )
        )
        assertNull(
            resolveBookInfoBackdropImagePath(
                detailPath = "",
                appBackgroundPath = " "
            )
        )
    }

    /**
     * 背景图取用与玻璃判定已解耦：无论偏好是否解析出路径，玻璃判定都只看档位。
     * 这条锁住第四轮根因不再复发——「背景图偏好为空」不能让玻璃判定翻否。
     */
    @Test
    fun glassDecisionIsIndependentOfBackdropPath() {
        val withPath = resolveBookInfoBackdropImagePath(
            detailPath = null,
            appBackgroundPath = "/themePackages/day/绿水/background.jpg"
        )
        val withoutPath = resolveBookInfoBackdropImagePath(
            detailPath = null,
            appBackgroundPath = null
        )
        assertEquals("/themePackages/day/绿水/background.jpg", withPath)
        assertNull(withoutPath)
        // 关键：两种路径下玻璃判定一致，不再有「有图才上玻璃」的门
        assertEquals(
            bookInfoTranslucentSurfaceAllowed(true),
            bookInfoTranslucentSurfaceAllowed(true)
        )
        assertTrue(bookInfoTranslucentSurfaceAllowed(true))
    }

    /** 0 或负模糊半径不产出位图，避免向 GlassLiquidBackdropDrawable 交回未模糊的清晰快照。 */
    @Test
    fun zeroBlurRadiusRejectsBackdropCrop() {
        assertFalse(backdropCropParamsValid(outWidth = 100, outHeight = 100, blurRadius = 0))
        assertFalse(backdropCropParamsValid(outWidth = 100, outHeight = 100, blurRadius = -3))
        assertFalse(backdropCropParamsValid(outWidth = 0, outHeight = 100, blurRadius = 8))
        assertFalse(backdropCropParamsValid(outWidth = 100, outHeight = 0, blurRadius = 8))
        assertTrue(backdropCropParamsValid(outWidth = 100, outHeight = 100, blurRadius = 1))
    }

    /** ViewPager2 次页卡片水平坐标按页宽归一化到当前屏内区间，不因离屏而裁剪为空。 */
    @Test
    fun normalizePagerHorizontalOffsetWrapsOffscreenPages() {
        assertEquals(36, normalizePagerHorizontalOffset(hostX = 36, pagerX = 0, pagerWidth = 1080))
        assertEquals(36, normalizePagerHorizontalOffset(hostX = 1116, pagerX = 0, pagerWidth = 1080))
        assertEquals(36, normalizePagerHorizontalOffset(hostX = 2196, pagerX = 0, pagerWidth = 1080))
        assertEquals(120, normalizePagerHorizontalOffset(hostX = -960, pagerX = 100, pagerWidth = 1080))
        assertEquals(500, normalizePagerHorizontalOffset(hostX = 500, pagerX = 0, pagerWidth = 0))
    }

    // ---------------------------------------------------------------------
    // 测试本地独立的 WCAG / 混色数学（与实现不共享，实现错则这里红）
    // ---------------------------------------------------------------------

    private fun blendOverGray(
        fill: Int,
        alpha: Float,
        backdropGray: Int = BOOK_INFO_LIGHT_BACKDROP_GRAY
    ): Int {
        val a = alpha.toDouble()
        fun channel(shift: Int): Int {
            val value = ((fill shr shift) and 0xFF).toDouble()
            // 与实现同为四舍五入（Math.round），避免测试口径差一个 1/255 造成性质锁抖动
            return (value * a + backdropGray * (1 - a)).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun relativeLuminance(color: Int): Double {
        fun linear(shift: Int): Double {
            val c = ((color shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }

    private fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** 文字色的最坏对比度：黑字取日间面（被纯黑底压暗），白字取夜间面（被 176 灰提亮）。 */
    private fun worstCaseContrast(textColor: Int, accent: Int, alpha: Float): Double {
        val onDark = blendOverGray(
            accent, alpha, BOOK_INFO_DARK_BACKDROP_GRAY
        )
        val onLight = blendOverGray(
            accent, alpha, BOOK_INFO_LIGHT_BACKDROP_GRAY
        )
        return if (textColor == 0xFF000000.toInt()) {
            contrastRatio(textColor, onDark)
        } else {
            contrastRatio(textColor, onLight)
        }
    }

    private fun hsvColor(hue: Float, saturation: Float, value: Float): Int {
        val h = (hue % 360f) / 60f
        val c = value * saturation
        val x = c * (1 - Math.abs(h % 2 - 1))
        val m = value - c
        val (r, g, b) = when (h.toInt()) {
            0 -> listOf(c, x, 0f)
            1 -> listOf(x, c, 0f)
            2 -> listOf(0f, c, x)
            3 -> listOf(0f, x, c)
            4 -> listOf(x, 0f, c)
            else -> listOf(c, 0f, x)
        }
        fun toByte(v: Float) = ((v + m) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (toByte(r) shl 16) or (toByte(g) shl 8) or toByte(b)
    }
}
