package io.legado.app.lib.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UiFontFileStampTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun fileStamp_nonLocalOrMissing_returnsOriginalInput() {
        assertEquals("", fileStamp(""))
        assertEquals("https://example.com/font.ttf", fileStamp("https://example.com/font.ttf"))
        assertEquals("content://com.example/font", fileStamp("content://com.example/font"))

        val missing = File(tempFolder.root, "missing.ttf").absolutePath
        assertEquals(missing, fileStamp(missing))
        assertEquals(tempFolder.root.absolutePath, fileStamp(tempFolder.root.absolutePath))
    }

    @Test
    fun fileStamp_and_cacheKey_changeWhenFileSizeChangesAtSamePath() {
        val fontFile = tempFolder.newFile("custom_ui.ttf")
        fontFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        val initialTime = 1_700_000_000_000L
        assertTrue(fontFile.setLastModified(initialTime))

        val stampBefore = fileStamp(fontFile.absolutePath)
        val cacheKeyBefore = UiTypefaceCache.cacheKey(fontFile.absolutePath)
        assertEquals(stampBefore, cacheKeyBefore)
        assertEquals("${fontFile.absolutePath}#4:$initialTime", stampBefore)

        fontFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertTrue(fontFile.setLastModified(initialTime))

        val stampAfter = fileStamp(fontFile.absolutePath)
        val cacheKeyAfter = UiTypefaceCache.cacheKey(fontFile.absolutePath)
        assertEquals(stampAfter, cacheKeyAfter)
        assertNotEquals(stampBefore, stampAfter)
        assertNotEquals(cacheKeyBefore, cacheKeyAfter)
    }

    @Test
    fun fileStamp_and_cacheKey_changeWhenLastModifiedChangesWithSameSize() {
        val fontFile = tempFolder.newFile("custom_title.ttf")
        fontFile.writeBytes(byteArrayOf(10, 20, 30, 40))
        val time1 = 1_700_000_000_000L
        val time2 = 1_700_000_005_000L
        assertTrue(fontFile.setLastModified(time1))

        val stampBefore = fileStamp(fontFile.absolutePath)
        val cacheKeyBefore = UiTypefaceCache.cacheKey(fontFile.absolutePath)

        fontFile.writeBytes(byteArrayOf(99, 88, 77, 66))
        assertTrue(fontFile.setLastModified(time2))

        val stampAfter = fileStamp(fontFile.absolutePath)
        val cacheKeyAfter = UiTypefaceCache.cacheKey(fontFile.absolutePath)
        assertNotEquals(stampBefore, stampAfter)
        assertNotEquals(cacheKeyBefore, cacheKeyAfter)
        assertEquals("${fontFile.absolutePath}#4:$time2", cacheKeyAfter)
    }

    @Test
    fun invalidate_clearsWithoutError() {
        val fontFile = tempFolder.newFile("font.ttf")
        fontFile.writeBytes(byteArrayOf(1, 2, 3))
        UiTypefaceCache.invalidate(fontFile.absolutePath)
        UiTypefaceCache.invalidate(null)
    }
}
