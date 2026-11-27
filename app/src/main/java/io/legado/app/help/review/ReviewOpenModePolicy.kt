package io.legado.app.help.review

import io.legado.app.help.config.AppConfig

/**
 * 评论打开方式取值归一化纯策略：
 * 独立于 [AppConfig] 单例初始化（不触发 `appCtx`），可在纯 JVM 单测中直接校验。
 */
internal object ReviewOpenModePolicy {

    /** 将历史 4 模式取值及未知值平滑归一到当前三模式（历史 snapshot_only 保持映射到缓存优先）。 */
    fun normalize(raw: String?): String = when (raw) {
        AppConfig.ReviewOpenMode.OFFLINE -> AppConfig.ReviewOpenMode.OFFLINE
        AppConfig.ReviewOpenMode.SNAPSHOT_FIRST, "snapshot_only" -> AppConfig.ReviewOpenMode.SNAPSHOT_FIRST
        AppConfig.ReviewOpenMode.NETWORK, "network_only" -> AppConfig.ReviewOpenMode.NETWORK
        else -> AppConfig.ReviewOpenMode.NETWORK
    }
}
