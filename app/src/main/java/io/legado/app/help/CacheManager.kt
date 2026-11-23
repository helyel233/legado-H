package io.legado.app.help

import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import androidx.annotation.Keep
import androidx.collection.LruCache
import io.legado.app.data.appDb
import io.legado.app.data.dao.CacheDao
import io.legado.app.data.entities.Cache
import io.legado.app.model.analyzeRule.QueryTTF
import io.legado.app.utils.ACache
import io.legado.app.utils.memorySize
import java.util.concurrent.ConcurrentHashMap

private val queryTTFMap = LruCache<String, QueryTTF>(4)

/**
 * 内存缓存条目的过期时间（毫秒时间戳），缺省表示永不过期。
 * 文件级私有，AppCacheManager 与 CacheManager 共用同一份。
 */
private val memoryDeadlines = ConcurrentHashMap<String, Long>()

/**
 * 最多只缓存50M的数据,防止OOM
 */
private val memoryLruCache = object : LruCache<String, Any>(1024 * 1024 * 50) {

    override fun sizeOf(key: String, value: Any): Int {
        return value.estimatedMemorySize().coerceAtLeast(1)
    }

    override fun entryRemoved(evicted: Boolean, key: String, oldValue: Any, newValue: Any?) {
        super.entryRemoved(evicted, key, oldValue, newValue)
        if (newValue == null) {
            // 容量淘汰时同步清掉过期时间表条目，否则 memoryDeadlines 会无界慢涨
            memoryDeadlines.remove(key)
        }
    }

}

private fun Any?.estimatedMemorySize(): Int {
    val size = when (this) {
        null -> 0L
        is String -> memorySize().toLong()
        is CharSequence -> toString().memorySize().toLong()
        is ByteArray -> size.toLong()
        is Bitmap -> runCatching { allocationByteCount }.getOrElse { rowBytes * height }.toLong()
        is Int, is Float -> 4L
        is Long, is Double -> 8L
        is Boolean, is Byte -> 1L
        is Short, is Char -> 2L
        is Pair<*, *> -> 16L + first.estimatedMemorySize() + second.estimatedMemorySize()
        is Collection<*> -> fold(16L) { acc, item -> acc + item.estimatedMemorySize() }
        is Map<*, *> -> entries.fold(32L) { acc, entry ->
            acc + entry.key.estimatedMemorySize() + entry.value.estimatedMemorySize()
        }
        else -> toString().memorySize().toLong()
    }
    return size.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

object AppCacheManager {

    fun put(key: String, queryTTF: QueryTTF) {
        queryTTFMap.put(key, queryTTF)
    }

    fun getQueryTTF(key: String): QueryTTF? {
        return queryTTFMap[key]
    }

    fun clearSourceVariables() {
        memoryLruCache.snapshot().keys.forEach {
            if (it.startsWith("v_")
                || it.startsWith("userInfo_")
                || it.startsWith("loginHeader_")
                || it.startsWith("sourceVariable_")
                // 与磁盘侧 CacheDao.deleteSourceVariables 保持一致：
                // 发现页的 InfoMap 也带登录态，漏掉会让已删源的信息在重新导入后复活
                || it.startsWith("infoMap_")
            ) {
                memoryLruCache.remove(it)
                memoryDeadlines.remove(it)
            }
        }
    }

}


internal class CacheStore(
    private val cacheDaoProvider: () -> CacheDao,
    private val aCacheProvider: () -> ACache?,
    private val timeProvider: () -> Long,
    private val memoryDeadlines: ConcurrentHashMap<String, Long> = ConcurrentHashMap(),
    private val memoryLruCache: LruCache<String, Any> = createMemoryLruCache(memoryDeadlines)
) {

    internal fun computeDeadline(saveTime: Int, now: Long = timeProvider()): Long {
        return if (saveTime <= 0) 0L else now + saveTime.toLong() * 1000L
    }

    internal fun clearMemory() {
        memoryLruCache.evictAll()
        memoryDeadlines.clear()
    }

    internal fun getMemoryDeadline(key: String): Long? = memoryDeadlines[key]

    fun put(key: String, value: Any, saveTime: Int = 0) {
        val deadline = computeDeadline(saveTime)
        when (value) {
            is ByteArray -> {
                deleteMemory(key)
                aCacheProvider()?.put(key, value, saveTime)
            }
            else -> {
                val valueStr = value.toString()
                putMemoryInternal(key, valueStr, deadline)
                val cache = Cache(key, valueStr, deadline)
                cacheDaoProvider().insert(cache)
            }
        }
    }

    fun putMemory(key: String, value: Any) {
        putMemoryInternal(key, value, 0L)
    }

    private fun putMemoryInternal(key: String, value: Any, deadline: Long) {
        // 先写/清 deadline 再写 LruCache：避免并发读看到新值却误读旧 deadline 或无 TTL，
        // 若 LruCache 立即淘汰该条目，entryRemoved 也会同步清除 memoryDeadlines[key]。
        if (deadline > 0L) {
            memoryDeadlines[key] = deadline
        } else {
            memoryDeadlines.remove(key)
        }
        memoryLruCache.put(key, value)
    }

    fun getFromMemory(key: String): Any? {
        val value = memoryLruCache[key] ?: return null
        val memoryDecision = CacheExpiryPolicy.memoryDecision(
            hasEntry = true,
            deadline = memoryDeadlines[key] ?: 0L,
            now = timeProvider()
        )
        return when (memoryDecision) {
            CacheExpiryPolicy.Decision.MEMORY_HIT -> value
            CacheExpiryPolicy.Decision.MEMORY_EXPIRED -> {
                deleteMemory(key)
                null
            }
            else -> null
        }
    }

    fun deleteMemory(key: String) {
        memoryLruCache.remove(key)
        memoryDeadlines.remove(key)
    }

    fun get(key: String): String? {
        val memoryValue = getFromMemory(key)
        if (memoryValue is String) {
            return memoryValue
        }
        val now = timeProvider()
        val cache = cacheDaoProvider().get(key)
        val diskDecision = CacheExpiryPolicy.diskDecision(
            hasEntry = cache != null,
            deadline = cache?.deadline ?: 0L,
            now = now
        )
        if (diskDecision == CacheExpiryPolicy.Decision.DISK_HIT) {
            return cache?.value?.also {
                putMemoryInternal(key, it, cache.deadline)
            }
        }
        return null
    }

    fun get(key: String, onlyDisk: Boolean): String? {
        if (!onlyDisk) {
            return get(key)
        }
        val cache = cacheDaoProvider().get(key)
        if (cache != null && !CacheExpiryPolicy.isExpired(cache.deadline, timeProvider())) {
            return cache.value
        }
        return null
    }

    fun getInt(key: String): Int? {
        getFromMemory(key)?.let {
            when (it) {
                is Int -> return it
                is String -> it.toIntOrNull()?.let { parsed -> return parsed }
            }
        }
        return get(key, true)?.toIntOrNull()
    }

    fun getLong(key: String): Long? {
        getFromMemory(key)?.let {
            when (it) {
                is Long -> return it
                is String -> it.toLongOrNull()?.let { parsed -> return parsed }
            }
        }
        return get(key, true)?.toLongOrNull()
    }

    fun getDouble(key: String): Double? {
        getFromMemory(key)?.let {
            when (it) {
                is Double -> return it
                is String -> it.toDoubleOrNull()?.let { parsed -> return parsed }
            }
        }
        return get(key, true)?.toDoubleOrNull()
    }

    fun getFloat(key: String): Float? {
        getFromMemory(key)?.let {
            when (it) {
                is Float -> return it
                is String -> it.toFloatOrNull()?.let { parsed -> return parsed }
            }
        }
        return get(key, true)?.toFloatOrNull()
    }

    fun getByteArray(key: String): ByteArray? {
        return aCacheProvider()?.getAsBinary(key)
    }

    fun putFile(key: String, value: String, saveTime: Int = 0) {
        aCacheProvider()?.put(key, value, saveTime)
    }

    fun getFile(key: String): String? {
        return aCacheProvider()?.getAsString(key)
    }

    fun delete(key: String) {
        cacheDaoProvider().delete(key)
        deleteMemory(key)
        aCacheProvider()?.remove(key)
    }
}

private fun createMemoryLruCache(
    deadlines: ConcurrentHashMap<String, Long>
): LruCache<String, Any> = object : LruCache<String, Any>(1024 * 1024 * 50) {

    override fun sizeOf(key: String, value: Any): Int {
        return value.estimatedMemorySize().coerceAtLeast(1)
    }

    override fun entryRemoved(evicted: Boolean, key: String, oldValue: Any, newValue: Any?) {
        super.entryRemoved(evicted, key, oldValue, newValue)
        if (newValue == null) {
            // 容量淘汰时同步清掉过期时间表条目，否则 memoryDeadlines 会无界慢涨
            deadlines.remove(key)
        }
    }
}


@Keep
@Suppress("unused")
object CacheManager {

    private val defaultStore = CacheStore(
        cacheDaoProvider = { appDb.cacheDao },
        aCacheProvider = { ACache.get() },
        timeProvider = { System.currentTimeMillis() },
        memoryDeadlines = memoryDeadlines,
        memoryLruCache = memoryLruCache
    )

    internal fun clearMemoryForTest() {
        defaultStore.clearMemory()
    }

    /**
     * saveTime 单位为秒
     */
    @JvmOverloads
    fun put(key: String, value: Any, saveTime: Int = 0) {
        defaultStore.put(key, value, saveTime)
    }

    fun putMemory(key: String, value: Any) {
        defaultStore.putMemory(key, value)
    }

    //从内存中获取数据 使用lruCache
    fun getFromMemory(key: String): Any? {
        return defaultStore.getFromMemory(key)
    }

    fun deleteMemory(key: String) {
        defaultStore.deleteMemory(key)
    }

    fun get(key: String): String? {
        return defaultStore.get(key)
    }

    fun get(key: String, onlyDisk: Boolean): String? {
        return defaultStore.get(key, onlyDisk)
    }

    fun getInt(key: String): Int? {
        return defaultStore.getInt(key)
    }

    fun getLong(key: String): Long? {
        return defaultStore.getLong(key)
    }

    fun getDouble(key: String): Double? {
        return defaultStore.getDouble(key)
    }

    fun getFloat(key: String): Float? {
        return defaultStore.getFloat(key)
    }

    fun getByteArray(key: String): ByteArray? {
        return defaultStore.getByteArray(key)
    }

    fun putFile(key: String, value: String, saveTime: Int = 0) {
        defaultStore.putFile(key, value, saveTime)
    }

    fun getFile(key: String): String? {
        return defaultStore.getFile(key)
    }

    fun delete(key: String) {
        defaultStore.delete(key)
    }
}

@Keep
@Suppress("unused")
object WebCacheManager {
    @JvmOverloads
    @JavascriptInterface
    fun put(key: String, value: String, saveTime: Int = 0) {
        CacheManager.put(key, value, saveTime)
    }
    @JavascriptInterface
    fun putMemory(key: String, value: String) {
        CacheManager.putMemory(key, value)
    }
    @JavascriptInterface
    fun getFromMemory(key: String): String? {
        return CacheManager.getFromMemory(key)?.toString()
    }
    @JavascriptInterface
    fun deleteMemory(key: String) {
        CacheManager.deleteMemory(key)
    }
    @JavascriptInterface
    fun get(key: String): String? {
        return CacheManager.get(key)
    }
    @JavascriptInterface
    fun get(key: String, onlyDisk: Boolean): String? {
        return CacheManager.get(key, onlyDisk)
    }
    @JavascriptInterface
    fun putFile(key: String, value: String, saveTime: Int = 0) {
        CacheManager.putFile(key, value, saveTime)
    }
    @JavascriptInterface
    fun getFile(key: String): String? {
        return CacheManager.getFile(key)
    }
    @JavascriptInterface
    fun delete(key: String) {
        CacheManager.delete(key)
    }
}
