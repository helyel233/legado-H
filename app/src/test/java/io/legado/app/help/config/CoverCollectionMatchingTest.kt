package io.legado.app.help.config

import org.junit.Assert.assertEquals
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
}
