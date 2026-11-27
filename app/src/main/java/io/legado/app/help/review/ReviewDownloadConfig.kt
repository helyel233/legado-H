package io.legado.app.help.review

import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefInt
import splitties.init.appCtx

/** 评论下载核心配置定义；设置界面直接使用这里的默认值。 */
object ReviewDownloadConfig {
    const val ENGINE = PreferKey.reviewDownloadEngine

    enum class Number(
        val key: String,
        val title: Int,
        val default: Int,
        val minimum: Int = 1,
        val maximum: Int = Int.MAX_VALUE,
    ) {
        PAGE_TIMEOUT(PreferKey.reviewDownloadPageTimeout, R.string.review_download_page_timeout, 60000, 5_000, 120_000),
        DATA_TIMEOUT(PreferKey.reviewDownloadDataTimeout, R.string.review_download_data_timeout, 30000, 3_000, 120_000),
        PAGE_SIZE(PreferKey.reviewDownloadPageSize, R.string.review_download_page_size, 20, 1, 100);

        /**
         * 实际生效值。历史偏好越界时回退默认值并写回偏好。
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
}
