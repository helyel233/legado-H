package io.legado.app.help.source

/**
 * 书源规则中的一个可编辑字段，用于 API 扫描定位。
 */
data class SourceApiField(
    val tabIndex: Int,
    val tabName: String,
    val key: String,
    val value: String?
)

/**
 * 某个 API 在字段中的使用位置。
 */
data class SourceApiUsageLocation(
    val tabIndex: Int,
    val tabName: String,
    val fieldKey: String,
    val count: Int,
    val snippet: String
)

/**
 * 一个 API 及其全部使用位置。
 */
data class SourceApiUsage(
    val api: String,
    val locations: List<SourceApiUsageLocation>
) {
    val useCount: Int get() = locations.sumOf { it.count }
}

/**
 * 扫描书源规则中用到的 API（java.xxx、cookie.xxx、ajax 等），记录每个 API 的使用位置，
 * 供「源所用API」界面展示、跳转与复制。
 */
object SourceApiUsageScanner {

    // 扫描单个字段的最大长度，防止超长 jsLib 拖慢扫描
    private const val MAX_FIELD_LENGTH = 100_000
    private const val SNIPPET_RADIUS = 28

    /**
     * API 调用模式：对象成员（java.xxx / cookie.xxx / source.xxx）与独立函数（ajax( / connect(）。
     * 正则清单集中在此，便于后续补充。
     */
    private val memberApiRegex =
        Regex("""\b(java|cookie|source|book|cache|Formats)\.(\w+)""")
    private val functionApiRegex =
        Regex("""\b(ajax|connect|xhr|startObserve)\s*\(""")

    fun scan(fields: List<SourceApiField>): List<SourceApiUsage> {
        val grouped = linkedMapOf<String, LinkedHashMap<FieldKey, Int>>()
        val snippets = linkedMapOf<FieldKey, String>()
        fields.forEach { field ->
            val value = field.value?.take(MAX_FIELD_LENGTH) ?: return@forEach
            if (value.isBlank()) return@forEach
            val key = FieldKey(field.tabIndex, field.key)
            fun record(api: String, offset: Int) {
                grouped.getOrPut(api) { LinkedHashMap() }
                    .merge(key, 1, Int::plus)
                snippets.putIfAbsent(key, buildSnippet(value, offset))
            }
            memberApiRegex.findAll(value).forEach { record(it.value.trim(), it.range.first) }
            functionApiRegex.findAll(value).forEach { record(it.value.trim(), it.range.first) }
        }
        return grouped.map { (api, byField) ->
            val locations = byField.map { (key, count) ->
                SourceApiUsageLocation(
                    tabIndex = key.tabIndex,
                    tabName = fields.first { it.tabIndex == key.tabIndex }.tabName,
                    fieldKey = key.fieldKey,
                    count = count,
                    snippet = snippets[key].orEmpty()
                )
            }.sortedWith(compareBy({ it.tabIndex }, { it.fieldKey }))
            SourceApiUsage(api, locations)
        }.sortedWith(compareByDescending<SourceApiUsage> { it.useCount }.thenBy { it.api })
    }

    private fun buildSnippet(value: String, offset: Int): String {
        val start = (offset - SNIPPET_RADIUS).coerceAtLeast(0)
        val end = (offset + SNIPPET_RADIUS).coerceAtMost(value.length)
        val text = value.substring(start, end).replace(Regex("""\s+"""), " ").trim()
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < value.length) "…" else ""
        return prefix + text + suffix
    }

    private data class FieldKey(val tabIndex: Int, val fieldKey: String)
}
