package io.legado.app.utils.compress

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File
import java.security.MessageDigest
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

    @Test
    fun `zip output is deterministic for unchanged sources`() {
        val textFile = tmp.newFile("bookSource.json").apply {
            writeText("{\"a\":1}".repeat(2000))
        }
        val imageFile = tmp.newFile("cover.png").apply {
            writeBytes(ByteArray(4096) { (it % 251).toByte() })
        }
        val sources = listOf(
            ZipUtils.ZipSource(textFile),
            ZipUtils.ZipSource(imageFile)
        )
        val zip1 = File(tmp.root, "backup1.zip")
        val zip2 = File(tmp.root, "backup2.zip")

        // 两次打包之间故意 sleep，若条目时间取打包时刻而非源文件 mtime，字节必不同
        val ok1 = kotlinx.coroutines.runBlocking { ZipUtils.zipFiles(sources, zip1.absolutePath, null) }
        assertTrue(ok1)
        Thread.sleep(1500)
        val ok2 = kotlinx.coroutines.runBlocking { ZipUtils.zipFiles(sources, zip2.absolutePath, null) }
        assertTrue(ok2)

        assertEquals(sha256(zip1), sha256(zip2))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
