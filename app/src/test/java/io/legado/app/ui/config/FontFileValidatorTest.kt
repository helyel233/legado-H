package io.legado.app.ui.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FontFileValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `validate TrueType magic 0x00010000 and apple true with SFNT name table`() {
        val sfntBytes = buildMinimalSfnt(
            magic = 0x00010000,
            names = listOf(
                NameEntry(platformId = 3, encodingId = 1, languageId = 0x0409, nameId = 1, text = "Legado Sans"),
                NameEntry(platformId = 3, encodingId = 1, languageId = 0x0804, nameId = 16, text = "思源黑体测试")
            )
        )
        val result = FontFileValidator.inspectBytes(sfntBytes)
        assertTrue(result.isValid)
        assertTrue(result.isNativeSupported)
        assertEquals(FontFormat.TRUETYPE, result.format)
        assertEquals("ttf", result.suggestedExtension)
        assertEquals("思源黑体测试", result.familyName)
        assertNull(result.errorReason)

        val appleTrueSfnt = buildMinimalSfnt(
            magic = 0x74727565, // "true"
            names = listOf(
                NameEntry(platformId = 3, encodingId = 1, languageId = 0x0409, nameId = 1, text = "AppleTrueType")
            )
        )
        val appleResult = FontFileValidator.inspectBytes(appleTrueSfnt)
        assertTrue(appleResult.isValid)
        assertTrue(appleResult.isNativeSupported)
        assertEquals(FontFormat.TRUETYPE, appleResult.format)
        assertEquals("AppleTrueType", appleResult.familyName)
    }

    @Test
    fun `validate OpenType OTTO magic and nameID fallback priority`() {
        val otfBytes = buildMinimalSfnt(
            magic = 0x4F54544F, // "OTTO"
            names = listOf(
                NameEntry(platformId = 1, encodingId = 0, languageId = 0, nameId = 4, text = "MacFullName"),
                NameEntry(platformId = 3, encodingId = 1, languageId = 0x0409, nameId = 1, text = "SourceHanSerif")
            )
        )
        val result = FontFileValidator.inspectBytes(otfBytes)
        assertTrue(result.isValid)
        assertTrue(result.isNativeSupported)
        assertEquals(FontFormat.OPENTYPE_CFF, result.format)
        assertEquals("otf", result.suggestedExtension)
        // nameID 1 优先级高于 nameID 4
        assertEquals("SourceHanSerif", result.familyName)
        assertNull(result.errorReason)
    }

    @Test
    fun `validate TTC ttcf collection and parse first embedded SFNT name table`() {
        val innerSfnt = buildMinimalSfnt(
            magic = 0x00010000,
            names = listOf(
                NameEntry(platformId = 3, encodingId = 1, languageId = 0x0804, nameId = 1, text = "方正楷体合集")
            )
        )
        val ttcHeaderSize = 16
        val out = ByteBuffer.allocate(ttcHeaderSize + innerSfnt.size).order(ByteOrder.BIG_ENDIAN)
        out.putInt(0x74746366) // "ttcf"
        out.putInt(0x00010000) // version 1.0
        out.putInt(1) // numFonts = 1
        out.putInt(ttcHeaderSize) // OffsetTable[0]
        // 内嵌 SFNT 表记录偏移是相对于文件头的，需要修正 name 表偏移
        val patchedInner = buildMinimalSfnt(
            magic = 0x00010000,
            names = listOf(
                NameEntry(platformId = 3, encodingId = 1, languageId = 0x0804, nameId = 1, text = "方正楷体合集")
            ),
            baseFileOffset = ttcHeaderSize
        )
        out.put(patchedInner)
        val ttcBytes = out.array()

        val result = FontFileValidator.inspectBytes(ttcBytes)
        assertTrue(result.isValid)
        assertTrue(result.isNativeSupported)
        assertEquals(FontFormat.TRUETYPE_COLLECTION, result.format)
        assertEquals("ttc", result.suggestedExtension)
        assertEquals("方正楷体合集", result.familyName)
    }

    @Test
    fun `detect WOFF and WOFF2 formats as non-native with readable hint`() {
        val woffBytes = byteArrayOf('w'.code.toByte(), 'O'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(), 0, 1, 0, 0)
        val woffResult = FontFileValidator.inspectBytes(woffBytes)
        assertTrue(woffResult.isValid)
        assertFalse(woffResult.isNativeSupported)
        assertEquals(FontFormat.WOFF, woffResult.format)
        assertEquals("woff", woffResult.suggestedExtension)
        assertNotNull(woffResult.errorReason)

        val woff2Bytes = byteArrayOf('w'.code.toByte(), 'O'.code.toByte(), 'F'.code.toByte(), '2'.code.toByte(), 0, 1, 0, 0)
        val woff2Result = FontFileValidator.inspectBytes(woff2Bytes)
        assertTrue(woff2Result.isValid)
        assertFalse(woff2Result.isNativeSupported)
        assertEquals(FontFormat.WOFF2, woff2Result.format)
        assertEquals("woff2", woff2Result.suggestedExtension)
        assertNotNull(woff2Result.errorReason)
    }

    @Test
    fun `reject invalid headers empty bytes and PNG payload with explicit error`() {
        val emptyResult = FontFileValidator.inspectBytes(byteArrayOf(0x00, 0x01))
        assertFalse(emptyResult.isValid)
        assertFalse(emptyResult.isNativeSupported)
        assertTrue(emptyResult.errorReason?.contains("不足 4 字节") == true)

        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val pngResult = FontFileValidator.inspectBytes(pngBytes, fallbackFileName = "fake.dat")
        assertFalse(pngResult.isValid)
        assertFalse(pngResult.isNativeSupported)
        assertNull(pngResult.suggestedExtension)
        assertTrue(pngResult.errorReason?.contains("PNG") == true)

        val jsonBytes = """{"name":"not_a_font"}""".toByteArray(Charsets.UTF_8)
        val jsonResult = FontFileValidator.inspectBytes(jsonBytes)
        assertFalse(jsonResult.isValid)
        assertTrue(jsonResult.errorReason?.contains("JSON") == true)
    }

    @Test
    fun `inspectFile recovers true extension for dat renamed font and rejects fake dat`() {
        val sfnt = buildMinimalSfnt(
            magic = 0x4F54544F,
            names = listOf(NameEntry(3, 1, 0x0409, 1, "RenamedOtf"))
        )
        val datFont = tempFolder.newFile("legacy_import.dat")
        datFont.writeBytes(sfnt)

        val validResult = FontFileValidator.inspectFile(datFont)
        assertTrue(validResult.isValid)
        assertTrue(validResult.isNativeSupported)
        assertEquals("otf", validResult.suggestedExtension)
        assertEquals("RenamedOtf", validResult.familyName)
        assertTrue(FontFileValidator.isNativeRenderableFile(datFont))

        val brokenDat = tempFolder.newFile("broken.dat")
        brokenDat.writeBytes(byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x00, 0x00))
        val invalidResult = FontFileValidator.inspectFile(brokenDat)
        assertFalse(invalidResult.isValid)
        assertFalse(invalidResult.isNativeSupported)
        assertFalse(FontFileValidator.isNativeRenderableFile(brokenDat))
        assertTrue(invalidResult.errorReason?.contains("12345678") == true)
    }

    @Test
    fun `inspectStream and formatFileSize produce expected output`() {
        val sfnt = buildMinimalSfnt(
            magic = 0x00010000,
            names = listOf(NameEntry(3, 1, 0x0409, 1, "StreamFont"))
        )
        val streamResult = FontFileValidator.inspectStream(
            input = ByteArrayInputStream(sfnt),
            fallbackFileName = "stream.ttf",
            knownSizeBytes = sfnt.size.toLong()
        )
        assertTrue(streamResult.isValid)
        assertEquals("StreamFont", streamResult.familyName)

        assertEquals("512 B", FontFileValidator.formatFileSize(512L))
        assertEquals("1.5 KB", FontFileValidator.formatFileSize(1536L))
        assertEquals("14.2 MB", FontFileValidator.formatFileSize((14.2 * 1024 * 1024).toLong()))
    }

    private data class NameEntry(
        val platformId: Int,
        val encodingId: Int,
        val languageId: Int,
        val nameId: Int,
        val text: String
    )

    private fun buildMinimalSfnt(
        magic: Int,
        names: List<NameEntry>,
        baseFileOffset: Int = 0
    ): ByteArray {
        val encodedStrings = names.map { entry ->
            val charset = if (entry.platformId == 1) Charsets.ISO_8859_1 else Charsets.UTF_16BE
            entry to entry.text.toByteArray(charset)
        }
        val stringStorage = ByteArrayOutputStream()
        val recordStringOffsets = mutableListOf<Int>()
        for ((_, bytes) in encodedStrings) {
            recordStringOffsets += stringStorage.size()
            stringStorage.write(bytes)
        }
        val stringBytes = stringStorage.toByteArray()

        val nameHeaderLen = 6 + encodedStrings.size * 12
        val nameTableLen = nameHeaderLen + stringBytes.size
        val sfntHeaderLen = 12 + 16 // 1 table ("name")
        val nameTableOffsetFromFile = baseFileOffset + sfntHeaderLen

        val buf = ByteBuffer.allocate(sfntHeaderLen + nameTableLen).order(ByteOrder.BIG_ENDIAN)
        // OffsetTable
        buf.putInt(magic)
        buf.putShort(1) // numTables
        buf.putShort(16) // searchRange
        buf.putShort(0) // entrySelector
        buf.putShort(0) // rangeShift
        // TableRecord ("name")
        buf.put('n'.code.toByte())
        buf.put('a'.code.toByte())
        buf.put('m'.code.toByte())
        buf.put('e'.code.toByte())
        buf.putInt(0) // checkSum
        buf.putInt(nameTableOffsetFromFile)
        buf.putInt(nameTableLen)
        // NameTable header
        buf.putShort(0) // format 0
        buf.putShort(encodedStrings.size.toShort())
        buf.putShort(nameHeaderLen.toShort())
        // NameRecords
        encodedStrings.forEachIndexed { idx, (entry, bytes) ->
            buf.putShort(entry.platformId.toShort())
            buf.putShort(entry.encodingId.toShort())
            buf.putShort(entry.languageId.toShort())
            buf.putShort(entry.nameId.toShort())
            buf.putShort(bytes.size.toShort())
            buf.putShort(recordStringOffsets[idx].toShort())
        }
        buf.put(stringBytes)
        return buf.array()
    }
}
