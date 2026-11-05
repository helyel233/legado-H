package io.legado.app.ui.config

import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.utils.postEvent

class DiscoverySubscriptionConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.discovery_subscription_settings_title

    override fun buildPageSpec(): SettingPageSpec {
        val discoveryMode = AppConfig.discoveryPageMode
        val useModernRss = booleanSetting(PreferKey.modernRssPage, true)
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    title = str(R.string.discovery_settings_title),
                    items = listOf(
                        SettingChoiceSpec(
                            key = KEY_DISCOVERY_MODE,
                            title = str(R.string.modern_discovery_page),
                            options = pageModeOptions(
                                entriesRes = R.array.discovery_page_mode_entries,
                                valuesRes = R.array.discovery_page_mode_values
                            ),
                            selectedValue = discoveryMode,
                            summary = pageModeLabel(
                                entriesRes = R.array.discovery_page_mode_entries,
                                valuesRes = R.array.discovery_page_mode_values,
                                selectedValue = discoveryMode
                            ),
                            onSelected = {
                                AppConfig.discoveryPageMode = it
                            },
                            searchKeys = listOf(
                                PreferKey.discoveryPageMode,
                                PreferKey.modernDiscoveryPage,
                                KEY_SEARCH_JUMP_DISCOVERY_MODE
                            )
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.subscription_settings_title),
                    items = listOf(
                        SettingChoiceSpec(
                            key = KEY_RSS_MODE,
                            title = str(R.string.modern_rss_page),
                            options = pageModeOptions(
                                entriesRes = R.array.rss_page_mode_entries,
                                valuesRes = R.array.page_mode_values
                            ),
                            selectedValue = if (useModernRss) {
                                PAGE_MODE_MODERN
                            } else {
                                PAGE_MODE_LEGACY
                            },
                            summary = pageModeLabel(
                                entriesRes = R.array.rss_page_mode_entries,
                                valuesRes = R.array.page_mode_values,
                                selectedValue = if (useModernRss) {
                                    PAGE_MODE_MODERN
                                } else {
                                    PAGE_MODE_LEGACY
                                }
                            ),
                            onSelected = {
                                updateBooleanSetting(
                                    PreferKey.modernRssPage,
                                    it == PAGE_MODE_MODERN
                                )
                            },
                            searchKeys = listOf(
                                PreferKey.modernRssPage,
                                KEY_SEARCH_JUMP_MODERN_RSS_PAGE,
                                KEY_SEARCH_JUMP_RSS_MODE
                            )
                        )
                    )
                )
            )
        )
    }

    override fun normalizeTargetKey(rawKey: String): String {
        return when (rawKey) {
            PreferKey.discoveryPageMode,
            PreferKey.modernDiscoveryPage,
            KEY_SEARCH_JUMP_DISCOVERY_MODE -> KEY_DISCOVERY_MODE
            PreferKey.modernRssPage,
            KEY_SEARCH_JUMP_MODERN_RSS_PAGE,
            KEY_SEARCH_JUMP_RSS_MODE -> KEY_RSS_MODE
            else -> rawKey
        }
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.discoveryPageMode,
            PreferKey.modernDiscoveryPage,
            PreferKey.modernRssPage -> postEvent(EventBus.NOTIFY_MAIN, false)
        }
    }

    private fun pageModeOptions(
        entriesRes: Int,
        valuesRes: Int
    ): List<SettingChoiceOption> {
        val entries = ctx.resources.getStringArray(entriesRes)
        val values = ctx.resources.getStringArray(valuesRes)
        return values.mapIndexed { index, value ->
            SettingChoiceOption(
                value = value,
                label = entries.getOrElse(index) { value }
            )
        }
    }

    private fun pageModeLabel(
        entriesRes: Int,
        valuesRes: Int,
        selectedValue: String
    ): String {
        return pageModeOptions(entriesRes, valuesRes)
            .firstOrNull { it.value == selectedValue }
            ?.label
            ?.toString()
            .orEmpty()
    }

    companion object {
        private const val PAGE_MODE_MODERN = "modern"
        private const val PAGE_MODE_LEGACY = "legacy"
        private const val KEY_DISCOVERY_MODE = "modernDiscoveryMode"
        private const val KEY_RSS_MODE = "modernRssMode"
        private const val KEY_SEARCH_JUMP_MODERN_RSS_PAGE = "search_jump_modernRssPage"
        private const val KEY_SEARCH_JUMP_DISCOVERY_MODE = "search_jump_modernDiscoveryMode"
        private const val KEY_SEARCH_JUMP_RSS_MODE = "search_jump_modernRssMode"
    }
}
