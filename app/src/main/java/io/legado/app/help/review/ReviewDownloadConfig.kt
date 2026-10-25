package io.legado.app.help.review

import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefInt
import splitties.init.appCtx

/**
 * 默认并发按设备核数自适应：低端机不再一上来就 8 路，但也不低于 2 路、不高于 8 路。
 * 高级设置里仍可手动调整（各并发项上界 32）。
 */
private fun defaultConcurrency(): Int =
    Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

/** 评论下载各阶段的唯一配置定义；设置界面直接使用这里的默认值。 */
object ReviewDownloadConfig {
    const val ENGINE = "reviewDownloadEngine"
    const val BACKOFF = "reviewDownloadBackoff"
    const val REUSE_REPLIES = "reviewDownloadReuseReplies"
    const val REUSE_RESOURCES = "reviewDownloadReuseResources"
    const val REQUIRE_AVATARS = "reviewDownloadRequireAvatars"
    const val REQUIRE_IMAGES = "reviewDownloadRequireImages"
    const val REQUIRE_CSS = "reviewDownloadRequireCss"
    const val REQUIRE_FONTS = "reviewDownloadRequireFonts"

    enum class Number(
        val key: String,
        val title: Int,
        val default: Int,
        val minimum: Int = 1,
        val maximum: Int = Int.MAX_VALUE,
    ) {
        DATA("reviewDownloadDataConcurrency", R.string.review_download_data_concurrency, defaultConcurrency(), 1, 32),
        REPLIES("reviewDownloadReplyConcurrency", R.string.review_download_reply_concurrency, defaultConcurrency(), 1, 32),
        RESOURCES("reviewDownloadResourceConcurrency", R.string.review_download_resource_concurrency, defaultConcurrency(), 1, 32),
        PAGES("reviewDownloadPageConcurrency", R.string.review_download_page_concurrency, 2, 1, 16),
        HEAVY("reviewDownloadHeavyConcurrency", R.string.review_download_heavy_concurrency, 1, 1, 8),
        PAGE_SIZE("reviewDownloadPageSize", R.string.review_download_page_size, 20, 1, 100),
        REPLY_SIZE("reviewDownloadReplySize", R.string.review_download_reply_size, 20, 1, 100),
        STABLE_INTERVAL("reviewDownloadStableInterval", R.string.review_download_stable_interval, 800, 0, 10_000),
        STABLE_ROUNDS("reviewDownloadStableRounds", R.string.review_download_stable_rounds, 3, 1, 20),
        PAGE_TIMEOUT("reviewDownloadPageTimeout", R.string.review_download_page_timeout, 60000, 0, 600_000),
        DATA_TIMEOUT("reviewDownloadDataTimeout", R.string.review_download_data_timeout, 30000, 0, 600_000),
        RESOURCE_TIMEOUT("reviewDownloadResourceTimeout", R.string.review_download_resource_timeout, 8000, 0, 600_000),
        RETRIES("reviewDownloadRetries", R.string.review_download_retries, 0, 0, 10),
        RETRY_INTERVAL("reviewDownloadRetryInterval", R.string.review_download_retry_interval, 1000, 0, 60_000);

        /**
         * 实际生效值。
         *
         * 历史版本、手工改偏好或导入配置都可能留下越界值（设置界面本身受输入校验
         * 保护）。这类坏值一律回退默认值并写回偏好：读取方散布在下载、预热和设置页
         * 各处，让它们各自处理异常只会把「一个字段坏了」放大成「设置页打不开、
         * 整次抓取失败」。
         */
        var value: Int
            get() {
                val stored = appCtx.getPrefInt(key, default)
                if (stored in minimum..maximum) return stored
                val fallback = default.coerceIn(minimum, maximum)
                AppLog.put("评论下载配置 $key 的历史值 $stored 超出 $minimum..$maximum，已回退为 $fallback")
                runCatching { appCtx.putPrefInt(key, fallback) }
                return fallback
            }
            set(value) {
                require(value in minimum..maximum) { "$key 必须落在 $minimum..$maximum" }
                appCtx.putPrefInt(key, value)
                ReviewDownloadScheduler.settingsChanged()
            }
    }

    enum class Engine(val value: String) {
        AUTO("auto"),
        DATA("data"),
        WEB("web")
    }

    /** 未知引擎名按 auto 处理：坏偏好不该让整次抓取失败或让设置页崩掉。 */
    val engine: Engine
        get() {
            val stored = appCtx.getPrefString(ENGINE, Engine.AUTO.value)
            if (Engine.entries.none { it.value == stored }) {
                AppLog.put("未知评论抓取引擎 $stored，按 auto 处理")
            }
            return parseEngine(stored)
        }

    internal fun parseEngine(raw: String?): Engine =
        Engine.entries.firstOrNull { it.value == raw } ?: Engine.AUTO

    enum class Backoff(val value: String) {
        FIXED("fixed"),
        LINEAR("linear"),
        EXPONENTIAL("exponential")
    }

    /** 未知退避方式按 fixed 处理：所有重试等同样长，最保守且不会算错等待时间。 */
    val backoff: Backoff
        get() {
            val stored = appCtx.getPrefString(BACKOFF, Backoff.FIXED.value)
            if (Backoff.entries.none { it.value == stored }) {
                AppLog.put("未知评论重试退避方式 $stored，按 fixed 处理")
            }
            return parseBackoff(stored)
        }

    internal fun parseBackoff(raw: String?): Backoff =
        Backoff.entries.firstOrNull { it.value == raw } ?: Backoff.FIXED

    fun enabled(key: String): Boolean = appCtx.getPrefBoolean(key, true)

    /**
     * 第 [attempt] 次重试（从 1 开始）前的等待毫秒数。
     *
     * 溢出是明确错误，不静默压低用户配置的等待时间；但「未知退避方式」这类坏偏好
     * 已经回落到 fixed，不会走到这里。
     */
    fun retryDelay(attempt: Int): Long =
        retryDelayFor(Number.RETRY_INTERVAL.value.toLong(), backoff, attempt)

    internal fun retryDelayFor(baseMillis: Long, mode: Backoff, attempt: Int): Long {
        require(attempt >= 1) { "评论重试序号从 1 开始，实际为 $attempt" }
        if (baseMillis <= 0L) return 0L
        return when (mode) {
            Backoff.FIXED -> baseMillis
            Backoff.LINEAR -> Math.multiplyExact(baseMillis, attempt.toLong())
            Backoff.EXPONENTIAL -> {
                require(attempt <= 63) { "评论重试指数退避超过 Long 毫秒表示范围" }
                Math.multiplyExact(baseMillis, 1L shl (attempt - 1))
            }
        }
    }
}
