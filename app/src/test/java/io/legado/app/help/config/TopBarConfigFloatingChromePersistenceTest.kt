package io.legado.app.help.config

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * floatingChrome 持久化链路回归测试（纯 JVM）。
 *
 * 背景：顶栏圆角方案曾出现「机制已接线但永不生效」——读端（TopBarChromeSpec → 渲染 →
 * 签名失效）完整，但没有任何写入端把 floatingChrome 置 true。本测试锁定持久化环节：
 * 编辑器写入的悬浮开关必须经 GSON 往返无损保留，存量包 JSON 缺字段必须默认 false。
 */
class TopBarConfigFloatingChromePersistenceTest {

    @Test
    fun gsonRoundTripPreservesFloatingChrome() {
        val config = TopBarConfig.Config(
            name = "t",
            isNightMode = false,
            style = TopBarConfig.STYLE_REGULAR,
            cornerScale = 2f,
            floatingChrome = true,
        )
        val json = GSON.toJson(config)
        val restored = GSON.fromJsonObject<TopBarConfig.Config>(json).getOrThrow()
        assertTrue("悬浮开关经 JSON 往返后必须保留", restored.floatingChrome)
        assertEquals(2f, restored.cornerScale!!, 0.0001f)
        assertEquals(TopBarConfig.STYLE_REGULAR, restored.style)
    }

    @Test
    fun gsonRoundTripPreservesDisabledState() {
        val config = TopBarConfig.Config(name = "t", isNightMode = false, cornerScale = 0f)
        val restored = GSON.fromJsonObject<TopBarConfig.Config>(GSON.toJson(config)).getOrThrow()
        assertFalse(restored.floatingChrome)
    }

    /** 存量顶栏包 JSON 无 floatingChrome 字段 → 默认 false（视觉零变化）。 */
    @Test
    fun legacyJsonWithoutFieldDefaultsToNonFloating() {
        val legacyJson = """
            {"name":"legacy","isNightMode":false,"style":"regular","cornerScale":1.0}
        """.trimIndent()
        val restored = GSON.fromJsonObject<TopBarConfig.Config>(legacyJson).getOrThrow()
        assertFalse("缺字段必须走默认 false，不得由 cornerScale 反推", restored.floatingChrome)
    }
}
