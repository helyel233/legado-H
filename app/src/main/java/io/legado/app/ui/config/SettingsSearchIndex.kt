package io.legado.app.ui.config

import android.content.Context
import io.legado.app.BuildConfig
import io.legado.app.ui.book.read.config.ReadAloudConfigDialog
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingItemSpec
import io.legado.app.ui.main.explore.DiscoverySuiteManageActivity
import io.legado.app.utils.LogUtils

/**
 * 设置搜索目标路由（JVM 单测可构造）。
 */
sealed interface SearchDestination {
    data class ConfigFragment(val configTag: String, val targetKey: String) : SearchDestination
    data class Activity(
        val className: String,
        val extras: Map<String, String> = emptyMap()
    ) : SearchDestination
}

/**
 * 非 ComposeSettingFragment 托管的独立管理 Activity 自述搜索接口。
 * 每个管理页自行声明标题、摘要与搜索别名，避免在索引里硬编码影子文案。
 */
interface ActivitySearchSource {
    fun searchEntries(context: Context): List<SettingsSearchIndex.Entry>
}

/**
 * 设置项搜索索引。
 *
 * 1. 二级设置页：直接遍历各 [ComposeSettingFragment] 的 [SettingItemSpec] 生成；
 * 2. 独立管理 Activity：由各页面伴生对象实现 [ActivitySearchSource] 自述生成。
 */
object SettingsSearchIndex {

    data class Entry(
        val ownerKey: String,
        val ownerConfigTag: String,
        val key: String,
        val title: String,
        val summary: String,
        val aliases: List<String>,
        val destination: SearchDestination = SearchDestination.ConfigFragment(ownerConfigTag, key)
    )

    private val activitySourceRegistry: List<Pair<String, ActivitySearchSource>> = listOf(
        ThemeManageActivity::class.java.name to ThemeManageActivity,
        NavigationBarManageActivity::class.java.name to NavigationBarManageActivity,
        BubbleManageActivity::class.java.name to BubbleManageActivity,
        AdvancedTitleManageActivity::class.java.name to AdvancedTitleManageActivity,
        AdvancedTipManageActivity::class.java.name to AdvancedTipManageActivity,
        EpubLoadingTemplateManageActivity::class.java.name to EpubLoadingTemplateManageActivity,
        ShareNoteTemplateManageActivity::class.java.name to ShareNoteTemplateManageActivity,
        DiscoverySuiteManageActivity::class.java.name to DiscoverySuiteManageActivity,
        BookInfoManageActivity::class.java.name to BookInfoManageActivity
    )

    val ALLOWED_SEARCH_ACTIVITIES: Set<String> =
        activitySourceRegistry.mapTo(linkedSetOf()) { it.first }

    fun isAllowedActivityDestination(dest: SearchDestination.Activity): Boolean {
        return dest.className in ALLOWED_SEARCH_ACTIVITIES
    }

    private class PageSource(
        val ownerKey: String,
        val configTag: String,
        val create: () -> ComposeSettingFragment
    )

    private val pages = listOf(
        PageSource(ConfigTag.READING_CONFIG, ConfigTag.READING_CONFIG) { ReadingConfigFragment() },
        PageSource(ConfigTag.BOOKSHELF_CONFIG, ConfigTag.BOOKSHELF_CONFIG) { BookshelfSettingsFragment() },
        PageSource(ConfigTag.AUDIO_CONFIG, ConfigTag.AUDIO_CONFIG) { AudioConfigFragment() },
        PageSource(ConfigTag.AUDIO_CONFIG, ConfigTag.AUDIO_CONFIG) {
            ReadAloudConfigDialog.ReadAloudPreferenceFragment().apply { indexAllGroups = true }
        },
        PageSource(ConfigTag.MANGA_VIDEO_CONFIG, ConfigTag.MANGA_VIDEO_CONFIG) {
            MangaVideoConfigFragment()
        },
        // 以下 4 页现在由「外观中心」(appearanceKit) 统一入口承载
        PageSource("appearanceKit", ConfigTag.THEME_CONFIG) { ThemeConfigFragment() },
        PageSource("appearanceKit", ConfigTag.COVER_CONFIG) { CoverConfigFragment() },
        PageSource("appearanceKit", ConfigTag.WELCOME_CONFIG) { WelcomeConfigFragment() },
        PageSource("appearanceKit", ConfigTag.EXPLORE_GLASS_CONFIG) {
            ExploreGlassConfigFragment()
        },
        PageSource(ConfigTag.UI_LAYOUT_CONFIG, ConfigTag.UI_LAYOUT_CONFIG) {
            UiLayoutConfigFragment()
        },
        PageSource("web_dav_setting", ConfigTag.BACKUP_CONFIG) { BackupConfigFragment() },
        PageSource("ai_setting", ConfigTag.AI_CONFIG) { AiConfigFragment() },
        PageSource(ConfigTag.NETWORK_CONFIG, ConfigTag.NETWORK_CONFIG) { NetworkConfigFragment() },
        PageSource(ConfigTag.NETWORK_CONFIG, ConfigTag.NETWORK_DNS_CONFIG) {
            NetworkDnsConfigFragment()
        },
        PageSource(ConfigTag.STORAGE_CONFIG, ConfigTag.STORAGE_CONFIG) { StorageConfigFragment() },
        PageSource(ConfigTag.ADVANCED_CONFIG, ConfigTag.ADVANCED_CONFIG) { AdvancedConfigFragment() },
        PageSource(ConfigTag.COMMENT_CONFIG, ConfigTag.COMMENT_CONFIG) { CommentConfigFragment() },
        PageSource(ConfigTag.DISCOVERY_SUBSCRIPTION_CONFIG, ConfigTag.DISCOVERY_SUBSCRIPTION_CONFIG) {
            DiscoverySubscriptionConfigFragment()
        }
    )

    fun build(context: Context): List<Entry> {
        val fragmentEntries = pages.flatMap { page ->
            val pageSpec = try {
                page.create().buildSpec(context)
            } catch (e: Throwable) {
                if (BuildConfig.DEBUG) throw e
                LogUtils.e("SettingsSearchIndex", "构建 ${page.configTag} 的搜索索引失败: $e")
                return@flatMap emptyList<Entry>()
            }
            pageSpec.sections
                .flatMap { it.items }
                .filter { it.visible }
                .map { it.toEntry(page) }
        }
        val activityEntries = activitySourceRegistry.flatMap { (className, source) ->
            try {
                source.searchEntries(context).filter { entry ->
                    val dest = entry.destination
                    dest !is SearchDestination.Activity ||
                        (dest.className == className && isAllowedActivityDestination(dest))
                }
            } catch (e: Throwable) {
                if (BuildConfig.DEBUG) throw e
                LogUtils.e("SettingsSearchIndex", "构建 Activity 搜索索引失败: $e")
                emptyList()
            }
        }
        return (fragmentEntries + activityEntries).distinctBy {
            when (val dest = it.destination) {
                is SearchDestination.ConfigFragment -> "${dest.configTag}:${dest.targetKey}"
                is SearchDestination.Activity -> "${dest.className}:${it.key}"
            }
        }
    }

    private fun SettingItemSpec.toEntry(page: PageSource): Entry {
        return Entry(
            ownerKey = page.ownerKey,
            ownerConfigTag = page.configTag,
            key = key,
            title = title.toString(),
            summary = summary?.toString().orEmpty(),
            aliases = searchKeys
        )
    }
}
