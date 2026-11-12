package io.legado.app.ui.widget

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.FrameLayout
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
 * 顶栏玻璃**降级回退**的回归锁（对应真机链路：开背景图 + 玻璃档 → 进设置切 solid /
 * 关背景图 / 开墨水屏 → 返回页面）。
 *
 * 上一版的缺陷是「判否只 `return false`」：`TitleBar` 是同一实例，旧玻璃 drawable 与
 * 旧签名都留在身上，页面永远停在玻璃上，违反 solid / 降级逐值回退。修复把这段拆成三个
 * 可单测的部分，本类逐个锁住：
 *
 * - [titleBarGlassAction]：五态决策表（该铺 / 保持 / 重建 / 回退 / 不动）；
 * - [TitleBarGlassLedger]：原始外观快照与「已铺」标记（回退只发生一次）；
 * - [restoreTitleBarSurface]：真正把背景 / elevation 铺回去并解绑位置跟踪的那段代码。
 *
 * 环境说明：Robolectric 下 `ThemeConfig.hasUsableBgImage` 恒为 false，`TitleBar` 本身的
 * 「开局铺玻璃」需要真实壁纸位图，因此这里直接驱动生产代码里的决策与回退函数，
 * 用 `FrameLayout` 当宿主验证回退**确实把原背景铺回去了**（而不是只清了个标记）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class TitleBarGlassRollbackTest {

    private lateinit var host: FrameLayout
    private lateinit var ledger: TitleBarGlassLedger

    /** 模拟 init 里的透明档顶栏：透明 ColorDrawable + 半透明档的 0.1f elevation。 */
    private val originalBackground = ColorDrawable(Color.TRANSPARENT)
    private val originalElevation = 0.1f

    /** 模拟一档玻璃：与「原背景」明显不同的不透明实例，便于断言被换掉 / 换回。 */
    private val glassBackground = ColorDrawable(0xFF203040.toInt())

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
        host = FrameLayout(RuntimeEnvironment.getApplication())
        ledger = TitleBarGlassLedger()
        ledger.captureOriginalSurface(originalBackground, originalElevation)
    }

    // ---------- 决策表 ----------

    @Test
    fun `enabled with no applied signature means apply`() {
        assertEquals(
            TitleBarGlassAction.APPLY,
            titleBarGlassAction(enabled = true, signature = "liquid", appliedSignature = null)
        )
    }

    @Test
    fun `enabled with same signature keeps the existing drawable`() {
        assertEquals(
            "同签名重复调用必须保持不动（onResume 每次都会走到这里）",
            TitleBarGlassAction.KEEP,
            titleBarGlassAction(enabled = true, signature = "liquid", appliedSignature = "liquid")
        )
    }

    @Test
    fun `enabled with changed signature rebuilds`() {
        assertEquals(
            "档位 / 强度 / 描边色 / 夜间模式变化后要换一份材质",
            TitleBarGlassAction.REBUILD,
            titleBarGlassAction(enabled = true, signature = "liquid", appliedSignature = "solid")
        )
    }

    @Test
    fun `disabled after glass was applied releases it`() {
        assertEquals(
            "判否且铺过 → 必须回退（这正是上一版缺的分支）",
            TitleBarGlassAction.RELEASE,
            titleBarGlassAction(enabled = false, signature = "liquid", appliedSignature = "liquid")
        )
    }

    @Test
    fun `disabled without ever applying changes nothing`() {
        assertEquals(
            "判否且没铺过 → 不能动原背景，否则本该透明的顶栏会被重铺",
            TitleBarGlassAction.SKIP,
            titleBarGlassAction(enabled = false, signature = "liquid", appliedSignature = null)
        )
    }

    @Test
    fun `signature is irrelevant once the decision is to release`() {
        // 判否时不能因为「签名恰好一致」而走 KEEP，否则旧的「同签名提前 return」bug 复现
        assertEquals(
            TitleBarGlassAction.RELEASE,
            titleBarGlassAction(enabled = false, signature = "same", appliedSignature = "same")
        )
    }

    @Test
    fun `auto opt in yields to a page that took over the title bar`() {
        // WebView 阅读页（opaque = true）用受控入口自己铺了玻璃；BaseActivity.onResume 的
        // 自动判定会认为「不该铺」。自动路径必须整条让位，否则每次回前台都会把页面铺的
        // 玻璃撤掉再重铺一遍（撤 → 铺 → 撤 …），既闪又白付采样。
        assertEquals(
            TitleBarGlassAction.SKIP,
            titleBarGlassAction(
                enabled = false,
                signature = "liquid",
                appliedSignature = "liquid",
                yieldToPage = true
            )
        )
        assertEquals(
            TitleBarGlassAction.SKIP,
            titleBarGlassAction(
                enabled = false,
                signature = "liquid",
                appliedSignature = null,
                yieldToPage = true
            )
        )
    }

    @Test
    fun `controlled page still releases its own glass when it decides otherwise`() {
        // 页面接管后判定翻否（切 solid / 关背景图 / 开墨水屏）：回退必须真的发生，
        // 让位规则只作用于**自动**路径，不能把页面的回退也一起挡掉。
        assertEquals(
            TitleBarGlassAction.RELEASE,
            titleBarGlassAction(
                enabled = false,
                signature = "liquid",
                appliedSignature = "liquid",
                yieldToPage = false
            )
        )
    }

    // ---------- 账本 ----------

    @Test
    fun `ledger captures the first surface only once`() {
        // init 只调一次；但二次捕获若覆盖快照，回退就会把「第一次铺的玻璃」当成原背景
        ledger.captureOriginalSurface(ColorDrawable(Color.RED), 8f)
        assertSame(originalBackground, ledger.originalBackground)
        assertEquals(originalElevation, ledger.originalElevation, 0f)
        assertTrue(ledger.originalSurfaceCaptured)
    }

    @Test
    fun `consume applied reports release exactly once`() {
        ledger.markApplied("liquid")
        assertTrue("第一次判否必须真的回退", ledger.consumeApplied())
        assertFalse("回退后再判否是空操作（onResume 每次都会走）", ledger.consumeApplied())
        assertNull(ledger.appliedSignature)
    }

    @Test
    fun `consume applied on a never applied ledger is a no-op`() {
        assertFalse(ledger.consumeApplied())
        assertNull(ledger.appliedSignature)
    }

    // ---------- View 级回退 ----------

    @Test
    fun `release puts the original background and elevation back`() {
        // 不透明档顶栏的原形态：主色实底 + 有阴影。这里单独一份账本，避免复用 setUp 里
        // 已经捕获过的透明档快照（快照只认第一次，见上一条用例）。
        val opaqueLedger = TitleBarGlassLedger()
        opaqueLedger.captureOriginalSurface(ColorDrawable(0xFF00FF00.toInt()), 4f)
        opaqueLedger.markApplied("liquid")
        host.background = glassBackground
        // 铺玻璃后 applyWallpaperGlass 会把 elevation 压到 0.1f（避免横屏文字变色）
        host.elevation = 0.1f

        val released = restoreTitleBarSurface(host, opaqueLedger)

        assertTrue("铺过玻璃时回退必须真的动手", released)
        assertSame(
            "必须铺回 init 捕获的那个 drawable 实例，而不是新造一个「看起来像」的",
            opaqueLedger.originalBackground,
            host.background
        )
        assertEquals("elevation 要逐值还原，否则顶栏阴影永久丢失", 4f, host.elevation, 0f)
        assertNull("回退后不得再认为已铺玻璃", opaqueLedger.appliedSignature)
    }

    @Test
    fun `release restores the transparent background of a transparent title bar`() {
        host.background = glassBackground
        host.elevation = 0.1f
        ledger.markApplied("liquid")

        restoreTitleBarSurface(host, ledger)

        assertSame("透明档顶栏回退后应回到透明 ColorDrawable", originalBackground, host.background)
        assertEquals(originalElevation, host.elevation, 0f)
    }

    @Test
    fun `release twice keeps the first restored surface`() {
        host.background = glassBackground
        ledger.markApplied("liquid")
        assertTrue(restoreTitleBarSurface(host, ledger))
        val restored = host.background

        // onResume 每次都会判否，第二次必须是零工作量
        assertFalse(restoreTitleBarSurface(host, ledger))
        assertSame(restored, host.background)
    }

    @Test
    fun `release without applied glass never touches the view`() {
        host.background = originalBackground
        val untouched = host.background

        assertFalse(restoreTitleBarSurface(host, ledger))

        assertSame("没铺过玻璃的顶栏不能被动过", untouched, host.background)
    }

    @Test
    fun `auto opt in followed by manual opt in applies only once`() {
        // BaseActivity.onCreate 的自动 opt-in 与页面 onActivityCreated 里的手写
        // applyWallpaperGlass() 会连着调两次；第二次必须走 KEEP，不重建 drawable
        assertEquals(
            TitleBarGlassAction.APPLY,
            titleBarGlassAction(true, "liquid", ledger.appliedSignature)
        )
        ledger.markApplied("liquid")
        assertEquals(
            TitleBarGlassAction.KEEP,
            titleBarGlassAction(true, "liquid", ledger.appliedSignature)
        )
    }

    @Test
    fun `re enabled after a release applies again`() {
        ledger.markApplied("liquid")
        host.background = glassBackground
        assertTrue(restoreTitleBarSurface(host, ledger))

        // 用户又把背景图打开 / 切回玻璃档：签名没有残留，必须重新铺
        assertEquals(
            TitleBarGlassAction.APPLY,
            titleBarGlassAction(true, "liquid", ledger.appliedSignature)
        )
    }

    @Test
    fun `release detaches position tracking without clobbering foreign tags`() {
        // 位置跟踪的 tag key 属于管线内部实现（GlassPositionTracker.TAG_KEY）。这里从行为侧
        // 锁两件事：回退在「没有挂过 tracker 的 View」上是无异常 no-op；解绑只清自己那一格
        // keyed tag，不会顺手抹掉调用方写在别的 key 上的数据。
        val foreignKey = 0x5F_41_42_43
        val foreignValue = Any()
        host.setTag(foreignKey, foreignValue)

        ledger.markApplied("liquid")
        assertTrue(restoreTitleBarSurface(host, ledger))

        assertSame("回退不得覆盖调用方写在其它 key 上的 tag", foreignValue, host.getTag(foreignKey))
    }
}
