package io.legado.app.ui.glass

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.FrameLayout
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.main.explore.GLASS_POSITION_TRACKING_TAG_KEY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

/**
 * 「回退时必须解绑位置跟踪」的回归锁（QA 第一轮 A / B 项）。
 *
 * 缺陷形态：`GlassSurface.pagePanelBackground` 与 `applyWallpaperGlassBackground` 的
 * 回退分支只把背景换回实底，没有解绑上一次 attach 的 `TrackerBinding`。宿主 View
 * （关于页卡、阅读记录面板、首页榜单/错误卡、订阅文章行）是常驻的，切 solid 或关背景图后
 * 旧跟踪仍挂在 layout / attach 回调与最近可滚动祖先的滚动监听上，继续逐帧给一份
 * 已经不在绘制的 drawable 重采样。
 *
 * **可观测性**：解绑的唯一外在副作用是往位置跟踪那一格 keyed tag 写 null
 * （见 [io.legado.app.ui.main.explore.GlassPositionTracker.detach]，它复用既有的
 * View-detach 路径）。这里用一个记录 keyed tag 写入的受控 View 观察这件事，
 * 不反射私有 TAG、也不伪造 tracker。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class GlassSurfaceTrackingReleaseTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
    }

    @Test
    fun `precondition without background image page glass is not allowed`() {
        // 后面几条断言依赖「页面玻璃不可用 → 走回退分支」；环境变化时让它显式失败
        assertFalse(ThemeConfig.hasUsableBgImage(context))
        assertFalse(GlassSurface.pageGlassAllowed(context))
    }

    @Test
    fun `page panel fallback releases the previous position tracking`() {
        val host = TrackingSpyView(context)
        val fallback = ColorDrawable(0xFF123456.toInt())

        val result = GlassSurface.pagePanelBackground(host, cornerRadiusPx = 8f) { fallback }

        assertSame("回退分支必须原样返回调用方的实底", fallback, result)
        assertEquals(
            "判否回退时必须解绑上一次的位置跟踪，且只碰那一格 keyed tag",
            listOf(GLASS_POSITION_TRACKING_TAG_KEY),
            host.clearedKeyedTags
        )
    }

    @Test
    fun `page panel fallback on a clean view is a harmless no-op write`() {
        val host = TrackingSpyView(context)

        GlassSurface.pagePanelBackground(host, cornerRadiusPx = 0f) { ColorDrawable(Color.GRAY) }

        // 没挂过 tracker 的 View：解绑只会写一次 null（幂等 no-op），不会抛错
        assertEquals(listOf(GLASS_POSITION_TRACKING_TAG_KEY), host.clearedKeyedTags)
        assertNull(host.getTag(GLASS_POSITION_TRACKING_TAG_KEY))
    }

    @Test
    fun `releasing twice stays idempotent and only touches the tracking slot`() {
        val host = TrackingSpyView(context)

        GlassSurface.releaseBackgroundTracking(host)
        GlassSurface.releaseBackgroundTracking(host)

        assertEquals(
            "重复解绑不得写到别的 keyed tag 上（否则会抹掉调用方的业务 tag）",
            setOf(GLASS_POSITION_TRACKING_TAG_KEY),
            host.clearedKeyedTags.toSet()
        )
    }

    @Test
    fun `view glass helper without wallpaper keeps the fallback and releases tracking`() {
        val host = TrackingSpyView(context)
        val fallback = ColorDrawable(0xFF654321.toInt())

        host.applyWallpaperGlassBackground(cornerRadiusPx = 12f) { fallback }

        assertSame("无壁纸时必须原样铺回调用方的实底", fallback, host.background)
        assertTrue(
            "关背景图路径也要解绑旧跟踪（QA 第一轮 A 项）",
            host.clearedKeyedTags.contains(GLASS_POSITION_TRACKING_TAG_KEY)
        )
    }

    @Test
    fun `repeated helper call with the same signature does not re-release`() {
        val host = TrackingSpyView(context)
        host.applyWallpaperGlassBackground(cornerRadiusPx = 0f) { ColorDrawable(Color.GRAY) }
        val firstWrites = host.clearedKeyedTags.size

        // 滚动复用 / 重新挂载：签名未变直接返回，不应再产生解绑副作用
        host.applyWallpaperGlassBackground(cornerRadiusPx = 0f) { ColorDrawable(Color.GRAY) }

        assertEquals(firstWrites, host.clearedKeyedTags.size)
    }

    @Test
    fun `tracking tag key is a legal keyed tag key`() {
        // View.setTag(int, Object) 要求 key 的应用位 >= 2，否则真机抛 IllegalArgumentException
        assertTrue(GLASS_POSITION_TRACKING_TAG_KEY ushr 24 >= 2)
        assertNull(FrameLayout(context).getTag(GLASS_POSITION_TRACKING_TAG_KEY))
    }

    /** 受控 View：记录所有「keyed tag 被写成 null」的写入，用来观察解绑是否发生。 */
    private class TrackingSpyView(context: Context) : FrameLayout(context) {

        val clearedKeyedTags = mutableListOf<Int>()

        override fun setTag(key: Int, tag: Any?) {
            if (tag == null) clearedKeyedTags += key
            super.setTag(key, tag)
        }
    }
}
