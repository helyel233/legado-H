package io.legado.app.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 书源校验会话状态（后端唯一真相，UI 只读）。
 *
 * 此前"校验进行中"的会话体验长在旧 [io.legado.app.ui.book.source.manage.BookSourceActivity]
 * 里：订阅 EventBus.CHECK_SOURCE 弹进度 Snackbar、500ms 轮询 [Debug.debugMessageMap]
 * 刷每源结果、完成时翻转 [Debug.isChecking]。旧 UI 退役后这些必须是后端状态——
 * 服务写、任何 UI collect，新壳不必继承旧 Activity。
 *
 * 写入方：[io.legado.app.service.CheckSourceService]（生命周期与进度）、
 * [Debug]（每源实时消息，与 debugMessageMap 同源双写）。
 */
object CheckSourceSession {

    data class State(
        /** 校验服务是否在跑 */
        val running: Boolean = false,
        /** 进度文案（已本地化，直接展示） */
        val progressText: String = "",
        /** 已完成数 */
        val finishCount: Int = 0,
        /** 总数 */
        val originSize: Int = 0,
        /** 每源最新校验消息（url → 消息），与 Debug.debugMessageMap 同步 */
        val sourceMessages: Map<String, String> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = originSize == 0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 读取单个源的校验消息（Compose 里避免整表重组时用）。 */
    fun messageOf(sourceUrl: String): String? = _state.value.sourceMessages[sourceUrl]

    /** 服务开始一轮校验。 */
    fun onStart(originSize: Int) {
        _state.value = State(
            running = true,
            originSize = originSize,
            finishCount = 0,
            sourceMessages = emptyMap(),
        )
    }

    /** 服务进度推进（每完成一个源；progressText 为服务合成的本地化文案）。 */
    fun onProgress(finishCount: Int, originSize: Int, progressText: String) {
        _state.update {
            it.copy(
                finishCount = finishCount,
                originSize = originSize,
                progressText = progressText,
            )
        }
    }

    /** 每源实时消息（由 Debug 写入，保持与 debugMessageMap 同步）。 */
    fun onSourceMessage(sourceUrl: String, message: String) {
        _state.update { it.copy(sourceMessages = it.sourceMessages + (sourceUrl to message)) }
    }

    /** 校验结束（正常完成或服务停止）。 */
    fun onStop() {
        _state.update { it.copy(running = false) }
    }
}
