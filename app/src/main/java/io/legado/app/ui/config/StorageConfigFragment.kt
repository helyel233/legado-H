package io.legado.app.ui.config

import androidx.fragment.app.activityViewModels
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.showComposeConfirmDialog

/**
 * 存储与清理：默认导入目录、过期自动清理、手动清缓存/清 WebView/收缩数据库。
 *
 * 「缓存管理」（按书清理已下载章节）是一级入口，这一页管的是全局存储。
 */
class StorageConfigFragment : ComposeSettingFragment() {

    private val viewModel by activityViewModels<ConfigViewModel>()
    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
        }
    }

    override val titleRes: Int = R.string.storage_cleanup_title

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        SettingActionSpec(
                            key = PreferKey.defaultBookTreeUri,
                            title = str(R.string.book_tree_uri_t),
                            summary = AppConfig.defaultBookTreeUri ?: str(R.string.book_tree_uri_s),
                            onClick = {
                                localBookTreeSelect.launch {
                                    title = str(R.string.select_book_folder)
                                    mode = HandleFileContract.DIR_SYS
                                }
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.storage_cleanup_actions_section),
                    items = listOf(
                        switch(
                            key = PreferKey.autoClearExpired,
                            title = str(R.string.auto_clear_expired),
                            summary = str(R.string.auto_clear_expired_summary),
                            defaultValue = true
                        ),
                        SettingActionSpec(
                            key = PreferKey.cleanCache,
                            title = str(R.string.clear_cache),
                            summary = str(R.string.clear_cache_summary),
                            onClick = ::clearCache
                        ),
                        SettingActionSpec(
                            key = PreferKey.clearWebViewData,
                            title = str(R.string.clear_webview_data),
                            summary = str(R.string.clear_webview_data_summary),
                            onClick = ::clearWebViewData
                        ),
                        SettingActionSpec(
                            key = PreferKey.shrinkDatabase,
                            title = str(R.string.shrink_database),
                            summary = str(R.string.shrink_database_summary),
                            onClick = ::shrinkDatabase
                        )
                    )
                )
            )
        )
    }

    private fun clearCache() {
        showComposeConfirmDialog(
            title = str(R.string.clear_cache),
            message = str(R.string.sure_del),
            positiveText = str(android.R.string.ok),
            negativeText = str(R.string.no),
            onPositive = { viewModel.clearCache() }
        )
    }

    private fun shrinkDatabase() {
        showComposeConfirmDialog(
            title = str(R.string.sure),
            message = str(R.string.shrink_database),
            positiveText = str(android.R.string.ok),
            negativeText = str(R.string.no),
            onPositive = { viewModel.shrinkDatabase() }
        )
    }

    private fun clearWebViewData() {
        showComposeConfirmDialog(
            title = str(R.string.clear_webview_data),
            message = str(R.string.sure_del),
            positiveText = str(android.R.string.ok),
            negativeText = str(R.string.no),
            onPositive = { viewModel.clearWebViewData() }
        )
    }

}
