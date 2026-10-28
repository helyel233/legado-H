package io.legado.app.help.cache

import io.legado.app.constant.AppLog
import io.legado.app.help.review.ReviewSnapshotManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 评论快照抓取的进程内宿主。
 *
 * 这里原本是前台服务 ReviewCacheService。Android 规定前台服务必须挂一条通知，
 * 于是评论缓存的每一种行为（抓取进度、完成、失败）都会落到通知栏里。
 * 现在抓取直接在应用进程内完成，评论缓存不再产生任何通知。
 *
 * 代价：进程被系统回收时抓取会中断。CacheTaskStore 会把任务状态落盘，
 * 下次启动把 RUNNING 恢复成 INTERRUPTED，再由 CacheWorkerDispatcher 重新派发，
 * 评论缓存不会丢，只是需要重新开始一轮。
 */
internal object ReviewCaptureHost {

    /** 评论章节按顺序消费；页面预热由下游的 Capture Pipeline 负责。 */
    private const val CHAPTER_CONSUMER_COUNT = 1

    /** 队列为空时的轮询间隔。 */
    private const val IDLE_POLL_DELAY_MS = 1500L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()

    /** 派发请求版本号：消除"入队完成"与"宿主停机"之间的竞态。 */
    private val requestVersion = AtomicInteger(0)

    /** 正在执行的抓取数。 */
    private val activeCount = AtomicInteger(0)

    @Volatile
    private var worker: Job? = null

    /**
     * 保证有一个抓取循环在运行；重复调用是安全的。
     *
     * 必须在快照入队之后调用：抓取循环靠"队列里有东西"来判断是否继续工作。
     */
    fun ensureStarted() {
        requestVersion.incrementAndGet()
        synchronized(lock) {
            val current = worker
            if (current != null && current.isActive) return
            // CoroutineExceptionHandler 兜底：消费循环里任何未防护的抛出
            // （如 tryTakeTask 的 check/error）都不允许炸掉整个进程
            worker = scope.launch(CoroutineExceptionHandler { _, e ->
                AppLog.put("评论缓存消费循环异常退出\n${e.localizedMessage}", e)
            }) { consumeLoop() }
        }
    }

    private suspend fun consumeLoop() {
        var seenVersion = requestVersion.get()
        coroutineScope {
            repeat(CHAPTER_CONSUMER_COUNT) {
                launch {
                    while (isActive) {
                        var task = runCatching { ReviewSnapshotManager.tryTakeTask() }
                            .onFailure {
                                ensureActive()
                                AppLog.put("评论任务出队失败\n${it.localizedMessage}", it)
                            }
                            .getOrNull()
                        if (task == null) {
                            delay(IDLE_POLL_DELAY_MS)
                            task = ReviewSnapshotManager.tryTakeTask()
                            if (task == null) {
                                if (activeCount.get() > 0) continue
                                // 退出前确认没有新的派发请求到达，避免任务滞留无人消费
                                val latest = requestVersion.get()
                                if (latest != seenVersion) {
                                    seenVersion = latest
                                    continue
                                }
                                break
                            }
                        }
                        activeCount.incrementAndGet()
                        try {
                            val result = runCatching { ReviewSnapshotManager.processTask(task) }
                                .onFailure {
                                    ensureActive()
                                    AppLog.put(
                                        "评论快照任务处理失败 ${task.key}\n${it.localizedMessage}",
                                        it,
                                    )
                                }
                                .getOrElse { ReviewSnapshotManager.TaskResult.FAILED }
                            val chapterIndex = task.key.substringAfter('|').toIntOrNull()
                            if (chapterIndex != null &&
                                result != ReviewSnapshotManager.TaskResult.STOPPED
                            ) {
                                task.executionLease.let { lease ->
                                    CacheReviewWorkerRegistry.onChapterFinished(
                                        lease,
                                        chapterIndex,
                                        result == ReviewSnapshotManager.TaskResult.SUCCEEDED,
                                    )
                                }
                            }
                        } finally {
                            activeCount.decrementAndGet()
                        }
                    }
                }
            }
        }
        onWorkerStopped(seenVersion)
    }

    /** 抓取循环结束：释放宿主引用；退出期间来了新请求就立刻重新拉起。 */
    private fun onWorkerStopped(exitVersion: Int) {
        synchronized(lock) {
            worker = null
            if (requestVersion.get() != exitVersion) {
                worker = scope.launch { consumeLoop() }
            }
        }
        if (CacheReviewWorkerRegistry.hasCoordinatorTasks()) {
            AppLog.put("评论缓存宿主结束：收敛未完成的 Coordinator 任务")
            CacheReviewWorkerRegistry.onServiceFinished(cancelled = true)
        }
    }
}
