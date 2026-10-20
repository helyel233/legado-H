package io.legado.app.ui.book.read.page

/** 一条滚动模式下的高级标题的落位结果。 */
internal data class ScrollTitlePlacement(
    val x: Float,
    val y: Float,
    val width: Int,
    val height: Int
)

/**
 * Where a title sits in the scrolling content view.
 *
 * In scroll mode the title is not bound to a page: it floats over the text and follows
 * it while the reader scrolls. Two rules decide whether it shows at all — the same ones
 * the text itself follows, so a title never appears in a place where no text is.
 */
internal object AdvancedTitleScrollGeometry {

    /** Identity of a title in scroll mode: chapter, page and the title text itself. */
    fun id(chapterIndex: Int, pageIndex: Int, title: String): String {
        return "$chapterIndex:$pageIndex:$title"
    }

    /**
     * Places a title, or returns null when it does not intersect the content viewport.
     *
     * @param relativeOffsetY the offset of the page the title lives in
     * @param contentWidth measured width of the content view
     * @param contentHeight measured height of the content view
     */
    fun place(
        blockOffsetX: Float,
        blockOffsetY: Float,
        blockWidth: Float,
        blockHeight: Float,
        relativeOffsetY: Float,
        contentWidth: Float,
        contentHeight: Float
    ): ScrollTitlePlacement? {
        val width = blockWidth.toInt().coerceAtLeast(1)
        val height = blockHeight.toInt().coerceAtLeast(1)
        // Identical to text: relativeOffset(pos) + in-page offsetY.
        val y = relativeOffsetY + blockOffsetY
        val x = blockOffsetX - (contentWidth - width) / 2f
        // Keep while any pixel still intersects the content viewport (like normal text).
        if (y + height <= 0f || y >= contentHeight) return null
        return ScrollTitlePlacement(x = x, y = y, width = width, height = height)
    }
}
