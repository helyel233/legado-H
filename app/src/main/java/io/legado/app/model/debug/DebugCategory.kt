package io.legado.app.model.debug

import androidx.compose.runtime.Immutable
import io.legado.app.R
import splitties.init.appCtx

/**
 * 调试事件分类
 *
 * 定义事件的来源类型，用于分类展示和过滤。
 * 包含ALL作为特殊值表示"全部分类"（仅用于UI筛选）。
 */
@Immutable
enum class DebugCategory(val displayName: String) {
    /** 全部分类（仅用于UI筛选，不作为实际事件分类）*/
    ALL(appCtx.getString(R.string.all)),

    /** 应用日志：来自AppLog的一般性日志 */
    APP(appCtx.getString(R.string.theme_apply)),

    /** 网络请求：来自网络层的访问记录 */
    NETWORK(appCtx.getString(R.string.i18n_final_debug_category_network)),

    /** 规则执行：来自书源/RSS源规则解析过程 */
    RULE(appCtx.getString(R.string.i18n_final_debug_category_rule)),

    /** 书源操作：与书源相关的其他操作 */
    SOURCE(appCtx.getString(R.string.book_source)),

    /** RSS源操作：与RSS源相关的操作 */
    RSS("RSS"),

    /** Toast消息：应用内所有Toast消息记录 */
    TOAST("Toast"),

    /** 书源校验：来自校验流程的结果 */
    CHECK(appCtx.getString(R.string.check_rss_source)),

    /** 应用崩溃：来自CrashHandler的崩溃信息 */
    CRASH(appCtx.getString(R.string.i18n_final_debug_category_crash)),

    /** 阅读器调试：阅读器模块专属调试日志 */
    READER(appCtx.getString(R.string.i18n_final_debug_category_reader))
}
