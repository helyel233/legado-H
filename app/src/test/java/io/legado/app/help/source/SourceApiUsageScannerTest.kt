package io.legado.app.help.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceApiUsageScannerTest {

    private fun field(
        tabIndex: Int,
        tabName: String,
        key: String,
        value: String?
    ) = SourceApiField(tabIndex, tabName, key, value)

    @Test
    fun scanFindsMemberAndFunctionApis() {
        val usages = SourceApiUsageScanner.scan(
            listOf(
                field(
                    5, "正文", "content",
                    "{{java.getString(source.getKey())}} ajax({{\"url\"}})"
                )
            )
        )
        val apis = usages.map { it.api }
        assertTrue(apis.contains("java.getString"))
        assertTrue(apis.contains("source.getKey"))
        assertTrue(apis.contains("ajax("))
        assertEquals(3, usages.sumOf { it.useCount })
    }

    @Test
    fun scanCountsPerFieldAndSortsByUseCount() {
        val usages = SourceApiUsageScanner.scan(
            listOf(
                field(0, "基础", "bookUrl", "java.get(x) java.get(y)"),
                field(1, "搜索", "searchUrl", "cookie.get(a)")
            )
        )
        assertEquals("java.get", usages.first().api)
        assertEquals(2, usages.first().useCount)
        val location = usages.first().locations.single()
        assertEquals(0, location.tabIndex)
        assertEquals("bookUrl", location.fieldKey)
        assertEquals(2, location.count)
    }

    @Test
    fun scanSnippetContainsMatchWithContext() {
        val value = "before-context java.getString(\"url\") after-context"
        val usages = SourceApiUsageScanner.scan(
            listOf(field(5, "正文", "content", value))
        )
        val snippet = usages.single().locations.single().snippet
        assertTrue(snippet.contains("java.getString"))
        assertTrue(snippet.contains("before-context"))
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun scanMergesSameFieldAcrossDuplicateEntries() {
        val usages = SourceApiUsageScanner.scan(
            listOf(
                field(1, "搜索", "searchUrl", "connect(url)"),
                field(1, "搜索", "searchUrl", "ajax(url)")
            )
        )
        // 同一 (tab, field) 的两个 API 不合并，但同 API 同字段只算一条位置记录
        assertEquals(2, usages.size)
        assertTrue(usages.none { it.locations.size > 1 })
    }

    @Test
    fun scanIgnoresBlankAndNullFields() {
        val usages = SourceApiUsageScanner.scan(
            listOf(
                field(0, "基础", "bookUrl", null),
                field(0, "基础", "name", "   ")
            )
        )
        assertTrue(usages.isEmpty())
    }

    @Test
    fun scanSkipsCookieRuleWithoutApiCall() {
        val usages = SourceApiUsageScanner.scan(
            listOf(field(5, "正文", "content", "纯文本规则，无任何 API 调用"))
        )
        assertTrue(usages.isEmpty())
    }
}
