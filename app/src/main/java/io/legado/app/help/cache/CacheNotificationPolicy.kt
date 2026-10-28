package io.legado.app.help.cache

/**
 * 缓存通知里"什么内容可以出现"的判定规则（纯逻辑，可单测）。
 *
 * 评论缓存静默时，通知栏里不允许出现任何评论相关内容：
 * - 进度通知只展示非评论任务；
 * - 当前只剩评论任务在跑时，一条通知都不发；
 * - 终态通知同理：评论任务本身不通知，但正文/媒体任务的完成通知照旧保留。
 */
internal object CacheNotificationPolicy {

    /** 评论缓存任务：REVIEW 阶段且存在正文/媒体前置的任务。 */
    fun isReviewTask(kind: CacheKind, phase: CachePhase): Boolean =
        phase == CachePhase.REVIEW && kind.reviewPrerequisitePhase() != null

    /** 通知里允许展示的任务列表；静默时评论任务被剔除。 */
    fun visibleTasks(tasks: List<CacheTaskState>, silenced: Boolean): List<CacheTaskState> {
        if (!silenced) return tasks
        return tasks.filterNot { isReviewTask(it.kind, it.phase) }
    }

    /** 是否还有可以出现在通知栏里的活跃任务（非评论任务）。 */
    fun hasVisibleActiveTask(
        tasks: List<CacheTaskState>,
        silenced: Boolean,
        isTerminal: (CacheTaskState) -> Boolean,
    ): Boolean = visibleTasks(tasks.filterNot(isTerminal), silenced).isNotEmpty()

    /** 终态任务是否允许发通知：静默时评论任务不通知。 */
    fun allowsTerminalNotice(task: CacheTaskState?, silenced: Boolean): Boolean {
        if (task == null) return false
        if (!silenced) return true
        return !isReviewTask(task.kind, task.phase)
    }
}
