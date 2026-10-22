package io.legado.app.model

/** Maximum failures after which a chapter is left alone by pre-download. */
internal const val PRE_DOWNLOAD_MAX_FAILURES = 3

/** Chapters kept behind the current one, at most. */
internal const val PRE_DOWNLOAD_BACKWARD_CHAPTERS = 5

/**
 * Which chapters pre-download should fetch.
 *
 * Pre-download warms the chapters the reader is about to reach, plus a few behind in case
 * they turn back. Two things decide whether a chapter is worth fetching: it must not be
 * downloaded already, and it must not have failed too often — retrying a chapter that
 * keeps failing only wastes the reader's connection.
 *
 * The plan only ever names chapters that exist: a chapter index outside the book is
 * meaningless, and handing one to the cache would create a task that can never finish.
 */
internal object PreDownloadPlan {

    fun of(
        durChapterIndex: Int,
        chapterSize: Int,
        preDownloadNum: Int,
        isDownloaded: (Int) -> Boolean,
        failCount: (Int) -> Int
    ): List<Int> {
        if (chapterSize <= 0) return emptyList()
        val lastIndex = chapterSize - 1
        val indexes = linkedSetOf<Int>()
        // 往后：当前章之后 preDownloadNum 章内（不含紧邻的两章，翻页时会自然加载到）
        val forwardLast = minOf(durChapterIndex + preDownloadNum, lastIndex)
        for (index in durChapterIndex + 2..forwardLast) {
            if (worthFetching(index, isDownloaded, failCount)) indexes.add(index)
        }
        // 往前：最多往回 5 章（同样受 preDownloadNum 约束）
        val backwardFirst = durChapterIndex - minOf(PRE_DOWNLOAD_BACKWARD_CHAPTERS, preDownloadNum)
        for (index in durChapterIndex - 2 downTo backwardFirst) {
            if (index < 0) break
            if (worthFetching(index, isDownloaded, failCount)) indexes.add(index)
        }
        return indexes.toList()
    }

    private fun worthFetching(
        index: Int,
        isDownloaded: (Int) -> Boolean,
        failCount: (Int) -> Int
    ): Boolean {
        if (index < 0) return false
        if (isDownloaded(index)) return false
        return failCount(index) < PRE_DOWNLOAD_MAX_FAILURES
    }
}
