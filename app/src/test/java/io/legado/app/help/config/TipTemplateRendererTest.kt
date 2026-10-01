package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Test

class TipTemplateRendererTest {

    private val context = AdvancedTipConfig.TipContext(
        book = "诡秘之主",
        author = "爱潜水的乌贼",
        title = "第一章 绯红",
        page = "3",
        pages = "120",
        progress = "2%",
        time = "08:30",
        battery = "66"
    )

    @Test
    fun renderReplacesAllPlaceholders() {
        assertEquals(
            "诡秘之主 · 第一章 绯红 · 08:30 66% 第3/120页 2%",
            TipTemplateRenderer.render(
                "{书名} · {章节名} · {时间} {电量百分比} 第{页码}/{总页数}页 {进度}",
                context
            )
        )
    }

    @Test
    fun renderReplacesAuthorAndBattery() {
        assertEquals(
            "爱潜水的乌贼 66",
            TipTemplateRenderer.render("{作者} {电量}", context)
        )
    }

    @Test
    fun renderKeepsPlainTextWithoutPlaceholders() {
        assertEquals("固定文本", TipTemplateRenderer.render("固定文本", context))
    }

    @Test
    fun renderRemovesUnresolvedPlaceholdersWhenValueMissing() {
        val empty = AdvancedTipConfig.TipContext()
        assertEquals(
            "  · ",
            TipTemplateRenderer.render("{书名} {电量} · {进度}", empty)
        )
    }

    @Test
    fun renderBlankTemplateReturnsEmpty() {
        assertEquals("", TipTemplateRenderer.render("", context))
        assertEquals("", TipTemplateRenderer.render("   ", context))
    }

    @Test
    fun batteryPercentageNormalizesExistingSuffix() {
        assertEquals(
            "66% 66%",
            TipTemplateRenderer.render("{电量百分比} {电量}", context.copy(battery = "66%"))
        )
    }

    @Test
    fun placeholdersAreDistinctTokens() {
        val joined = TipTemplateRenderer.placeholders.joinToString("")
        assertEquals(
            9,
            TipTemplateRenderer.placeholders.distinct().size
        )
        assertEquals(true, joined.contains("{书名}"))
    }
}
