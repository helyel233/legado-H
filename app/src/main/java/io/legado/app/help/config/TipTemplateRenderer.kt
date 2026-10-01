package io.legado.app.help.config

/**
 * 页眉页脚自定义模板渲染器
 * 支持占位符：{书名} {作者} {章节名} {时间} {电量} {电量百分比} {页码} {总页数} {进度}
 */
object TipTemplateRenderer {

    const val PLACEHOLDER_BOOK = "{书名}"
    const val PLACEHOLDER_AUTHOR = "{作者}"
    const val PLACEHOLDER_TITLE = "{章节名}"
    const val PLACEHOLDER_TIME = "{时间}"
    const val PLACEHOLDER_BATTERY = "{电量}"
    const val PLACEHOLDER_BATTERY_PERCENTAGE = "{电量百分比}"
    const val PLACEHOLDER_PAGE = "{页码}"
    const val PLACEHOLDER_PAGES = "{总页数}"
    const val PLACEHOLDER_PROGRESS = "{进度}"

    val placeholders = arrayOf(
        PLACEHOLDER_BOOK, PLACEHOLDER_AUTHOR, PLACEHOLDER_TITLE, PLACEHOLDER_TIME,
        PLACEHOLDER_BATTERY, PLACEHOLDER_BATTERY_PERCENTAGE, PLACEHOLDER_PAGE,
        PLACEHOLDER_PAGES, PLACEHOLDER_PROGRESS
    )

    fun render(template: String, context: AdvancedTipConfig.TipContext): String {
        if (template.isBlank()) return ""
        return template
            .replace(PLACEHOLDER_BOOK, context.book)
            .replace(PLACEHOLDER_AUTHOR, context.author)
            .replace(PLACEHOLDER_TITLE, context.title)
            .replace(PLACEHOLDER_TIME, context.time)
            .replace(PLACEHOLDER_BATTERY, context.battery)
            .replace(PLACEHOLDER_BATTERY_PERCENTAGE, batteryPercentage(context.battery))
            .replace(PLACEHOLDER_PAGE, context.page)
            .replace(PLACEHOLDER_PAGES, context.pages)
            .replace(PLACEHOLDER_PROGRESS, context.progress)
    }

    private fun batteryPercentage(battery: String): String {
        if (battery.isBlank()) return ""
        return battery.trim().trimEnd('%') + "%"
    }
}
