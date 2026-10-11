package io.legado.app.ui.main.explore

import io.legado.app.R
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.source.ExploreInfoCache
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.utils.InfoMap
import splitties.init.appCtx

/**
 * 「现代发现」分类引擎 —— 从 [ExploreFragment] 抽取的纯逻辑（P4 整改：
 * V5 发现页与旧壳共用同一套分类语义，旧文件零改动）。
 *
 * 职责：把一个书源的 exploreKinds() 分类列表解析为带角色的标签项
 * （选择器/开关/动作按钮/脚本按钮/分组/URL 标签），供 UI 层渲染与分发。
 * 交互语义（与旧壳一致）：
 *  - GlobalSelect：chars 选项 → infoMap[key]=选中值 → 清分类缓存 → 跑 action JS → 重载分类
 *  - Toggle：chars 循环取值 → infoMap → 跑 action
 *  - ActionButton / ScriptUrl：跑 action（java.open("explore") 由 UI 层回调承接）
 *  - UrlTag：kind.url → 打开发现结果页
 */
object DiscoverEngine {

    fun infoMap(sourceUrl: String): InfoMap {
        return ExploreInfoCache.infoMapList[sourceUrl] ?: InfoMap(sourceUrl).also {
            ExploreInfoCache.infoMapList.put(sourceUrl, it)
        }
    }

    fun buildTagItems(
        source: BookSource,
        kinds: List<ExploreKind>,
        blockedActions: Set<String> = emptySet(),
    ): List<DiscoverTagItem> {
        val blocked = blockedActions
        var currentGroup: String? = null
        val result = mutableListOf<DiscoverTagItem>()
        kinds.forEachIndexed { index, kind ->
            if (index == 0 && isDiscoverLeadingBlankPlaceholder(kind)) {
                return@forEachIndexed
            }

            val action = kind.action?.takeIf { it.isNotBlank() }
            val url = kind.normalizedDiscoverUrl()
            if (isDiscoverModernIgnoredControl(kind, currentGroup, url, action)) {
                return@forEachIndexed
            }
            val isSelect = kind.type == ExploreKind.Type.select
            val isActionControl = kind.type == ExploreKind.Type.button ||
                kind.type == ExploreKind.Type.toggle ||
                isDiscoverInputKind(kind)

            if (action.isNullOrBlank() && isDiscoverMajorGroupKind(kind)) {
                currentGroup = resolveDiscoverGroupTitle(kind)
                return@forEachIndexed
            }

            if (isSelect) {
                result += DiscoverTagItem(
                    kind = kind.copy(type = ExploreKind.Type.select),
                    text = resolveDiscoverTagText(kind).limitDiscoverText(6),
                    role = DiscoverTagItem.Role.GlobalSelect,
                    group = null
                )
                return@forEachIndexed
            }

            if (action.isNullOrBlank() && isDiscoverDecorativeGroupKind(kind)) {
                currentGroup = resolveDiscoverGroupTitle(kind)
                return@forEachIndexed
            }

            if (!url.isNullOrBlank() && isDiscoverScriptButtonUrl(url)) {
                result += DiscoverTagItem(
                    kind = kind.copy(type = ExploreKind.Type.button, action = url),
                    text = resolveDiscoverTagText(kind).limitDiscoverText(6),
                    role = DiscoverTagItem.Role.ScriptUrl,
                    group = null
                )
                return@forEachIndexed
            }

            if (!action.isNullOrBlank() && isActionControl) {
                if (blocked.contains(action)) return@forEachIndexed
                val role = if (kind.type == ExploreKind.Type.toggle) {
                    DiscoverTagItem.Role.Toggle
                } else {
                    DiscoverTagItem.Role.ActionButton
                }
                result += DiscoverTagItem(
                    kind = kind.copy(type = ExploreKind.Type.button),
                    text = resolveDiscoverControlText(kind, source.bookSourceUrl).limitDiscoverText(8),
                    role = role,
                    group = currentGroup
                )
                return@forEachIndexed
            }

            if (!url.isNullOrBlank()) {
                result += DiscoverTagItem(
                    kind = kind.copy(url = url),
                    text = resolveDiscoverTagText(kind).limitDiscoverText(6),
                    role = DiscoverTagItem.Role.UrlTag,
                    group = currentGroup
                )
                return@forEachIndexed
            }
        }
        val hasMajorGroup = result.any { !it.group.isNullOrBlank() }
        val normalized = if (hasMajorGroup) {
            result
        } else {
            result.map { it.copy(group = appCtx.getString(R.string.discover_group_other)) }
        }
        return normalized.distinctBy {
            "${it.group}|${it.role}|${it.kind.type}|${it.kind.title}|${it.kind.url}|${it.kind.action}|${it.kind.default}|${it.kind.viewName}|${it.kind.chars?.joinToString("\u001f")}"
        }
    }

    /** 选择器/开关当前生效值（infoMap 优先，其次 default/首项）。 */
    fun currentValue(kind: ExploreKind, sourceUrl: String): String {
        val key = kind.title
        return if (key.isBlank()) {
            kind.default ?: kind.chars?.firstOrNull().orEmpty()
        } else {
            val map = infoMap(sourceUrl)
            map[key] ?: (kind.default ?: kind.chars?.firstOrNull().orEmpty()).also {
                map[key] = it
            }
        }
    }

    fun toggleText(kind: ExploreKind, value: String): String {
        val label = resolveDiscoverTagText(kind)
        return when {
            label.isBlank() -> value
            value.isBlank() -> label
            else -> "$value$label"
        }
    }

    fun isInputKind(kind: ExploreKind): Boolean = isDiscoverInputKind(kind)

    // ---------------------------------------------------------------- 以下为
    // ExploreFragment 同名私有实现的逐行拷贝（仅把 source 依赖参数化）

    private fun isDiscoverMajorGroupKind(kind: ExploreKind): Boolean {
        if (!kind.normalizedDiscoverUrl().isNullOrBlank()) return false
        if (!kind.action.isNullOrBlank()) return false
        if (!isDiscoverFullWidthKind(kind)) return false
        if (kind.type == ExploreKind.Type.toggle) return true
        return kind.action.isNullOrBlank()
    }

    private fun isDiscoverInputKind(kind: ExploreKind): Boolean {
        return kind.type == ExploreKind.Type.text || kind.type == "password"
    }

    private fun isDiscoverModernIgnoredControl(
        kind: ExploreKind,
        currentGroup: String?,
        url: String?,
        action: String?
    ): Boolean {
        if (!url.isNullOrBlank()) return false
        val text = resolveDiscoverTagText(kind).trim()
        val compact = text
            .replace(Regex("[\\p{So}\\p{Sk}\\uFE0F\\s]+"), "")
            .trim()
        if (compact.isBlank() || compact == "※" || compact.equals("null", ignoreCase = true)) {
            return true
        }
        if (isDiscoverInputKind(kind)) {
            return true
        }
        val actionValue = action.orEmpty()
        val viewName = kind.viewName.orEmpty()
        val isTopSearchControl = currentGroup.isNullOrBlank() && (
            compact.contains("搜索") ||
                viewName.contains("搜索") ||
                actionValue.contains("toSearch", ignoreCase = true)
            )
        return isTopSearchControl
    }

    private fun isDiscoverFullWidthKind(kind: ExploreKind): Boolean {
        val style = kind.style()
        return style.layout_flexBasisPercent >= 0.95f ||
            (style.layout_flexGrow >= 1f && style.layout_flexBasisPercent < 0f)
    }

    private fun isDiscoverLeadingBlankPlaceholder(kind: ExploreKind): Boolean {
        if (!isDiscoverFullWidthKind(kind)) return false
        if (!kind.normalizedDiscoverUrl().isNullOrBlank()) return false
        if (!kind.action.isNullOrBlank()) return false
        val text = resolveDiscoverTagText(kind).trim()
        if (text.isNotBlank() && text != ExploreKind.Type.button) return false
        return kind.type == ExploreKind.Type.button || kind.action.isNullOrBlank()
    }

    private fun isDiscoverDecorativeGroupKind(kind: ExploreKind): Boolean {
        if (!kind.normalizedDiscoverUrl().isNullOrBlank()) return false
        val text = resolveDiscoverTagText(kind).trim()
        if (text.isBlank()) return false
        if (isDiscoverFullWidthKind(kind)) return true
        val compact = text.replace("\\s+".toRegex(), "")
        val hasDecoration = compact.count { it == '◎' || it == '●' || it == '○' || it == '◆' || it == '◇' || it == '=' || it == '-' } >= 2
        return hasDecoration || compact.endsWith("分类") || compact.endsWith("排行") || compact.endsWith("排行榜")
    }

    private fun isDiscoverScriptButtonUrl(url: String): Boolean {
        val value = url.trim()
        if (value.startsWith("@js:", ignoreCase = true) || value.startsWith("<js>", ignoreCase = true)) {
            return false
        }
        if (value.contains("page", ignoreCase = true) || value.contains("{{") || value.contains("{\\{")) {
            return false
        }
        return value.contains("java.startBrowser", ignoreCase = true) ||
            value.contains("java.longToast", ignoreCase = true) ||
            value.contains("java.toast", ignoreCase = true) ||
            value.contains("java.open", ignoreCase = true) ||
            value.contains("source.setVariable", ignoreCase = true)
    }

    private fun resolveDiscoverGroupTitle(kind: ExploreKind): String {
        val raw = resolveDiscoverTagText(kind).trim()
        if (raw.isBlank()) return appCtx.getString(R.string.discovery)
        val normalized = raw
            .replace(Regex("[\\p{So}\\p{Sk}\\uFE0F]+"), " ")
            .replace(Regex("[\\uFF1A:|/\\\\]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        return normalized.ifBlank { raw }
    }

    private fun resolveDiscoverTagText(kind: ExploreKind): String {
        val viewName = kind.viewName
        if (!viewName.isNullOrBlank()
            && viewName.length in 3..28
            && viewName.first() == '\''
            && viewName.last() == '\''
        ) {
            return viewName.substring(1, viewName.length - 1)
        }
        return kind.title.ifBlank { kind.type }
    }

    private fun resolveDiscoverControlText(kind: ExploreKind, sourceUrl: String): String {
        if (kind.type != ExploreKind.Type.toggle) {
            return resolveDiscoverTagText(kind)
        }
        val value = currentValue(kind, sourceUrl)
        return toggleText(kind, value)
    }

    private fun ExploreKind.normalizedDiscoverUrl(): String? {
        return url?.trim()?.takeIf {
            it.isNotBlank() && !it.equals("null", ignoreCase = true)
        }
    }

    fun normalizeActionScript(action: String): String {
        var value = action.trim()
        if (value.startsWith("@js:", ignoreCase = true)) {
            return value.substring(4).trim()
        }
        if (value.startsWith("<js>", ignoreCase = true) && value.endsWith("</js>", ignoreCase = true)) {
            return value.substring(4, value.length - 5).trim()
        }
        value = when {
            value.startsWith("{\\{") && value.endsWith("}}") -> value.substring(3, value.length - 2)
            value.startsWith("{{") && value.endsWith("}}") -> value.substring(2, value.length - 2)
            value.startsWith("{") && value.endsWith("}") && isExecutableObjectScript(value) ->
                value.substring(1, value.length - 1)
            else -> value
        }
        return value.trim()
    }

    private fun isExecutableObjectScript(value: String): Boolean {
        return value.contains("java.", ignoreCase = true) ||
            value.contains("source.", ignoreCase = true) ||
            value.contains("infoMap", ignoreCase = true)
    }

    private fun String.limitDiscoverText(max: Int): String {
        return if (length <= max) this else "${take(max.coerceAtLeast(2) - 1)}..."
    }
}
