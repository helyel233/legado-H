package io.legado.app.ui.rss.article

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [tabsGlassRelayoutRequired] 的真值表锁。
 *
 * 这段判定决定「订阅源文章页的栏目按钮要不要重铺表面」，三种错法都不容易在真机上一眼
 * 看出，全部由这里固定下来：
 *
 * - 首次创建 / 重建标签后不铺 → 开背景图 + 玻璃档下按钮永远是原表面（玻璃不出现）；
 * - 每次 `onResume` 都铺 → 每回到前台重建整排 drawable，白付一轮壁纸采样与位置监听；
 * - 只比玻璃观感签名（不含背景图开关）→ 关背景图后按钮停在玻璃上。
 */
class RssTabsGlassTest {

    @Test
    fun `first layout is always required because nothing has been applied yet`() {
        assertTrue(
            "lastSignature 为 null = 这批 View 还没铺过（新建 / 重建标签），必须铺一次",
            tabsGlassRelayoutRequired(lastSignature = null, currentSignature = "frosted|1.0")
        )
    }

    @Test
    fun `unchanged signature keeps the current surface instead of rebuilding it`() {
        // onResume 每次都调，未变化必须是零开销：这是「切主题后返回本页不卡顿」的前提
        assertFalse(
            tabsGlassRelayoutRequired(
                lastSignature = "frosted|1.0|bg",
                currentSignature = "frosted|1.0|bg"
            )
        )
    }

    @Test
    fun `appearance change relayouts the whole row`() {
        assertTrue(
            "切玻璃档 / 强度 / 描边色 / 夜间模式后必须重铺",
            tabsGlassRelayoutRequired(
                lastSignature = "frosted|1.0|bg",
                currentSignature = "glass|0.5|bg"
            )
        )
    }

    @Test
    fun `wallpaper availability change relayouts the whole row`() {
        // wallpaperGlassSignature 在无背景图时折叠成 no-bg-image：这一位变化说明表面
        // 该从玻璃换成原表面（或反之），不能因为观感参数没变就跳过
        assertTrue(
            "关/开背景图必须重铺，否则按钮会停在玻璃上或玻璃不出现",
            tabsGlassRelayoutRequired(
                lastSignature = "frosted|1.0|bg",
                currentSignature = "no-bg-image"
            )
        )
        assertTrue(
            tabsGlassRelayoutRequired(
                lastSignature = "no-bg-image",
                currentSignature = "frosted|1.0|bg"
            )
        )
    }

    @Test
    fun `relayout decision is symmetric and stable for identical inputs`() {
        val signature = "solid|1.0|no-bg-image"
        assertFalse(tabsGlassRelayoutRequired(signature, signature))
        assertFalse(tabsGlassRelayoutRequired(signature, signature))
    }
}
