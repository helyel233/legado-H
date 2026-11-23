package io.legado.app.lib.theme

import android.app.Application
import android.content.Context
import android.graphics.Typeface
import androidx.core.content.edit
import io.legado.app.utils.FileUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class UiTypefaceCacheProtocolTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
        context.defaultSharedPreferences.edit(commit = true) { clear() }
        UiTypefaceCache.invalidate(null)
    }

    @After
    fun tearDown() {
        UiTypefaceCache.invalidate(null)
        context.defaultSharedPreferences.edit(commit = true) { clear() }
    }

    @Test
    fun `cacheKey normalizes system and atFont protocols while keeping fileStamp`() {
        assertEquals("system", UiTypefaceCache.cacheKey("system"))
        assertEquals("system:sans", UiTypefaceCache.cacheKey("  SYSTEM:SANS "))
        assertEquals("system:sans", UiTypefaceCache.cacheKey("system:sans-serif"))
        assertEquals("system:serif", UiTypefaceCache.cacheKey("system:serif"))
        assertEquals("system:mono", UiTypefaceCache.cacheKey("system:mono"))
        assertEquals("system:mono", UiTypefaceCache.cacheKey("system:monospace"))
        assertEquals("@font:SourceHan.ttf", UiTypefaceCache.cacheKey("  @font:SourceHan.ttf "))

        val local = tempFolder.newFile("local.ttf")
        local.writeBytes(minimalTtfBytes())
        assertEquals(fileStamp(local.absolutePath), UiTypefaceCache.cacheKey(local.absolutePath))
    }

    @Test
    fun `get resolves system sans serif mono protocols`() {
        assertSame(Typeface.SANS_SERIF, UiTypefaceCache.get(context, "system:sans"))
        assertSame(Typeface.SERIF, UiTypefaceCache.get(context, "system:serif"))
        assertSame(Typeface.MONOSPACE, UiTypefaceCache.get(context, "system:mono"))
        assertNotNull(UiTypefaceCache.get(context, "system"))
    }

    @Test
    fun `get resolves atFont in externalFiles font dir with stamp key and magic validation`() {
        val fontDir = File(FileUtils.getPath(context.externalFiles, "font")).apply { mkdirs() }
        val fontFile = File(fontDir, "ProtocolDemo.ttf")
        fontFile.writeBytes(minimalTtfBytes())
        val initialTime = 1_700_000_000_000L
        assertTrue(fontFile.setLastModified(initialTime))

        val key1 = UiTypefaceCache.cacheKey(context, "@font:ProtocolDemo.ttf")
        assertEquals("@font:ProtocolDemo.ttf#${fontFile.absolutePath}#${fontFile.length()}:$initialTime", key1)

        val loaded = UiTypefaceCache.get(context, "@font:ProtocolDemo.ttf")
        assertNotNull(loaded)

        // 修改文件内容/时间戳后，cacheKey 同步失效变化
        fontFile.writeBytes(minimalTtfBytes() + byteArrayOf(1, 2, 3, 4))
        assertTrue(fontFile.setLastModified(initialTime + 5_000L))
        val key2 = UiTypefaceCache.cacheKey(context, "@font:ProtocolDemo.ttf")
        assertNotEquals(key1, key2)

        // 无效魔数（例如误将非字体存成 .dat 或伪造头）直接拦截返回 null
        val invalidFont = File(fontDir, "InvalidFake.dat")
        invalidFont.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00))
        assertNull(UiTypefaceCache.get(context, "@font:InvalidFake.dat"))
        assertNull(UiTypefaceCache.get(context, invalidFont.absolutePath))

        // 缺少的 @font 名称返回 null
        assertNull(UiTypefaceCache.get(context, "@font:NotExists.ttf"))

        UiTypefaceCache.invalidate("@font:ProtocolDemo.ttf")
    }

    private fun minimalTtfBytes(): ByteArray {
        val buf = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0x00010000) // TrueType magic
        buf.putShort(0) // numTables
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(0)
        return buf.array()
    }
}
