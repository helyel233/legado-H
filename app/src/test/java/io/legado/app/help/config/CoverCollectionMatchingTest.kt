package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverCollectionMatchingTest {

    private val virtualImages = listOf(
        "/virtual/assets/玄幻.jpg",
        "assets/SciFi.PNG",
        "/tmp/nonexistent/ 都市 .webp",
        "C:\\covers\\武侠.jpeg",
        "assets/a.b.png",
        "D:\\library/混合分隔.webp"
    )

    @Test
    fun matchGroupCoverByName_matchesVirtualPathsWithoutDiskAccess() {
        assertEquals(
            "/virtual/assets/玄幻.jpg",
            CoverCollectionManager.matchGroupCoverByName(virtualImages, "玄幻")
        )
        assertEquals(
            "assets/SciFi.PNG",
            CoverCollectionManager.matchGroupCoverByName(virtualImages, "scifi")
        )
        assertEquals(
            "assets/SciFi.PNG",
            CoverCollectionManager.matchGroupCoverByName(virtualImages, "SCIFI")
        )
        assertEquals(
            "/tmp/nonexistent/ 都市 .webp",
            CoverCollectionManager.matchGroupCoverByName(virtualImages, "  都市  ")
        )
        assertEquals(
            "C:\\covers\\武侠.jpeg",
            CoverCollectionManager.matchGroupCoverByName(virtualImages, "武侠")
        )
        assertEquals(
            "assets/a.b.png",
            CoverCollectionManager.matchGroupCoverByName(virtualImages, "a.b")
        )
        assertEquals(
            "D:\\library/混合分隔.webp",
            CoverCollectionManager.matchGroupCoverByName(virtualImages, "混合分隔")
        )
    }

    @Test
    fun matchGroupCoverByName_returnsNullOnMissBlankOrEmptyCollection() {
        assertNull(CoverCollectionManager.matchGroupCoverByName(virtualImages, "历史"))
        assertNull(CoverCollectionManager.matchGroupCoverByName(virtualImages, "a"))
        assertNull(CoverCollectionManager.matchGroupCoverByName(virtualImages, ""))
        assertNull(CoverCollectionManager.matchGroupCoverByName(virtualImages, "   "))
        assertNull(CoverCollectionManager.matchGroupCoverByName(emptyList(), "玄幻"))
    }

    @Test
    fun buildShuffledDeck_isDeterministicAndExactPermutation() {
        val images = (1..20).map { "assets/cover_%02d.jpg".format(it) }
        val deck1 = CoverCollectionManager.buildShuffledDeck(
            images = images,
            collectionId = "col_alpha",
            shuffleSeed = 0L,
            round = 0
        )
        val deck2 = CoverCollectionManager.buildShuffledDeck(
            images = images,
            collectionId = "col_alpha",
            shuffleSeed = 0L,
            round = 0
        )

        assertEquals(deck1, deck2)
        assertEquals(images.size, deck1.size)
        assertEquals(images.size, deck1.distinct().size)
        assertEquals(images.toSet(), deck1.toSet())

        assertEquals(
            emptyList<String>(),
            CoverCollectionManager.buildShuffledDeck(emptyList(), "col_alpha", 0L, 0)
        )
        assertEquals(
            listOf("only.jpg"),
            CoverCollectionManager.buildShuffledDeck(listOf("only.jpg"), "col_alpha", 0L, 0)
        )
    }

    @Test
    fun buildShuffledDeck_changesOrderAcrossShuffleSeedRoundAndCollectionId() {
        val images = (1..20).map { "assets/cover_%02d.jpg".format(it) }
        val baseDeck = CoverCollectionManager.buildShuffledDeck(
            images = images,
            collectionId = "col_shuffle",
            shuffleSeed = 0L,
            round = 0
        )
        val nextSeedDeck = CoverCollectionManager.buildShuffledDeck(
            images = images,
            collectionId = "col_shuffle",
            shuffleSeed = 1L,
            round = 0
        )
        val nextRoundDeck = CoverCollectionManager.buildShuffledDeck(
            images = images,
            collectionId = "col_shuffle",
            shuffleSeed = 0L,
            round = 1
        )
        val otherCollectionDeck = CoverCollectionManager.buildShuffledDeck(
            images = images,
            collectionId = "col_other",
            shuffleSeed = 0L,
            round = 0
        )

        assertNotEquals(images, baseDeck)
        assertNotEquals(baseDeck, nextSeedDeck)
        assertNotEquals(baseDeck, nextRoundDeck)
        assertNotEquals(nextSeedDeck, nextRoundDeck)
        assertNotEquals(baseDeck, otherCollectionDeck)

        listOf(nextSeedDeck, nextRoundDeck, otherCollectionDeck).forEach { deck ->
            assertEquals(images.size, deck.size)
            assertEquals(images.toSet(), deck.toSet())
        }
    }
}
