package io.legado.app.help.review

import io.legado.app.help.book.BookImgClick
import io.legado.app.help.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewOpenModePolicyTest {

    @Test
    fun offlineModeBlocksNetworkWhenSnapshotMissingAndOpensOfflineWhenHit() {
        assertEquals(
            BookImgClick.OpenAction.BLOCK_NO_SNAPSHOT,
            BookImgClick.decideOpenAction(AppConfig.ReviewOpenMode.OFFLINE, hasSnapshot = false),
        )
        assertEquals(
            BookImgClick.OpenAction.OPEN_SNAPSHOT_OFFLINE,
            BookImgClick.decideOpenAction(AppConfig.ReviewOpenMode.OFFLINE, hasSnapshot = true),
        )
    }

    @Test
    fun snapshotFirstAndNetworkModesKeepExistingFallbackSemantics() {
        assertEquals(
            BookImgClick.OpenAction.OPEN_SNAPSHOT_AND_REFRESH,
            BookImgClick.decideOpenAction(AppConfig.ReviewOpenMode.SNAPSHOT_FIRST, hasSnapshot = true),
        )
        assertEquals(
            BookImgClick.OpenAction.OPEN_NETWORK,
            BookImgClick.decideOpenAction(AppConfig.ReviewOpenMode.SNAPSHOT_FIRST, hasSnapshot = false),
        )
        assertEquals(
            BookImgClick.OpenAction.OPEN_NETWORK,
            BookImgClick.decideOpenAction(AppConfig.ReviewOpenMode.NETWORK, hasSnapshot = true),
        )
        assertEquals(
            BookImgClick.OpenAction.OPEN_NETWORK,
            BookImgClick.decideOpenAction(AppConfig.ReviewOpenMode.NETWORK, hasSnapshot = false),
        )
    }
}
