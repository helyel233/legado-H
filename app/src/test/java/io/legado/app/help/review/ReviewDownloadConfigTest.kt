package io.legado.app.help.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评论下载配置的「坏偏好不崩」契约。
 *
 * 这些读取点散布在下载、预热和设置页，历史版本、手工改偏好或导入配置都可能留下非法值；
 * 一旦某个读取点重新引入抛异常的分支，就会把「一个字段坏了」放大成崩溃，这里用测试钉住。
 */
class ReviewDownloadConfigTest {

    @Test
    fun everyNumberDefaultStaysWithinItsBounds() {
        ReviewDownloadConfig.Number.entries.forEach { number ->
            assertTrue(
                "${number.name} default=${number.default} not in ${number.minimum}..${number.maximum}",
                number.default in number.minimum..number.maximum
            )
        }
    }

    @Test
    fun unknownEngineAndBackoffFallBackInsteadOfThrowing() {
        assertEquals(ReviewDownloadConfig.Engine.AUTO, ReviewDownloadConfig.parseEngine(null))
        assertEquals(ReviewDownloadConfig.Engine.AUTO, ReviewDownloadConfig.parseEngine(""))
        assertEquals(
            ReviewDownloadConfig.Engine.AUTO,
            ReviewDownloadConfig.parseEngine("something-new")
        )
        assertEquals(ReviewDownloadConfig.Engine.WEB, ReviewDownloadConfig.parseEngine("web"))

        assertEquals(ReviewDownloadConfig.Backoff.FIXED, ReviewDownloadConfig.parseBackoff(null))
        assertEquals(
            ReviewDownloadConfig.Backoff.FIXED,
            ReviewDownloadConfig.parseBackoff("something-new")
        )
        assertEquals(
            ReviewDownloadConfig.Backoff.LINEAR,
            ReviewDownloadConfig.parseBackoff("linear")
        )
    }

    @Test
    fun retryDelayFollowsTheSelectedBackoff() {
        assertEquals(
            1000L,
            ReviewDownloadConfig.retryDelayFor(1000, ReviewDownloadConfig.Backoff.FIXED, 3)
        )
        assertEquals(
            3000L,
            ReviewDownloadConfig.retryDelayFor(1000, ReviewDownloadConfig.Backoff.LINEAR, 3)
        )
        assertEquals(
            4000L,
            ReviewDownloadConfig.retryDelayFor(1000, ReviewDownloadConfig.Backoff.EXPONENTIAL, 3)
        )
    }

    @Test
    fun retryDelayIsZeroWhenIntervalIsZero() {
        ReviewDownloadConfig.Backoff.entries.forEach { backoff ->
            assertEquals(0L, ReviewDownloadConfig.retryDelayFor(0L, backoff, 1))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun retryDelayRejectsNonPositiveAttempt() {
        ReviewDownloadConfig.retryDelayFor(1000, ReviewDownloadConfig.Backoff.LINEAR, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun exponentialBackoffRejectsAttemptBeyondLongShiftRange() {
        ReviewDownloadConfig.retryDelayFor(1, ReviewDownloadConfig.Backoff.EXPONENTIAL, 64)
    }

    @Test(expected = ArithmeticException::class)
    fun exponentialBackoffDoesNotSilentlyWrapAround() {
        ReviewDownloadConfig.retryDelayFor(2, ReviewDownloadConfig.Backoff.EXPONENTIAL, 63)
    }
}
