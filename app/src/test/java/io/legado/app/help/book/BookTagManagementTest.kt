package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookTagManagementTest {

    @Test
    fun mergeTagsKeepsConfiguredOrderAndAddsExistingTags() {
        assertEquals(
            listOf("科幻", "完结", "收藏"),
            BookTagManagement.mergeTags(
                configured = listOf("科幻", "完结"),
                existing = listOf("收藏", "科幻")
            )
        )
    }

    @Test
    fun mergeTagsDeduplicatesIgnoringCaseAndWhitespace() {
        assertEquals(
            listOf("SciFi", "History"),
            BookTagManagement.mergeTags(
                configured = listOf(" SciFi "),
                existing = listOf("scifi", "History", "")
            )
        )
    }

    @Test
    fun reusableTagsExcludesCurrentTagsIgnoringCase() {
        assertEquals(
            listOf("History", "Fantasy"),
            BookTagManagement.reusableTags(
                current = listOf(" SciFi ", "Finished"),
                all = listOf("scifi", "History", "FINISHED", "Fantasy", "history")
            )
        )
    }

    @Test
    fun updateTagOnlyChangesWhenSelectionDiffers() {
        assertNull(BookTagManagement.updateTag("科幻 完结", "科幻", selected = true))
        assertEquals(
            BookTagManagement.TagWrite("科幻,完结,收藏"),
            BookTagManagement.updateTag("科幻 完结", "收藏", true)
        )
        assertEquals(
            BookTagManagement.TagWrite("完结"),
            BookTagManagement.updateTag("科幻 完结", "科幻", false)
        )
        assertEquals(
            BookTagManagement.TagWrite(null),
            BookTagManagement.updateTag("科幻", "科幻", false)
        )
    }

    @Test
    fun renameTagReplacesOldWithNew() {
        assertEquals(
            BookTagManagement.TagWrite("科幻,完结"),
            BookTagManagement.renameTag("收藏 完结", "收藏", "科幻")
        )
    }

    @Test
    fun renameTagReturnsNullWhenSameName() {
        assertNull(BookTagManagement.renameTag("科幻,完结", "科幻", "科幻"))
        assertNull(BookTagManagement.renameTag(null, "科幻", "科幻"))
    }

    @Test
    fun renameTagRemovesOldWhenNewAlreadyPresent() {
        assertEquals(
            BookTagManagement.TagWrite("科幻,完结"),
            BookTagManagement.renameTag("收藏,科幻,完结", "收藏", "科幻")
        )
    }

    @Test
    fun renameTagReturnsNullWhenOldTagMissing() {
        assertNull(BookTagManagement.renameTag("科幻 完结", "收藏", "玄幻"))
        assertNull(BookTagManagement.renameTag(null, "收藏", "玄幻"))
    }
}
