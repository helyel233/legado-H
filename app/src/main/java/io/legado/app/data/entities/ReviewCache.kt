package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest

@Entity(
    tableName = "review_cache",
    indices = [
        Index(value = ["bookUrl", "chapterUrl"]),
        Index(value = ["bookUrl", "lastAccessAt"]),
        Index(value = ["savedAt"]),
    ]
)
data class ReviewCache(
    @PrimaryKey
    val cacheKey: String,
    val bookUrl: String,
    val chapterUrl: String,
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val buttonSrc: String,
    val url: String = "",
    val title: String = "",
    val html: String,
    val downloadEngine: String = "web",
    val savedAt: Long = 0L,
    val lastAccessAt: Long = 0L,
    val sizeBytes: Long = 0L,
) {
    companion object {
        private val HEX_CHARS = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
        )

        fun cacheKey(bookUrl: String, chapterUrl: String, buttonSrc: String): String {
            val raw = "$bookUrl\n${chapterUrl.trim()}\n${buttonSrc.trim()}"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
            val chars = CharArray(digest.size * 2)
            for (i in digest.indices) {
                val b = digest[i].toInt() and 0xFF
                chars[i * 2] = HEX_CHARS[b ushr 4]
                chars[i * 2 + 1] = HEX_CHARS[b and 0x0F]
            }
            return String(chars)
        }
    }
}
