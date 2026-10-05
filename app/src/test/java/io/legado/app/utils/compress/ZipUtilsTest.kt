package io.legado.app.utils.compress

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class ZipUtilsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `text entries use deflate and compressed formats use stored`() {
        val textFile = tmp.newFile("bookSource.json").apply {
            writeText("{\"a\":1}".repeat(2000))
        }
        val imageFile = tmp.newFile("cover.png").apply {
            writeBytes(ByteArray(4096) { (it % 251).toByte() })
        }
        val zip = File(tmp.root, "backup.zip")

        val ok = kotlinx.coroutines.runBlocking {
            ZipUtils.zipFiles(
                listOf(
                    ZipUtils.ZipSource(textFile),
                    ZipUtils.ZipSource(imageFile)
                ),
                zip.absolutePath
            ) { _, _ -> }
        }
        assertTrue(ok)

        ZipFile(zip).use { zf ->
            val textEntry = zf.getEntry("bookSource.json")
            assertEquals(ZipEntry.DEFLATED.toLong(), textEntry.method.toLong())

            val imageEntry = zf.getEntry("cover.png")
            assertEquals(ZipEntry.STORED.toLong(), imageEntry.method.toLong())
            assertEquals(imageFile.length(), imageEntry.size)
            assertArrayEquals(imageFile.readBytes(), zf.getInputStream(imageEntry).readBytes())
            assertArrayEquals(textFile.readBytes(), zf.getInputStream(textEntry).readBytes())
        }
    }
}
