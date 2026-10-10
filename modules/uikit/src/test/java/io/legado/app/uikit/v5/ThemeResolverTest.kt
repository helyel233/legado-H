package io.legado.app.uikit.v5

import io.legado.app.theme.pack.ThemePackageSpec
import io.legado.app.uikit.theme.BuiltinThemes
import io.legado.app.uikit.v5.resolve.BackgroundScene
import io.legado.app.uikit.v5.resolve.ThemeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P0-4 美术取值自检（手册 P0-4：给定种子色断言对比度达标）。
 */
class ThemeResolverTest {

    @Before
    fun reset() {
        ThemeResolver.applyTheme(BuiltinThemes.sky)
        ThemeResolver.applyWallpaperSeed(null)
        ThemeResolver.applyDark(true)
    }

    @Test
    fun `night mode is darker than day mode`() {
        ThemeResolver.applyDark(false)
        val day = ThemeResolver.colors().background
        ThemeResolver.applyDark(true)
        val night = ThemeResolver.colors().background
        assertTrue("夜间背景应比日间暗：day=$day night=$night", night.luminance() < day.luminance())
    }

    @Test
    fun `package override wins over seed derivation`() {
        ThemeResolver.applyDark(false)
        val before = ThemeResolver.colors().surface
        ThemeResolver.applyTheme(
            BuiltinThemes.sky.copy(
                id = "t_override", name = "override 测试", basedOn = "sky",
                override = mapOf("color.surface" to "#FFFF00FF"),
            )
        )
        assertEquals(0xFFFF00FF.toInt(), ThemeResolver.colors().surface.toArgbInt())
        ThemeResolver.applyTheme(BuiltinThemes.sky)
        assertEquals(before.toArgbInt(), ThemeResolver.colors().surface.toArgbInt())
    }

    @Test
    fun `body contrast reaches WCAG AA for every builtin theme in both modes`() {
        BuiltinThemes.all.forEach { theme ->
            ThemeResolver.applyTheme(theme)
            listOf(false, true).forEach { dark ->
                ThemeResolver.applyDark(dark)
                val ratio = ThemeResolver.bodyContrast()
                assertTrue(
                    "${theme.id}/dark=$dark 正文对比度不足：$ratio",
                    ratio >= 4.5,
                )
            }
        }
    }

    @Test
    fun `status colors stay semantic regardless of seed`() {
        ThemeResolver.applyTheme(BuiltinThemes.sky)
        val skySuccess = ThemeResolver.colors().success
        ThemeResolver.applyTheme(
            ThemePackageSpec(id = "t_hazel", name = "榛子棕测试", seed = "#8D6E63", darkStrategy = "tonal")
        )
        assertEquals("成功色不应跟随种子色变化", skySuccess.toArgbInt(), ThemeResolver.colors().success.toArgbInt())
    }

    @Test
    fun `wallpaper seed drives the dynamic theme`() {
        ThemeResolver.applyTheme(BuiltinThemes.dynamic)
        ThemeResolver.applyWallpaperSeed(null)
        val fallback = ThemeResolver.colors().primary
        ThemeResolver.applyWallpaperSeed(0xFFE91E63.toInt())
        assertNotEquals(fallback.toArgbInt(), ThemeResolver.colors().primary.toArgbInt())
    }

    @Test
    fun `night background falls back to the day image when absent`() {
        val spec = ThemePackageSpec(
            id = "t_img", name = "背景测试", seed = "#5B6ABF",
            images = io.legado.app.theme.pack.ThemeImages(background = "assets/bg.webp"),
        )
        ThemeResolver.applyTheme(spec)
        ThemeResolver.applyDark(true)
        assertEquals("assets/bg.webp", ThemeResolver.backgroundPath(BackgroundScene.MAIN))
    }

    @Test
    fun `invalid theme spec is rejected`() {
        try {
            ThemeResolver.applyTheme(ThemePackageSpec(id = "", name = "", seed = "not-a-color"))
            throw AssertionError("无效 spec 应当被拒")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `reader theme is produced without any layout values`() {
        ThemeResolver.applyDark(true)
        val reader = ThemeResolver.readerTheme()
        assertTrue(reader.background.toArgbInt() != 0)
        // 刻意不含形状/玻璃/动效/密度 —— 类型层面就不存在，此处只确认可安全取值
        assertEquals(null, reader.texturePath)
    }

    private fun androidx.compose.ui.graphics.Color.luminance(): Float =
        0.2126f * red + 0.7152f * green + 0.0722f * blue

    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int =
        ((alpha * 255).toInt() shl 24) or
            ((red * 255).toInt() shl 16) or
            ((green * 255).toInt() shl 8) or
            ((blue * 255).toInt())
}
