package io.legado.app.utils

import android.app.Application
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ACacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
        cacheDir = tempFolder.newFolder("acache")
    }

    @Test
    fun `distinct keys with identical hashCode do not collide`() {
        val keyA = "Aa"
        val keyB = "BB"
        assertEquals(2112, keyA.hashCode())
        assertEquals(keyA.hashCode(), keyB.hashCode())

        val cache = ACache.get(cacheDir)
        cache.clear()

        cache.put(keyA, "value-from-Aa")
        cache.put(keyB, "value-from-BB")

        assertEquals("value-from-Aa", cache.getAsString(keyA))
        assertEquals("value-from-BB", cache.getAsString(keyB))

        val fileA = cache.cacheFileForTest(keyA)!!
        val fileB = cache.cacheFileForTest(keyB)!!
        assertNotEquals(fileA.name, fileB.name)
        assertTrue(fileA.name.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(fileB.name.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(fileA.exists())
        assertTrue(fileB.exists())

        val binKeyA = "Aa-bin"
        val binKeyB = "BB-bin"
        // Verify binary round-trip under distinct keys
        cache.put(binKeyA, byteArrayOf(1, 2, 3))
        cache.put(binKeyB, byteArrayOf(4, 5, 6))
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.getAsBinary(binKeyA))
        assertArrayEquals(byteArrayOf(4, 5, 6), cache.getAsBinary(binKeyB))
    }

    @Test
    fun `legacy hashCode file is lazily migrated on read and removed on remove`() {
        val cache = ACache.get(cacheDir)
        cache.clear()

        val legacyKey = "legacy_js_lib_https://example.com/lib.js"
        val legacyFile = File(cacheDir, legacyKey.hashCode().toString())
        legacyFile.writeText("function hello() { return 42; }")

        // Wait briefly for init scan or read immediately; both must work seamlessly
        assertEquals("function hello() { return 42; }", cache.getAsString(legacyKey))

        val shaFile = cache.cacheFileForTest(legacyKey)!!
        assertTrue("Legacy file should be migrated to SHA-256 file", shaFile.exists())
        assertFalse("Legacy hashCode file should no longer exist after migration", legacyFile.exists())

        // Recreate legacy file alongside shaFile and verify remove(key) deletes both
        legacyFile.writeText("stale-legacy")
        assertTrue(cache.remove(legacyKey))
        assertFalse(shaFile.exists())
        assertFalse(legacyFile.exists())
        assertNull(cache.getAsString(legacyKey))
    }

    @Test
    fun `put removes leftover legacy hashCode file for same key`() {
        val cache = ACache.get(cacheDir)
        cache.clear()

        val key = "update_legacy_key"
        val legacyFile = File(cacheDir, key.hashCode().toString())
        legacyFile.writeText("old-value")

        cache.put(key, "new-value")

        assertEquals("new-value", cache.getAsString(key))
        assertFalse("Writing new value should clean up leftover legacy file", legacyFile.exists())
    }

    @Test
    fun `clear resets both cacheSize and cacheCount`() {
        val cache = ACache.get(cacheDir)
        cache.clear()

        cache.put("k1", "alpha")
        cache.put("k2", "beta-gamma")

        assertEquals(2, cache.cacheCountForTest())
        assertTrue(cache.cacheSizeForTest() > 0L)

        cache.clear()

        assertEquals(0, cache.cacheCountForTest())
        assertEquals(0L, cache.cacheSizeForTest())
        assertNull(cache.getAsString("k1"))
        assertNull(cache.getAsString("k2"))
    }

    @Test
    fun `peekFile is strictly read-only without migrating legacy file whereas getAsString migrates lazily`() {
        val cache = ACache.get(cacheDir)
        cache.clear()

        val key = "peek_legacy_key"
        val legacyFile = File(cacheDir, key.hashCode().toString())
        legacyFile.writeText("legacy-payload")
        val primaryFile = cache.cacheFileForTest(key)!!

        val peeked = cache.peekFile(key)
        assertEquals(legacyFile.absolutePath, peeked?.absolutePath)
        assertTrue("peekFile must not delete or rename legacy file", legacyFile.exists())
        assertFalse("peekFile must not create primary file via migration", primaryFile.exists())

        assertEquals("legacy-payload", cache.getAsString(key))
        assertTrue("getAsString should lazily migrate to primary file", primaryFile.exists())
        assertFalse("legacy file should be renamed on getAsString", legacyFile.exists())
    }

    @Test
    fun `remove with primary and legacy files decrements each file exact byte size`() {
        val dir = tempFolder.newFolder("acache-remove-accounting")
        val key = "dual_file_key"
        val seedCache = ACache.get(tempFolder.newFolder("acache-seed"))
        val primaryName = seedCache.cacheFileForTest(key)!!.name
        val primaryFile = File(dir, primaryName).apply {
            writeText("primary-content-longer-than-legacy") // 34 bytes
        }
        val legacyFile = File(dir, key.hashCode().toString()).apply {
            writeText("legacy-12") // 9 bytes
        }
        val primarySize = primaryFile.length()
        val legacySize = legacyFile.length()
        assertNotEquals(primarySize, legacySize)

        val cache = ACache.get(dir)
        val deadline = System.currentTimeMillis() + 3000L
        while (cache.cacheCountForTest() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(2, cache.cacheCountForTest())
        assertEquals(primarySize + legacySize, cache.cacheSizeForTest())

        cache.put("keeper", "keep-value")
        val keeperSize = cache.cacheFileForTest("keeper")!!.length()
        assertEquals(3, cache.cacheCountForTest())
        assertEquals(primarySize + legacySize + keeperSize, cache.cacheSizeForTest())

        assertTrue(cache.remove(key))
        assertEquals(1, cache.cacheCountForTest())
        assertEquals(keeperSize, cache.cacheSizeForTest())
        assertFalse(primaryFile.exists())
        assertFalse(legacyFile.exists())

        // Verify forgetFile only forgets the deleted file and never double-subtracts
        val dir2 = tempFolder.newFolder("acache-forget-accounting")
        val cache2 = ACache.get(dir2)
        cache2.clear()
        cache2.put("forget_key", "0123456789")
        val targetFile = cache2.cacheFileForTest("forget_key")!!
        val targetSize = targetFile.length()
        assertEquals(1, cache2.cacheCountForTest())
        assertEquals(targetSize, cache2.cacheSizeForTest())
        assertTrue(targetFile.delete())
        cache2.forgetFile("forget_key", targetSize)
        assertEquals(0, cache2.cacheCountForTest())
        assertEquals(0L, cache2.cacheSizeForTest())
    }

    @Test
    fun `concurrent puts with small countLimit evict safely without ConcurrentModificationException`() {
        val boundedDir = tempFolder.newFolder("acache-concurrent")
        val cache = ACache.get(boundedDir, maxSize = 64 * 1024L, maxCount = 8)
        cache.clear()

        val threadCount = 6
        val iterationsPerThread = 40
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val failure = AtomicReference<Throwable?>(null)

        try {
            repeat(threadCount) { t ->
                executor.submit {
                    try {
                        startLatch.await()
                        repeat(iterationsPerThread) { i ->
                            val key = "thread-$t-key-$i"
                            cache.put(key, "payload-$t-$i")
                            cache.getAsString(key)
                        }
                    } catch (e: Throwable) {
                        failure.compareAndSet(null, e)
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }
            startLatch.countDown()
            assertTrue(doneLatch.await(15, TimeUnit.SECONDS))
            assertNull("Concurrent cache operations must not throw", failure.get())
            assertTrue(cache.cacheCountForTest() <= 8)
        } finally {
            executor.shutdownNow()
        }
    }
}
