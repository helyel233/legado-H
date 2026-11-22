package io.legado.app.ui.main.homepage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主页模块加载并发调度器。
 *
 * 约束与职责：
 * 1. 全局最大并发度限制（默认 3），避免一次打满网络与 JS/WebView 池；
 * 2. 同书源串行排队（同一 `sourceUrl` 同一时间最多执行 1 个请求），防止单站被同源多模块并发打爆或触发限流；
 * 3. 三级优先级调度：首屏可见 (`PRIORITY_VISIBLE`) > 预加载/屏外 (`PRIORITY_PRELOAD`) > 后台 SWR 刷新 (`PRIORITY_REVALIDATE`)；
 * 4. 支持按 `visibleKeys` 动态升降排队任务优先级（滑入提升、滑出恢复基准优先级）。
 */
internal class HomepageLoadScheduler(
    scope: CoroutineScope,
    private val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
) {

    companion object {
        const val DEFAULT_MAX_CONCURRENCY = 3
        const val PRIORITY_VISIBLE = 0
        const val PRIORITY_PRELOAD = 1
        const val PRIORITY_REVALIDATE = 2
    }

    private data class PendingTask(
        val key: String,
        val sourceUrl: String,
        val basePriority: Int,
        var priority: Int,
        val order: Int,
        val seq: Long,
        val taskJob: CompletableJob,
        val block: suspend () -> Unit,
    )

    private val schedulerSupervisor = SupervisorJob(scope.coroutineContext[Job])
    private val schedulerScope = CoroutineScope(scope.coroutineContext + schedulerSupervisor)

    private val mutex = Mutex()
    private var seqCounter = 0L
    private val pending = mutableListOf<PendingTask>()
    private val runningJobs = mutableMapOf<String, Job>()
    private val runningSources = mutableSetOf<String>()

    /**
     * 入队一个模块/页签加载任务。若同 `key` 已在排队中则取消旧任务并替换；
     * 若同 `key` 正在运行中则先取消旧任务后再调度。
     */
    suspend fun enqueue(
        key: String,
        sourceUrl: String,
        priority: Int,
        order: Int,
        onFinally: (() -> Unit)? = null,
        block: suspend () -> Unit,
    ): Job {
        val outerJob: CompletableJob = Job(schedulerSupervisor)
        val finallyOnce = AtomicBoolean(false)
        outerJob.invokeOnCompletion { cause ->
            if (finallyOnce.compareAndSet(false, true)) {
                onFinally?.invoke()
            }
            if (cause is CancellationException && schedulerSupervisor.isActive) {
                schedulerScope.launch { removePending(key, outerJob) }
            }
        }
        mutex.withLock {
            val replaced = pending.filter { it.key == key }
            if (replaced.isNotEmpty()) {
                pending.removeAll { it.key == key }
                replaced.forEach { it.taskJob.cancel() }
            }
            runningJobs[key]?.cancel()
            pending.add(
                PendingTask(
                    key = key,
                    sourceUrl = sourceUrl,
                    basePriority = priority,
                    priority = priority,
                    order = order,
                    seq = seqCounter++,
                    taskJob = outerJob,
                    block = block,
                )
            )
            drainLocked()
        }
        return outerJob
    }

    /**
     * 动态调整待执行任务的优先级：
     * 属于 [visibleKeys] 的待执行任务提升为 [PRIORITY_VISIBLE]，滑出可视区的任务恢复其基准优先级。
     */
    suspend fun updateVisibleKeys(visibleKeys: Set<String>) {
        mutex.withLock {
            var changed = false
            pending.forEach { task ->
                val nextPriority = if (task.key in visibleKeys) {
                    PRIORITY_VISIBLE
                } else {
                    task.basePriority
                }
                if (task.priority != nextPriority) {
                    task.priority = nextPriority
                    changed = true
                }
            }
            if (changed) {
                drainLocked()
            }
        }
    }

    suspend fun isActive(key: String): Boolean = mutex.withLock {
        pending.any { it.key == key } || runningJobs[key]?.isActive == true
    }

    suspend fun cancel(key: String) {
        mutex.withLock {
            val removed = pending.filter { it.key == key }
            if (removed.isNotEmpty()) {
                pending.removeAll { it.key == key }
                removed.forEach { it.taskJob.cancel() }
            }
            runningJobs.remove(key)?.cancel()
        }
    }

    suspend fun cancelAll() {
        mutex.withLock {
            pending.forEach { it.taskJob.cancel() }
            pending.clear()
            runningJobs.values.forEach { it.cancel() }
            runningJobs.clear()
            runningSources.clear()
        }
    }

    /** 同步关闭调度器（供 ViewModel.onCleared 调用，不依赖已取消作用域再 launch） */
    fun close() {
        schedulerSupervisor.cancel()
    }

    private suspend fun removePending(key: String, job: Job) {
        mutex.withLock {
            pending.removeAll { it.key == key && it.taskJob === job }
        }
    }

    private fun drainLocked() {
        while (runningJobs.size < maxConcurrency && pending.isNotEmpty()) {
            val nextIndex = pickNextIndexLocked() ?: break
            val task = pending.removeAt(nextIndex)
            if (!task.taskJob.isActive) continue

            runningSources.add(task.sourceUrl)
            // LAZY 启动确保 runningJobs[key] 先完成登记，避免 Main.immediate/同步返回导致槽位泄漏
            lateinit var launched: Job
            launched = schedulerScope.launch(task.taskJob, start = CoroutineStart.LAZY) {
                try {
                    task.block()
                } finally {
                    withContext(NonCancellable) {
                        mutex.withLock {
                            if (runningJobs[task.key] === launched) {
                                runningJobs.remove(task.key)
                            }
                            runningSources.remove(task.sourceUrl)
                            drainLocked()
                        }
                        task.taskJob.complete()
                    }
                }
            }
            runningJobs[task.key] = launched
            launched.start()
        }
    }

    private fun pickNextIndexLocked(): Int? {
        var bestIdx: Int? = null
        var bestTask: PendingTask? = null
        for (i in pending.indices) {
            val candidate = pending[i]
            if (candidate.sourceUrl in runningSources) continue
            val currentBest = bestTask
            if (currentBest == null || isHigherPriority(candidate, currentBest)) {
                bestIdx = i
                bestTask = candidate
            }
        }
        return bestIdx
    }

    private fun isHigherPriority(a: PendingTask, b: PendingTask): Boolean {
        if (a.priority != b.priority) return a.priority < b.priority
        if (a.order != b.order) return a.order < b.order
        return a.seq < b.seq
    }
}
