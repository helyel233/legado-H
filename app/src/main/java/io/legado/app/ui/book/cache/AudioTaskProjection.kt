package io.legado.app.ui.book.cache

import io.legado.app.help.cache.CacheKind
import io.legado.app.help.cache.CacheLifecycle
import io.legado.app.help.cache.CacheLifecycleRules
import io.legado.app.help.cache.CachePhase
import io.legado.app.help.cache.CacheProgressMode
import io.legado.app.help.cache.CacheProgressSnapshot
import io.legado.app.help.cache.CacheProgressState
import io.legado.app.help.cache.CacheSnapshot
import io.legado.app.help.cache.CacheTaskState
import io.legado.app.help.cache.CacheUnitStatus

/**
 * 缓存管理页的音频任务展示模型。
 * 由 [AudioTaskProjection] 从缓存协调器状态投影得到，UI 组件不直接依赖协调器内部模型。
 */
enum class CacheTaskStatus {
    PENDING,
    RESOLVING,
    CACHING,
    PAUSED,
    COMPLETED,
    CANCELLED,
    FAILED
}

data class AudioCacheTaskState(
    val bookUrl: String,
    val bookName: String,
    val totalChapters: Int,
    val completedChapters: Int = 0,
    val currentChapterIndex: Int = 0,
    val currentChapterTitle: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val speedBytesPerSecond: Long = 0L,
    val status: CacheTaskStatus = CacheTaskStatus.PENDING,
    val message: String = "",
    val active: Boolean = true
)

/** 生成投影消息所需的事实集合；生产实现使用字符串资源，测试可注入纯函数。 */
data class AudioTaskMessageFacts(
    val status: CacheTaskStatus,
    val completedChapters: Int,
    val totalChapters: Int,
    val currentChapterIndex: Int,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val error: String?
)

/**
 * 缓存协调器状态 → 缓存管理页音频任务展示模型的投影。
 *
 * 每本书最多生成一个代表任务：活跃任务优先（MEDIA 优先于 REVIEW），
 * 无活跃任务时取最近更新的终态任务，供列表完成刷新与卡片终态消息使用。
 * 投影只覆盖音视频领域（AUDIO/VIDEO 的 MEDIA/REVIEW 任务），正文与 TTS 任务不会进入音频卡片。
 */
object AudioTaskProjection {

    fun project(
        snapshot: CacheSnapshot,
        progress: CacheProgressSnapshot,
        messageFor: (AudioTaskMessageFacts) -> String = { "" },
    ): Map<String, AudioCacheTaskState> {
        val result = linkedMapOf<String, AudioCacheTaskState>()
        snapshot.sessions.asSequence()
            .flatMap { it.tasks.asSequence() }
            .filter { it.isAudioDomainTask() }
            .groupBy { it.bookUrl }
            .forEach { (bookUrl, tasks) ->
                val task = tasks.representativeTask() ?: return@forEach
                val progressState = task.currentProgressState(progress)
                val completed = task.units.count { it.status == CacheUnitStatus.SUCCEEDED }
                val total = task.units.size
                val displayIndex = (completed + 1).coerceAtMost(total.coerceAtLeast(1))
                val byteProgress = progressState?.takeIf { it.mode == CacheProgressMode.BYTES }
                val displayStatus = task.displayStatus(progressState)
                val facts = AudioTaskMessageFacts(
                    status = displayStatus,
                    completedChapters = completed,
                    totalChapters = total,
                    currentChapterIndex = displayIndex,
                    downloadedBytes = byteProgress?.current ?: 0L,
                    totalBytes = byteProgress?.total,
                    error = task.error,
                )
                result[bookUrl] = AudioCacheTaskState(
                    bookUrl = bookUrl,
                    bookName = task.bookName,
                    totalChapters = total,
                    completedChapters = completed,
                    currentChapterIndex = displayIndex,
                    downloadedBytes = byteProgress?.current ?: 0L,
                    totalBytes = byteProgress?.total,
                    status = displayStatus,
                    message = messageFor(facts),
                    // 与旧展示模型对齐：暂停中不算 active，由 status 单独标识。
                    active = !CacheLifecycleRules.isTerminal(task.status) &&
                        task.status != CacheLifecycle.PAUSED,
                )
            }
        return result
    }

    private fun CacheTaskState.isAudioDomainTask(): Boolean {
        return (kind == CacheKind.AUDIO || kind == CacheKind.VIDEO) &&
            (phase == CachePhase.MEDIA || phase == CachePhase.REVIEW)
    }

    private fun List<CacheTaskState>.representativeTask(): CacheTaskState? {
        val active = filterNot { CacheLifecycleRules.isTerminal(it.status) }
        if (active.isNotEmpty()) {
            return active.firstOrNull { it.phase == CachePhase.MEDIA } ?: active.first()
        }
        return maxByOrNull { it.updatedAt }
    }

    private fun CacheTaskState.currentProgressState(progress: CacheProgressSnapshot): CacheProgressState? {
        return progress.states.asSequence()
            .filter { it.sessionId == sessionId && it.taskId == taskId }
            .maxByOrNull { it.updatedAt }
    }

    private fun CacheTaskState.displayStatus(progress: CacheProgressState?): CacheTaskStatus {
        return when (status) {
            CacheLifecycle.QUEUED -> CacheTaskStatus.PENDING
            CacheLifecycle.RUNNING -> when (progress?.mode) {
                CacheProgressMode.INDETERMINATE -> CacheTaskStatus.RESOLVING
                else -> CacheTaskStatus.CACHING
            }
            CacheLifecycle.PAUSING,
            CacheLifecycle.PAUSED,
            CacheLifecycle.INTERRUPTED -> CacheTaskStatus.PAUSED
            CacheLifecycle.CANCELLING,
            CacheLifecycle.CANCELLED -> CacheTaskStatus.CANCELLED
            CacheLifecycle.COMPLETED -> CacheTaskStatus.COMPLETED
            CacheLifecycle.FAILED -> CacheTaskStatus.FAILED
        }
    }
}

/** 该书所有非终态任务（任意领域），用于"开始/停止"按钮与活跃预检。 */
fun CacheSnapshot.activeTasksFor(bookUrl: String): List<CacheTaskState> {
    return sessions.asSequence()
        .flatMap { it.tasks.asSequence() }
        .filter { it.bookUrl == bookUrl && !CacheLifecycleRules.isTerminal(it.status) }
        .toList()
}

/** 是否存在任意非终态任务，用于批量下载菜单的运行中判断。 */
fun CacheSnapshot.hasActiveTasks(): Boolean {
    return sessions.asSequence()
        .flatMap { it.tasks.asSequence() }
        .any { !CacheLifecycleRules.isTerminal(it.status) }
}
