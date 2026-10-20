package io.legado.app.model

/**
 * Rules that decide whether a chapter load may still be used.
 *
 * Loading a chapter is asynchronous, and the reader may move on while it is in flight:
 * another book may be opened, the chapter may be laid out differently, the reader may
 * have turned a page. A result that no longer matches must be dropped, otherwise the
 * content of a chapter the reader has left would appear under the chapter they are
 * reading. These rules are pure so that they can be checked without a reader.
 */
internal object ChapterLoadGuard {

    /**
     * Only the chapter before, the current one and the one after are kept in memory.
     */
    fun isInChapterWindow(chapterIndex: Int, currentChapterIndex: Int): Boolean {
        return chapterIndex in currentChapterIndex - 1..currentChapterIndex + 1
    }

    /**
     * True when the token of this load is still the one the reader needs.
     *
     * The token covers the book, the load generation and the layout key: any of them
     * changing means the result was produced for a different situation.
     */
    fun isTokenCurrent(
        activeBookUrl: String?,
        requestBookUrl: String,
        chapterIndex: Int,
        currentChapterIndex: Int,
        recordedGeneration: Long?,
        requestGeneration: Long,
        recordedLayoutKey: String?,
        requestLayoutKey: String
    ): Boolean {
        if (activeBookUrl != requestBookUrl) return false
        if (recordedGeneration != requestGeneration) return false
        if (recordedLayoutKey != requestLayoutKey) return false
        return isInChapterWindow(chapterIndex, currentChapterIndex)
    }

    /**
     * True when the request may still be answered: the token is current and the layout
     * of the content did not change while the chapter was loading.
     */
    fun isRequestCurrent(
        activeBookUrl: String?,
        requestBookUrl: String,
        chapterIndex: Int,
        currentChapterIndex: Int,
        recordedGeneration: Long?,
        requestGeneration: Long,
        recordedLayoutKey: String?,
        requestLayoutKey: String,
        currentLayoutKey: String
    ): Boolean {
        if (!isTokenCurrent(
                activeBookUrl = activeBookUrl,
                requestBookUrl = requestBookUrl,
                chapterIndex = chapterIndex,
                currentChapterIndex = currentChapterIndex,
                recordedGeneration = recordedGeneration,
                requestGeneration = requestGeneration,
                recordedLayoutKey = recordedLayoutKey,
                requestLayoutKey = requestLayoutKey
            )
        ) {
            return false
        }
        return currentLayoutKey == requestLayoutKey
    }

    /**
     * True when the chapter may take over the given slot: the token is current, the
     * slot is the one the chapter belongs to, and the slot still holds the chapter the
     * caller expects.
     */
    fun isSlotCurrent(
        activeBookUrl: String?,
        requestBookUrl: String,
        chapterIndex: Int,
        currentChapterIndex: Int,
        recordedGeneration: Long?,
        requestGeneration: Long,
        recordedLayoutKey: String?,
        requestLayoutKey: String,
        expectedOffset: Int,
        slotMatches: Boolean
    ): Boolean {
        if (!isTokenCurrent(
                activeBookUrl = activeBookUrl,
                requestBookUrl = requestBookUrl,
                chapterIndex = chapterIndex,
                currentChapterIndex = currentChapterIndex,
                recordedGeneration = recordedGeneration,
                requestGeneration = requestGeneration,
                recordedLayoutKey = recordedLayoutKey,
                requestLayoutKey = requestLayoutKey
            )
        ) {
            return false
        }
        if (chapterIndex - currentChapterIndex != expectedOffset) return false
        return slotMatches
    }
}
