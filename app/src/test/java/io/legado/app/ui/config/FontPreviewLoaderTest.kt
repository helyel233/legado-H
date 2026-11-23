package io.legado.app.ui.config

import android.app.Application
import android.content.Context
import android.graphics.Typeface
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class FontPreviewLoaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
        FontPreviewLoader.clearCachesForTest()
    }

    @After
    fun tearDown() {
        FontPreviewLoader.clearCachesForTest()
    }

    @Test
    fun `candidate LRU strictly bounds capacity at 10 and evicts oldest entry`() {
        assertEquals(10, FontPreviewLoader.CANDIDATE_LRU_MAX_SIZE)

        val tf = Typeface.SANS_SERIF
        for (i in 1..12) {
            FontPreviewLoader.putCandidateForTest("font-$i", tf)
        }

        assertEquals(10, FontPreviewLoader.candidateCacheSize())
        assertNull(FontPreviewLoader.getCandidateForTest("font-1"))
        assertNull(FontPreviewLoader.getCandidateForTest("font-2"))
        for (i in 3..12) {
            assertNotNull("Expected font-$i to remain in LRU", FontPreviewLoader.getCandidateForTest("font-$i"))
        }
    }

    @Test
    fun `candidate LRU updates recency on hit and keeps pinned slots isolated`() {
        val pinnedUi = Typeface.SERIF
        val pinnedTitle = Typeface.MONOSPACE
        FontPreviewLoader.putPinnedForTest("pinned-ui", pinnedUi)
        FontPreviewLoader.putPinnedForTest("pinned-title", pinnedTitle)

        val tf = Typeface.SANS_SERIF
        for (i in 1..10) {
            FontPreviewLoader.putCandidateForTest("k-$i", tf)
        }
        // 访问 k-1 使其成为最近使用，随后插入 k-11 应淘汰 k-2 而非 k-1
        assertNotNull(FontPreviewLoader.getCandidateForTest("k-1"))
        FontPreviewLoader.putCandidateForTest("k-11", tf)

        assertEquals(10, FontPreviewLoader.candidateCacheSize())
        assertNotNull(FontPreviewLoader.getCandidateForTest("k-1"))
        assertNull(FontPreviewLoader.getCandidateForTest("k-2"))
        assertNotNull(FontPreviewLoader.getCandidateForTest("k-11"))

        // 再向候选 LRU 写入大量字体也不得挤出编辑器固定槽位的 2 个引用
        for (i in 12..25) {
            FontPreviewLoader.putCandidateForTest("k-$i", tf)
        }
        assertEquals(10, FontPreviewLoader.candidateCacheSize())
        assertEquals(2, FontPreviewLoader.pinnedCacheSize())
        assertSame(pinnedUi, FontPreviewLoader.getPinnedForTest("pinned-ui"))
        assertSame(pinnedTitle, FontPreviewLoader.getPinnedForTest("pinned-title"))
    }

    @Test
    fun `loadCandidatePreview and loadPinnedPreview reject invalid dat and load valid SFNT asynchronously`() = runBlocking {
        val brokenDat = tempFolder.newFile("fake_font.dat")
        brokenDat.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x01))

        val invalidState = FontPreviewLoader.loadPinnedPreview(context, brokenDat.absolutePath)
        assertFalse(invalidState.isValid)
        assertEquals("格式无效", invalidState.sourceBadge)
        assertNull(invalidState.typeface)
        assertNotNull(invalidState.errorReason)

        val validFile = tempFolder.newFile("valid_preview.ttf")
        validFile.writeBytes(buildMinimalTtf("预览字体名"))

        val pinnedState = FontPreviewLoader.loadPinnedPreview(context, validFile.absolutePath)
        assertTrue(pinnedState.isValid)
        assertEquals("预览字体名", pinnedState.familyName)
        assertEquals("本地嵌入", pinnedState.sourceBadge)
        assertNotNull(pinnedState.typeface)
        assertEquals(1, FontPreviewLoader.pinnedCacheSize())
        assertEquals(0, FontPreviewLoader.candidateCacheSize())

        val candidateState = FontPreviewLoader.loadCandidatePreview(context, validFile.absolutePath)
        assertTrue(candidateState.isValid)
        assertEquals(1, FontPreviewLoader.candidateCacheSize())
    }

    private fun buildMinimalTtf(familyName: String): ByteArray {
        val nameBytes = familyName.toByteArray(Charsets.UTF_16BE)
        val strOut = ByteArrayOutputStream().apply { write(nameBytes) }.toByteArray()
        val nameHeaderLen = 6 + 12
        val nameTableLen = nameHeaderLen + strOut.size
        val sfntHeaderLen = 12 + 16
        val buf = ByteBuffer.allocate(sfntHeaderLen + nameTableLen).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0x00010000)
        buf.putShort(1)
        buf.putShort(16)
        buf.putShort(0)
        buf.putShort(0)
        buf.put('n'.code.toByte())
        buf.put('a'.code.toByte())
        buf.put('m'.code.toByte())
        buf.put('e'.code.toByte())
        buf.putInt(0)
        buf.putInt(sfntHeaderLen)
        buf.putInt(nameTableLen)
        buf.putShort(0)
        buf.putShort(1)
        buf.putShort(nameHeaderLen.toShort())
        buf.putShort(3)
        buf.putShort(1)
        buf.putShort(0x0804.toShort())
        buf.putShort(1)
        buf.putShort(nameBytes.size.toShort())
        buf.putShort(0)
        buf.put(strOut)
        return buf.array()
    }
}
