package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.ReviewCache

data class ReviewChapterCount(
    val chapterUrl: String,
    val count: Int,
)

data class ReviewCacheEvictCandidate(
    val cacheKey: String,
    val sizeBytes: Long,
)

@Dao
interface ReviewCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entry: ReviewCache)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIgnoreAll(entries: List<ReviewCache>)

    @Query("SELECT * FROM review_cache WHERE cacheKey = :cacheKey LIMIT 1")
    fun get(cacheKey: String): ReviewCache?

    @Query("SELECT 1 FROM review_cache WHERE cacheKey = :cacheKey LIMIT 1")
    fun exists(cacheKey: String): Int?

    @Query("SELECT savedAt FROM review_cache WHERE cacheKey = :cacheKey LIMIT 1")
    fun savedAt(cacheKey: String): Long?

    @Query("SELECT buttonSrc FROM review_cache WHERE bookUrl = :bookUrl AND chapterUrl = :chapterUrl")
    fun buttonSrcsForChapter(bookUrl: String, chapterUrl: String): List<String>

    @Query("SELECT DISTINCT chapterUrl FROM review_cache WHERE bookUrl = :bookUrl")
    fun chapterUrlsForBook(bookUrl: String): List<String>

    @Query("SELECT chapterUrl, COUNT(*) AS count FROM review_cache WHERE bookUrl = :bookUrl GROUP BY chapterUrl")
    fun chapterCountsForBook(bookUrl: String): List<ReviewChapterCount>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM review_cache WHERE bookUrl = :bookUrl")
    fun totalBytesForBook(bookUrl: String): Long

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM review_cache")
    fun totalBytesAll(): Long

    @Query(
        """UPDATE review_cache
        SET savedAt = CASE WHEN savedAt < :savedAt THEN :savedAt ELSE savedAt END
        WHERE cacheKey = :cacheKey"""
    )
    fun touchSavedAt(cacheKey: String, savedAt: Long): Int

    @Query("UPDATE review_cache SET lastAccessAt = :lastAccessAt WHERE cacheKey = :cacheKey")
    fun touchLastAccessAt(cacheKey: String, lastAccessAt: Long): Int

    @Query("DELETE FROM review_cache WHERE cacheKey = :cacheKey")
    fun deleteByKey(cacheKey: String): Int

    @Query("DELETE FROM review_cache WHERE bookUrl = :bookUrl AND chapterUrl = :chapterUrl")
    fun deleteChapter(bookUrl: String, chapterUrl: String): Int

    @Query("DELETE FROM review_cache WHERE bookUrl = :bookUrl")
    fun deleteByBook(bookUrl: String): Int

    @Query("DELETE FROM review_cache")
    fun deleteAll(): Int

    @Query("DELETE FROM review_cache WHERE bookUrl = :bookUrl AND savedAt > 0 AND savedAt < :expireBefore")
    fun deleteExpiredForBook(bookUrl: String, expireBefore: Long): Int

    @Query("DELETE FROM review_cache WHERE savedAt > 0 AND savedAt < :expireBefore")
    fun deleteExpiredAll(expireBefore: Long): Int

    @Query(
        """SELECT cacheKey, sizeBytes FROM review_cache
        WHERE bookUrl = :bookUrl
        ORDER BY lastAccessAt ASC, savedAt ASC
        LIMIT :limit"""
    )
    fun oldestEntriesForBook(bookUrl: String, limit: Int): List<ReviewCacheEvictCandidate>

    @Query(
        """SELECT cacheKey, sizeBytes FROM review_cache
        ORDER BY lastAccessAt ASC, savedAt ASC
        LIMIT :limit"""
    )
    fun oldestEntriesGlobal(limit: Int): List<ReviewCacheEvictCandidate>

    @Query("DELETE FROM review_cache WHERE cacheKey IN (:keys)")
    fun deleteByKeys(keys: List<String>): Int

    @Query("SELECT * FROM review_cache WHERE bookUrl = :bookUrl")
    fun listByBook(bookUrl: String): List<ReviewCache>
}
