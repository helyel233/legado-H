package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CacheBook
import io.legado.app.model.ChapterPrefetchPolicy
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 下一章正文预取。
 *
 * 背景：在线书翻到下一章时必须等网络返回才显示正文。
 * 这里在当前章正文就绪后后台抓取下一章并落盘，
 * 翻过去时直接命中本地缓存，不再等网络。
 */
object ChapterPrefetcher {

    /**
     * 当前预取任务。调度方在 IO 线程写入、取消方在主线程读写，
     * 不加 volatile 会读到过期值（漏取消或产生孤儿 Job）。
     */
    @Volatile
    private var prefetchJob: Job? = null

    /**
     * 预取下一章。
     *
     * 同一时刻只保留一个预取任务：翻章快时旧的直接取消，不堆积请求。
     * 调用方可能在主线程，这里只做两个轻量判断，查库与文件检查都放进协程里。
     *
     * [source] 必须由调用方在调度时刻捕获（与 [book] 同源）：若在任务执行时
     * 才读全局当前源，换书/换源窗口内会用新书源抓旧书章节，错配内容落盘。
     */
    fun prefetchNextChapter(
        book: Book,
        current: BookChapter,
        source: BookSource?,
        scope: CoroutineScope
    ) {
        if (!AppConfig.prefetchNextChapter) return
        if (book.isLocal) return
        if (source == null) return
        // 先建不启动的任务，再在锁内「取消旧 + 装新」，避免两个调度方并发时
        // 后写者覆盖先写者、先写者的 Job 变成孤儿仍在跑
        val newJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val next = appDb.bookChapterDao.getChapter(book.bookUrl, current.index + 1)
                ?: return@launch
            val shouldPrefetch = ChapterPrefetchPolicy.shouldPrefetch(
                enabled = true,
                isLocal = book.isLocal,
                networkAvailable = NetworkUtils.isAvailable(),
                hasNextChapter = true,
                alreadyCached = BookHelp.hasContent(book, next),
                downloading = CacheBook.isDownloading(next)
            )
            if (!shouldPrefetch) return@launch
            runCatching {
                // needSave 默认 true：抓到即落盘，下次读直接命中本地缓存
                WebBook.getContentAwait(source, book, next)
            }.onFailure {
                AppLog.put("预取下一章失败 " + book.name + " #" + next.index + ": " + it.localizedMessage)
            }
        }
        synchronized(this) {
            prefetchJob?.cancel()
            prefetchJob = newJob
        }
        newJob.start()
    }

    /**
     * 取消预取（离开书时调用）
     */
    fun cancel() {
        synchronized(this) {
            prefetchJob?.cancel()
            prefetchJob = null
        }
    }
}
