package io.legado.app.model

import io.legado.app.data.entities.BookProgress

/** What to do about a progress the cloud holds. */
internal enum class ProgressSyncDecision {
    /** The cloud is behind (or has nothing): upload the local progress. */
    UPLOAD,

    /** The cloud is ahead: let the reader take the cloud progress. */
    APPLY_SERVER,

    /** Both are at the same position. */
    IN_SYNC
}

/**
 * Decides which of two progress positions is the newer one.
 *
 * Reading position is a pair: chapter and character offset inside it. Comparing them is
 * not a plain number comparison — the same offset means different things in chapters of
 * different length, and a later chapter always wins over an earlier one no matter what
 * the offsets say. Getting this backwards either overwrites the reader's furthest
 * position or drags them back to an older one.
 */
internal object ReadProgressSyncPolicy {

    fun decide(server: BookProgress, local: BookProgress): ProgressSyncDecision {
        val serverAhead = server.durChapterIndex > local.durChapterIndex ||
            (server.durChapterIndex == local.durChapterIndex &&
                server.durChapterPos > local.durChapterPos)
        if (serverAhead) return ProgressSyncDecision.APPLY_SERVER
        val localAhead = server.durChapterIndex < local.durChapterIndex ||
            (server.durChapterIndex == local.durChapterIndex &&
                server.durChapterPos < local.durChapterPos)
        if (localAhead) return ProgressSyncDecision.UPLOAD
        return ProgressSyncDecision.IN_SYNC
    }
}
