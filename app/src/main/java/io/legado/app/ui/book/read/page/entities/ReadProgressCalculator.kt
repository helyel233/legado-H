package io.legado.app.ui.book.read.page.entities

import java.text.DecimalFormat

/**
 * The reading progress shown for the current page.
 *
 * The percentage is a blend of the chapter and the page inside it, so the reader sees
 * the position in the whole book rather than only inside the chapter. One rule needs
 * care: the progress may reach 100% before the book is really finished — for instance on
 * the first page of the last chapter, which still has more pages after it. Showing 100%
 * there would tell the reader the book is over while there is still text to read.
 */
internal object ReadProgressCalculator {

    /**
     * @param chapterSize number of chapters in the book
     * @param pageSize number of pages in the current chapter
     * @param chapterIndex index of the current chapter
     * @param pageIndex index of the current page inside its chapter
     * @param formatter formats the ratio as a percentage
     */
    fun format(
        chapterSize: Int,
        pageSize: Int,
        chapterIndex: Int,
        pageIndex: Int,
        formatter: DecimalFormat
    ): String {
        if (chapterSize == 0 || pageSize == 0 && chapterIndex == 0) {
            return "0.0%"
        }
        if (pageSize == 0) {
            return formatter.format((chapterIndex + 1.0f) / chapterSize.toDouble())
        }
        var percent = formatter.format(
            chapterIndex * 1.0f / chapterSize +
                1.0f / chapterSize * (pageIndex + 1) / pageSize.toDouble()
        )
        if (percent == "100.0%" && (chapterIndex + 1 != chapterSize || pageIndex + 1 != pageSize)) {
            percent = "99.9%"
        }
        return percent
    }
}
