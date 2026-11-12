package io.legado.app.ui.glass

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.FrameLayout
import io.legado.app.help.config.ThemeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * [applyWallpaperGlassBackground] 的「作用域与幂等」回归锁。
 *
 * 这段逻辑被订阅文章行、水瀑布文章卡、首页书卡 / 榜单卡 / 页卡共用，它的三种错法
 * 都很难在真机上一眼看出：
 *
 * - **无壁纸时铺了玻璃实底壳**：玻璃入口在无玻璃档返回的是**底栏色不透明壳**，
 *   直接拿来铺页面卡片会从「主题卡片」跳成「底栏色」；
 * - **命中玻璃后没有去重**：滚动复用会反复走同一条路径，每帧重建 drawable
 *   等于每帧重新采样壁纸；
 * - **签名没带「壁纸是否可用」**：开启背景图后已复用的 View 会停在前一档材质上。
 *
 * 环境说明：Robolectric 下 `ThemeConfig.hasUsableBgImage` 恒为 false（没有配置背景图），
 * 因此这里锁的是「无壁纸 → 原表面」这一支；玻璃 drawable 本身需要真实 BaseActivity 宿主
 * 与壁纸位图，属于真机验证项。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class GlassViewBackgroundTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
    }

    @Test
    fun `precondition without background image the helper takes the plain branch`() {
        // 后面几条断言依赖「无背景图」这一支；环境变化时让它显式失败，而不是静默换分支
        assertFalse(ThemeConfig.hasUsableBgImage(context))
    }

    @Test
    fun `without background image the fallback drawable is applied as is`() {
        val view = FrameLayout(context)
        val fallback = ColorDrawable(0xFF123456.toInt())

        view.applyWallpaperGlassBackground(cornerRadiusPx = 8f) { fallback }

        assertSame("无壁纸时必须原样铺回调用方的实底 drawable", fallback, view.background)
        assertEquals(
            "无壁纸档的签名要落在默认 tag 上",
            NO_WALLPAPER_SIGNATURE,
            view.getTag(GLASS_SIGNATURE_TAG)
        )
    }

    @Test
    fun `transparent fallback stays transparent without wallpaper`() {
        val view = FrameLayout(context)

        view.applyWallpaperGlassBackground(cornerRadiusPx = 0f) { null }

        assertNull("原本透明的行不能被换成不透明实底壳", view.background)
        assertNotNull("签名仍要落 tag，避免重复判定", view.getTag(GLASS_SIGNATURE_TAG))
    }

    @Test
    fun `repeated application keeps the same surface instead of rebuilding it`() {
        val view = FrameLayout(context)
        view.applyWallpaperGlassBackground(cornerRadiusPx = 8f) {
            ColorDrawable(Color.GRAY)
        }
        val firstBackground = view.background
        val firstTag = view.getTag(GLASS_SIGNATURE_TAG)

        // 滚动复用会反复走到 onViewAttachedToWindow
        view.applyWallpaperGlassBackground(cornerRadiusPx = 8f) {
            ColorDrawable(Color.GRAY)
        }
        view.applyWallpaperGlassBackground(cornerRadiusPx = 8f) {
            ColorDrawable(Color.GRAY)
        }

        assertSame("签名未变时不得重建背景", firstBackground, view.background)
        assertSame("签名未变时不得改写 tag", firstTag, view.getTag(GLASS_SIGNATURE_TAG))
    }

    @Test
    fun `call sites with their own tag key do not overwrite each other`() {
        val view = FrameLayout(context)
        val ownKey = 0x5F_41_42_43

        view.applyWallpaperGlassBackground(
            cornerRadiusPx = 0f,
            tagKey = ownKey
        ) { ColorDrawable(Color.GRAY) }

        assertNotNull("调用方自有 key 上要有签名", view.getTag(ownKey))
        assertNull(
            "自有 key 不能写到默认 key 上，否则同一 View 的其它玻璃表面会被误判成已铺过",
            view.getTag(GLASS_SIGNATURE_TAG)
        )
    }

    @Test
    fun `default signature tag key is a legal keyed tag key`() {
        // View.setTag(int, Object) 要求 key 的应用位 >= 2，否则真机抛 IllegalArgumentException
        // （Robolectric 不校验该契约，所以这里按契约断言）
        assertTrue(GLASS_SIGNATURE_TAG ushr 24 >= 2)
        assertNull(FrameLayout(context).getTag(GLASS_SIGNATURE_TAG))
    }
}
