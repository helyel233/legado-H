package io.legado.app.ui.book.read.page

/**
 * Geometry of an advanced title inside the content view.
 *
 * The title is drawn over the text, so its size and position come from the block that
 * describes it and from the size of the content view. Both are plain numbers here, so
 * the rules can be checked without a view: a title that is placed wrongly is far more
 * visible to the reader than a title that is one frame late.
 */
internal object AdvancedTitleGeometry {

    /** The size the title view is given; a block never collapses to nothing. */
    fun viewSize(blockWidth: Float, blockHeight: Float): Pair<Int, Int> {
        return blockWidth.toInt().coerceAtLeast(1) to blockHeight.toInt().coerceAtLeast(1)
    }

    /**
     * Horizontal position of the title.
     *
     * The block carries an offset relative to the text, and the title is centred in the
     * content view, so the centring has to be taken out again. Without a measured
     * content width the offset is used as is.
     */
    fun translationX(
        blockOffsetX: Float,
        targetWidth: Int,
        contentWidth: Int,
        pageOffsetX: Float
    ): Float {
        if (contentWidth <= 0) return pageOffsetX + blockOffsetX
        val centeredX = (contentWidth - targetWidth) / 2f
        return pageOffsetX + blockOffsetX - centeredX
    }

    /**
     * Vertical position of the title in paging mode.
     *
     * The title may not reach below the content, so the offset is clamped into the
     * space the view leaves.
     */
    fun translationY(
        blockOffsetY: Float,
        targetHeight: Int,
        contentHeight: Int
    ): Float {
        if (contentHeight <= 0) return blockOffsetY
        val maxTranslation = (contentHeight - targetHeight).toFloat().coerceAtLeast(0f)
        return blockOffsetY.coerceIn(0f, maxTranslation)
    }

    /**
     * Vertical position of the title in scroll mode.
     *
     * Scrolling has no page to clamp against: the title follows the content, so the
     * absolute position is used.
     */
    fun scrollTranslationY(blockOffsetY: Float, scrollBaseY: Float): Float {
        return scrollBaseY + blockOffsetY
    }
}
