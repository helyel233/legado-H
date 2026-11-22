package io.legado.app.help.config

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ThemeModeEInkSyncTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
        context.defaultSharedPreferences.edit(commit = true) { clear() }
        AppConfig.setThemeModeSelection("0")
    }

    @After
    fun tearDown() {
        AppConfig.setThemeModeSelection("0")
        context.defaultSharedPreferences.edit(commit = true) { clear() }
    }

    @Test
    fun `enabling eInk from studio updates display theme mode to 3 and can be exited from my settings`() {
        AppConfig.setThemeModeSelection("1")
        assertFalse(AppConfig.isEInkMode)
        assertEquals("1", AppConfig.resolveDisplayThemeMode())

        // 外观中心开启独立墨水屏开关
        AppConfig.setEInkModeEnabled(true)
        assertTrue(AppConfig.isEInkMode)
        assertTrue(context.getPrefBoolean(PreferKey.eInkMode, false))
        assertEquals("1", context.getPrefString(PreferKey.themeMode, "0"))
        assertEquals("3", AppConfig.resolveDisplayThemeMode())

        // 「我的 -> 外观与界面」切回亮色/暗色/跟随系统必须同时清掉 eInkMode
        AppConfig.setThemeModeSelection("1")
        assertFalse(AppConfig.isEInkMode)
        assertFalse(context.getPrefBoolean(PreferKey.eInkMode, false))
        assertEquals("1", context.getPrefString(PreferKey.themeMode, "0"))
        assertEquals("1", AppConfig.resolveDisplayThemeMode())

        AppConfig.setEInkModeEnabled(true)
        assertTrue(AppConfig.isEInkMode)
        AppConfig.setThemeModeSelection("2")
        assertFalse(AppConfig.isEInkMode)
        assertFalse(context.getPrefBoolean(PreferKey.eInkMode, false))
        assertEquals("2", AppConfig.themeMode)
        assertEquals("2", AppConfig.resolveDisplayThemeMode())
    }

    @Test
    fun `selecting eInk from my settings and turning off in studio restores previous non-eInk mode`() {
        AppConfig.setThemeModeSelection("2")
        assertEquals("2", AppConfig.resolveDisplayThemeMode())

        // 「我的」页点击 E-Ink(墨水屏)
        AppConfig.setThemeModeSelection("3")
        assertTrue(AppConfig.isEInkMode)
        assertTrue(context.getPrefBoolean(PreferKey.eInkMode, false))
        assertEquals("3", context.getPrefString(PreferKey.themeMode, "0"))
        assertEquals("3", AppConfig.resolveDisplayThemeMode())

        // 外观中心关闭独立墨水屏开关 -> 恢复此前非墨水屏模式 "2"
        AppConfig.setEInkModeEnabled(false)
        assertFalse(AppConfig.isEInkMode)
        assertFalse(context.getPrefBoolean(PreferKey.eInkMode, false))
        assertEquals("2", context.getPrefString(PreferKey.themeMode, "0"))
        assertEquals("2", AppConfig.resolveDisplayThemeMode())
    }

    @Test
    fun `studio orthogonal eInk toggle preserves underlying dark mode when closed in studio`() {
        AppConfig.setThemeModeSelection("2")
        AppConfig.setEInkModeEnabled(true)
        assertTrue(AppConfig.isEInkMode)
        assertEquals("2", AppConfig.themeMode)

        AppConfig.setEInkModeEnabled(false)
        assertFalse(AppConfig.isEInkMode)
        assertEquals("2", AppConfig.themeMode)
        assertEquals("2", AppConfig.resolveDisplayThemeMode())
    }
}
