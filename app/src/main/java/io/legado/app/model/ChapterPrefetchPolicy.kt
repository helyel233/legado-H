package io.legado.app.model

/**
 * 下一章预取的决策（纯函数）。
 *
 * 「预取下一章」是在线阅读最直接的提速点：当前章正文就绪后，
 * 后台把下一章抓下来落盘，翻过去时直接命中本地缓存，不再等网络。
 */
internal object ChapterPrefetchPolicy {

    /**
     * 是否应该预取下一章正文
     *
     * @param enabled 预取开关
     * @param isLocal 本地书不需要预取
     * @param networkAvailable 当前是否有可用网络
     * @param hasNextChapter 是否存在下一章
     * @param alreadyCached 下一章是否已有本地缓存
     * @param downloading 下一章是否已在下载中
     */
    fun shouldPrefetch(
        enabled: Boolean,
        isLocal: Boolean,
        networkAvailable: Boolean,
        hasNextChapter: Boolean,
        alreadyCached: Boolean,
        downloading: Boolean
    ): Boolean {
        if (!enabled) return false
        if (isLocal) return false
        if (!networkAvailable) return false
        if (!hasNextChapter) return false
        if (alreadyCached) return false
        if (downloading) return false
        return true
    }

    /**
     * 这段内容是不是加载失败的提示文案。
     *
     * 失败时不该再预取下一章（网络多半正不通）。
     */
    fun isLoadFailureContent(content: String): Boolean {
        return content.startsWith("获取正文失败") || content.startsWith("加载正文失败")
    }
}
