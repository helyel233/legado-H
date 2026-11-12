package io.legado.app.ui.glass

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.widget.FrameLayout
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.explore.GLASS_POSITION_TRACKING_TAG_KEY
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.putPrefString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

/**
 * 第一轮 A/B 修复的**双向**回归锁：回退必须解绑，成功铺玻璃时必须不解绑。
 *
 * [GlassSurfaceTrackingReleaseTest] 只在「无壁纸 / 判否」这一支上观察解绑（Robolectric 下
 * `hasUsableBgImage` 恒为 false），因此它不能回答「成功拿到玻璃时会不会把刚挂上的新 tracker
 * 又误 detach 掉」——而后者正是这次修复最容易引入的新缺陷：
 * [GlassSurface.wallpaperDrawable] 的判空解绑若写错位置（例如先解绑再取 drawable，或把
 * 「没拿到 = 该解绑」理解成「每次调用都解绑」），玻璃会照常铺上却失去位置跟随，真机表现
 * 为「滚动后玻璃里的壁纸不跟着动」。
 *
 * 这里绕过 `pageGlassAllowed` 的宿主回溯，直接驱动 drawable 入口（`panelBackground`），
 * 用**同一个 spy View** 观察三件事：
 * 1. 成功取到玻璃时：tag 上出现 tracker，且没有任何解绑写入；
 * 2. 同一 host 随后切到 solid：`pagePanelBackground` 判否回退，**刚刚挂上的那个 tracker**
 *    被摘掉（tag 从非空变 null）——这是「解绑确实作用于已挂绑定」的可观测证据，
 *    而不只是断言某个生产函数被调用；
 * 3. 非壁纸域调用：拿不到 drawable，同时解绑（判空分支的正向覆盖）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class GlassSurfaceTrackingPathTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
        context.putPrefString(PreferKey.exploreGlassEffectMode, GlassMaterialPreset.FROSTED)
    }

    @After
    fun tearDown() {
        context.defaultSharedPreferences.edit()
            .remove(PreferKey.exploreGlassEffectMode)
            .remove(PreferKey.exploreGlassEnabled)
            .apply()
    }

    /**
     * 成功铺玻璃：位置上必须**最终**挂着 tracker（不能被自己清掉）。
     *
     * 注意这里断言的是「最后一次写入」，不是「没有任何清空写入」：
     * `GlassPositionTracker.attach` 按设计先 `detach` 再挂新 binding（「行复用会反复设置
     * 新背景，先把上一次挂接的 tracker 解绑」），所以全新 View 上也会出现一次 null 写入。
     * 真正要锁的是「铺完玻璃之后那格 tag 里有 tracker」——写错判空解绑的版本会在这里变成
     * null（先 attach 再被 release 清掉）。
     *
     * 若某个 Robolectric 版本连 drawable 都建不出来（返回 null），用 [assumeNotNull] 显式
     * 跳过并留下原因，而不是让断言变成假绿。
     */
    @Test
    fun `glass drawable leaves tracking attached after a successful apply`() {
        val host = TrackingSpyView(context)

        val glass = GlassSurface.panelBackground(host, GlassSamplingDomain.WALLPAPER, 8f)

        assumeNotNull("Robolectric 未能构建玻璃 drawable，本用例不适用", glass)
        assertNotNull(
            "成功铺玻璃必须挂上位置跟踪，否则滚动后玻璃里的壁纸不会跟随",
            host.getTag(GLASS_POSITION_TRACKING_TAG_KEY)
        )
        assertNotNull(
            "最后一次写 tracking 槽必须是挂上 tracker，而不是被 own 的回退逻辑清成 null："
                + "${host.writesFor(GLASS_POSITION_TRACKING_TAG_KEY)}",
            host.writesFor(GLASS_POSITION_TRACKING_TAG_KEY).lastOrNull()
        )
    }

    /**
     * 端到端：先成功铺玻璃 → 再切 solid → 回退时必须摘掉**刚才那个** tracker。
     *
     * 这是「解绑作用于已挂绑定」的可观测证据：tag 在铺玻璃后非 null，回退后为 null。
     */
    @Test
    fun `switching to solid releases the tracker that was just attached`() {
        val host = TrackingSpyView(context)
        val glass = GlassSurface.panelBackground(host, GlassSamplingDomain.WALLPAPER, 8f)
        assumeNotNull("Robolectric 未能构建玻璃 drawable，本用例不适用", glass)
        assertNotNull(host.getTag(GLASS_POSITION_TRACKING_TAG_KEY))
        host.clearedKeyedTags.clear()

        context.putPrefString(PreferKey.exploreGlassEffectMode, GlassMaterialPreset.SOLID)
        val fallback = ColorDrawable(0xFF123456.toInt())
        val surface = GlassSurface.pagePanelBackground(host, 8f) { fallback }

        assertSame("solid 档必须回退到调用方原表面", fallback, surface)
        assertNull(
            "回退必须摘掉刚刚挂上的位置跟踪（否则旧 drawable 仍被逐帧驱动）",
            host.getTag(GLASS_POSITION_TRACKING_TAG_KEY)
        )
        assertTrue(
            "解绑只能落在位置跟踪这一格 keyed tag 上，不得波及业务 tag",
            host.clearedKeyedTags.all { it == GLASS_POSITION_TRACKING_TAG_KEY }
        )
    }

    /** 非壁纸域（面板 / 内容）拿不到 drawable：判空分支必须解绑，不留定位残留。 */
    @Test
    fun `non wallpaper domain returns null and releases tracking`() {
        val host = TrackingSpyView(context)
        host.setTag(GLASS_POSITION_TRACKING_TAG_KEY, Any())

        val drawable: Drawable? =
            GlassSurface.panelBackground(host, GlassSamplingDomain.PANEL, 8f)

        assertNull("面板域不得从壁纸入口取图", drawable)
        assertNull(
            "拿不到玻璃时必须解绑，避免上一份跟踪继续驱动已被换下的 drawable",
            host.getTag(GLASS_POSITION_TRACKING_TAG_KEY)
        )
    }

    /** 无可用背景图时 helper 走原表面分支，且同签名重复调用零副作用。 */
    @Test
    fun `helper without wallpaper is idempotent on the tracking slot`() {
        val host = TrackingSpyView(context)
        val fallback = ColorDrawable(Color.GRAY)

        assertFalse(ThemeConfig.hasUsableBgImage(context))
        host.applyWallpaperGlassBackground(cornerRadiusPx = 8f) { fallback }
        val writesAfterFirst = host.clearedKeyedTags.size
        host.applyWallpaperGlassBackground(cornerRadiusPx = 8f) { fallback }

        assertEquals("同签名重复调用必须零副作用", writesAfterFirst, host.clearedKeyedTags.size)
    }

    /** 受控 View：记录 keyed tag 的写入序列，用来观察解绑与重新挂接的先后顺序。 */
    private class TrackingSpyView(context: Context) : FrameLayout(context) {

        /** 所有「keyed tag 被写成 null」的 key（解绑的可观测副作用），按发生顺序记录。 */
        val clearedKeyedTags = mutableListOf<Int>()

        private val writes = mutableMapOf<Int, MutableList<Any?>>()

        fun writesFor(key: Int): List<Any?> = writes[key].orEmpty()

        override fun setTag(key: Int, tag: Any?) {
            if (tag == null) clearedKeyedTags += key
            writes.getOrPut(key) { mutableListOf() } += tag
            super.setTag(key, tag)
        }
    }
}
