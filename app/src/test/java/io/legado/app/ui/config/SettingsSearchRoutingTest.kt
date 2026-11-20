package io.legado.app.ui.config

import io.legado.app.ui.main.explore.DiscoverySuiteManageActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4 纯 JVM 单测：验证 [SearchDestination.Activity] 白名单校验与可构造性。
 */
class SettingsSearchRoutingTest {

    @Test
    fun allowedManageActivities_passWhitelistCheck() {
        val allowed = listOf(
            ThemeManageActivity::class.java.name,
            TopBarManageActivity::class.java.name,
            NavigationBarManageActivity::class.java.name,
            BubbleManageActivity::class.java.name,
            AdvancedTitleManageActivity::class.java.name,
            AdvancedTipManageActivity::class.java.name,
            EpubLoadingTemplateManageActivity::class.java.name,
            ShareNoteTemplateManageActivity::class.java.name,
            DiscoverySuiteManageActivity::class.java.name,
            BookInfoManageActivity::class.java.name
        )
        allowed.forEach { className ->
            val dest = SearchDestination.Activity(className)
            assertTrue("Expected allowed: $className", SettingsSearchIndex.isAllowedActivityDestination(dest))
        }
    }

    @Test
    fun arbitraryOrUnknownActivity_isRejectedByWhitelist() {
        assertFalse(
            SettingsSearchIndex.isAllowedActivityDestination(
                SearchDestination.Activity("io.legado.app.ui.main.MainActivity")
            )
        )
        assertFalse(
            SettingsSearchIndex.isAllowedActivityDestination(
                SearchDestination.Activity("com.example.EvilActivity")
            )
        )
    }
}
