package io.legado.app.lib.theme

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger

/**
 * 视觉配置单事务批量提交控制（P0-2）。
 *
 * 硬边界：
 * 1. [isInBatch] 仅用于抑制视觉缓存（[ThemeUiSignatureCache]）在批内的逐键失效与重算，
 *    严禁抑制 `AppConfig.onSharedPreferenceChanged` 内存镜像刷新。
 * 2. 最外层事务结束时统一执行一次重算自愈（即使批内发生跨线程 SP 写入也不会陈旧）。
 */
object PrefBatchTransaction {

    private val batchDepth = AtomicInteger(0)

    val isInBatch: Boolean
        get() = batchDepth.get() > 0

    fun beginBatch() {
        batchDepth.incrementAndGet()
    }

    /**
     * 结束一次批量提交。最外层结束时执行 [onOuterExit]（生产路径为重算 signature）。
     */
    fun endBatch(onOuterExit: () -> Unit) {
        val remaining = batchDepth.decrementAndGet()
        if (remaining <= 0) {
            if (remaining < 0) {
                batchDepth.set(0)
            }
            onOuterExit()
        }
    }

    inline fun <T> runWithCallback(
        noinline onOuterExit: () -> Unit,
        block: () -> T
    ): T {
        beginBatch()
        return try {
            block()
        } finally {
            endBatch(onOuterExit)
        }
    }

    inline fun <T> run(context: Context, block: () -> T): T {
        val appContext = context.applicationContext
        return runWithCallback(
            onOuterExit = { appContext.recomputeThemeUiSignatureAfterBatch() },
            block = block
        )
    }
}
