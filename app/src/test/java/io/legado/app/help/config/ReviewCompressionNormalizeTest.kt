package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 评论压缩阈值的下限归一化。
 *
 * 0/负数阈值会让压缩失去意义，也会让抓取侧的资源判定失效；读写两侧都必须收敛到
 * [AppConfig.MIN_REVIEW_COMPRESSION_KB]。
 */
class ReviewCompressionNormalizeTest {

    @Test
    fun nonPositiveThresholdsAreRaisedToTheMinimum() {
        assertEquals(
            AppConfig.MIN_REVIEW_COMPRESSION_KB,
            normalizeReviewCompressionKb(0)
        )
        assertEquals(
            AppConfig.MIN_REVIEW_COMPRESSION_KB,
            normalizeReviewCompressionKb(-1024)
        )
    }

    @Test
    fun validThresholdsAreKeptAsIs() {
        assertEquals(
            AppConfig.DEFAULT_REVIEW_AVATAR_COMPRESSION_KB,
            normalizeReviewCompressionKb(AppConfig.DEFAULT_REVIEW_AVATAR_COMPRESSION_KB)
        )
        assertEquals(
            AppConfig.DEFAULT_REVIEW_IMAGE_COMPRESSION_KB,
            normalizeReviewCompressionKb(AppConfig.DEFAULT_REVIEW_IMAGE_COMPRESSION_KB)
        )
    }
}
