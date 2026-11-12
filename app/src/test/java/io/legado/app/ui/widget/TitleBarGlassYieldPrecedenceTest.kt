package io.legado.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「页面接管后自动 opt-in 整条让位」的**优先级**锁（第二轮 D 项）。
 *
 * `TitleBarGlassRollbackTest` 已经锁住了 `yieldToPage = true` 时 `enabled = false` 的两个
 * 形态，但没锁住**决策表的顺序**：真机上自动路径的 `enabled` 由
 * `titleBarAutoWallpaperGlassEnabled` 现算，若有人把 `yieldToPage` 判断挪到
 * `appliedSignature == signature → KEEP` 或 `appliedSignature != signature → REBUILD`
 * 之后，`enabled = false` 的两个用例**依然全绿**，而自动路径会在页面已接管时把页面铺好的
 * 玻璃重建一次或撤掉——正是 D 项要防的「两个入口互抢」。
 *
 * 因此这里直接对生产决策表断言：`yieldToPage` 必须**优先于一切**（含 `enabled = true`
 * 且签名已过期那种最容易被抢的形态）。
 */
class TitleBarGlassYieldPrecedenceTest {

    @Test
    fun `page takeover wins over an enabled auto path with a stale signature`() {
        // 自动路径判「该铺」，且已铺的签名与当前签名不同（本应 REBUILD）。
        // 页面已接管时必须让位：不重建、不换 drawable、不动页面的账本。
        assertEquals(
            "页面接管后自动路径不能重建玻璃",
            TitleBarGlassAction.SKIP,
            titleBarGlassAction(
                enabled = true,
                signature = "solid|8|0|off|night",
                appliedSignature = "liquid|8|0|off|night",
                yieldToPage = true
            )
        )
    }

    @Test
    fun `page takeover wins over an enabled auto path with a fresh signature`() {
        // 签名一致（本应 KEEP）也必须让位：让位是「整条不插手」，不是「只挡重建」。
        assertEquals(
            TitleBarGlassAction.SKIP,
            titleBarGlassAction(
                enabled = true,
                signature = "liquid|8|0|off|night",
                appliedSignature = "liquid|8|0|off|night",
                yieldToPage = true
            )
        )
    }

    @Test
    fun `page takeover wins before the very first apply`() {
        // 页面接管发生在自动路径之前（ReadRssActivity.onCreate 的 initView 之后）：
        // 此时 auto 侧还没铺过，必须 SKIP 而不是 APPLY。
        assertEquals(
            TitleBarGlassAction.SKIP,
            titleBarGlassAction(
                enabled = true,
                signature = "liquid|8|0|off|night",
                appliedSignature = null,
                yieldToPage = true
            )
        )
    }

    @Test
    fun `without page takeover the same inputs still rebuild`() {
        // 反向锁：去掉让位位，同一组输入必须回到 REBUILD——否则上面的断言可能只是
        // 「恰好都返回 SKIP」，而不是真的验证了优先级。
        assertEquals(
            TitleBarGlassAction.REBUILD,
            titleBarGlassAction(
                enabled = true,
                signature = "solid|8|0|off|night",
                appliedSignature = "liquid|8|0|off|night",
                yieldToPage = false
            )
        )
    }
}
