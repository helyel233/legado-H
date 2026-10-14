package io.legado.app.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.utils.postEvent

class DiscoveryConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.discovery_settings_title

    override fun buildPageSpec(): SettingPageSpec {
        val discoveryMode = AppConfig.discoveryPageMode
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        SettingChoiceSpec(
                            key = KEY_DISCOVERY_MODE,
                            title = getString(R.string.modern_discovery_page),
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
                        ),
                        SettingActionSpec(
                            key = PreferKey.discoveryPageLayout,
                            title = getString(R.string.discovery_page_layout),
                            summary = discoveryLayoutSummary(),
                            visible = discoveryMode == AppConfig.DISCOVERY_PAGE_MODE_MODERN,
                            onClick = ::showDiscoveryLayoutDialog
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
            else -> rawKey
        }
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.discoveryPageMode,
            PreferKey.modernDiscoveryPage,
            PreferKey.discoveryPageLayout -> Unit
        }
    }

    private fun showDiscoveryLayoutDialog() {
        layoutDialogOpen = true
    }

    internal var layoutDialogOpen by mutableStateOf(false)

    override val extraDialogs: @Composable () -> Unit = {
        if (layoutDialogOpen) {
            AppFilterDialog(
                title = getString(R.string.discovery_page_layout),
                options = DISCOVERY_LAYOUT_VALUES.map(::discoveryLayoutLabel),
                selectedIndex = DISCOVERY_LAYOUT_VALUES.indexOf(AppConfig.discoveryPageLayout),
                onSelect = { index ->
                    layoutDialogOpen = false
                    val value = DISCOVERY_LAYOUT_VALUES.getOrNull(index) ?: return@AppFilterDialog
                    AppConfig.discoveryPageLayout = value
                    refreshSettings()
                },
                onDismiss = { layoutDialogOpen = false },
            )
        }
    }

    private fun discoveryLayoutSummary(): String {
        return getString(
            R.string.discovery_page_layout_summary,
            discoveryLayoutLabel(AppConfig.discoveryPageLayout)
        )
    }

    private fun discoveryLayoutLabel(value: Int): String {
        return when (value) {
            2 -> getString(R.string.discovery_page_layout_waterfall)
            3 -> getString(R.string.discovery_page_layout_grid)
            else -> getString(R.string.discovery_page_layout_list)
        }
    }

    private fun pageModeOptions(
        entriesRes: Int,
        valuesRes: Int
    ): List<SettingChoiceOption> {
        val entries = resources.getStringArray(entriesRes)
        val values = resources.getStringArray(valuesRes)
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
        private const val KEY_DISCOVERY_MODE = "modernDiscoveryMode"
        private const val KEY_SEARCH_JUMP_DISCOVERY_MODE = "search_jump_modernDiscoveryMode"
        private val DISCOVERY_LAYOUT_VALUES = listOf(1, 2, 3)
    }
}
