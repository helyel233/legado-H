package io.legado.app.ui.config

import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.glide.HtmlCoverRenderer
import io.legado.app.model.BookCover
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.config.coverhtml.CoverHtmlActivity
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
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
                        )
                    )
                )
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

    private fun refreshCoverCollection() {
        BookCover.upDefaultCover()
        postEvent(EventBus.BOOKSHELF_REFRESH, UUID.randomUUID().toString())
        postEvent(EventBus.REFRESH_BOOK_INFO, false)
    }

    companion object {
        private const val KEY_COVER_RULE = "coverRule"
        private const val KEY_COVER_HTML_CODE = "coverHtmlCode"
    }
}
