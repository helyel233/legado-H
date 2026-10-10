package io.legado.app.uikit.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutPackageSpecTest {

    @Test
    fun `parse full spec with unknown fields`() {
        val r = LayoutPackageSpec.parse(
            """
            {"formatVersion":1,"id":"side","name":"侧滑沉浸","navPosition":"side",
             "navVisibility":"scrollHide","densityLevel":"compact",
             "glassEnabled":true,"glassTransparency":0.45,"glassBlur":20,
             "shapeScale":1.3,"iconStroke":2.0,"basedOn":"classic","future":123}
            """.trimIndent(),
        )
        assertTrue(r is LayoutParseResult.Ok)
        val s = (r as LayoutParseResult.Ok).spec
        assertEquals("side", s.navPosition)
        assertEquals(0.45f, s.glassTransparency)
        assertEquals("classic", s.basedOn)
        assertTrue(s.isValid())
    }

    @Test
    fun `defaults are classic-like`() {
        val s = LayoutPackageSpec(id = "x", name = "X")
        assertEquals(LayoutPackageSpec.NAV_BOTTOM, s.navPosition)
        assertEquals(1f, s.densityFactor)
        assertTrue(s.isValid())
    }

    @Test
    fun `density factors`() {
        assertEquals(0.85f, LayoutPackageSpec(id = "x", name = "X", densityLevel = "compact").densityFactor)
        assertEquals(1.15f, LayoutPackageSpec(id = "x", name = "X", densityLevel = "comfortable").densityFactor)
    }

    @Test
    fun `future version and malformed rejected`() {
        assertTrue(LayoutPackageSpec.parse("""{"formatVersion":2,"id":"x","name":"X"}""") is LayoutParseResult.Error)
        assertTrue(LayoutPackageSpec.parse("junk") is LayoutParseResult.Error)
    }

    @Test
    fun `builtin four packages valid and distinct`() {
        assertEquals(4, BuiltinLayouts.all.size)
        assertTrue(BuiltinLayouts.all.all { it.isValid() })
        assertEquals(4, BuiltinLayouts.all.map { it.navPosition + it.id }.toSet().size)
        assertEquals(LayoutPackageSpec.NAV_FLOAT, BuiltinLayouts.float.navPosition)
    }
}
