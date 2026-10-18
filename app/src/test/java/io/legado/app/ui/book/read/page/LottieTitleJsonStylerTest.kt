package io.legado.app.ui.book.read.page

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import io.legado.app.ui.book.read.page.entities.TextPage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 进阶标题 JSON 样式化单测。
 *
 * 这些逻辑原本埋在 PageView（2821 行、零测试覆盖）里，字号/比例算错直接表现为
 * 标题大小不对或全宽图片露出边。拆成独立对象时先用测试把行为锁死，再搬代码，
 * 确保"只搬不改"。
 *
 * 需要 Robolectric：被测代码用 android 的 org.json，纯 JVM 下那是抛
 * "not mocked" 的 stub，拿不到真实解析结果。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LottieTitleJsonStylerTest {

    // ---- 字号与比例 ----

    @Test
    fun `标题字号等于正文加标题乘系数`() {
        // 20sp 正文 + 12sp 标题 → 20 + 12*1.25 = 35
        assertEquals(35f, LottieTitleJsonStyler.textSizeSp(20f, 12f), 0.001f)
    }

    @Test
    fun `标题字号有 1sp 下限`() {
        assertEquals(1f, LottieTitleJsonStyler.textSizeSp(0f, 0f), 0.001f)
    }

    @Test
    fun `缩放倍率按字号比计算并被夹住`() {
        // textSizeSp/textSize = 35/20 = 1.75
        assertEquals(1.75f, LottieTitleJsonStyler.scale(20f, 12f), 0.001f)
        // 极端大：夹到 2.5
        assertEquals(2.5f, LottieTitleJsonStyler.scale(1f, 100f), 0.001f)
        // 极端小：标题字号为 0 时与正文同大，倍率就是 1.0
        assertEquals(1.0f, LottieTitleJsonStyler.scale(100f, 0f), 0.001f)
    }

    @Test
    fun `正文为 0 时缩放不崩`() {
        // 不能除 0：内部会把 textSize 兜到 1
        assertEquals(2.5f, LottieTitleJsonStyler.scale(0f, 100f), 0.001f)
    }

    @Test
    fun `块未铺满内容区时宽度不放大`() {
        val block = TextPage.EpubEmbeddedBlock(offsetX = 0f, offsetY = 0f, width = 500f, height = 100f, commands = emptyList())
        // 500/1000 = 0.5 < 0.98 → 原样
        assertEquals(1f, LottieTitleJsonStyler.textLayerScale(block, 1000f, 20f, 12f, 1f), 0.001f)
    }

    @Test
    fun `页宽为 0 时回退用块自身宽度`() {
        val block = TextPage.EpubEmbeddedBlock(offsetX = 0f, offsetY = 0f, width = 800f, height = 100f, commands = emptyList())
        // pageWidth=0 → contentWidth=800，比例 1.0，正常参与计算
        val scale = LottieTitleJsonStyler.textLayerScale(block, 0f, 20f, 12f, 1f)
        assertTrue("应为放大后的比例，实际 $scale", scale > 1f)
        assertTrue(scale <= 2.5f)
    }

    @Test
    fun `内容区宽度为 0 时直接返回 1`() {
        val block = TextPage.EpubEmbeddedBlock(offsetX = 0f, offsetY = 0f, width = 0f, height = 100f, commands = emptyList())
        assertEquals(1f, LottieTitleJsonStyler.textLayerScale(block, 0f, 20f, 12f, 1f), 0.001f)
    }

    // ---- JSON 打补丁 ----

    private val textLayerJson = """
        {"w":1000,"h":300,"layers":[
          {"ty":5,"t":{"d":{"k":[{"s":{"f":"","fc":[1,1,1],"s":40,"lh":50}}]}}}
        ]}
    """.trimIndent()

    @Test
    fun `缺字体的文字层会补上默认字体`() {
        val styled = LottieTitleJsonStyler.applyFallbackStyle(textLayerJson, 1f, 0xFF0000)
        val root = org.json.JSONObject(styled.json)
        val style = root.getJSONArray("layers")
            .getJSONObject(0)
            .getJSONObject("t")
            .getJSONObject("d")
            .getJSONArray("k")
            .getJSONObject(0)
            .getJSONObject("s")
        assertEquals("legado_default_font", style.getString("f"))
    }

    @Test
    fun `缺配色的文字层会补上正文字色`() {
        val styled = LottieTitleJsonStyler.applyFallbackStyle(textLayerJson, 1f, 0xFF3366)
        val root = org.json.JSONObject(styled.json)
        val style = styleOf(root)
        val fc = style.getJSONArray("fc")
        assertEquals(3, fc.length())
        // 0xFF3366 → 255/255, 51/255, 102/255
        assertEquals(1.0, fc.getDouble(0), 0.01)
        assertEquals(0.2, fc.getDouble(1), 0.01)
        assertEquals(0.4, fc.getDouble(2), 0.01)
    }

    @Test
    fun `字体列表会补上对应条目`() {
        val styled = LottieTitleJsonStyler.applyFallbackStyle(textLayerJson, 1f, 0xFF000000.toInt())
        val root = org.json.JSONObject(styled.json)
        val list = root.getJSONObject("fonts").getJSONArray("list")
        var found = false
        for (i in 0 until list.length()) {
            if (list.getJSONObject(i).optString("fName") == "legado_default_font") found = true
        }
        assertTrue("fonts.list 里应补上默认字体", found)
    }

    @Test
    fun `字号会按缩放倍率放大`() {
        val styled = LottieTitleJsonStyler.applyFallbackStyle(textLayerJson, 2f, 0xFF000000.toInt())
        val style = styleOf(org.json.JSONObject(styled.json))
        // 原字号 40 × 2 = 80
        assertEquals(80.0, style.getDouble("s"), 0.01)
        // 原行高 50 × 2 = 100
        assertEquals(100.0, style.getDouble("lh"), 0.01)
    }

    @Test
    fun `缩放不超过 1 时不改字号`() {
        val styled = LottieTitleJsonStyler.applyFallbackStyle(textLayerJson, 1f, 0xFF000000.toInt())
        val style = styleOf(org.json.JSONObject(styled.json))
        assertEquals(40.0, style.getDouble("s"), 0.01)
    }

    @Test
    fun `超大 JSON 直接原样返回`() {
        val huge = buildString {
            append("""{"w":100,"h":100,"layers":[""")
            append("x".repeat(600 * 1024))
            append("]}")
        }
        val styled = LottieTitleJsonStyler.applyFallbackStyle(huge, 1f, 0xFF000000.toInt())
        assertEquals(huge, styled.json)
        assertNull(styled.compositionSize)
    }

    @Test
    fun `坏 JSON 原样返回不崩`() {
        val broken = """{"w":100,"h":100,"layers":[{"ty":5,"t":"""
        val styled = LottieTitleJsonStyler.applyFallbackStyle(broken, 1f, 0xFF000000.toInt())
        assertEquals(broken, styled.json)
        assertNull(styled.compositionSize)
    }

    @Test
    fun `同一入参命中缓存返回同一结果`() {
        val first = LottieTitleJsonStyler.applyFallbackStyle(textLayerJson, 1.5f, 0xFF00FF00.toInt())
        val second = LottieTitleJsonStyler.applyFallbackStyle(textLayerJson, 1.5f, 0xFF00FF00.toInt())
        assertEquals(first.json, second.json)
    }

    @Test
    fun `不同文字色不会串缓存`() {
        val red = LottieTitleJsonStyler.applyFallbackStyle(textLayerJson, 1f, 0xFFFF0000.toInt())
        val blue = LottieTitleJsonStyler.applyFallbackStyle(textLayerJson, 1f, 0xFF0000FF.toInt())
        assertFalse(red.json == blue.json)
    }

    @Test
    fun `可样式化时才解析出合成尺寸`() {
        val styled = LottieTitleJsonStyler.applyFallbackStyle(textLayerJson, 1f, 0xFF000000.toInt())
        assertNotNull(styled.compositionSize)
        assertEquals(1000, styled.compositionSize!!.width)
        assertEquals(300, styled.compositionSize!!.height)
    }

    // ---- 全宽图片图层归一化 ----

    @Test
    fun `全宽图片图层被放大到铺满`() {
        val json = """
            {"w":1000,"h":500,
             "assets":[{"id":"img1","w":1000}],
             "layers":[{"ty":2,"refId":"img1","ks":{"s":{"k":[50,50]}}}]}
        """.trimIndent()
        val styled = LottieTitleJsonStyler.applyFallbackStyle(json, 1f, 0xFF000000.toInt())
        val scale = org.json.JSONObject(styled.json)
            .getJSONArray("layers")
            .getJSONObject(0)
            .getJSONObject("ks")
            .getJSONObject("s")
            .getJSONArray("k")
        // 50% → 100%（缺口补足）
        assertEquals(100.0, scale.getDouble(0), 0.01)
        assertEquals(100.0, scale.getDouble(1), 0.01)
    }

    @Test
    fun `非全宽图片不动`() {
        val json = """
            {"w":1000,"h":500,
             "assets":[{"id":"img1","w":400}],
             "layers":[{"ty":2,"refId":"img1","ks":{"s":{"k":[50,50]}}}]}
        """.trimIndent()
        val styled = LottieTitleJsonStyler.applyFallbackStyle(json, 1f, 0xFF000000.toInt())
        val scale = org.json.JSONObject(styled.json)
            .getJSONArray("layers")
            .getJSONObject(0)
            .getJSONObject("ks")
            .getJSONObject("s")
            .getJSONArray("k")
        assertEquals(50.0, scale.getDouble(0), 0.01)
    }

    @Test
    fun `已满缩放不再动`() {
        val json = """
            {"w":1000,"h":500,
             "assets":[{"id":"img1","w":1000}],
             "layers":[{"ty":2,"refId":"img1","ks":{"s":{"k":[100,100]}}}]}
        """.trimIndent()
        val styled = LottieTitleJsonStyler.applyFallbackStyle(json, 1f, 0xFF000000.toInt())
        val scale = org.json.JSONObject(styled.json)
            .getJSONArray("layers")
            .getJSONObject(0)
            .getJSONObject("ks")
            .getJSONObject("s")
            .getJSONArray("k")
        assertEquals(100.0, scale.getDouble(0), 0.01)
    }

    private fun styleOf(root: org.json.JSONObject): org.json.JSONObject =
        root.getJSONArray("layers")
            .getJSONObject(0)
            .getJSONObject("t")
            .getJSONObject("d")
            .getJSONArray("k")
            .getJSONObject(0)
            .getJSONObject("s")
}
