package io.legado.app.ui.book.read.page

import io.legado.app.help.config.AdvancedTipSlot

internal data class AdvancedTipRequestToken(
    val contentGeneration: Long,
    val requestGeneration: Long,
    val slot: AdvancedTipSlot,
    val compositionKey: String
)

/**
 * Tracks the tip request that is current for each slot.
 *
 * A tip is loaded asynchronously, so the callback that finally arrives may belong to a
 * request that has been replaced long ago. The gate keeps the current token per slot
 * together with the content generation it belongs to, so a stale callback can be told
 * apart from the one that is still wanted.
 */
internal class AdvancedTipRequestGate {

    private var requestGeneration = 0L
    private val currentRequests =
        arrayOfNulls<AdvancedTipRequestToken>(AdvancedTipSlot.entries.size)

    fun begin(
        slot: AdvancedTipSlot,
        contentGeneration: Long,
        compositionKey: String
    ): AdvancedTipRequestToken {
        val token = AdvancedTipRequestToken(
            contentGeneration = contentGeneration,
            requestGeneration = ++requestGeneration,
            slot = slot,
            compositionKey = compositionKey
        )
        currentRequests[slot.ordinal] = token
        return token
    }

    /**
     * True when this token is still the one wanted for its slot.
     *
     * [contentGeneration] is the generation that is current right now: a token from an
     * older content is stale even if the slot still holds the very same token.
     */
    fun accepts(token: AdvancedTipRequestToken, contentGeneration: Long): Boolean {
        return token.contentGeneration == contentGeneration &&
            currentRequests[token.slot.ordinal] == token
    }

    /** True when the slot already asked for exactly this key in this generation. */
    fun hasCurrent(
        slot: AdvancedTipSlot,
        contentGeneration: Long,
        compositionKey: String
    ): Boolean {
        val token = currentRequests[slot.ordinal] ?: return false
        return token.contentGeneration == contentGeneration &&
            token.compositionKey == compositionKey
    }

    fun clear(slot: AdvancedTipSlot) {
        currentRequests[slot.ordinal] = null
    }

    fun clearAll() {
        currentRequests.fill(null)
    }
}
