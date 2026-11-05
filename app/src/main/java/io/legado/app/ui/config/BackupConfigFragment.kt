package io.legado.app.ui.config

import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
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
import io.legado.app.help.config.SecurePrefs
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
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
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeMultiChoiceDialog
import io.legado.app.ui.widget.compose.showComposeTextFormDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
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
        title = str(R.string.menu_import_old_version)
        mode = HandleFileContract.DIR
        allowExtensions = arrayOf("zip", "json")
        otherActions = arrayListOf(
            SelectItem(str(R.string.select_zip_backup), HandleFileContract.FILE)
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
                AppLog.put(appCtx.getString(R.string.backup_legacy_import_fail, it.localizedMessage), it)
                appCtx.toastOnUi(appCtx.getString(R.string.backup_legacy_import_fail, it.localizedMessage))
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
        menu.applyTint(ctx)
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

    override fun buildPageSpec(): SettingPageSpec {
        val type = CloudStorageType.from(ctx.getPrefString(PreferKey.cloudStorageType))
        val webDavVisible = type == CloudStorageType.WEBDAV
        val s3Visible = type == CloudStorageType.S3
        val syncBookProgress = booleanSetting(PreferKey.syncBookProgress, true)
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    title = str(R.string.web_dav_set),
                    items = listOf(
                        choice(
                            key = PreferKey.cloudStorageType,
                            title = str(R.string.cloud_storage),
                            entriesRes = R.array.cloud_storage_types,
                            valuesRes = R.array.cloud_storage_type_values,
                            defaultValue = CloudStorageType.WEBDAV.name
                        ),
                        SettingActionSpec(
                            key = KEY_WEB_DAV_ACCOUNT_MANAGE,
                            title = str(R.string.webdav_account_manage),
                            summary = webDavAccountSummary(),
                            visible = webDavVisible,
                            onClick = ::showWebDavAccountDialog
                        ),
                        SettingActionSpec(
                            key = PreferKey.webDavDir,
                            title = str(R.string.sub_dir),
                            summary = AppConfig.webDavDir ?: "legado",
                            visible = webDavVisible,
                            onClick = {
                                showTextSettingDialog(
                                    key = PreferKey.webDavDir,
                                    title = str(R.string.sub_dir),
                                    initialValue = AppConfig.webDavDir ?: "legado"
                                )
                            }
                        ),
                        SettingActionSpec(
                            key = PreferKey.webDavDeviceName,
                            title = str(R.string.webdav_device_name),
                            summary = ctx.getPrefString(PreferKey.webDavDeviceName),
                            visible = webDavVisible,
                            onClick = {
                                showTextSettingDialog(
                                    key = PreferKey.webDavDeviceName,
                                    title = str(R.string.webdav_device_name),
                                    initialValue = AppConfig.webDavDeviceName ?: ""
                                )
                            }
                        ),
                        switch(
                            key = PreferKey.webDavDeleteOldBackup,
                            title = str(R.string.webdav_delete_old_backup_t),
                            summary = str(R.string.webdav_delete_old_backup_s),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.webDavBackupCover,
                            title = str(R.string.webdav_backup_cover_t),
                            summary = str(R.string.webdav_backup_cover_s),
                            defaultValue = false
                        ),
                        SettingActionSpec(
                            key = KEY_S3_CONTAINER_MANAGE,
                            navigates = true,
                            title = str(R.string.s3_container_manage),
                            summary = str(R.string.s3_container_manage_summary),
                            visible = s3Visible,
                            onClick = {
                                ctx.startActivity<S3ContainerManageActivity>()
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_LIBRARY_CONTAINER_MANAGE,
                            navigates = true,
                            title = str(R.string.backup_library_container_title),
                            summary = str(R.string.backup_library_container_summary),
                            onClick = {
                                ctx.startActivity<LibraryContainerManageActivity>()
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.autoSwitchS3Container,
                            title = str(R.string.s3_auto_switch_container),
                            summary = str(R.string.s3_auto_switch_container_summary),
                            checked = booleanSetting(PreferKey.autoSwitchS3Container, true),
                            visible = s3Visible,
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.autoSwitchS3Container, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.syncThemePackages,
                            title = str(R.string.sync_theme_packages),
                            summary = str(R.string.sync_theme_packages_summary),
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
                            title = str(R.string.sync_book_progress_t),
                            summary = str(R.string.sync_book_progress_s),
                            defaultValue = true
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.syncBookProgressPlus,
                            title = str(R.string.sync_book_progress_plus_t),
                            summary = str(R.string.sync_book_progress_plus_s),
                            checked = booleanSetting(PreferKey.syncBookProgressPlus, false),
                            enabled = syncBookProgress,
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.syncBookProgressPlus, it)
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.backup_restore),
                    items = listOf(
                        SettingActionSpec(
                            key = PreferKey.backupPath,
                            title = str(R.string.backup_path),
                            summary = AppConfig.backupPath ?: str(R.string.select_backup_path),
                            onClick = { selectBackupPath.launch() }
                        ),
                        SettingActionSpec(
                            key = KEY_WEB_DAV_BACKUP,
                            title = str(R.string.backup),
                            summary = str(R.string.backup_summary),
                            onClick = { backup() }
                        ),
                        SettingActionSpec(
                            key = KEY_WEB_DAV_RESTORE,
                            title = str(R.string.restore),
                            summary = str(R.string.restore_summary),
                            onClick = { restore() },
                            onLongClick = { restoreFromLocal() }
                        ),
                        SettingActionSpec(
                            key = PreferKey.restoreIgnore,
                            title = str(R.string.restore_ignore),
                            summary = str(R.string.restore_ignore_summary),
                            onClick = ::backupIgnore
                        ),
                        SettingActionSpec(
                            key = KEY_IMPORT_OLD,
                            title = str(R.string.menu_import_old_version),
                            summary = str(R.string.import_old_summary),
                            onClick = { restoreOld.launch(restoreOldParam) }
                        ),
                        switch(
                            key = PreferKey.backupBookFiles,
                            title = str(R.string.backup_book_files_t),
                            summary = str(R.string.backup_book_files_s),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.backupAssetsSeparately,
                            title = str(R.string.backup_assets_separately_t),
                            summary = str(R.string.backup_assets_separately_s),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.onlyLatestBackup,
                            title = str(R.string.only_latest_backup_t),
                            summary = str(R.string.only_latest_backup_s),
                            defaultValue = true
                        ),
                        switch(
                            key = PreferKey.autoCheckNewBackup,
                            title = str(R.string.auto_check_new_backup_t),
                            summary = str(R.string.auto_check_new_backup_s),
                            defaultValue = true
                        ),
                        switch(
                            key = PreferKey.autoBackupOnShelfChange,
                            title = str(R.string.auto_backup_on_shelf_change_t),
                            summary = str(R.string.auto_backup_on_shelf_change_s),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.overwriteShelfOnRestore,
                            title = str(R.string.restore_overwrite_shelf_t),
                            summary = str(R.string.restore_overwrite_shelf_s),
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
        val entries = ctx.resources.getStringArray(entriesRes)
        val values = ctx.resources.getStringArray(valuesRes)
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
        showComposeTextInputDialog(
            title = title,
            hint = title,
            initialValue = initialValue,
            onPositive = {
                appCtx.defaultSharedPreferences.edit {
                    putString(key, it.trim())
                }
            }
        )
    }

    private fun hasCloudStorageAccount(): Boolean {
        return when (CloudStorageType.from(ctx.getPrefString(PreferKey.cloudStorageType))) {
            CloudStorageType.WEBDAV -> hasWebDavAccount()
            CloudStorageType.S3 -> hasS3Account()
        }
    }

    private fun hasWebDavAccount(): Boolean {
        return !ctx.getPrefString(PreferKey.webDavAccount).isNullOrBlank()
                && !SecurePrefs.read(PreferKey.webDavPassword).isNullOrBlank()
    }

    private fun hasS3Account(): Boolean {
        return AppCloudStorage.listContainers().any {
            it.enabled && it.endpoint.isNotBlank() && it.bucket.isNotBlank()
                    && it.accessKey.isNotBlank() && it.secretKey.isNotBlank()
        }
    }

    private fun webDavAccountSummary(): String {
        val url = ctx.getPrefString(PreferKey.webDavUrl).orEmpty()
        val account = ctx.getPrefString(PreferKey.webDavAccount).orEmpty()
        val password = SecurePrefs.read(PreferKey.webDavPassword).orEmpty()
        return when {
            url.isBlank() && account.isBlank() && password.isBlank() ->
                str(R.string.webdav_account_manage_summary)
            account.isBlank() ->
                url.ifBlank { str(R.string.webdav_account_manage_summary) }
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
        val checkedIndices = BackupConfig.ignoreKeys.indices
            .filter { BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[it]] ?: false }
            .toSet()
        showComposeMultiChoiceDialog(
            title = str(R.string.restore_ignore),
            labels = BackupConfig.ignoreTitle.toList(),
            checkedIndices = checkedIndices,
            negativeText = str(android.R.string.ok),
            onItemCheckedChange = { which, isChecked ->
                BackupConfig.ignoreKeys.getOrNull(which)?.let { key ->
                    BackupConfig.ignoreConfig[key] = isChecked
                    BackupConfig.saveIgnoreConfig()
                }
            }
        )
    }

    private fun showWebDavAccountDialog() {
        showComposeTextFormDialog(
            title = str(R.string.webdav_account_manage),
            labels = listOf(
                str(R.string.web_dav_url),
                str(R.string.web_dav_account),
                str(R.string.web_dav_pw)
            ),
            initialValues = listOf(
                ctx.getPrefString(PreferKey.webDavUrl).orEmpty(),
                ctx.getPrefString(PreferKey.webDavAccount).orEmpty(),
                SecurePrefs.read(PreferKey.webDavPassword).orEmpty()
            ),
            passwordFields = setOf(2),
            onPositive = { values ->
                appCtx.defaultSharedPreferences.edit {
                    putString(PreferKey.webDavUrl, values.getOrNull(0).orEmpty().trim())
                    putString(PreferKey.webDavAccount, values.getOrNull(1).orEmpty().trim())
                }
                // 与旧版一致：密码不做 trim（可能合法包含首尾空格），仅空串视为删除
                SecurePrefs.write(PreferKey.webDavPassword, values.getOrNull(2)?.ifEmpty { null })
                refreshSettings()
                viewModel.upCloudStorageConfig()
            }
        )
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
        if (BackupProgressHolder.isRunning) {
            // 已有备份任务在后台进行，仅重新打开进度界面
            showBackupProgressDialog()
            return
        }
        activeBackupPath = backupPath
        BackupProgressHolder.update(BackupProgress(BackupStage.PREPARING))
        showBackupProgressDialog()
        val appContext = ctx.applicationContext
        // 使用全局协程：点「后台继续」或离开页面不会中断备份
        Coroutine.async(Coroutine.defaultScope) {
            Backup.backupLocked(appContext, backupPath, uploadCloud, uploadWebDavFallback) { progress ->
                BackupProgressHolder.update(progress)
            }
        }.onSuccess {
            BackupProgressHolder.update(null)
            dismissBackupProgressDialog()
            appCtx.toastOnUi(R.string.backup_success)
        }.onError { e ->
            BackupProgressHolder.update(null)
            dismissBackupProgressDialog()
            if (e is S3CapacityFullException && isAdded && showS3FullFallbackAfterFailure(backupPath)) {
                return@onError
            }
            AppLog.put(appCtx.getString(R.string.backup_fail, e.localizedMessage), e)
            appCtx.toastOnUi(
                appCtx.getString(
                    R.string.backup_fail,
                    e.localizedMessage
                )
            )
        }.onFinally {
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
        val context = ctx
        var dialog = _waitDialog
        if (dialog == null || dialog.context !== context) {
            dialog = WaitDialog(context).also { _waitDialog = it }
        }
        return dialog
    }

    private fun obtainBackupProgressDialog(): BackupProgressDialog {
        val context = ctx
        var dialog = _backupProgressDialog
        if (dialog == null || dialog.context !== context) {
            dialog = BackupProgressDialog(context).also { _backupProgressDialog = it }
        }
        return dialog
    }

    private fun shouldShowS3FullWebDavFallback(): Boolean {
        if (CloudStorageType.from(ctx.getPrefString(PreferKey.cloudStorageType)) != CloudStorageType.S3) {
            return false
        }
        if (appCtx.defaultSharedPreferences.getBoolean(PreferKey.s3FullWebDavFallbackNeverRemind, false)) {
            return false
        }
        val items = AppCloudStorage.listContainers().filter { it.enabled }
        return items.isNotEmpty() && items.all { it.isFull }
    }

    private fun showS3FullFallbackAfterFailure(backupPath: String): Boolean {
        if (appCtx.defaultSharedPreferences.getBoolean(PreferKey.s3FullWebDavFallbackNeverRemind, false)) {
            return false
        }
        showS3FullWebDavFallbackDialog(backupPath)
        return true
    }

    private fun showS3FullWebDavFallbackDialog(backupPath: String) {
        pendingS3FullBackupPath = backupPath
        val hasWebDav = !ctx.getPrefString(PreferKey.webDavAccount).isNullOrBlank()
                && !SecurePrefs.read(PreferKey.webDavPassword).isNullOrBlank()
        val messageRes = if (hasWebDav) {
            R.string.s3_full_webdav_fallback_message
        } else {
            R.string.s3_full_no_webdav_fallback_message
        }
        val neverRemind = {
            appCtx.defaultSharedPreferences.edit {
                putBoolean(PreferKey.s3FullWebDavFallbackNeverRemind, true)
            }
            retryActiveBackup(uploadCloud = false, uploadWebDavFallback = false)
        }
        if (hasWebDav) {
            showComposeConfirmDialog(
                title = str(R.string.s3_full_webdav_fallback_title),
                message = str(messageRes),
                positiveText = str(R.string.s3_full_webdav_fallback_upload),
                negativeText = str(R.string.s3_full_webdav_fallback_ignore),
                neutralText = str(R.string.s3_full_webdav_fallback_never),
                onPositive = {
                    retryActiveBackup(uploadCloud = true, uploadWebDavFallback = true)
                },
                onNegative = {
                    retryActiveBackup(uploadCloud = false, uploadWebDavFallback = false)
                },
                onNeutral = neverRemind
            )
        } else {
            showComposeConfirmDialog(
                title = str(R.string.s3_full_webdav_fallback_title),
                message = str(messageRes),
                positiveText = str(R.string.s3_full_webdav_fallback_ignore),
                neutralText = str(R.string.s3_full_webdav_fallback_never),
                showNegative = false,
                onPositive = {
                    retryActiveBackup(uploadCloud = false, uploadWebDavFallback = false)
                },
                onNeutral = neverRemind
            )
        }
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
            showRestoreDialog(ctx)
        }.onError {
            AppLog.put(appCtx.getString(R.string.backup_restore_fail, it.localizedMessage), it)
            if (context == null) {
                return@onError
            }
            showComposeConfirmDialog(
                title = str(R.string.restore),
                message = "Cloud storage error\n${it.localizedMessage}\nRestore from local backup?",
                onPositive = {
                    restoreFromLocal()
                }
            )
        }.onFinally {
            _waitDialog?.dismiss()
        }
    }

    private suspend fun showRestoreDialog(context: Context) {
        val names = withContext(IO) {
            ensureCloudStorageForRestore()
            AppCloudStorage.getBackupNames()
        }
        if (AppCloudStorage.isJianGuoYun && names.size > 700) {
            context.toastOnUi(str(R.string.backup_nutstore_limit))
        }
        if (names.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
                withContext(Main) {
                    context.showComposeChoiceListDialog(
                        title = context.getString(R.string.select_restore_file),
                        labels = names
                    ) { index ->
                        if (index in 0 until names.size) {
                            view?.post {
                                restoreWebDav(names[index])
                            }
                        }
                    }
                }
        } else {
            throw NoStackTraceException("Cloud storage backup file not found")
        }
    }

    private suspend fun ensureCloudStorageForRestore() {
        val currentType = CloudStorageType.from(ctx.getPrefString(PreferKey.cloudStorageType))
        if (currentType == CloudStorageType.S3 && hasS3Account()) {
            AppCloudStorage.upConfig()
            return
        }
        if (currentType == CloudStorageType.WEBDAV && hasWebDavAccount()) {
            AppCloudStorage.upConfig()
            return
        }
        val fallbackType = when {
            hasS3Account() -> CloudStorageType.S3
            hasWebDavAccount() -> CloudStorageType.WEBDAV
            else -> throw NoStackTraceException("Cloud storage is not configured")
        }
        appCtx.defaultSharedPreferences.edit {
            putString(PreferKey.cloudStorageType, fallbackType.name)
        }
        withContext(Main) {
            refreshSettings()
        }
        AppCloudStorage.upConfig()
    }

    private fun restoreWebDav(name: String) {
        Coroutine.async(Coroutine.defaultScope) {
            AppCloudStorage.listAssetsBackupNames()
        }.onError {
            startCloudRestore(name, emptyList())
        }.onSuccess { assetsNames ->
            if (assetsNames.isEmpty()) {
                startCloudRestore(name, emptyList())
            } else {
                view?.post {
                    showComposeConfirmDialog(
                        title = str(R.string.restore),
                        message = str(R.string.backup_assets_restore_message, assetsNames.joinToString("\n")),
                        onPositive = { startCloudRestore(name, assetsNames) },
                        onNegative = { startCloudRestore(name, emptyList()) }
                    )
                }
            }
        }
    }

    private fun startCloudRestore(name: String, assetsFileNames: List<String>) {
        obtainWaitDialog().setText(R.string.restore)
        obtainWaitDialog().show()
        val task = Coroutine.async(Coroutine.defaultScope) {
            AppCloudStorage.restore(name, assetsFileNames)
        }.onError {
            AppLog.put(appCtx.getString(R.string.backup_cloud_restore_fail, it.localizedMessage), it)
            appCtx.toastOnUi(appCtx.getString(R.string.backup_cloud_restore_fail, it.localizedMessage))
        }.onFinally {
            _waitDialog?.dismiss()
        }
        obtainWaitDialog().setOnCancelListener {
            task.cancel()
        }
    }

    private fun restoreFromLocal() {
        restoreDoc.launch {
            title = str(R.string.select_restore_file)
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("zip")
        }
    }

    /**
     * 本地恢复的资源包发现：主包与资源包都同步在备份路径目录下，
     * 从该目录枚举 backup_assets 前缀的 zip（附带显示名供弹窗确认）；
     * 目录不可枚举时返回空。
     */
    private suspend fun findLocalAssetsUris(): List<Pair<Uri, String>> {
        val path = AppConfig.backupPath
        if (path.isNullOrBlank()) return emptyList()
        return runCatching {
            if (path.isContentScheme()) {
                DocumentFile.fromTreeUri(appCtx, Uri.parse(path))?.listFiles()
                    ?.filter { it.isFile && it.name?.startsWith("backup_assets") == true }
                    ?.mapNotNull { doc -> doc.name?.let { doc.uri to it } }
                    .orEmpty()
            } else {
                java.io.File(path).listFiles()
                    ?.filter { it.isFile && it.name.startsWith("backup_assets") }
                    ?.map { Uri.fromFile(it) to it.name }
                    .orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    private fun askLocalRestore(uri: Uri, assetsUris: List<Pair<Uri, String>>) {
        if (assetsUris.isEmpty()) {
            startLocalRestore(uri, emptyList())
            return
        }
        view?.post {
            showComposeConfirmDialog(
                title = str(R.string.restore),
                message = str(R.string.backup_assets_restore_path_message, assetsUris.joinToString("\n") { it.second }),
                onPositive = { startLocalRestore(uri, assetsUris.map { it.first }) },
                onNegative = { startLocalRestore(uri, emptyList()) }
            )
        }
    }

    private fun startLocalRestore(uri: Uri, assetsUris: List<Uri>) {
        obtainWaitDialog().setText(R.string.restore)
        obtainWaitDialog().show()
        val task = Coroutine.async(Coroutine.defaultScope) {
            Restore.restore(appCtx, uri, assetsUris)
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
