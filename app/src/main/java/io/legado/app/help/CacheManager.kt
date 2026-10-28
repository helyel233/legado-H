package io.legado.app.help

import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import androidx.annotation.Keep
import androidx.collection.LruCache
import io.legado.app.data.appDb
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


@Keep
@Suppress("unused")
object CacheManager {

    /**
     * saveTime 单位为秒
     */
    @JvmOverloads
    fun put(key: String, value: Any, saveTime: Int = 0) {
        val deadline =
            if (saveTime == 0) 0 else System.currentTimeMillis() + saveTime * 1000
        when (value) {
            is ByteArray -> ACache.get().put(key, value, saveTime)
            else -> {
                val valueStr = value.toString()
                putMemory(key, valueStr)
                // 内存副本也要记过期时间：否则 TTL 只对磁盘生效，内存命中会把过期数据一直返回
                if (deadline > 0L) {
                    memoryDeadlines[key] = deadline
                }
                val cache = Cache(key, valueStr, deadline)
                appDb.cacheDao.insert(cache)
            }
        }
    }

    fun putMemory(key: String, value: Any) {
        memoryLruCache.put(key, value)
        // 重新写入视为刷新，过期时间一并清掉
        memoryDeadlines.remove(key)
    }

    //从内存中获取数据 使用lruCache
    fun getFromMemory(key: String): Any? {
        return memoryLruCache[key]
    }

    fun deleteMemory(key: String) {
        memoryLruCache.remove(key)
        memoryDeadlines.remove(key)
    }

    fun get(key: String): String? {
        val now = System.currentTimeMillis()
        val memoryValue = getFromMemory(key)
        if (memoryValue is String) {
            val memoryDecision = CacheExpiryPolicy.memoryDecision(
                hasEntry = true,
                deadline = memoryDeadlines[key] ?: 0L,
                now = now
            )
            when (memoryDecision) {
                CacheExpiryPolicy.Decision.MEMORY_HIT -> return memoryValue
                // 内存副本已过期：清掉后回落到磁盘判定
                CacheExpiryPolicy.Decision.MEMORY_EXPIRED -> deleteMemory(key)
                else -> Unit
            }
        }
        val cache = appDb.cacheDao.get(key)
        val diskDecision = CacheExpiryPolicy.diskDecision(
            hasEntry = cache != null,
            deadline = cache?.deadline ?: 0L,
            now = now
        )
        if (diskDecision == CacheExpiryPolicy.Decision.DISK_HIT) {
            return cache?.value?.also {
                putMemory(key, it)
                // 回填内存时保留磁盘上的过期时间，避免 TTL 在内存层失效
                if (cache.deadline > 0L) {
                    memoryDeadlines[key] = cache.deadline
                }
            }
        }
        return null
    }

    fun get(key: String, onlyDisk: Boolean): String? {
        if (!onlyDisk) {
            return get(key)
        }
        val cache = appDb.cacheDao.get(key)
        if (cache != null && (cache.deadline == 0L || cache.deadline > System.currentTimeMillis())) {
            return cache.value
        }
        return null
    }

    fun getInt(key: String): Int? {
        getFromMemory(key)?.let {
            if (it is Int) return it
        }
        return get(key, true)?.toIntOrNull()
    }

    fun getLong(key: String): Long? {
        getFromMemory(key)?.let {
            if (it is Long) return it
        }
        return get(key, true)?.toLongOrNull()
    }

    fun getDouble(key: String): Double? {
        getFromMemory(key)?.let {
            if (it is Double) return it
        }
        return get(key, true)?.toDoubleOrNull()
    }

    fun getFloat(key: String): Float? {
        getFromMemory(key)?.let {
            if (it is Float) return it
        }
        return get(key, true)?.toFloatOrNull()
    }

    fun getByteArray(key: String): ByteArray? {
        return ACache.get().getAsBinary(key)
    }

    fun putFile(key: String, value: String, saveTime: Int = 0) {
        ACache.get().put(key, value, saveTime)
    }

    fun getFile(key: String): String? {
        return ACache.get().getAsString(key)
    }

    fun delete(key: String) {
        appDb.cacheDao.delete(key)
        deleteMemory(key)
        ACache.get().remove(key)
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
        memoryLruCache.put(key, value)
        memoryDeadlines.remove(key)
    }
    @JavascriptInterface
    fun getFromMemory(key: String): String? {
        return memoryLruCache[key]?.toString()
    }
    @JavascriptInterface
    fun deleteMemory(key: String) {
        memoryLruCache.remove(key)
        memoryDeadlines.remove(key)
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
