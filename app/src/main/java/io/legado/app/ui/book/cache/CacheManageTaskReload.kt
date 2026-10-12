package io.legado.app.ui.book.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 缓存管理页的「任务结束/失踪 → 刷新列表」调度器。
 * 此前写在 CacheManageActivity；V5 壳迁移时抽出共用，禁止两壳各抄一份。
 */
class CacheManageTaskReloadController(
    private val scope: CoroutineScope,
    private val audioModeActive: () -> Boolean,
    private val currentBookUrls: () -> Set<String>,
    private val reloadAudio: () -> Unit,
    private val reloadCurrent: () -> Unit,
) {
    private var audioReloadJob: Job? = null
    private var lastMissingTaskReloadAt = 0L
    private val handledTerminalTaskReloads = hashSetOf<String>()

    /** 听读任务状态变化：活跃任务不在列表（刚触发）→ 延迟补载；任务终态 → 延迟刷新。 */
    fun onAudioStates(states: Map<String, AudioCacheTaskState>) {
        val activeTaskBookUrls = states.values.asSequence()
            .filter { it.active }
            .mapTo(hashSetOf()) { it.bookUrl }
        if (activeTaskBookUrls.isNotEmpty()) {
            val missing = activeTaskBookUrls - currentBookUrls()
            if (missing.isNotEmpty()) {
                val now = System.currentTimeMillis()
                if (now - lastMissingTaskReloadAt > MISSING_TASK_RELOAD_INTERVAL_MS && audioModeActive()) {
                    lastMissingTaskReloadAt = now
                    scheduleAudioTaskReload(MISSING_TASK_RELOAD_DELAY_MS)
                }
            }
        }
        states.values
            .filter { !it.active && it.status.isTerminalForListRefresh() }
            .forEach { state ->
                val key = "${state.bookUrl}:${state.status}:${state.completedChapters}:${state.totalChapters}"
                if (handledTerminalTaskReloads.add(key)) {
                    scheduleAudioTaskReload(TERMINAL_TASK_RELOAD_DELAY_MS)
                }
            }
    }

    /** WebDav 任务状态变化：终态任务直接刷新当前模式列表。 */
    fun onWebDavStates(states: Map<String, WebDavTaskState>) {
        states.values
            .filter { !it.active && it.status.isTerminalForWebDavRefresh() }
            .forEach { state ->
                val key = "webdav:${state.key}:${state.type}:${state.status}"
                if (handledTerminalTaskReloads.add(key)) {
                    reloadCurrent()
                }
            }
    }

    private fun scheduleAudioTaskReload(delayMs: Long) {
        if (audioReloadJob?.isActive == true) return
        audioReloadJob = scope.launch {
            delay(delayMs)
            if (audioModeActive()) {
                reloadAudio()
            }
        }
    }
}

internal fun CacheTaskStatus.isTerminalForListRefresh(): Boolean {
    return this == CacheTaskStatus.COMPLETED ||
        this == CacheTaskStatus.PAUSED ||
        this == CacheTaskStatus.CANCELLED ||
        this == CacheTaskStatus.FAILED
}

internal fun WebDavTaskStatus.isTerminalForWebDavRefresh(): Boolean {
    return this == WebDavTaskStatus.COMPLETED ||
        this == WebDavTaskStatus.CANCELLED ||
        this == WebDavTaskStatus.FAILED
}

/** 当前列表项（含多源变体）覆盖的 bookUrl 集合，供失踪任务判定。 */
fun Iterable<CacheBookItem>.toVisibleBookUrls(): Set<String> = buildSet {
    this@toVisibleBookUrls.forEach { item ->
        if (item.sourceVariants.isEmpty()) {
            add(item.book.bookUrl)
        } else {
            item.sourceVariants.forEach { add(it.book.bookUrl) }
        }
    }
}

private const val MISSING_TASK_RELOAD_INTERVAL_MS = 2500L
private const val MISSING_TASK_RELOAD_DELAY_MS = 250L
private const val TERMINAL_TASK_RELOAD_DELAY_MS = 600L
