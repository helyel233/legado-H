package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import splitties.init.appCtx
import java.util.UUID

/**
 * 智能分组条件类型
 */
object SmartConditionType {
    const val TAG = "tag"           // customTag 含指定标签
    const val SOURCE = "source"     // 书源 origin/originName 等于指定值
    const val NAME = "name"         // 书名包含指定关键字
    const val RECENT = "recent"     // 最近 N 天内读过

    val all = listOf(TAG, SOURCE, NAME, RECENT)
}

/**
 * 单个分组条件（规则内多条件为 AND 语义）
 */
data class SmartGroupCondition(
    val type: String = SmartConditionType.TAG,
    val value: String = "",
    val days: Int = 7
)

/**
 * 智能分组规则：命中后把书籍归入指定分组（book.group 位掩码）。
 * 注意：规则管理的分组由规则独占维护，应用规则时先清除该组位再按规则重新归属。
 */
data class SmartGroupRule(
    val id: String = UUID.randomUUID().toString(),
    val groupId: Long = 0,
    val ruleName: String = "",
    val enabled: Boolean = true,
    val priority: Int = 0,
    val conditions: List<SmartGroupCondition> = emptyList()
)

/**
 * 智能书架分组引擎。
 */
object SmartGroupEngine {

    var rules: List<SmartGroupRule>
        get() {
            return runCatching {
                GSON.fromJsonArray<SmartGroupRule>(
                    appCtx.getPrefString(PreferKey.smartGroupRules)
                ).getOrNull() ?: emptyList()
            }.getOrDefault(emptyList())
        }
        set(value) {
            val normalized = value.distinctBy { it.id }
            appCtx.putPrefString(PreferKey.smartGroupRules, GSON.toJson(normalized))
        }

    var autoRunOnShelfRefresh: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.smartGroupAutoRun)
        set(value) = appCtx.putPrefBoolean(PreferKey.smartGroupAutoRun, value)

    fun matchCondition(book: io.legado.app.data.entities.Book, condition: SmartGroupCondition): Boolean {
        val value = condition.value.trim()
        if (value.isEmpty() && condition.type != SmartConditionType.RECENT) return false
        return when (condition.type) {
            SmartConditionType.TAG -> BookTagHelper.has(book.customTag, value)
            SmartConditionType.SOURCE ->
                book.origin == value || book.originName == value
            SmartConditionType.NAME ->
                book.name.contains(value, ignoreCase = true)
            SmartConditionType.RECENT -> {
                val span = condition.days.coerceAtLeast(1) * 24 * 60 * 60 * 1000L
                book.durChapterTime > 0 &&
                    System.currentTimeMillis() - book.durChapterTime <= span
            }
            else -> false
        }
    }

    fun matchRule(book: io.legado.app.data.entities.Book, rule: SmartGroupRule): Boolean {
        if (!rule.enabled || rule.groupId <= 0) return false
        if (rule.conditions.isEmpty()) return false
        return rule.conditions.all { matchCondition(book, it) }
    }

    /**
     * 书架刷新时静默执行（需开启 autoRunOnShelfRefresh）。
     * @return 发生变更的书籍数，未开启或无规则返回 0
     */
    suspend fun autoRunIfNeeded(): Int {
        if (!autoRunOnShelfRefresh) return 0
        return runCatching {
            applyRules()
        }.onFailure {
            AppLog.put("智能分组自动执行失败", it)
        }.getOrDefault(0)
    }

    /**
     * 应用全部规则：先清除规则管理分组的 book.group 位，再按优先级重新归属。
     * @return 发生变更的书籍数
     */
    suspend fun applyRules(): Int {
        val allRules = rules.sortedBy { it.priority }
        if (allRules.isEmpty()) return 0
        val managedGroupIds = allRules.map { it.groupId }.toSet()
        var changed = 0
        val books = appDb.bookDao.all
        appDb.runInTransaction {
            books.forEach { book ->
                var group = book.group
                managedGroupIds.forEach { group = group and it.inv() }
                val matched = allRules.firstOrNull { matchRule(book, it) }
                if (matched != null) {
                    group = group or matched.groupId
                }
                if (group != book.group) {
                    book.group = group
                    appDb.bookDao.update(book)
                    changed++
                }
            }
        }
        postEvent(EventBus.BOOKSHELF_STRUCTURE_CHANGED, "")
        return changed
    }
}
