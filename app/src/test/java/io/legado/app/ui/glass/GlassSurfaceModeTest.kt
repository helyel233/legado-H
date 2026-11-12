package io.legado.app.ui.glass

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.FrameLayout
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.explore.resolvedExploreGlassMode
import io.legado.app.ui.widget.TitleBarGlassAction
import io.legado.app.ui.widget.TitleBarGlassLedger
import io.legado.app.ui.widget.restoreTitleBarSurface
import io.legado.app.ui.widget.titleBarGlassAction
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.putPrefString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * **降级 / 回退**链路上的真实偏好判定（`resolvedExploreGlassMode` → `GlassSurface` →
 * `TitleBar` 决策表），补 [TitleBarGlassRollbackTest] / [TitleBarAutoGlassTest] 的空白。
 *
 * 那两支测试传入的是**常量**档位判定结果，锁住的是决策表本身；本类把它们换成**真实读取的
 * 偏好**，锁的是「用户切到无玻璃档 / 开墨水屏之后，生产代码会得出 RELEASE 而不是别的」，
 * 也就是反馈里那条真机链路的判定侧：
 *
 * 1. 开背景图 + 玻璃档 → 顶栏铺玻璃；
 * 2. 进设置切「无玻璃」（或开墨水屏）→ 返回页面；
 * 3. 顶栏必须回到 init 时的**原背景实例**与原 elevation，而不是底栏色实底壳。
 *
 * 第 3 步的「不是实底壳」由第一组用例保证：[GlassSurface.wallpaperGlassEnabled] 在无玻璃档
 * 必须判否——若它判是，`applyWallpaperGlassDecision` 会去取 `panelBackground`，而无玻璃档
 * 那里返回的是**底栏色不透明壳**，顶栏就被贴上一块本不该出现的实底。
 *
 * 另一半是「页面内卡片」的双重判定：`pageGlassAllowed` 比 `wallpaperGlassEnabled` 多一条
 * 「宿主页面真的露出壁纸」的回溯，因此同样的玻璃档下，没有 `BaseActivity` 宿主的 View
 * （内容域 / 独立窗口场景）必须回退调用方原表面。两条判定混用会让卡片采到看不见的壁纸，
 * 或让顶栏永远判否——这正是本类用同一份偏好同时断言两支的原因。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class GlassSurfaceModeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
        clearGlassPrefs()
        AppConfig.isEInkMode = false
    }

    @After
    fun tearDown() {
        AppConfig.isEInkMode = false
        clearGlassPrefs()
    }

    // region 无玻璃档：顶栏必须判否（否则会拿到实底壳）

    @Test
    fun `solid mode disables wallpaper glass for surfaces that sit on the wallpaper`() {
        putMode(GlassMaterialPreset.SOLID)

        assertEquals(GlassMaterialPreset.SOLID, resolvedExploreGlassMode())
        assertFalse(
            "无玻璃档顶栏必须判否，否则会取到 panelBackground 的底栏色实底壳而不是原背景",
            GlassSurface.wallpaperGlassEnabled(context)
        )
    }

    /** 阳性对照：同一设备、同一页面，玻璃档下顶栏判定必须为是（否则自动 opt-in 永不起效）。 */
    @Test
    fun `glass modes keep wallpaper glass available for the title bar`() {
        for (mode in listOf(GlassMaterialPreset.GLASS, GlassMaterialPreset.FROSTED)) {
            putMode(mode)
            assertEquals(mode, resolvedExploreGlassMode())
            assertTrue("mode=$mode", GlassSurface.wallpaperGlassEnabled(context))
        }
    }

    /**
     * 无玻璃档下页面卡片必须逐值回退到调用方原表面（不是底栏色壳）。
     *
     * 这条覆盖书签行 / 缓存书卡 / 关于页卡 / 首页卡共用的
     * [GlassSurface.pagePanelBackground]：`fallback` 提供的就是改动前的实底 drawable。
     */
    @Test
    fun `solid mode returns the caller surface for page cards`() {
        putMode(GlassMaterialPreset.SOLID)
        val host = FrameLayout(context)
        val callerSurface = ColorDrawable(0xFF123456.toInt())

        assertFalse(GlassSurface.pageGlassAllowed(context))
        assertSame(
            "无玻璃档必须原样返回调用方实底，不能换成底栏色壳",
            callerSurface,
            GlassSurface.pagePanelBackground(host, 8f) { callerSurface }
        )
    }

    // endregion

    // region 页面内卡片的双重判定（宿主页面是否真的露出壁纸）

    /**
     * 玻璃档 + 没有 `BaseActivity` 宿主（内容域 / 独立窗口）：卡片仍要回退原表面。
     *
     * 与上一条的区别是「否」的原因不同（档位 vs 宿主），两者都必须落到同一个回退上，
     * 否则内容域页面会采到被自己盖住的壁纸。
     */
    @Test
    fun `glass mode still falls back when the host page does not expose wallpaper`() {
        putMode(GlassMaterialPreset.FROSTED)
        val host = FrameLayout(context)
        val callerSurface = ColorDrawable(0xFF123456.toInt())

        assertFalse(
            "无 BaseActivity 宿主（无法证明背后是壁纸）时页面卡片必须判否",
            GlassSurface.pageGlassAllowed(context)
        )
        assertSame(
            callerSurface,
            GlassSurface.pagePanelBackground(host, 8f) { callerSurface }
        )
        assertTrue(
            "同一份偏好下贴壁纸表面的判定仍为是：两条判定的差别只在宿主回溯，"
                + "混用会让顶栏永远判否或让卡片采到看不见的壁纸",
            GlassSurface.wallpaperGlassEnabled(context)
        )
    }

    /** 墨水屏：即使是玻璃档，两条判定都必须判否（顶栏回到原背景，卡片回到原实底）。 */
    @Test
    fun `e ink mode disables both judgements even in a glass mode`() {
        putMode(GlassMaterialPreset.FROSTED)
        AppConfig.isEInkMode = true

        assertFalse(GlassSurface.wallpaperGlassEnabled(context))
        assertFalse(GlassSurface.pageGlassAllowed(context))
    }

    // endregion

    // region 配置签名：REBUILD / KEEP 的触发源

    @Test
    fun `config signature is stable for an unchanged setting and changes with the mode`() {
        putMode(GlassMaterialPreset.FROSTED)
        val frosted = GlassSurface.configSignature(context)
        assertEquals("同档重复读取必须稳定，否则 onResume 每次都会重建 drawable",
            frosted, GlassSurface.configSignature(context))

        putMode(GlassMaterialPreset.SOLID)
        assertNotEquals("切档必须改变签名，否则顶栏 / 卡片会停在旧材质上",
            frosted, GlassSurface.configSignature(context))
    }

    // endregion

    // region 端到端判定：真实偏好 + 生产决策表 + 生产回退

    /**
     * 切到无玻璃档后，生产决策表给出 RELEASE，且回退真的把原背景实例铺回去。
     *
     * 这是反馈里那条真机链路的**判定侧**（判定表 + 回退实现 + 真实偏好三件套连起来跑）：
     * 玻璃档下顶栏铺过玻璃 → 用户切「无玻璃」→ 回页面时 `BaseActivity.onResume` 重判 →
     * 必须 RELEASE 并恢复 init 时捕获的那张 drawable 与 elevation，而不是留着旧玻璃。
     */
    @Test
    fun `switching to solid releases the title bar back to its original surface`() {
        putMode(GlassMaterialPreset.FROSTED)
        val bar = FrameLayout(context)
        val original = ColorDrawable(Color.TRANSPARENT)
        bar.background = original
        bar.elevation = 0.1f

        val ledger = TitleBarGlassLedger()
        ledger.captureOriginalSurface(original, bar.elevation)
        // 玻璃档下铺过一档材质（签名 = 当次配置）
        ledger.markApplied(GlassSurface.configSignature(context))
        bar.background = ColorDrawable(0xFF203040.toInt())

        putMode(GlassMaterialPreset.SOLID)
        val action = titleBarGlassAction(
            enabled = GlassSurface.wallpaperGlassEnabled(context),
            signature = GlassSurface.configSignature(context),
            appliedSignature = ledger.appliedSignature
        )

        assertEquals("切无玻璃档后必须回退，不能只 return false 把旧 drawable 留在身上",
            TitleBarGlassAction.RELEASE, action)
        assertTrue(restoreTitleBarSurface(bar, ledger))
        assertSame("回退必须铺回 init 捕获的原背景实例", original, bar.background)
        assertEquals("elevation 要逐值还原", 0.1f, bar.elevation, 0f)
    }

    /**
     * 同档下反复 `onResume`：判定必须是 KEEP（不重建、不重采壁纸）。
     *
     * 与上一条成对：前者锁「切档必须换」，这条锁「没切不许换」。两条都要成立，
     * `onResume` 的无条件重判才是零开销的。
     */
    @Test
    fun `unchanged setting keeps the applied title bar glass`() {
        putMode(GlassMaterialPreset.FROSTED)
        val applied = GlassSurface.configSignature(context)

        assertEquals(
            TitleBarGlassAction.KEEP,
            titleBarGlassAction(
                enabled = GlassSurface.wallpaperGlassEnabled(context),
                signature = GlassSurface.configSignature(context),
                appliedSignature = applied
            )
        )
    }

    /** 从未铺过玻璃的页面在判否时必须完全不动（顶栏本来透明，不能被「回退」重铺一次）。 */
    @Test
    fun `a page that never got glass stays untouched when judgement is negative`() {
        putMode(GlassMaterialPreset.SOLID)
        val bar = FrameLayout(context)
        val untouched = ColorDrawable(Color.TRANSPARENT)
        bar.background = untouched
        bar.elevation = 0.1f
        val ledger = TitleBarGlassLedger()
        ledger.captureOriginalSurface(untouched, bar.elevation)

        assertEquals(
            TitleBarGlassAction.SKIP,
            titleBarGlassAction(
                enabled = GlassSurface.wallpaperGlassEnabled(context),
                signature = GlassSurface.configSignature(context),
                appliedSignature = ledger.appliedSignature
            )
        )
        assertFalse(restoreTitleBarSurface(bar, ledger))
        assertSame(untouched, bar.background)
    }

    // endregion

    // region 辅助

    private fun putMode(mode: String) {
        context.putPrefString(PreferKey.exploreGlassEffectMode, mode)
    }

    /** 档位键与旧总开关都要清：任一残留都会让下一条用例读到别的档位。 */
    private fun clearGlassPrefs() {
        context.defaultSharedPreferences.edit()
            .remove(PreferKey.exploreGlassEffectMode)
            .remove(PreferKey.exploreGlassEnabled)
            .apply()
    }

    // endregion
}
