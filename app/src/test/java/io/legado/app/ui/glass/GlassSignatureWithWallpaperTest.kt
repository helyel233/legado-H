package io.legado.app.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [glassSignatureWithWallpaper] 的真值表回归锁（QA 复核 P3-⑤）。
 *
 * 缺陷形态：`ReadRecordFragment.onResume` 与 [applyWallpaperGlassBackground] 都曾就地用
 * `GlassSurface.configSignature(context)` 当「要不要重铺」的判定键，而那份签名只含
 * 档位 / 强度 / 倍率 / 描边色 / 夜间，**不含背景图开关**。于是「开背景图 + 玻璃 →
 * 进设置关背景图 → 返回」签名不变 → 不重铺 → 页根背景已由 `BaseActivity` 换成实底、
 * 面板却停在玻璃上；反向开背景图则玻璃不出现。
 *
 * 这里锁三件事：
 * 1. 有壁纸时原样返回观感签名（不改变既有语义，旧的重绑判定不会被这次合并挤掉）；
 * 2. 无壁纸时折叠成 [NO_WALLPAPER_SIGNATURE]（此时不论观感参数如何都没有玻璃，
 *    折叠是刻意的，避免「无壁纸 + 改档位」触发一轮无意义重铺）；
 * 3. 同观感签名下 `hasBgImage` 翻转必须给出**不同**结果——这是本次回归的核心锁，
 *    等价于 `GlassPanelKeyTest.bind key changes when wallpaper availability changes`。
 */
class GlassSignatureWithWallpaperTest {

    @Test
    fun `with background image the appearance signature is returned verbatim`() {
        assertEquals(
            "有壁纸时必须原样透传观感签名，否则切档 / 改描边后的重绑判定会失效",
            "hq|1.0|1.2|#FFFFFF|false",
            glassSignatureWithWallpaper(
                configSignature = "hq|1.0|1.2|#FFFFFF|false",
                hasBgImage = true
            )
        )
    }

    @Test
    fun `without background image the signature folds to the no-wallpaper marker`() {
        assertEquals(
            NO_WALLPAPER_SIGNATURE,
            glassSignatureWithWallpaper(configSignature = "hq|1.0|1.2|#FFFFFF|false", hasBgImage = false)
        )
    }

    @Test
    fun `toggling the background image changes the signature`() {
        // 本缺陷的复现路径：签名不变、背景图关掉，重绑判定命中相同值 → 面板停在玻璃上
        val withWallpaper = glassSignatureWithWallpaper(configSignature = "hq|1.0", hasBgImage = true)
        val withoutWallpaper = glassSignatureWithWallpaper(configSignature = "hq|1.0", hasBgImage = false)

        assertNotEquals(
            "同一份观感签名下，壁纸可用性翻转必须给出不同的重绑签名",
            withWallpaper,
            withoutWallpaper
        )
    }

    @Test
    fun `appearance changes are still separated while the background image stays on`() {
        // 防止「加了壁纸位」把旧语义挤掉：有壁纸时改档位仍要能区分
        assertNotEquals(
            glassSignatureWithWallpaper(configSignature = "liquid|1.0", hasBgImage = true),
            glassSignatureWithWallpaper(configSignature = "hq|1.0", hasBgImage = true)
        )
        assertNotEquals(
            glassSignatureWithWallpaper(configSignature = "hq|1.0", hasBgImage = true),
            glassSignatureWithWallpaper(configSignature = "hq|2.0", hasBgImage = true)
        )
    }

    @Test
    fun `appearance changes collapse while the background image stays off`() {
        // 无壁纸时不会铺玻璃，观感差异不影响最终表面 —— 折叠成同一值是有意为之
        assertEquals(
            glassSignatureWithWallpaper(configSignature = "liquid|1.0", hasBgImage = false),
            glassSignatureWithWallpaper(configSignature = "hq|2.0", hasBgImage = false)
        )
    }

    @Test
    fun `the no wallpaper marker cannot be produced while the wallpaper is usable`() {
        // 排除「某个观感签名恰好等于 NO_WALLPAPER_SIGNATURE，导致有壁纸时被误判成无壁纸」：
        // 有壁纸分支原样透传，因此只有当 configSignature 本身取该字面量才会相等，
        // 而该字面量不是任何真实签名的取值形态（见 GlassSurface.configSignature 的实现）。
        assertNotEquals(
            NO_WALLPAPER_SIGNATURE,
            glassSignatureWithWallpaper(configSignature = "hq|1.0", hasBgImage = true)
        )
    }
}
