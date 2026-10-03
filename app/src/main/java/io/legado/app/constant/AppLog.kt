package io.legado.app.constant

import android.util.Log
import io.legado.app.data.repository.debug.DebugEventCenter
import io.legado.app.help.config.AppConfig
import io.legado.app.model.debug.DebugCategory
import io.legado.app.model.debug.DebugEvent
import io.legado.app.model.debug.DebugLevel
import io.legado.app.model.debug.DebugLogScope
import io.legado.app.utils.LogUtils
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import splitties.init.appCtx

object AppLog {

    private val mLogs = arrayListOf<Triple<Long, String, Throwable?>>()

    val logs get() = mLogs.toList()

    @Synchronized
    fun put(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        message ?: return
        if (toast) {
            appCtx.toastOnUi(message)
        }
        if (mLogs.size > 100) {
            mLogs.removeLastOrNull()
        }
        if (throwable == null) {
            LogUtils.d("AppLog", message)
        } else {
            LogUtils.d("AppLog", "$message\n${throwable.stackTraceToString()}")
        }
        mLogs.add(0, Triple(System.currentTimeMillis(), message, throwable))
        if (AppConfig.recordLog) {
            val stackTrace = Thread.currentThread().stackTrace
            Log.e(stackTrace[3].className, message, throwable)
        }
        emitToDebugCenter(message, throwable)
    }

    @Synchronized
    fun putNotSave(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        message ?: return
        if (toast) {
            appCtx.toastOnUi(message)
        }
        if (mLogs.size > 100) {
            mLogs.removeLastOrNull()
        }
        mLogs.add(0, Triple(System.currentTimeMillis(), message, throwable))
        if (AppConfig.recordLog) {
            val stackTrace = Thread.currentThread().stackTrace
            Log.e(stackTrace[3].className, message, throwable)
        }
        emitToDebugCenter(message, throwable)
    }

    @Synchronized
    fun clear() {
        mLogs.clear()
    }

    fun putDebug(message: String?, throwable: Throwable? = null) {
        if (AppConfig.recordLog) {
            put(message, throwable)
        } else {
            // 不落盘也要进调试日志中心，便于调试日志页实时排查
            emitToDebugCenter(message, throwable)
        }
    }

    /** 转发到调试事件中心（分类：应用） */
    private fun emitToDebugCenter(message: String?, throwable: Throwable?) {
        message ?: return
        DebugLogScope.launch {
            runCatching {
                DebugEventCenter.emit(
                    DebugEvent(
                        level = if (throwable != null) DebugLevel.ERROR else DebugLevel.INFO,
                        category = DebugCategory.APP,
                        message = message,
                        detail = throwable?.stackTraceToString(),
                        throwable = throwable
                    )
                )
            }
        }
    }

}
