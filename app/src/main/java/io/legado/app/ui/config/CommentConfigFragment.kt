package io.legado.app.ui.config

import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.review.ReviewSnapshotStore
import io.legado.app.help.review.ReviewWindowPrefetcher
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 评论缓存设置页（精简为 4 项核心设置与全局清空入口）。
 */
class CommentConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.review_config_title

    @Volatile
    private var cachedTotalBytes: Long? = null

    override fun onResume() {
        super.onResume()
        refreshCachedTotalBytesAsync()
    }

    override fun buildPageSpec(): SettingPageSpec {
        val cacheEnabled = booleanSetting(PreferKey.syncCacheReview, true)
        val wifiOnly = booleanSetting(PreferKey.reviewPreheatWifiOnly, false)
        val openModeOptions = listOf(
            SettingChoiceOption(
                value = AppConfig.ReviewOpenMode.NETWORK,
                label = str(R.string.menu_review_open_mode_network)
            ),
            SettingChoiceOption(
                value = AppConfig.ReviewOpenMode.SNAPSHOT_FIRST,
                label = str(R.string.menu_review_open_mode_snapshot_first)
            )
        )
        val rawOpenMode = stringSetting(PreferKey.reviewOpenMode, AppConfig.ReviewOpenMode.NETWORK)
        val selectedOpenMode = if (
            rawOpenMode == AppConfig.ReviewOpenMode.SNAPSHOT_FIRST ||
            rawOpenMode == "snapshot_only"
        ) {
            AppConfig.ReviewOpenMode.SNAPSHOT_FIRST
        } else {
            AppConfig.ReviewOpenMode.NETWORK
        }
        val clearSummary = cachedTotalBytes?.let { bytes ->
            str(R.string.review_cache_clear_all_summary, ConvertUtils.formatFileSize(bytes))
        } ?: str(R.string.review_cache_clear_all_summary_loading)

        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    title = str(R.string.review_cache_section),
                    items = listOf(
                        switch(
                            key = PreferKey.syncCacheReview,
                            title = str(R.string.menu_sync_cache_review),
                            summary = str(R.string.ps_sync_cache_review),
                            defaultValue = true
                        ),
                        SettingChoiceSpec(
                            key = PreferKey.reviewOpenMode,
                            title = str(R.string.menu_review_open_mode),
                            summary = choiceDisplayLabel(openModeOptions, selectedOpenMode),
                            options = openModeOptions,
                            selectedValue = selectedOpenMode,
                            onSelected = { mode ->
                                updateStringSetting(PreferKey.reviewOpenMode, mode)
                                refreshSettings()
                            }
                        ),
                        switch(
                            key = PreferKey.reviewPreheatWifiOnly,
                            title = str(R.string.menu_review_preheat_wifi_only),
                            summary = str(
                                if (wifiOnly) R.string.review_preheat_wifi_only_on
                                else R.string.review_preheat_wifi_only_off
                            ),
                            defaultValue = false,
                            visible = cacheEnabled
                        ),
                        SettingActionSpec(
                            key = KEY_CLEAR_ALL_REVIEW_CACHE,
                            title = str(R.string.review_cache_clear_all),
                            summary = clearSummary,
                            onClick = ::confirmClearAllReviewCache
                        )
                    )
                )
            )
        )
    }

    private fun refreshCachedTotalBytesAsync() {
        val owner = viewLifecycleOwnerLiveData.value ?: return
        owner.lifecycleScope.launch(Dispatchers.IO) {
            val total = runCatching { ReviewSnapshotStore.totalBytesAll() }.getOrDefault(0L)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                cachedTotalBytes = total
                refreshSettings()
            }
        }
    }

    private fun confirmClearAllReviewCache() {
        val owner = viewLifecycleOwnerLiveData.value ?: return
        owner.lifecycleScope.launch {
            val totalBytes = cachedTotalBytes ?: withContext(Dispatchers.IO) {
                runCatching { ReviewSnapshotStore.totalBytesAll() }.getOrDefault(0L)
            }
            if (!isAdded) return@launch
            if (totalBytes <= 0L) {
                toastOnUi(R.string.cache_manage_review_empty)
                cachedTotalBytes = 0L
                refreshSettings()
                return@launch
            }
            val sizeText = ConvertUtils.formatFileSize(totalBytes)
            alert(
                str(R.string.review_cache_clear_all),
                str(R.string.review_cache_clear_all_confirm, sizeText)
            ) {
                yesButton {
                    owner.lifecycleScope.launch {
                        val removed = withContext(Dispatchers.IO) {
                            ReviewWindowPrefetcher.clearAllCooldown()
                            ReviewSnapshotStore.clearAllGlobal()
                        }
                        if (!isAdded) return@launch
                        toastOnUi(str(R.string.cache_manage_review_cleared, removed))
                        cachedTotalBytes = null
                        refreshSettings()
                        refreshCachedTotalBytesAsync()
                    }
                }
                noButton()
            }
        }
    }

    private companion object {
        const val KEY_CLEAR_ALL_REVIEW_CACHE = "clear_all_review_cache"
    }
}
