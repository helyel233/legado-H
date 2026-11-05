package io.legado.app.ui.config

import android.content.Context
import io.legado.app.BuildConfig
import io.legado.app.ui.book.read.config.ReadAloudConfigDialog
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingItemSpec
import io.legado.app.utils.LogUtils

/**
 * 设置项搜索索引。
 *
 * 索引直接遍历各二级设置页的 [SettingItemSpec]，不再解析 `res/xml/pref_*.xml`。
 * 这些 XML 早就不是页面本体（页面已经全部是 Compose spec），只剩「给搜索提供标题」
 * 这一个用途，于是同一份设置要在两处各写一遍：spec 里新增的设置项搜不到，
 * 删掉的设置项则留成一条点进去定位不到的死结果。
 *
 * 改成从 spec 生成之后，索引的完整性与正确性由编译器保证：
 * 新增设置项自动可搜，[SettingItemSpec.searchKeys] 里的别名（如「沉浸」「EPUB」）
 * 也随之进入搜索范围——这是旧 XML 索引给不了的能力。
 *
 * 注意：这里会为每个页面 new 一个未 attach 的 Fragment 实例只用来构建 spec，
 * 所以各设置页的 `buildPageSpec()` 必须只依赖 [Context]（[ComposeSettingFragment.ctx]、
 * `str()`），不能碰 `requireActivity()` / `parentFragmentManager` / `view`。
 */
object SettingsSearchIndex {

    /**
     * 一条可搜索的设置项。
     *
     * [ownerKey] 是它在「我的」页归属的一级行 key，[ownerConfigTag] 是深链要跳的二级页面。
     * 两者可以不同：DNS 是网络设置的下一层，但搜索结果挂在「网络设置」那一行下面。
     */
    data class Entry(
        val ownerKey: String,
        val ownerConfigTag: String,
        val key: String,
        val title: String,
        val summary: String,
        val aliases: List<String>
    )

    private class PageSource(
        val ownerKey: String,
        val configTag: String,
        val create: () -> ComposeSettingFragment
    )

    private val pages = listOf(
        PageSource(ConfigTag.READING_CONFIG, ConfigTag.READING_CONFIG) { ReadingConfigFragment() },
        PageSource(ConfigTag.BOOKSHELF_CONFIG, ConfigTag.BOOKSHELF_CONFIG) { BookshelfSettingsFragment() },
        // 朗读与音频、漫画与视频从阅读页菜单/弹窗提到一级行，子项随页面 spec 一起进索引
        PageSource(ConfigTag.AUDIO_CONFIG, ConfigTag.AUDIO_CONFIG) { AudioConfigFragment() },
        // 朗读弹窗里的「多角色 / 配乐 / 提示词 / 发言人」等三十来项原先完全搜不到：
        // 它们不在任何页面 spec 里，只活在这个弹窗的四个 Tab 内。
        // indexAllGroups 让弹窗一次吐出全部分组，搜索命中后由 AudioConfigFragment
        // 带着 key 重新打开弹窗并切到对应 Tab。
        PageSource(ConfigTag.AUDIO_CONFIG, ConfigTag.AUDIO_CONFIG) {
            ReadAloudConfigDialog.ReadAloudPreferenceFragment().apply { indexAllGroups = true }
        },
        PageSource(ConfigTag.MANGA_VIDEO_CONFIG, ConfigTag.MANGA_VIDEO_CONFIG) {
            MangaVideoConfigFragment()
        },
        PageSource("theme_setting", ConfigTag.THEME_CONFIG) { ThemeConfigFragment() },
        PageSource(ConfigTag.COVER_CONFIG, ConfigTag.COVER_CONFIG) { CoverConfigFragment() },
        PageSource(ConfigTag.WELCOME_CONFIG, ConfigTag.WELCOME_CONFIG) { WelcomeConfigFragment() },
        PageSource("web_dav_setting", ConfigTag.BACKUP_CONFIG) { BackupConfigFragment() },
        PageSource("ai_setting", ConfigTag.AI_CONFIG) { AiConfigFragment() },
        PageSource(ConfigTag.NETWORK_CONFIG, ConfigTag.NETWORK_CONFIG) { NetworkConfigFragment() },
        // DNS 是「网络设置」的下一层，所以它的子项挂在「网络设置」这一行下面
        PageSource(ConfigTag.NETWORK_CONFIG, ConfigTag.NETWORK_DNS_CONFIG) {
            NetworkDnsConfigFragment()
        },
        PageSource(ConfigTag.STORAGE_CONFIG, ConfigTag.STORAGE_CONFIG) { StorageConfigFragment() },
        PageSource(ConfigTag.ADVANCED_CONFIG, ConfigTag.ADVANCED_CONFIG) { AdvancedConfigFragment() },
        // 评论抓取已从高级设置拆出为独立二级页，子项挂在「我的」页的评论行下
        PageSource(ConfigTag.COMMENT_CONFIG, ConfigTag.COMMENT_CONFIG) { CommentConfigFragment() },
        // 发现/订阅页设置已上提为「内容与规则」下的一级行，ownerKey 随之改变
        PageSource(ConfigTag.DISCOVERY_SUBSCRIPTION_CONFIG, ConfigTag.DISCOVERY_SUBSCRIPTION_CONFIG) {
            DiscoverySubscriptionConfigFragment()
        },
        // 三项页面级布局原先分散在各 Tab 菜单、完全搜不到，归拢到独立二级页后整页进索引
        PageSource(ConfigTag.PAGE_LAYOUT_CONFIG, ConfigTag.PAGE_LAYOUT_CONFIG) {
            PageLayoutConfigFragment()
        }
    )

    fun build(context: Context): List<Entry> {
        return pages.flatMap { page ->
            val pageSpec = try {
                page.create().buildSpec(context)
            } catch (e: Throwable) {
                // 构建失败只应该发生在某个页面动了宿主相关的代码（spec 必须只依赖 Context）。
                // debug 直接抛出去，让开发期立刻看到；release 退化成「少搜一页」，
                // 总好过整个设置搜索打不开。
                if (BuildConfig.DEBUG) throw e
                LogUtils.e("SettingsSearchIndex", "构建 ${page.configTag} 的搜索索引失败: $e")
                return@flatMap emptyList<Entry>()
            }
            pageSpec.sections
                .flatMap { it.items }
                .filter { it.visible }
                .map { it.toEntry(page) }
        }.distinctBy { it.ownerConfigTag to it.key }
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
