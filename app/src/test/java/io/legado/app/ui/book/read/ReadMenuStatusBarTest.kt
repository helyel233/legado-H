package io.legado.app.ui.book.read

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadMenuStatusBarTest {

    @Test
    fun floatingNormalBookFollowsPageStatusIconDarkOverThemeStatusBar() {
        // 浅色 App 主题 + 深色阅读背景：悬浮态直接透出正文，应返回 false（浅色图标）
        assertFalse(
            resolveReadMenuStatusBarLight(
                topBarFloating = true,
                usesPageTemplate = false,
                darkSkyTemplate = false,
                pageStatusIconDark = false,
                readBarStyleFollowPage = false,
                isColorBgType = true,
                useBgMeanColor = false,
                bgMeanColorIsLight = false,
                themeStatusBarIsLight = true
            )
        )
        // 深色 App 主题 + 浅色阅读背景：悬浮态应返回 true（深色图标）
        assertTrue(
            resolveReadMenuStatusBarLight(
                topBarFloating = true,
                usesPageTemplate = false,
                darkSkyTemplate = false,
                pageStatusIconDark = true,
                readBarStyleFollowPage = false,
                isColorBgType = true,
                useBgMeanColor = false,
                bgMeanColorIsLight = true,
                themeStatusBarIsLight = false
            )
        )
    }

    @Test
    fun floatingEpubTemplateRespectsDarkSkyTemplateAndPageIconDark() {
        // 星空默认模板下强制浅色图标（false）
        assertFalse(
            resolveReadMenuStatusBarLight(
                topBarFloating = true,
                usesPageTemplate = true,
                darkSkyTemplate = true,
                pageStatusIconDark = true,
                readBarStyleFollowPage = false,
                isColorBgType = false,
                useBgMeanColor = false,
                bgMeanColorIsLight = true,
                themeStatusBarIsLight = true
            )
        )
        // 非星空模板时跟随 pageStatusIconDark
        assertTrue(
            resolveReadMenuStatusBarLight(
                topBarFloating = true,
                usesPageTemplate = true,
                darkSkyTemplate = false,
                pageStatusIconDark = true,
                readBarStyleFollowPage = false,
                isColorBgType = false,
                useBgMeanColor = false,
                bgMeanColorIsLight = false,
                themeStatusBarIsLight = false
            )
        )
        assertFalse(
            resolveReadMenuStatusBarLight(
                topBarFloating = true,
                usesPageTemplate = true,
                darkSkyTemplate = false,
                pageStatusIconDark = false,
                readBarStyleFollowPage = false,
                isColorBgType = false,
                useBgMeanColor = false,
                bgMeanColorIsLight = true,
                themeStatusBarIsLight = true
            )
        )
    }

    @Test
    fun nonFloatingFollowsPageOrThemeStatusBarColor() {
        // 跟随阅读页纯色背景：取 bgMeanColorIsLight
        assertFalse(
            resolveReadMenuStatusBarLight(
                topBarFloating = false,
                usesPageTemplate = false,
                darkSkyTemplate = false,
                pageStatusIconDark = true,
                readBarStyleFollowPage = true,
                isColorBgType = true,
                useBgMeanColor = false,
                bgMeanColorIsLight = false,
                themeStatusBarIsLight = true
            )
        )
        // useBgMeanColor = true 时也取 bgMeanColorIsLight
        assertTrue(
            resolveReadMenuStatusBarLight(
                topBarFloating = false,
                usesPageTemplate = false,
                darkSkyTemplate = false,
                pageStatusIconDark = false,
                readBarStyleFollowPage = false,
                isColorBgType = false,
                useBgMeanColor = true,
                bgMeanColorIsLight = true,
                themeStatusBarIsLight = false
            )
        )
        // 常规贴边且不跟随正文时取 themeStatusBarIsLight
        assertTrue(
            resolveReadMenuStatusBarLight(
                topBarFloating = false,
                usesPageTemplate = false,
                darkSkyTemplate = false,
                pageStatusIconDark = false,
                readBarStyleFollowPage = false,
                isColorBgType = true,
                useBgMeanColor = false,
                bgMeanColorIsLight = false,
                themeStatusBarIsLight = true
            )
        )
    }
}
