package io.legado.app.model

import io.legado.app.data.entities.Book

/** What to do with a book's display style. */
internal sealed interface BookDisplayStyleDecision {

    /** Leave the current style as it is. */
    data object Keep : BookDisplayStyleDecision

    /**
     * Set the style.
     *
     * @param style the style to write; null clears it, which is a real outcome: a web
     * book whose source defines no image style must not inherit the previous book's.
     * @param resetPageAnim switch the page animation back to simulation (image books
     * are paged one image at a time)
     */
    data class Set(val style: String?, val resetPageAnim: Boolean) : BookDisplayStyleDecision
}

/**
 * Decides a book's image style and page animation (ReadBook.upWebBook).
 *
 * The rules are about what the reader sees when opening the book: image and PDF books
 * default to one full image per page unless something said otherwise, a web book takes
 * the style its source defines, and a source that wants single-image paging also flips
 * the page animation. Getting these backwards makes every image book open as scrolling
 * text pages.
 */
internal object BookDisplayStylePolicy {

    /**
     * @param isLocal whether the book is a local file
     * @param isImageOrPdf whether the book is an image or PDF book
     * @param currentStyle the style the book already carries, if any
     * @param sourceStyle the style the book source defines, if any (web books only)
     */
    fun decide(
        isLocal: Boolean,
        isImageOrPdf: Boolean,
        currentStyle: String?,
        sourceStyle: String?
    ): BookDisplayStyleDecision {
        if (isLocal) {
            return if (currentStyle.isNullOrBlank() && isImageOrPdf) {
                BookDisplayStyleDecision.Set(Book.imgStyleFull, false)
            } else {
                BookDisplayStyleDecision.Keep
            }
        }
        if (!currentStyle.isNullOrBlank()) return BookDisplayStyleDecision.Keep
        var style = sourceStyle
        if (style.isNullOrBlank() && isImageOrPdf) style = Book.imgStyleFull
        return BookDisplayStyleDecision.Set(
            style = style,
            resetPageAnim = style?.equals(Book.imgStyleSingle, ignoreCase = true) == true
        )
    }
}
