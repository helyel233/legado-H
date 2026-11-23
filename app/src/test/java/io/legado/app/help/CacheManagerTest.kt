package io.legado.app.help

import io.legado.app.data.dao.BoundedCacheValue
import io.legado.app.data.dao.CacheDao
import io.legado.app.data.entities.Cache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class CacheManagerTest {

    private class FakeCacheDao : CacheDao {
        val store = ConcurrentHashMap<String, Cache>()

        override fun get(key: String): Cache? = store[key]?.copy()

        override fun get(key: String, now: Long): String? {
            val entry = store[key] ?: return null
            return if (entry.deadline == 0L || entry.deadline > now) entry.value else null
        }

        override fun getBoundedValue(key: String, now: Long, maxBytes: Long): BoundedCacheValue? {
            val value = get(key, now) ?: return null
            val bytes = value.toByteArray().size.toLong()
            return BoundedCacheValue(bytes, if (bytes <= maxBytes) value else null)
        }

        override fun insert(vararg cache: Cache) {
            cache.forEach { store[it.key] = it.copy() }
        }

        override fun getSourceRuntimeCaches(): List<Cache> = emptyList()

        override fun delete(key: String) {
            store.remove(key)
        }

        override fun deleteIfValueMatches(key: String, value: String?) {
            val existing = store[key] ?: return
            if (existing.value == value) {
                store.remove(key)
            }
        }

        override fun deleteIfValueOversized(key: String, maxBytes: Long) {
            val existing = store[key] ?: return
            val bytes = existing.value?.toByteArray()?.size?.toLong() ?: 0L
            if (bytes > maxBytes) {
                store.remove(key)
            }
        }

        override fun deleteSourceVariables(key: String) {
            store.keys.removeIf {
                it.startsWith("v_${key}_") ||
                    it == "userInfo_$key" ||
                    it == "loginHeader_$key" ||
                    it == "sourceVariable_$key" ||
                    it == "infoMap_$key"
            }
        }

        override fun clearDeadline(now: Long) {
            store.entries.removeIf { it.value.deadline in 1 until now }
        }
    }

    private lateinit var fakeDao: FakeCacheDao
    private lateinit var cacheStore: CacheStore
    private var currentNow = 1_700_000_000_000L

    @Before
    fun setUp() {
        fakeDao = FakeCacheDao()
        currentNow = 1_700_000_000_000L
        cacheStore = CacheStore(
            cacheDaoProvider = { fakeDao },
            aCacheProvider = { null },
            timeProvider = { currentNow }
        )
        CacheManager.clearMemoryForTest()
    }

    @After
    fun tearDown() {
        cacheStore.clearMemory()
        CacheManager.clearMemoryForTest()
    }

    @Test
    fun `put with saveTime greater than 25 days uses 64-bit math without Int overflow`() {
        val thirtyDaysSeconds = 30 * 24 * 3600 // 2_592_000 > Int.MAX_VALUE / 1000 (2_147_483)
        val expectedDeadline = currentNow + thirtyDaysSeconds.toLong() * 1000L

        cacheStore.put("long_ttl_key", "persisted", thirtyDaysSeconds)

        val stored = fakeDao.store["long_ttl_key"]!!
        assertEquals(expectedDeadline, stored.deadline)
        assertTrue("Deadline must be strictly in the future", stored.deadline > currentNow)
        assertEquals(expectedDeadline, cacheStore.getMemoryDeadline("long_ttl_key"))
        assertEquals("persisted", cacheStore.get("long_ttl_key"))

        // Still valid after 29 days
        currentNow += 29L * 24 * 3600 * 1000L
        assertEquals("persisted", cacheStore.get("long_ttl_key"))

        // Expired after 30 days
        currentNow += 1L * 24 * 3600 * 1000L
        assertNull(cacheStore.get("long_ttl_key"))
        assertNull(cacheStore.getFromMemory("long_ttl_key"))
    }

    @Test
    fun `putting with saveTime zero clears old memory deadline`() {
        cacheStore.put("overwrite_key", "v1", saveTime = 10)
        assertEquals(currentNow + 10_000L, cacheStore.getMemoryDeadline("overwrite_key"))

        cacheStore.put("overwrite_key", "v2", saveTime = 0)
        assertNull(cacheStore.getMemoryDeadline("overwrite_key"))

        currentNow += 60_000L
        assertEquals("v2", cacheStore.get("overwrite_key"))
        assertEquals("v2", cacheStore.getFromMemory("overwrite_key"))
    }

    @Test
    fun `expired in-memory entries are evicted and never returned by any typed getter`() {
        cacheStore.put("str_key", "hello", saveTime = 5)
        cacheStore.put("int_key", 42, saveTime = 5)
        cacheStore.put("long_key", 9_876_543_210L, saveTime = 5)
        cacheStore.put("double_key", 3.14159, saveTime = 5)
        cacheStore.put("float_key", 2.5f, saveTime = 5)

        assertEquals("hello", cacheStore.get("str_key"))
        assertEquals("hello", cacheStore.getFromMemory("str_key"))
        assertEquals(42, cacheStore.getInt("int_key"))
        assertEquals(9_876_543_210L, cacheStore.getLong("long_key"))
        assertEquals(3.14159, cacheStore.getDouble("double_key")!!, 0.00001)
        assertEquals(2.5f, cacheStore.getFloat("float_key")!!, 0.0001f)

        // Advance past 5s TTL
        currentNow += 5_000L

        assertNull(cacheStore.getFromMemory("str_key"))
        assertNull(cacheStore.get("str_key"))
        assertNull(cacheStore.getInt("int_key"))
        assertNull(cacheStore.getLong("long_key"))
        assertNull(cacheStore.getDouble("double_key"))
        assertNull(cacheStore.getFloat("float_key"))
        assertNull(cacheStore.getMemoryDeadline("str_key"))
        assertNull(cacheStore.getMemoryDeadline("int_key"))
    }

    @Test
    fun `raw typed object in memory still respects memoryDeadlines`() {
        // Simulate an entry put in DB/memory with TTL where memory holds raw Int/Long/Double/Float
        cacheStore.put("raw_int", 99, saveTime = 5)
        // Verify even when disk entry is missing, expired memory entry is evicted
        fakeDao.delete("raw_int")
        assertEquals(99, cacheStore.getInt("raw_int"))

        currentNow += 5_000L
        assertNull(cacheStore.getInt("raw_int"))
        assertNull(cacheStore.getFromMemory("raw_int"))
    }

    @Test
    fun `delete removes both memory value and memory deadline`() {
        cacheStore.put("del_key", "val", saveTime = 60)
        assertEquals(currentNow + 60_000L, cacheStore.getMemoryDeadline("del_key"))

        cacheStore.delete("del_key")

        assertNull(cacheStore.getFromMemory("del_key"))
        assertNull(cacheStore.get("del_key"))
        assertNull(cacheStore.getMemoryDeadline("del_key"))
    }

    @Test
    fun `WebCacheManager memory bridge delegates cleanly without leaking across tests`() {
        WebCacheManager.putMemory("wm_mem_key", "cached-js-val")
        assertEquals("cached-js-val", WebCacheManager.getFromMemory("wm_mem_key"))
        assertEquals("cached-js-val", CacheManager.getFromMemory("wm_mem_key"))

        WebCacheManager.deleteMemory("wm_mem_key")
        assertNull(WebCacheManager.getFromMemory("wm_mem_key"))
    }
}
