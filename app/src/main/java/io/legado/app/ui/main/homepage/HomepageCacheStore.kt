package io.legado.app.ui.main.homepage

import android.database.sqlite.SQLiteBlobTooBigException
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.dao.CacheDao
import io.legado.app.data.entities.Cache
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * 聚合主页 SWR（Stale-While-Revalidate）缓存策略与键值规范。
 *
 * 复用与发现页一致的 512KB 单行字节上限和 SearchBook 字段裁剪策略，避免 Room CursorWindow 溢出。
 */
internal object HomepageCachePolicy {

    const val FRESH_TTL_MS: Long = 10L * 60L * 1000L
    const val MAX_DISK_TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L
    const val MAX_SQLITE_VALUE_BYTES: Long = 512L * 1024L
    const val MAX_CACHED_BOOKS: Int = 40
    const val MAX_CACHE_ENTRIES: Int = 80

    private const val PREFIX_MODULE = "hp_mod_"
    private const val PREFIX_TAB = "hp_tab_"
    private const val PREFIX_BUTTONS = "hp_btn_"
    const val INDEX_KEY = "hp_lru_index"

    private const val MAX_NAME_LENGTH = 512
    private const val MAX_AUTHOR_LENGTH = 512
    private const val MAX_ORIGIN_NAME_LENGTH = 512
    private const val MAX_KIND_LENGTH = 2_048
    private const val MAX_INTRO_LENGTH = 4_096
    private const val MAX_CHAPTER_TITLE_LENGTH = 1_024
    private const val MAX_COUNT_TEXT_LENGTH = 256
    private const val MAX_REQUIRED_FIELD_BYTES = 16L * 1024L
    private const val MAX_OPTIONAL_URL_BYTES = 32L * 1024L
    private const val MAX_VARIABLE_BYTES = 16L * 1024L

    fun moduleKey(globalId: String, sourceUrl: String, moduleUrl: String?): String {
        val raw = "$globalId\u001F$sourceUrl\u001F${moduleUrl ?: ""}"
        return "$PREFIX_MODULE${MD5Utils.md5Encode16(raw)}"
    }

    fun rankingTabKey(
        globalId: String,
        sourceUrl: String,
        tabIndex: Int,
        tabUrl: String,
    ): String {
        val raw = "$globalId\u001F$sourceUrl\u001F$tabIndex\u001F$tabUrl"
        return "$PREFIX_TAB${MD5Utils.md5Encode16(raw)}"
    }

    fun buttonsKey(globalId: String, sourceUrl: String, args: String?): String {
        val raw = "$globalId\u001F$sourceUrl\u001F${args ?: ""}"
        return "$PREFIX_BUTTONS${MD5Utils.md5Encode16(raw)}"
    }

    fun isFresh(updatedAt: Long, now: Long): Boolean {
        return updatedAt > 0L && now >= updatedAt && (now - updatedAt) < FRESH_TTL_MS
    }

    /** 将 [newKey] 置于 LRU 尾部并按 [maxEntries] 裁剪，返回 `(保留索引, 需淘汰的旧键)` */
    fun updateLruIndex(
        existingKeys: List<String>,
        newKey: String,
        maxEntries: Int = MAX_CACHE_ENTRIES,
    ): Pair<List<String>, List<String>> {
        val next = existingKeys.filter { it != newKey && it.isNotBlank() }.toMutableList()
        next.add(newKey)
        val overflow = (next.size - maxEntries).coerceAtLeast(0)
        if (overflow == 0) return next to emptyList()
        val evicted = next.subList(0, overflow).toList()
        val retained = next.subList(overflow, next.size).toList()
        return retained to evicted
    }

    fun canRead(storedByteCount: Long?): Boolean {
        return storedByteCount != null && storedByteCount in 0..MAX_SQLITE_VALUE_BYTES
    }

    fun canStore(value: String): Boolean {
        return utf8ByteCount(value, MAX_SQLITE_VALUE_BYTES) <= MAX_SQLITE_VALUE_BYTES
    }

    fun toBoundedJson(value: Any): String? {
        val output = BoundedByteArrayOutputStream(MAX_SQLITE_VALUE_BYTES.toInt())
        return try {
            OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
                GSON.toJson(value, writer)
            }
            output.toString(StandardCharsets.UTF_8.name())
        } catch (_: CacheSizeLimitExceededException) {
            null
        }
    }

    fun compact(book: SearchBook): SearchBook? {
        if (!book.bookUrl.fits(MAX_REQUIRED_FIELD_BYTES) ||
            !book.origin.fits(MAX_REQUIRED_FIELD_BYTES) ||
            !book.tocUrl.fits(MAX_OPTIONAL_URL_BYTES) ||
            !book.variable.fits(MAX_VARIABLE_BYTES)
        ) {
            return null
        }
        return book.copy(
            originName = book.originName.limitLength(MAX_ORIGIN_NAME_LENGTH),
            name = book.name.limitLength(MAX_NAME_LENGTH),
            author = book.author.limitLength(MAX_AUTHOR_LENGTH),
            kind = book.kind?.limitLength(MAX_KIND_LENGTH),
            coverUrl = book.coverUrl?.takeIf { it.fits(MAX_OPTIONAL_URL_BYTES) },
            intro = book.intro?.limitLength(MAX_INTRO_LENGTH),
            wordCount = book.wordCount?.limitLength(MAX_COUNT_TEXT_LENGTH),
            latestChapterTitle = book.latestChapterTitle?.limitLength(MAX_CHAPTER_TITLE_LENGTH),
            tocUrl = book.tocUrl,
            chapterWordCountText = book.chapterWordCountText?.limitLength(MAX_COUNT_TEXT_LENGTH),
        )
    }

    private fun utf8ByteCount(value: String, stopAfter: Long = Long.MAX_VALUE): Long {
        var byteCount = 0L
        var index = 0
        while (index < value.length) {
            val char = value[index]
            byteCount += when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                char.isHighSurrogate() &&
                    index + 1 < value.length &&
                    value[index + 1].isLowSurrogate() -> {
                    index++
                    4
                }
                else -> 3
            }
            if (byteCount > stopAfter) return byteCount
            index++
        }
        return byteCount
    }

    private fun String.limitLength(maxLength: Int): String {
        return if (length <= maxLength) this else take(maxLength)
    }

    private fun String?.fits(maxBytes: Long): Boolean {
        return this == null || utf8ByteCount(this, maxBytes) <= maxBytes
    }

    private class BoundedByteArrayOutputStream(
        private val maxBytes: Int,
    ) : ByteArrayOutputStream(minOf(maxBytes, 8 * 1024)) {

        override fun write(value: Int) {
            ensureCapacityFor(1)
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            ensureCapacityFor(length)
            super.write(bytes, offset, length)
        }

        private fun ensureCapacityFor(additionalBytes: Int) {
            if (additionalBytes < 0 || count > maxBytes - additionalBytes) {
                throw CacheSizeLimitExceededException()
            }
        }
    }

    private class CacheSizeLimitExceededException : RuntimeException()
}

internal data class HomepageBooksSnapshot(
    val books: List<SearchBook> = emptyList(),
    val hasMore: Boolean = false,
    val updatedAt: Long = 0L,
)

internal data class HomepageButtonsSnapshot(
    val kinds: List<ExploreKind> = emptyList(),
    val updatedAt: Long = 0L,
)

internal data class HomepageCacheHit<T>(
    val data: T,
    val updatedAt: Long,
    val isFresh: Boolean,
)

/**
 * 基于 [CacheDao] 的主页模块磁盘快照仓库。
 * 所有读写方法均为同步方法，调用方需在 IO 线程执行。
 */
internal class HomepageCacheStore(
    private val cacheDaoProvider: () -> CacheDao = { appDb.cacheDao },
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {

    fun readBooks(key: String): HomepageCacheHit<HomepageBooksSnapshot>? {
        val now = nowProvider()
        val raw = readBounded(key, now) ?: return null
        val snapshot = runCatching {
            GSON.fromJson(raw, HomepageBooksSnapshot::class.java)
        }.getOrNull() ?: return null
        return HomepageCacheHit(
            data = snapshot,
            updatedAt = snapshot.updatedAt,
            isFresh = HomepageCachePolicy.isFresh(snapshot.updatedAt, now),
        )
    }

    fun writeBooks(key: String, books: List<SearchBook>, hasMore: Boolean) {
        val now = nowProvider()
        val compactBooks = books
            .take(HomepageCachePolicy.MAX_CACHED_BOOKS)
            .mapNotNull(HomepageCachePolicy::compact)
        val snapshot = HomepageBooksSnapshot(
            books = compactBooks,
            hasMore = hasMore,
            updatedAt = now,
        )
        val json = HomepageCachePolicy.toBoundedJson(snapshot) ?: return
        writeBounded(key, json, now)
    }

    fun readButtons(key: String): HomepageCacheHit<HomepageButtonsSnapshot>? {
        val now = nowProvider()
        val raw = readBounded(key, now) ?: return null
        val snapshot = runCatching {
            GSON.fromJson(raw, HomepageButtonsSnapshot::class.java)
        }.getOrNull() ?: return null
        return HomepageCacheHit(
            data = snapshot,
            updatedAt = snapshot.updatedAt,
            isFresh = HomepageCachePolicy.isFresh(snapshot.updatedAt, now),
        )
    }

    fun writeButtons(key: String, kinds: List<ExploreKind>) {
        val now = nowProvider()
        val snapshot = HomepageButtonsSnapshot(kinds = kinds, updatedAt = now)
        val json = HomepageCachePolicy.toBoundedJson(snapshot) ?: return
        writeBounded(key, json, now)
    }

    private val writeLock = Any()
    private var prunedOnce = false

    fun invalidate(key: String) {
        synchronized(writeLock) {
            runCatching {
                val dao = cacheDaoProvider()
                dao.delete(key)
                val current = readIndexLocked(dao)
                if (key in current) {
                    writeIndexLocked(dao, current.filter { it != key })
                }
            }
        }
    }

    private fun readBounded(key: String, now: Long): String? {
        val cacheDao = cacheDaoProvider()
        return try {
            val result = cacheDao.getBoundedValue(
                key = key,
                now = now,
                maxBytes = HomepageCachePolicy.MAX_SQLITE_VALUE_BYTES,
            ) ?: return null
            if (!HomepageCachePolicy.canRead(result.byteCount)) {
                cacheDao.deleteIfValueOversized(key, HomepageCachePolicy.MAX_SQLITE_VALUE_BYTES)
                return null
            }
            if (result.value == null) {
                cacheDao.deleteIfValueMatches(key, null)
                return null
            }
            result.value
        } catch (e: SQLiteBlobTooBigException) {
            runCatching {
                cacheDao.deleteIfValueOversized(key, HomepageCachePolicy.MAX_SQLITE_VALUE_BYTES)
            }
            AppLog.put("主页模块缓存行过大，已清理", e)
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeBounded(key: String, json: String, now: Long) {
        if (!HomepageCachePolicy.canStore(json)) return
        synchronized(writeLock) {
            runCatching {
                val dao = cacheDaoProvider()
                if (!prunedOnce) {
                    prunedOnce = true
                    dao.clearDeadline(now)
                }
                dao.insert(
                    Cache(
                        key = key,
                        value = json,
                        deadline = now + HomepageCachePolicy.MAX_DISK_TTL_MS,
                    )
                )
                val (retained, evicted) = HomepageCachePolicy.updateLruIndex(
                    readIndexLocked(dao),
                    key,
                )
                evicted.forEach { dao.delete(it) }
                writeIndexLocked(dao, retained)
            }
        }
    }

    private fun readIndexLocked(dao: CacheDao): List<String> {
        val raw = dao.get(HomepageCachePolicy.INDEX_KEY, Long.MAX_VALUE)
            ?: dao.get(HomepageCachePolicy.INDEX_KEY)?.value
            ?: return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    private fun writeIndexLocked(dao: CacheDao, keys: List<String>) {
        dao.insert(
            Cache(
                key = HomepageCachePolicy.INDEX_KEY,
                value = keys.joinToString("\n"),
                deadline = 0L,
            )
        )
    }
}
