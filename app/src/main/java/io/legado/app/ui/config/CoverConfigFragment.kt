package io.legado.app.ui.config

import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.glide.HtmlCoverRenderer
import io.legado.app.model.BookCover
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.config.coverhtml.CoverHtmlActivity
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import kotlinx.coroutines.launch
import java.util.UUID

class CoverConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.cover_config

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            CoverCollectionManager.load(false)
            CoverCollectionManager.load(true)
            refreshSettings()
        }
    }

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        SettingSwitchSpec(
                            key = PreferKey.loadCoverOnlyWifi,
                            title = str(R.string.only_wifi),
                            summary = str(R.string.only_wifi_summary),
                            checked = booleanSetting(PreferKey.loadCoverOnlyWifi, false),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.loadCoverOnlyWifi, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.loadCoverHighQuality,
                            title = str(R.string.load_cover_high_quality),
                            summary = str(R.string.load_cover_high_quality_summary),
                            checked = booleanSetting(PreferKey.loadCoverHighQuality, false),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.loadCoverHighQuality, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.bookCoverShadow,
                            title = str(R.string.book_cover_shadow),
                            summary = str(R.string.book_cover_shadow_summary),
                            checked = booleanSetting(PreferKey.bookCoverShadow, true),
                            searchKeys = listOf("coverShadow", "cover_shadow", "shadow"),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.bookCoverShadow, it)
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_COVER_RULE,
                            title = str(R.string.cover_rule),
                            summary = str(R.string.cover_rule_summary),
                            onClick = {
                                showDialogFragment(CoverRuleConfigDialog())
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.useDefaultCover,
                            title = str(R.string.use_default_cover),
                            summary = str(R.string.use_default_cover_s),
                            checked = booleanSetting(PreferKey.useDefaultCover, false),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.useDefaultCover, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.coverHtmlEnable,
                            title = str(R.string.cover_html_enable),
                            summary = str(R.string.cover_html_enable_summary),
                            checked = booleanSetting(PreferKey.coverHtmlEnable, false),
                            searchKeys = listOf("coverHtml", "cover_html", "html"),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.coverHtmlEnable, it)
                                HtmlCoverRenderer.clearCache()
                                refreshCoverCollection()
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_COVER_HTML_CODE,
                            navigates = true,
                            title = str(R.string.cover_html_code),
                            summary = str(R.string.cover_html_code_summary),
                            onClick = {
                                CoverHtmlActivity.start(ctx)
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_COVER_COLLECTION_MANAGE,
                            navigates = true,
                            title = str(R.string.cover_collection_manage),
                            onClick = {
                                startActivity<CoverCollectionManageActivity>()
                            }
                        )
                    )
                ),
                coverCollectionSection(isNight = false),
                coverCollectionSection(isNight = true)
            )
        )
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.coverCollectionDay,
            PreferKey.coverCollectionNight -> refreshSettings()

            PreferKey.coverShowName,
            PreferKey.coverShowNameN -> {
                BookCover.upDefaultCover()
                refreshSettings()
            }

            PreferKey.coverShowAuthor,
            PreferKey.coverShowAuthorN,
            PreferKey.coverCollectionModeDay,
            PreferKey.coverCollectionModeNight,
            PreferKey.bookCoverShadow -> refreshCoverCollection()
        }
    }

    private fun coverCollectionSection(isNight: Boolean): SettingSectionSpec {
        val collectionKey = if (isNight) {
            PreferKey.coverCollectionNight
        } else {
            PreferKey.coverCollectionDay
        }
        val modeKey = if (isNight) {
            PreferKey.coverCollectionModeNight
        } else {
            PreferKey.coverCollectionModeDay
        }
        val showNameKey = if (isNight) PreferKey.coverShowNameN else PreferKey.coverShowName
        val showAuthorKey = if (isNight) PreferKey.coverShowAuthorN else PreferKey.coverShowAuthor
        val showName = booleanSetting(showNameKey, true)
        val mode = stringSetting(modeKey, CoverCollectionManager.MODE_RANDOM)
        return SettingSectionSpec(
            title = str(if (isNight) R.string.night else R.string.day),
            items = listOf(
                SettingActionSpec(
                    key = collectionKey,
                    title = str(R.string.cover_collection_select),
                    summary = coverCollectionSummary(isNight, stringSetting(collectionKey, "")),
                    onClick = { selectCoverCollection(isNight) }
                ),
                SettingChoiceSpec(
                    key = modeKey,
                    title = str(R.string.cover_collection_mode),
                    options = coverModeOptions(),
                    selectedValue = mode,
                    summary = coverModeLabel(mode),
                    onSelected = {
                        updateStringSetting(modeKey, it)
                    }
                ),
                SettingSwitchSpec(
                    key = showNameKey,
                    title = str(R.string.cover_show_name),
                    summary = str(R.string.cover_show_name_summary),
                    checked = showName,
                    onCheckedChange = {
                        updateBooleanSetting(showNameKey, it)
                    }
                ),
                SettingSwitchSpec(
                    key = showAuthorKey,
                    title = str(R.string.cover_show_author),
                    summary = str(R.string.cover_show_author_summary),
                    checked = booleanSetting(showAuthorKey, true),
                    enabled = showName,
                    onCheckedChange = {
                        updateBooleanSetting(showAuthorKey, it)
                    }
                )
            )
        )
    }

    private fun coverModeOptions(): List<SettingChoiceOption> {
        val entries = ctx.resources.getStringArray(R.array.cover_collection_mode_entries)
        val values = ctx.resources.getStringArray(R.array.cover_collection_mode_values)
        return values.mapIndexed { index, value ->
            SettingChoiceOption(
                value = value,
                label = entries.getOrElse(index) { value }
            )
        }
    }

    private fun coverModeLabel(value: String): String {
        return coverModeOptions()
            .firstOrNull { it.value == value }
            ?.label
            ?.toString()
            .orEmpty()
    }

    private fun coverCollectionSummary(
        isNight: Boolean,
        selectedId: String?
    ): String {
        return CoverCollectionManager.cachedName(isNight, selectedId)
            ?: str(R.string.cover_collection_none)
    }

    private fun selectCoverCollection(isNight: Boolean) {
        lifecycleScope.launch {
            val collections = CoverCollectionManager.load(isNight)
            val labels = listOf(str(R.string.cover_collection_none)) +
                collections.map { "${it.name} (${it.images.size})" }
            val selectedId = stringSetting(
                if (isNight) PreferKey.coverCollectionNight else PreferKey.coverCollectionDay,
                ""
            )
            showComposeChoiceListDialog(
                title = str(R.string.cover_collection_select),
                labels = labels,
                selectedIndex = if (selectedId.isBlank()) {
                    0
                } else {
                    collections.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
                        ?.plus(1)
                        ?: -1
                }
            ) { index ->
                val selected = if (index <= 0) null else collections.getOrNull(index - 1)
                CoverCollectionManager.setSelected(isNight, selected?.id)
                lifecycleScope.launch {
                    AppearanceKitManager.syncCurrentCoverCollectionRef(isNight, selected)
                }
                refreshSettings()
                refreshCoverCollection()
            }
        }
    }

    private fun refreshCoverCollection() {
        BookCover.upDefaultCover()
        postEvent(EventBus.BOOKSHELF_REFRESH, UUID.randomUUID().toString())
        postEvent(EventBus.REFRESH_BOOK_INFO, false)
    }

    companion object {
        private const val KEY_COVER_RULE = "coverRule"
        private const val KEY_COVER_COLLECTION_MANAGE = "coverCollectionManage"
        private const val KEY_COVER_HTML_CODE = "coverHtmlCode"
    }
}
