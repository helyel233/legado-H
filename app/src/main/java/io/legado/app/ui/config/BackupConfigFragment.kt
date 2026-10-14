package io.legado.app.ui.config

import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.edit
import androidx.core.view.MenuProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppCloudStorage
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.cloud.S3CapacityFullException
import io.legado.app.lib.dialogs.SelectItem
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.BackupRestoreController
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.BackupProgress
import io.legado.app.help.storage.BackupProgressHolder
import io.legado.app.help.storage.BackupStage
import io.legado.app.help.storage.ImportOldData
import io.legado.app.help.storage.Restore
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.BackupProgressDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppListItem
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.FileDoc
import io.legado.app.utils.applyTint
import io.legado.app.utils.checkWrite
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class BackupConfigFragment : ComposeSettingFragment(), MenuProvider {

    private companion object {
        const val KEY_WEB_DAV_ACCOUNT_MANAGE = "webDavAccountManage"
        const val KEY_S3_CONTAINER_MANAGE = "s3ContainerManage"
        const val KEY_LIBRARY_CONTAINER_MANAGE = "libraryContainerManage"
        const val KEY_WEB_DAV_BACKUP = "web_dav_backup"
        const val KEY_WEB_DAV_RESTORE = "web_dav_restore"
        const val KEY_IMPORT_OLD = "import_old"
    }

    private val viewModel by activityViewModels<ConfigViewModel>()
    private var _waitDialog: WaitDialog? = null
    private var _backupProgressDialog: BackupProgressDialog? = null
    private var restoreJob: Job? = null
    private var activeBackupPath: String? = null
    private var pendingS3FullBackupPath: String? = null

    /**
     * 旧版数据选择器：默认选数据目录，附加动作可选旧版备份压缩包(zip)
     * 或单个旧版 json 文件（myBookShelf.json 等）。
     */
    private val restoreOldParam: HandleFileContract.HandleFileParam.() -> Unit = {
        title = getString(R.string.menu_import_old_version)
        mode = HandleFileContract.DIR
        allowExtensions = arrayOf("zip", "json")
        otherActions = arrayListOf(
            SelectItem(getString(R.string.select_zip_backup), HandleFileContract.FILE)
        )
    }

    private val selectBackupPath = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
            } else {
                AppConfig.backupPath = uri.path
            }
        }
    }
    private val backupDir = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
                backup(uri.toString())
            } else {
                uri.path?.let { path ->
                    AppConfig.backupPath = path
                    backup(path)
                }
            }
        }
    }
    private val restoreDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            obtainWaitDialog().setText(R.string.restore)
            obtainWaitDialog().show()
            val task = Coroutine.async(Coroutine.defaultScope) {
                findLocalAssetsUris()
            }.onSuccess { assetsUris ->
                _waitDialog?.dismiss()
                askLocalRestore(uri, assetsUris)
            }.onError {
                _waitDialog?.dismiss()
                askLocalRestore(uri, emptyList())
            }
            obtainWaitDialog().setOnCancelListener {
                task.cancel()
            }
        }
    }
    private val restoreOld = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            obtainWaitDialog().setText(R.string.loading)
            obtainWaitDialog().show()
            val task = Coroutine.async(Coroutine.defaultScope) {
                ImportOldData.importUri(appCtx, uri)
            }.onSuccess { summary ->
                _waitDialog?.dismiss()
                appCtx.toastOnUi(summary)
            }.onError {
                _waitDialog?.dismiss()
                AppLog.put("导入旧版数据出错\n${it.localizedMessage}", it)
                appCtx.toastOnUi("导入旧版数据失败\n${it.localizedMessage}")
            }
            obtainWaitDialog().setOnCancelListener {
                task.cancel()
            }
        }
    }

    override val titleRes: Int = R.string.backup_restore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migrateCloudStoragePreferenceTypes()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.addMenuProvider(this, viewLifecycleOwner)
        if (!LocalConfig.backupHelpVersionIsLast) {
            showHelp("webDavHelp")
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.backup_restore, menu)
        menu.applyTint(requireContext())
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_help -> {
                showHelp("webDavHelp")
                return true
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return false
    }

    // ── V5 弹窗状态 ─────────────────────────────────────────────
    private var textSettingKey by mutableStateOf<String?>(null)
    private var textSettingTitle by mutableStateOf("")
    private var textSettingValue by mutableStateOf("")
    private var ignoreOpen by mutableStateOf(false)
    private var ignoreChecked by mutableStateOf<Set<String>>(emptySet())
    private var webDavFormOpen by mutableStateOf(false)
    private var webDavUrlText by mutableStateOf("")
    private var webDavAccountText by mutableStateOf("")
    private var webDavPasswordText by mutableStateOf("")
    private var s3FullFallbackOpen by mutableStateOf(false)
    private var restoreErrorText by mutableStateOf<String?>(null)
    private var restoreFileCandidates by mutableStateOf<List<String>?>(null)
    private var cloudAssetsConfirm by mutableStateOf<Pair<String, List<String>>?>(null)
    private var localAssetsConfirm by mutableStateOf<LocalAssetsConfirm?>(null)

    private data class LocalAssetsConfirm(
        val uri: Uri,
        val assets: List<Pair<Uri, String>>
    )

    override val extraDialogs: @Composable () -> Unit = {
        val density = LocalVisualSpec.current.density

        textSettingKey?.let { key ->
            AppInputDialog(
                title = textSettingTitle,
                value = textSettingValue,
                onValueChange = { textSettingValue = it },
                confirmLabel = stringResource(R.string.ok),
                cancelLabel = stringResource(R.string.cancel),
                onConfirm = { text ->
                    textSettingKey = null
                    appCtx.defaultSharedPreferences.edit {
                        putString(key, text.trim())
                    }
                },
                onDismiss = { textSettingKey = null },
            )
        }

        if (ignoreOpen) {
            AppDialog(onDismiss = { ignoreOpen = false }) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    BackupConfig.ignoreKeys.forEachIndexed { index, key ->
                        AppListItem(
                            headline = BackupConfig.ignoreTitle.getOrNull(index) ?: key,
                            card = false,
                            leading = {
                                Checkbox(
                                    checked = key in ignoreChecked,
                                    onCheckedChange = { checked -> toggleIgnoreKey(key, checked) },
                                )
                            },
                            onClick = { toggleIgnoreKey(key, key !in ignoreChecked) },
                        )
                    }
                    AppFilledButton(
                        text = stringResource(android.R.string.ok),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { ignoreOpen = false },
                    )
                }
            }
        }

        if (webDavFormOpen) {
            AppDialog(onDismiss = { webDavFormOpen = false }) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    AppTextField(
                        value = webDavUrlText,
                        onValueChange = { webDavUrlText = it },
                        placeholder = getString(R.string.web_dav_url),
                    )
                    AppTextField(
                        value = webDavAccountText,
                        onValueChange = { webDavAccountText = it },
                        placeholder = getString(R.string.web_dav_account),
                    )
                    AppTextField(
                        value = webDavPasswordText,
                        onValueChange = { webDavPasswordText = it },
                        placeholder = getString(R.string.web_dav_pw),
                        keyboardType = KeyboardType.Password,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                        AppGhostButton(
                            text = stringResource(R.string.cancel),
                            modifier = Modifier.weight(1f),
                            onClick = { webDavFormOpen = false },
                        )
                        AppFilledButton(
                            text = stringResource(R.string.ok),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                webDavFormOpen = false
                                appCtx.defaultSharedPreferences.edit {
                                    putString(PreferKey.webDavUrl, webDavUrlText.trim())
                                    putString(PreferKey.webDavAccount, webDavAccountText.trim())
                                    putString(PreferKey.webDavPassword, webDavPasswordText)
                                }
                                refreshSettings()
                                viewModel.upCloudStorageConfig()
                            },
                        )
                    }
                }
            }
        }

        if (s3FullFallbackOpen) {
            val hasWebDav = hasWebDavAccount()
            AppDialog(onDismiss = { s3FullFallbackOpen = false }) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    Text(
                        text = getString(R.string.s3_full_webdav_fallback_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = getString(
                            if (hasWebDav) R.string.s3_full_webdav_fallback_message
                            else R.string.s3_full_no_webdav_fallback_message
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                        if (hasWebDav) {
                            AppFilledButton(
                                text = stringResource(R.string.s3_full_webdav_fallback_upload),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    s3FullFallbackOpen = false
                                    retryActiveBackup(uploadCloud = true, uploadWebDavFallback = true)
                                },
                            )
                            AppGhostButton(
                                text = stringResource(R.string.s3_full_webdav_fallback_ignore),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    s3FullFallbackOpen = false
                                    retryActiveBackup(uploadCloud = false, uploadWebDavFallback = false)
                                },
                            )
                            AppGhostButton(
                                text = stringResource(R.string.s3_full_webdav_fallback_never),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    s3FullFallbackOpen = false
                                    neverRemindS3FullFallback()
                                },
                            )
                        } else {
                            AppFilledButton(
                                text = stringResource(R.string.s3_full_webdav_fallback_ignore),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    s3FullFallbackOpen = false
                                    retryActiveBackup(uploadCloud = false, uploadWebDavFallback = false)
                                },
                            )
                            AppGhostButton(
                                text = stringResource(R.string.s3_full_webdav_fallback_never),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    s3FullFallbackOpen = false
                                    neverRemindS3FullFallback()
                                },
                            )
                        }
                    }
                }
            }
        }

        restoreErrorText?.let { message ->
            AppConfirmDialog(
                title = stringResource(R.string.restore),
                message = message,
                confirmLabel = stringResource(R.string.ok),
                cancelLabel = stringResource(R.string.cancel),
                onConfirm = {
                    restoreErrorText = null
                    restoreFromLocal()
                },
                onDismiss = { restoreErrorText = null },
            )
        }

        restoreFileCandidates?.let { names ->
            AppFilterDialog(
                title = getString(R.string.select_restore_file),
                options = names,
                selectedIndex = -1,
                onSelect = { index ->
                    restoreFileCandidates = null
                    names.getOrNull(index)?.let { name ->
                        view?.post { restoreWebDav(name) }
                    }
                },
                onDismiss = { restoreFileCandidates = null },
            )
        }

        cloudAssetsConfirm?.let { (name, assetsNames) ->
            AppConfirmDialog(
                title = stringResource(R.string.restore),
                message = "检测到资源文件包\n${assetsNames.joinToString("\n")}\n是否一并恢复字体、背景图等资源？",
                confirmLabel = stringResource(R.string.ok),
                cancelLabel = stringResource(R.string.cancel),
                onConfirm = {
                    cloudAssetsConfirm = null
                    startCloudRestore(name, assetsNames)
                },
                onDismiss = {
                    cloudAssetsConfirm = null
                    startCloudRestore(name, emptyList())
                },
            )
        }

        localAssetsConfirm?.let { req ->
            AppConfirmDialog(
                title = stringResource(R.string.restore),
                message = "备份路径下检测到资源文件包\n${req.assets.joinToString("\n") { it.second }}\n是否一并恢复字体、背景图等资源？",
                confirmLabel = stringResource(R.string.ok),
                cancelLabel = stringResource(R.string.cancel),
                onConfirm = {
                    localAssetsConfirm = null
                    startLocalRestore(req.uri, req.assets.map { it.first })
                },
                onDismiss = {
                    localAssetsConfirm = null
                    startLocalRestore(req.uri, emptyList())
                },
            )
        }
    }

    private fun toggleIgnoreKey(key: String, checked: Boolean) {
        BackupConfig.ignoreConfig[key] = checked
        BackupConfig.saveIgnoreConfig()
        ignoreChecked = if (checked) ignoreChecked + key else ignoreChecked - key
    }

    override fun buildPageSpec(): SettingPageSpec {
        val type = CloudStorageType.from(getPrefString(PreferKey.cloudStorageType))
        val webDavVisible = type == CloudStorageType.WEBDAV
        val s3Visible = type == CloudStorageType.S3
        val syncBookProgress = booleanSetting(PreferKey.syncBookProgress, true)
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    title = getString(R.string.web_dav_set),
                    items = listOf(
                        choice(
                            key = PreferKey.cloudStorageType,
                            title = getString(R.string.cloud_storage),
                            entriesRes = R.array.cloud_storage_types,
                            valuesRes = R.array.cloud_storage_type_values,
                            defaultValue = CloudStorageType.WEBDAV.name
                        ),
                        SettingActionSpec(
                            key = KEY_WEB_DAV_ACCOUNT_MANAGE,
                            title = getString(R.string.webdav_account_manage),
                            summary = webDavAccountSummary(),
                            visible = webDavVisible,
                            onClick = ::showWebDavAccountDialog
                        ),
                        SettingActionSpec(
                            key = PreferKey.webDavDir,
                            title = getString(R.string.sub_dir),
                            summary = AppConfig.webDavDir ?: "legado",
                            visible = webDavVisible,
                            onClick = {
                                showTextSettingDialog(
                                    key = PreferKey.webDavDir,
                                    title = getString(R.string.sub_dir),
                                    initialValue = AppConfig.webDavDir ?: "legado"
                                )
                            }
                        ),
                        SettingActionSpec(
                            key = PreferKey.webDavDeviceName,
                            title = getString(R.string.webdav_device_name),
                            summary = getPrefString(PreferKey.webDavDeviceName),
                            visible = webDavVisible,
                            onClick = {
                                showTextSettingDialog(
                                    key = PreferKey.webDavDeviceName,
                                    title = getString(R.string.webdav_device_name),
                                    initialValue = AppConfig.webDavDeviceName ?: ""
                                )
                            }
                        ),
                        switch(
                            key = PreferKey.webDavDeleteOldBackup,
                            title = getString(R.string.webdav_delete_old_backup_t),
                            summary = getString(R.string.webdav_delete_old_backup_s),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.webDavBackupCover,
                            title = getString(R.string.webdav_backup_cover_t),
                            summary = getString(R.string.webdav_backup_cover_s),
                            defaultValue = false
                        ),
                        SettingActionSpec(
                            key = KEY_S3_CONTAINER_MANAGE,
                            title = getString(R.string.s3_container_manage),
                            summary = getString(R.string.s3_container_manage_summary),
                            visible = s3Visible,
                            onClick = {
                                requireContext().startActivity<S3ContainerManageActivity>()
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_LIBRARY_CONTAINER_MANAGE,
                            title = "书库容器",
                            summary = "管理用于同步阅读章节缓存的独立 S3 容器",
                            onClick = {
                                requireContext().startActivity<LibraryContainerManageActivity>()
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.autoSwitchS3Container,
                            title = getString(R.string.s3_auto_switch_container),
                            summary = getString(R.string.s3_auto_switch_container_summary),
                            checked = booleanSetting(PreferKey.autoSwitchS3Container, true),
                            visible = s3Visible,
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.autoSwitchS3Container, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.syncThemePackages,
                            title = getString(R.string.sync_theme_packages),
                            summary = getString(R.string.sync_theme_packages_summary),
                            checked = booleanSetting(PreferKey.syncThemePackages, false),
                            onCheckedChange = { checked ->
                                if (checked && !hasCloudStorageAccount()) {
                                    toastOnUi(R.string.cloud_storage_config_required)
                                } else {
                                    updateBooleanSetting(PreferKey.syncThemePackages, checked)
                                }
                            }
                        ),
                        switch(
                            key = PreferKey.syncBookProgress,
                            title = getString(R.string.sync_book_progress_t),
                            summary = getString(R.string.sync_book_progress_s),
                            defaultValue = true
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.syncBookProgressPlus,
                            title = getString(R.string.sync_book_progress_plus_t),
                            summary = getString(R.string.sync_book_progress_plus_s),
                            checked = booleanSetting(PreferKey.syncBookProgressPlus, false),
                            enabled = syncBookProgress,
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.syncBookProgressPlus, it)
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.backup_restore),
                    items = listOf(
                        SettingActionSpec(
                            key = PreferKey.backupPath,
                            title = getString(R.string.backup_path),
                            summary = AppConfig.backupPath ?: getString(R.string.select_backup_path),
                            onClick = { selectBackupPath.launch() }
                        ),
                        SettingActionSpec(
                            key = KEY_WEB_DAV_BACKUP,
                            title = getString(R.string.backup),
                            summary = getString(R.string.backup_summary),
                            onClick = { backup() }
                        ),
                        SettingActionSpec(
                            key = KEY_WEB_DAV_RESTORE,
                            title = getString(R.string.restore),
                            summary = getString(R.string.restore_summary),
                            onClick = { restore() },
                            onLongClick = { restoreFromLocal() }
                        ),
                        SettingActionSpec(
                            key = PreferKey.restoreIgnore,
                            title = getString(R.string.restore_ignore),
                            summary = getString(R.string.restore_ignore_summary),
                            onClick = ::backupIgnore
                        ),
                        SettingActionSpec(
                            key = KEY_IMPORT_OLD,
                            title = getString(R.string.menu_import_old_version),
                            summary = getString(R.string.import_old_summary),
                            onClick = { restoreOld.launch(restoreOldParam) }
                        ),
                        switch(
                            key = PreferKey.backupBookFiles,
                            title = getString(R.string.backup_book_files_t),
                            summary = getString(R.string.backup_book_files_s),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.backupAssetsSeparately,
                            title = getString(R.string.backup_assets_separately_t),
                            summary = getString(R.string.backup_assets_separately_s),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.onlyLatestBackup,
                            title = getString(R.string.only_latest_backup_t),
                            summary = getString(R.string.only_latest_backup_s),
                            defaultValue = true
                        ),
                        switch(
                            key = PreferKey.autoCheckNewBackup,
                            title = getString(R.string.auto_check_new_backup_t),
                            summary = getString(R.string.auto_check_new_backup_s),
                            defaultValue = true
                        ),
                        switch(
                            key = PreferKey.autoBackupOnShelfChange,
                            title = getString(R.string.auto_backup_on_shelf_change_t),
                            summary = getString(R.string.auto_backup_on_shelf_change_s),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.overwriteShelfOnRestore,
                            title = getString(R.string.restore_overwrite_shelf_t),
                            summary = getString(R.string.restore_overwrite_shelf_s),
                            defaultValue = false
                        )
                    )
                )
            )
        )
    }

    private fun migrateCloudStoragePreferenceTypes() {
        val preferences = appCtx.defaultSharedPreferences
        val booleanDefaults = mapOf(
            PreferKey.s3PathStyle to true,
            PreferKey.autoSwitchS3Container to true,
            PreferKey.s3FullWebDavFallbackNeverRemind to false
        )
        booleanDefaults.forEach { (key, defaultValue) ->
            val raw = preferences.all[key]
            if (raw != null && raw !is Boolean) {
                val value = when (raw) {
                    is String -> raw.toBooleanStrictOrNull() ?: (raw == "1")
                    is Number -> raw.toInt() != 0
                    else -> defaultValue
                }
                preferences.edit().putBoolean(key, value).apply()
            }
        }
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.backupPath,
            PreferKey.webDavDeviceName -> refreshSettings()

            PreferKey.cloudStorageType,
            PreferKey.webDavUrl,
            PreferKey.webDavAccount,
            PreferKey.webDavPassword,
            PreferKey.webDavDir -> view?.post {
                refreshSettings()
                viewModel.upCloudStorageConfig()
            }

            PreferKey.s3Containers,
            PreferKey.s3ContainerSelections,
            PreferKey.autoSwitchS3Container -> view?.post {
                refreshSettings()
                viewModel.upCloudStorageConfig()
            }
        }
    }

    private fun switch(
        key: String,
        title: String,
        summary: String,
        defaultValue: Boolean
    ): SettingSwitchSpec {
        return SettingSwitchSpec(
            key = key,
            title = title,
            summary = summary,
            checked = booleanSetting(key, defaultValue),
            onCheckedChange = { updateBooleanSetting(key, it) }
        )
    }

    private fun choice(
        key: String,
        title: String,
        entriesRes: Int,
        valuesRes: Int,
        defaultValue: String
    ): SettingChoiceSpec {
        val options = choiceOptions(entriesRes, valuesRes)
        return SettingChoiceSpec(
            key = key,
            title = title,
            summary = choiceLabel(options, stringSetting(key, defaultValue)),
            options = options,
            selectedValue = stringSetting(key, defaultValue),
            onSelected = { updateStringSetting(key, it) }
        )
    }

    private fun choiceOptions(
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

    private fun choiceLabel(
        options: List<SettingChoiceOption>,
        selectedValue: String
    ): String {
        return options.firstOrNull { it.value == selectedValue }
            ?.label
            ?.toString()
            ?: selectedValue
    }

    private fun showTextSettingDialog(
        key: String,
        title: String,
        initialValue: String
    ) {
        textSettingKey = key
        textSettingTitle = title
        textSettingValue = initialValue
    }

    // 配置完备性判定下沉 BackupRestoreController（台账 #29-B7）
    private fun hasCloudStorageAccount(): Boolean =
        BackupRestoreController.hasCloudStorageAccount()

    private fun hasWebDavAccount(): Boolean =
        BackupRestoreController.hasWebDavAccount()

    private fun hasS3Account(): Boolean =
        BackupRestoreController.hasS3Account()

    private fun webDavAccountSummary(): String {
        val url = getPrefString(PreferKey.webDavUrl).orEmpty()
        val account = getPrefString(PreferKey.webDavAccount).orEmpty()
        val password = getPrefString(PreferKey.webDavPassword).orEmpty()
        return when {
            url.isBlank() && account.isBlank() && password.isBlank() ->
                getString(R.string.webdav_account_manage_summary)
            account.isBlank() ->
                url.ifBlank { getString(R.string.webdav_account_manage_summary) }
            url.isBlank() ->
                account
            else ->
                "$url / $account"
        }
    }

    /**
     * 备份忽略设置
     */
    private fun backupIgnore() {
        ignoreChecked = BackupConfig.ignoreKeys.indices
            .filter { BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[it]] ?: false }
            .mapNotNull { BackupConfig.ignoreKeys.getOrNull(it) }
            .toSet()
        ignoreOpen = true
    }

    private fun showWebDavAccountDialog() {
        webDavUrlText = getPrefString(PreferKey.webDavUrl).orEmpty()
        webDavAccountText = getPrefString(PreferKey.webDavAccount).orEmpty()
        webDavPasswordText = getPrefString(PreferKey.webDavPassword).orEmpty()
        webDavFormOpen = true
    }


    fun backup(ignoreS3FullPrompt: Boolean = false) {
        val backupPath = AppConfig.backupPath
        if (backupPath.isNullOrEmpty()) {
            backupDir.launch()
        } else {
            if (backupPath.isContentScheme()) {
                lifecycleScope.launch {
                    val canWrite = withContext(IO) {
                        FileDoc.fromDir(backupPath).checkWrite()
                    }
                    if (canWrite) {
                        backup(backupPath, uploadCloud = true, checkS3FullPrompt = !ignoreS3FullPrompt)
                    } else {
                        backupDir.launch()
                    }
                }
            } else {
                backupUsePermission(backupPath, checkS3FullPrompt = !ignoreS3FullPrompt)
            }
        }
    }

    private fun backup(
        backupPath: String,
        uploadCloud: Boolean = true,
        uploadWebDavFallback: Boolean = false,
        checkS3FullPrompt: Boolean = true
    ) {
        if (uploadCloud && checkS3FullPrompt && shouldShowS3FullWebDavFallback()) {
            showS3FullWebDavFallbackDialog(backupPath)
            return
        }
        if (BackupRestoreController.isBackupRunning()) {
            // 已有备份任务在后台进行，仅重新打开进度界面
            showBackupProgressDialog()
            return
        }
        activeBackupPath = backupPath
        showBackupProgressDialog()
        // 编排（含进度写入与全局协程）下沉 BackupRestoreController
        BackupRestoreController.startBackup(
            backupPath = backupPath,
            uploadCloud = uploadCloud,
            uploadWebDavFallback = uploadWebDavFallback,
        ) { e ->
            dismissBackupProgressDialog()
            if (e == null) {
                appCtx.toastOnUi(R.string.backup_success)
            } else if (BackupRestoreController.isS3CapacityFull(e) && isAdded
                && showS3FullFallbackAfterFailure(backupPath)
            ) {
                return@startBackup
            } else {
                AppLog.put("备份出错\n${e.localizedMessage}", e)
                appCtx.toastOnUi(
                    appCtx.getString(
                        R.string.backup_fail,
                        e.localizedMessage
                    )
                )
            }
            activeBackupPath = null
        }
    }

    private fun showBackupProgressDialog() {
        val dialog = obtainBackupProgressDialog()
        if (!dialog.isShowing) {
            dialog.show()
        }
    }

    private fun dismissBackupProgressDialog() {
        _backupProgressDialog?.let { dialog ->
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }
    }

    // Dialog 一经创建便绑定创建时的 Activity：宿主 Activity 重建后旧实例
    // 持有失效 window token，show 会抛 BadTokenException，须以当前 Activity 重建
    private fun obtainWaitDialog(): WaitDialog {
        val context = requireContext()
        var dialog = _waitDialog
        if (dialog == null || dialog.context !== context) {
            dialog = WaitDialog(context).also { _waitDialog = it }
        }
        return dialog
    }

    private fun obtainBackupProgressDialog(): BackupProgressDialog {
        val context = requireContext()
        var dialog = _backupProgressDialog
        if (dialog == null || dialog.context !== context) {
            dialog = BackupProgressDialog(context).also { _backupProgressDialog = it }
        }
        return dialog
    }

    private fun shouldShowS3FullWebDavFallback(): Boolean =
        BackupRestoreController.shouldShowS3FullWebDavFallback()

    private fun showS3FullFallbackAfterFailure(backupPath: String): Boolean {
        if (!BackupRestoreController.shouldShowS3FullFallbackAfterFailure()) {
            return false
        }
        showS3FullWebDavFallbackDialog(backupPath)
        return true
    }

    private fun showS3FullWebDavFallbackDialog(backupPath: String) {
        pendingS3FullBackupPath = backupPath
        s3FullFallbackOpen = true
    }

    private fun neverRemindS3FullFallback() {
        appCtx.defaultSharedPreferences.edit {
            putBoolean(PreferKey.s3FullWebDavFallbackNeverRemind, true)
        }
        retryActiveBackup(uploadCloud = false, uploadWebDavFallback = false)
    }

    private fun retryActiveBackup(uploadCloud: Boolean, uploadWebDavFallback: Boolean) {
        val path = pendingS3FullBackupPath ?: activeBackupPath
        pendingS3FullBackupPath = null
        if (path.isNullOrBlank()) {
            backup(ignoreS3FullPrompt = true)
        } else {
            backup(
                path,
                uploadCloud = uploadCloud,
                uploadWebDavFallback = uploadWebDavFallback,
                checkS3FullPrompt = false
            )
        }
    }
    private fun backupUsePermission(path: String, checkS3FullPrompt: Boolean = true) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                backup(path, uploadCloud = true, checkS3FullPrompt = checkS3FullPrompt)
            }
            .request()
    }

    fun restore() {
        obtainWaitDialog().setText(R.string.loading)
        obtainWaitDialog().setOnCancelListener {
            restoreJob?.cancel()
        }
        obtainWaitDialog().show()
        Coroutine.async(Coroutine.defaultScope) {
            restoreJob = coroutineContext[Job]
            showRestoreDialog(requireContext())
        }.onError {
            AppLog.put("恢复备份出错\n${it.localizedMessage}", it)
            if (context == null) {
                return@onError
            }
            restoreErrorText = "Cloud storage error\n${it.localizedMessage}\nRestore from local backup?"
        }.onFinally {
            _waitDialog?.dismiss()
        }
    }

    private suspend fun showRestoreDialog(context: Context) {
        val names = withContext(IO) {
            BackupRestoreController.listCloudBackups()
        }
        if (AppCloudStorage.isJianGuoYun && names.size > 700) {
            context.toastOnUi("由于坚果云限制列出文件数量，部分备份可能未显示，请及时清理旧备份")
        }
        if (names.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            withContext(Main) {
                restoreFileCandidates = names
            }
        } else {
            throw NoStackTraceException("Cloud storage backup file not found")
        }
    }

    /** 云存储类型回退下沉 BackupRestoreController；偏好改写后由本页刷新设置树。 */
    private suspend fun ensureCloudStorageForRestore() {
        val typeBefore = BackupRestoreController.currentCloudStorageType()
        val typeAfter = BackupRestoreController.ensureCloudStorageForRestore()
        if (typeAfter != typeBefore) {
            withContext(Main) {
                refreshSettings()
            }
        }
    }

    private fun restoreWebDav(name: String) {
        Coroutine.async(Coroutine.defaultScope) {
            BackupRestoreController.listCloudAssetNames()
        }.onError {
            startCloudRestore(name, emptyList())
        }.onSuccess { assetsNames ->
            if (assetsNames.isEmpty()) {
                startCloudRestore(name, emptyList())
            } else {
                cloudAssetsConfirm = name to assetsNames
            }
        }
    }

    private fun startCloudRestore(name: String, assetsFileNames: List<String>) {
        obtainWaitDialog().setText(R.string.restore)
        obtainWaitDialog().show()
        val task = Coroutine.async(Coroutine.defaultScope) {
            BackupRestoreController.restoreFromCloud(name, assetsFileNames)
        }.onError {
            AppLog.put("云端恢复出错\n${it.localizedMessage}", it)
            appCtx.toastOnUi("云端恢复出错\n${it.localizedMessage}")
        }.onFinally {
            _waitDialog?.dismiss()
        }
        obtainWaitDialog().setOnCancelListener {
            task.cancel()
        }
    }

    private fun restoreFromLocal() {
        restoreDoc.launch {
            title = getString(R.string.select_restore_file)
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("zip")
        }
    }

    /**
     * 本地恢复的资源包发现：主包与资源包都同步在备份路径目录下，
     * 从该目录枚举 backup_assets 前缀的 zip（附带显示名供弹窗确认）；
     * 目录不可枚举时返回空。
     */
    private suspend fun findLocalAssetsUris(): List<Pair<Uri, String>> =
        BackupRestoreController.findLocalAssetsUris()

    private fun askLocalRestore(uri: Uri, assetsUris: List<Pair<Uri, String>>) {
        if (assetsUris.isEmpty()) {
            startLocalRestore(uri, emptyList())
            return
        }
        localAssetsConfirm = LocalAssetsConfirm(uri, assetsUris)
    }

    private fun startLocalRestore(uri: Uri, assetsUris: List<Uri>) {
        obtainWaitDialog().setText(R.string.restore)
        obtainWaitDialog().show()
        val task = Coroutine.async(Coroutine.defaultScope) {
            BackupRestoreController.restoreFromLocal(uri, assetsUris)
        }.onFinally {
            _waitDialog?.dismiss()
        }
        obtainWaitDialog().setOnCancelListener {
            task.cancel()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 不走 getter：销毁阶段 requireContext 可能已不可用
        _waitDialog?.dismiss()
        _waitDialog = null
        _backupProgressDialog?.dismiss()
        _backupProgressDialog = null
    }

}
