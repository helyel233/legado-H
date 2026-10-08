package io.legado.app.help.ai

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiChatException
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import splitties.init.appCtx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AiDailyUsage(
    val day: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val requests: Int
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/**
 * 全局 AI token 用量统计与每日预算护栏。
 *
 * 所有走 AiChatService 的模型请求（聊天、agent、总结、朗读角色/BGM、生图提示词改写、单工具调用）
 * 的 usage 都会在这里累加；超过每日预算后，发起新的模型请求会被拦截。
 */
object AiUsageTracker {

    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val lock = Any()

    /** 记录一次模型请求上报的 usage */
    fun record(stats: AiUsageStats) {
        if (stats.inputTokens <= 0 && stats.outputTokens <= 0) return
        synchronized(lock) {
            rollDayIfNeeded()
            appCtx.putPrefLong(
                PreferKey.aiUsageInputTokens,
                appCtx.getPrefLong(PreferKey.aiUsageInputTokens, 0L) + stats.inputTokens
            )
            appCtx.putPrefLong(
                PreferKey.aiUsageOutputTokens,
                appCtx.getPrefLong(PreferKey.aiUsageOutputTokens, 0L) + stats.outputTokens
            )
            appCtx.putPrefInt(
                PreferKey.aiUsageRequests,
                appCtx.getPrefInt(PreferKey.aiUsageRequests, 0) + 1
            )
        }
    }

    /** 今日用量（跨天自动归零） */
    fun todayUsage(): AiDailyUsage {
        synchronized(lock) {
            rollDayIfNeeded()
            return AiDailyUsage(
                day = appCtx.getPrefString(PreferKey.aiUsageDay).orEmpty(),
                inputTokens = appCtx.getPrefLong(PreferKey.aiUsageInputTokens, 0L),
                outputTokens = appCtx.getPrefLong(PreferKey.aiUsageOutputTokens, 0L),
                requests = appCtx.getPrefInt(PreferKey.aiUsageRequests, 0)
            )
        }
    }

    fun resetToday() {
        synchronized(lock) {
            resetTo(today())
        }
    }

    /**
     * 每次模型请求前调用；超出每日预算时抛出带明确文案的异常。
     * 文案刻意不含数字，避免命中可重试错误的关键词匹配（429/500 等）。
     */
    fun ensureWithinDailyBudget() {
        val budget = AppConfig.aiDailyTokenBudget
        if (budget <= 0L) return
        val usage = todayUsage()
        if (usage.totalTokens < budget) return
        throw AiChatException(
            message = "今日 AI 用量已达到每日预算上限，请调整预算或明日再试。",
            debugLog = "dailyBudgetExceeded budget=$budget used=${usage.totalTokens}"
        )
    }

    private fun rollDayIfNeeded() {
        val today = today()
        if (appCtx.getPrefString(PreferKey.aiUsageDay).orEmpty() != today) {
            resetTo(today)
        }
    }

    private fun resetTo(day: String) {
        appCtx.putPrefString(PreferKey.aiUsageDay, day)
        appCtx.putPrefLong(PreferKey.aiUsageInputTokens, 0L)
        appCtx.putPrefLong(PreferKey.aiUsageOutputTokens, 0L)
        appCtx.putPrefInt(PreferKey.aiUsageRequests, 0)
    }

    private fun today(): String = dayFormat.format(Date())
}
