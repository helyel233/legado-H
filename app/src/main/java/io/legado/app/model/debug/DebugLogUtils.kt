package io.legado.app.model.debug

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试日志模块共享工具函数（移植自 Legado_Max）
 */
object DebugLogUtils {

    private val timeFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }

    private val shortTimeFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }

    /** 格式化时间戳为完整日期时间 */
    fun formatFullTime(timestamp: Long): String {
        return timeFormatter.get()!!.format(Date(timestamp))
    }

    /** 格式化时间戳为短时间（时:分:秒.毫秒） */
    fun formatShortTime(timestamp: Long): String {
        return shortTimeFormatter.get()!!.format(Date(timestamp))
    }

    /** 格式化耗时为人类可读文本（支持 null 输入） */
    fun formatDuration(ms: Long?): String? {
        return ms?.let {
            when {
                it < 1000 -> "${it}ms"
                it < 60000 -> "${it / 1000.0}s"
                else -> "${it / 60000}m ${it % 60000 / 1000}s"
            }
        }
    }
}

/**
 * 把任意对象转为用于调试日志的简短字符串，避免对 Collection、Map 等容器直接调用 toString()
 * 产生超大字符串导致 OOM。
 */
fun Any?.toDebugString(maxLength: Int = 200): String? {
    if (this == null) return null
    if (this is String) return take(maxLength)
    if (this is CharSequence) return toString().take(maxLength)
    if (this is Collection<*>) {
        val header = "Collection(size=$size)"
        if (isEmpty() || header.length >= maxLength) return header.take(maxLength)
        val remaining = maxLength - header.length - 2
        if (remaining <= 0) return header.take(maxLength)
        val first = firstOrNull()?.toDebugString(remaining.coerceAtMost(120))
        return "$header[$first]"
    }
    if (this is Array<*>) {
        return "Array(size=$size)".take(maxLength)
    }
    if (this is Map<*, *>) {
        return "Map(size=$size)".take(maxLength)
    }
    return toString().take(maxLength)
}
