package io.legado.app.model

/**
 * The book-specific part of the layout key.
 *
 * Only present when a book is open: a key built without one must stay different from a
 * key built with one, otherwise content laid out before a book was set could be reused
 * for it.
 */
internal data class ChapterLayoutBookInputs(
    val usesDirectReader: Boolean,
    val isEpub: Boolean,
    val useReplaceRule: Boolean,
    val delTag: Long,
    val reSegment: Boolean,
    val imageStyle: String?,
    /** Revision of the direct-reader replacement rules; null when they do not apply. */
    val directReplacementRevision: String?
)

/** Everything the layout of a chapter depends on. */
internal data class ChapterLayoutKeyInputs(
    val viewWidth: Int,
    val viewHeight: Int,
    val visibleWidth: Int,
    val visibleHeight: Int,
    val textSize: Float,
    val textColor: Int,
    val textTypefaceStyle: Int,
    val titleSize: Float,
    val titleColor: Int,
    val titleTypefaceStyle: Int,
    val contentTextHeight: Float,
    val titleTextHeight: Float,
    val lineSpacingExtra: Float,
    val paragraphSpacing: Int,
    val paragraphIndent: String,
    val useZhLayout: Boolean,
    val textFullJustify: Boolean,
    val adaptSpecialStyle: Boolean,
    /** Joined description of the content replace rules; empty when there are none. */
    val replaceRuleKey: String,
    /** Stable key of the paragraph rules enabled for this book. */
    val paragraphRuleKey: String,
    val highlightRevision: String,
    val book: ChapterLayoutBookInputs? = null
)

/**
 * Builds the key that says whether a chapter has to be laid out again.
 *
 * A chapter that is already laid out may be reused only while nothing about the layout
 * changed. That covers the viewport, both paints, the spacing settings, the replace and
 * paragraph rules and the highlight rules — miss one and the reader sees text laid out
 * for the previous settings. Building it from plain values keeps that list checkable.
 */
internal object ChapterLayoutKey {

    fun of(inputs: ChapterLayoutKeyInputs): String {
        return buildString {
            append(inputs.book?.usesDirectReader == true).append('|')
            append(inputs.viewWidth).append('x').append(inputs.viewHeight)
            append('|').append(inputs.visibleWidth).append('x').append(inputs.visibleHeight)
            append('|').append(inputs.textSize).append('|').append(inputs.textColor)
            append('|').append(inputs.textTypefaceStyle)
            append('|').append(inputs.titleSize).append('|').append(inputs.titleColor)
            append('|').append(inputs.titleTypefaceStyle)
            append('|').append(inputs.contentTextHeight)
            append('|').append(inputs.titleTextHeight)
            append('|').append(inputs.lineSpacingExtra)
            append('|').append(inputs.paragraphSpacing)
            append('|').append(inputs.paragraphIndent)
            append('|').append(inputs.useZhLayout)
            append('|').append(inputs.textFullJustify)
            append('|').append(inputs.adaptSpecialStyle)
            inputs.book?.let { book ->
                append('|').append(book.useReplaceRule)
                append('|').append(book.delTag)
                append('|').append(book.reSegment)
                append('|').append(book.imageStyle)
                if (!book.isEpub && book.usesDirectReader) {
                    append('|').append(book.directReplacementRevision)
                }
            }
            append('|').append(inputs.replaceRuleKey)
            append('|').append(inputs.paragraphRuleKey)
            append("|highlight:").append(inputs.highlightRevision)
        }
    }
}
