package io.legado.app.model.webBook

/**
 * Pure gating policy for early first-chapter content dispatch during paged TOC parsing.
 */
internal object TocEarlyChapterDispatchPolicy {

    /**
     * Returns true when page 1's parsed chapters can be dispatched immediately in parallel with
     * remaining TOC pages.
     *
     * Hard pre-gate:
     * 1. Caller opted into early dispatch;
     * 2. Not a local, audio, or video book;
     * 3. Source TOC rule is NOT reversed (`-` prefix) and book-level `reverseToc` is off;
     * 4. Source TOC rule does NOT define global `formatJs` reordering/formatting;
     * 5. There are subsequent TOC pages to load (or page 1 already contains `targetIndex`).
     */
    fun canDispatchAfterFirstPage(
        earlyDispatchEnabled: Boolean,
        isLocal: Boolean,
        isAudioOrVideo: Boolean,
        isReverseSourceRule: Boolean,
        isBookReverseToc: Boolean,
        formatJs: String?,
        targetChapterIndex: Int,
        firstPageChapterCount: Int,
        hasNextTocPages: Boolean
    ): Boolean {
        if (!earlyDispatchEnabled) return false
        if (isLocal || isAudioOrVideo) return false
        if (isReverseSourceRule || isBookReverseToc) return false
        if (!formatJs.isNullOrBlank()) return false
        if (!hasNextTocPages) return false
        return targetChapterIndex in 0 until firstPageChapterCount
    }

    /**
     * Returns true when the post-reverse / post-format in-memory chapter list should trigger an
     * async chapter fetch (either because the first-page gate did not fire or because deduplication
     * shifted the target chapter URL).
     */
    fun shouldDispatchAfterFinalList(
        earlyDispatchEnabled: Boolean,
        isLocal: Boolean,
        isAudioOrVideo: Boolean,
        earlyDispatchedUrl: String?,
        resolvedChapterUrl: String?
    ): Boolean {
        if (!earlyDispatchEnabled) return false
        if (isLocal || isAudioOrVideo) return false
        if (resolvedChapterUrl.isNullOrBlank()) return false
        return earlyDispatchedUrl == null || earlyDispatchedUrl != resolvedChapterUrl
    }
}
