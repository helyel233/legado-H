package io.legado.app.data.repository.debug

import io.legado.app.model.debug.DebugCategory
import io.legado.app.model.debug.DebugEvent
import io.legado.app.model.debug.DebugLevel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 调试事件中心（移植自 Legado_Max）
 *
 * 统一管理和分发调试事件的核心单例。
 * 职责：
 * - 接收所有调试事件
 * - 提供实时事件流（SharedFlow）
 * - 维护内存环形缓冲区（ArrayDeque）
 * - 提供清空、导出、按分类/级别查询等能力
 */
object DebugEventCenter {

    /** 实时事件流 */
    private val _eventFlow = MutableSharedFlow<DebugEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 对外暴露的只读事件流 */
    val eventFlow: SharedFlow<DebugEvent> = _eventFlow.asSharedFlow()

    /** 内存环形缓冲区 */
    private val _events = ArrayDeque<DebugEvent>()

    /** 内存中最大保留的事件数量 */
    const val MAX_EVENTS = 500
    private const val MAX_MESSAGE_CHARS = 4096
    private const val MAX_DETAIL_CHARS = 4096

    /** 当前内存中的事件总数 */
    val eventCount: Int get() = synchronized(_events) { _events.size }

    suspend fun emit(event: DebugEvent) {
        val boundedEvent = event.copy(
            message = event.message.limitForEvent(MAX_MESSAGE_CHARS),
            detail = event.detail?.limitForEvent(MAX_DETAIL_CHARS)
        )
        synchronized(_events) {
            _events.addFirst(boundedEvent)
            while (_events.size > MAX_EVENTS) {
                _events.removeLast()
            }
        }
        _eventFlow.emit(boundedEvent)
    }

    private fun String.limitForEvent(maxChars: Int): String {
        if (length <= maxChars) return this
        return take(maxChars) + "\n...[truncated ${length - maxChars} chars]"
    }

    /** 按分类过滤日志（ALL 返回全部） */
    fun getLogsByCategory(
        category: DebugCategory,
        limit: Int = MAX_EVENTS
    ): List<DebugEvent> {
        return synchronized(_events) {
            if (category == DebugCategory.ALL) {
                _events.take(limit)
            } else {
                _events.filter { it.category == category }.take(limit)
            }
        }
    }

    /** 按级别过滤日志 */
    fun getLogsByMinLevel(level: DebugLevel, limit: Int = MAX_EVENTS): List<DebugEvent> {
        return synchronized(_events) {
            _events.filter { it.level.priority >= level.priority }.take(limit)
        }
    }

    /** 清空所有日志 */
    fun clear() {
        synchronized(_events) {
            _events.clear()
        }
    }

    /** 导出为纯文本格式 */
    fun exportToText(): String {
        return synchronized(_events) {
            buildString {
                append("=== 调试日志导出 ===\n")
                append(
                    "导出时间: ${
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    }\n"
                )
                append("总条数: ${_events.size}\n\n")
                _events.forEachIndexed { index, event ->
                    append("--- [${index + 1}] ---\n")
                    append("[${event.level.displayName}] [${event.category.displayName}]\n")
                    append(
                        "时间: ${
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                                .format(Date(event.time))
                        }\n"
                    )
                    append("消息: ${event.message}\n")
                    event.url?.let { append("URL: $it\n") }
                    event.method?.let { append("方法: $it\n") }
                    event.statusCode?.let { append("状态码: $it\n") }
                    event.duration?.let { append("耗时: ${it}ms\n") }
                    event.sourceName?.let { append("书源: $it\n") }
                    event.detail?.let { append("\n详情:\n$it\n") }
                    event.throwable?.let { append("\n异常:\n${it.stackTraceToString()}\n") }
                    append("\n")
                }
            }
        }
    }
}
