package io.legado.app.ui.config

import io.legado.app.help.config.NavigationBarIconConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationBarEditSnapshotTest {

    @Test
    fun `json round trip preserves edited config wallpaper icons and metadata`() {
        val snapshot = NavigationBarEditSnapshot(
            entryDirName = "my_bar_pack",
            entrySourceName = NavigationBarIconConfig.Source.BOTH.name,
            entryRemoteUpdatedAt = 123456789L,
            editingName = "Edited Bar Name",
            config = NavigationBarIconConfig.Config(
                name = "Original Bar Name",
                isNightMode = true,
                layoutMode = "sidebar",
                sidebarGravity = "end",
                opacity = 85,
                sidebarBackgroundPath = "/tmp/sidebar.png",
                wallpaperPath = "/tmp/wallpaper.png",
                hideSearchInFloatingStyle = true,
                icons = linkedMapOf(
                    "bookshelf_normal" to "icons/bookshelf_normal.png",
                    "explore_selected" to "icons/explore_selected.png"
                )
            )
        )

        val restored = NavigationBarEditSnapshot.fromJson(snapshot.toJson())
        assertNotNull(restored)
        restored!!
        assertEquals("my_bar_pack", restored.entryDirName)
        assertEquals(NavigationBarIconConfig.Source.BOTH.name, restored.entrySourceName)
        assertEquals(123456789L, restored.entryRemoteUpdatedAt)
        assertEquals("Edited Bar Name", restored.editingName)
        assertEquals(true, restored.config.isNightMode)
        assertEquals("/tmp/sidebar.png", restored.config.sidebarBackgroundPath)
        assertEquals("/tmp/wallpaper.png", restored.config.wallpaperPath)
        assertEquals(
            mapOf(
                "bookshelf_normal" to "icons/bookshelf_normal.png",
                "explore_selected" to "icons/explore_selected.png"
            ),
            restored.config.icons
        )
    }

    @Test
    fun `fromJson returns null on blank or malformed input`() {
        assertNull(NavigationBarEditSnapshot.fromJson(null))
        assertNull(NavigationBarEditSnapshot.fromJson(""))
        assertNull(NavigationBarEditSnapshot.fromJson("{not json"))
    }
}
