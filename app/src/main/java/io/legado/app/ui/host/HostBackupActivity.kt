package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.BackupProgress
import io.legado.app.help.storage.BackupProgressHolder
import io.legado.app.help.storage.BackupRestoreController
import io.legado.app.help.storage.BackupStage
import io.legado.app.help.storage.ImportOldData
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.ui.config.S3ContainerManageActivity
import io.legado.app.ui.config.LibraryContainerManageActivity
import io.legado.app.ui.host.AppFilePickers
import io.legado.app.ui.host.rememberAppFilePickers
import io.legado.app.uikit.facade.AppToast
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppFormDialog
import io.legado.app.uikit.v5.components.AppFormField
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppOptionRow
import io.legado.app.uikit.v5.components.AppProgressDialog
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppSectionHeader
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefString
import io.legado.app.utils.FileDoc
import io.legado.app.utils.checkWrite
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * P4-3：备份与恢复页 V5 原生（台账 #2 最后一项）。
 *
 * 流程全走后端 BackupRestoreController（#29-B7 已下沉）：
 * 备份触发/进度、S3 满回退判定、云存储类型回退、云端/本地恢复、资源包发现。
 * 本页只负责：设置树渲染、文件/目录选择、确认弹窗、进度展示。
 */
class HostBackupActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, HostBackupActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostBackupScreen() }
    }
}

/** 页面设置快照：偏好读一次，改动后 revision++ 重读。 */
private data class BackupSettings(
    val cloudType: CloudStorageType = CloudStorageType.WEBDAV,
    val webDavUrl: String = "",
    val webDavAccount: String = "",
    val webDavPassword: String = "",
    val webDavDir: String = "",
    val webDavDeviceName: String = "",
    val backupPath: String = "",
    val booleans: Map<String, Boolean> = emptyMap(),
)

private fun loadSettings(): BackupSettings {
    val prefs = appCtx.defaultSharedPreferences
    val keys = listOf(
        PreferKey.webDavDeleteOldBackup, PreferKey.webDavBackupCover,
        PreferKey.autoSwitchS3Container, PreferKey.syncThemePackages,
        PreferKey.syncBookProgress, PreferKey.syncBookProgressPlus,
        PreferKey.backupBookFiles, PreferKey.backupAssetsSeparately,
        PreferKey.onlyLatestBackup, PreferKey.autoCheckNewBackup,
        PreferKey.autoBackupOnShelfChange, PreferKey.overwriteShelfOnRestore,
    )
    return BackupSettings(
        cloudType = AppCloudStorage.type,
        webDavUrl = appCtx.getPrefString(PreferKey.webDavUrl).orEmpty(),
        webDavAccount = appCtx.getPrefString(PreferKey.webDavAccount).orEmpty(),
        webDavPassword = appCtx.getPrefString(PreferKey.webDavPassword).orEmpty(),
        webDavDir = AppConfig.webDavDir ?: "legado",
        webDavDeviceName = AppConfig.webDavDeviceName.orEmpty(),
        backupPath = AppConfig.backupPath.orEmpty(),
        booleans = keys.associateWith { prefs.getBoolean(it, defaultValueOf(it)) },
    )
}

private fun defaultValueOf(key: String): Boolean = when (key) {
    PreferKey.syncBookProgress -> true
    PreferKey.autoSwitchS3Container -> true
    PreferKey.onlyLatestBackup -> true
    PreferKey.autoCheckNewBackup -> true
    else -> false
}
@Composable
private fun HostBackupScreen() {
    val context = LocalContext.current
    val activity = context as HostBackupActivity
    val scope = rememberCoroutineScope()
    val visual = io.legado.app.uikit.v5.runtime.LocalVisualSpec.current
    val density = visual.density

    // 偏好改动后重读设置树
    var revision by remember { mutableIntStateOf(0) }
    val settings = remember(revision) { loadSettings() }

    val backupProgress by BackupProgressHolder.progress.collectAsState()
    var restoring by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // 选完备份目录后待执行的备份目标（在 startBackup 定义之后统一触发）
    var pendingBackupPath by remember { mutableStateOf<String?>(null) }
    var localRestoreAssets by remember { mutableStateOf<Pair<String, List<Pair<String, String>>>?>(null) }
    // 目录/文件选择器：pathPicker=设置项改路径；backupDirPicker=备份触发时改完即备份
    val pathPicker = rememberAppFilePickers { uri, _ ->
        val value = if (uri.isContentScheme()) uri.toString() else uri.path.orEmpty()
        if (value.isNotBlank()) {
            AppConfig.backupPath = value
            revision++
        }
    }
    val backupDirPicker = rememberAppFilePickers { uri, _ ->
        val value = if (uri.isContentScheme()) uri.toString() else uri.path.orEmpty()
        if (value.isNotBlank()) {
            AppConfig.backupPath = value
            revision++
            pendingBackupPath = value
        }
    }
    val filePicker = rememberAppFilePickers { uri, requestCode ->
        when (requestCode) {
            REQUEST_LOCAL_RESTORE -> scope.launch {
                busy = true
                val assets = BackupRestoreController.findLocalAssetsUris()
                busy = false
                localRestoreAssets = uri.toString() to assets.map { it.first.toString() to it.second }
            }
            REQUEST_IMPORT_OLD -> scope.launch {
                busy = true
                val summary = withContext(Dispatchers.IO) {
                    runCatching { ImportOldData.importUri(appCtx, uri) }
                }
                busy = false
                summary.onSuccess { AppToast.show(it) }
                    .onFailure {
                        AppLog.put("导入旧版数据出错\n${it.localizedMessage}", it)
                        AppToast.show("导入旧版数据失败\n${it.localizedMessage}")
                    }
            }
        }
    }

    // 弹层状态（文案由页面提供，组件不内置字符串）
    var textDialog by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var webDavDialog by remember { mutableStateOf(false) }
    var ignoreDialog by remember { mutableStateOf(false) }
    var restoreNames by remember { mutableStateOf<List<String>?>(null) }
    var cloudAssets by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var s3Fallback by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun setBoolean(key: String, value: Boolean) {
        appCtx.defaultSharedPreferences.edit { putBoolean(key, value) }
        revision++
    }

    fun setString(key: String, value: String) {
        appCtx.defaultSharedPreferences.edit { putString(key, value) }
        revision++
    }

    fun upCloudConfig() {
        scope.launch {
            runCatching { AppCloudStorage.upConfig() }
        }
    }

    fun startBackup(path: String, uploadCloud: Boolean = true, uploadWebDavFallback: Boolean = false) {
        if (uploadCloud && BackupRestoreController.shouldShowS3FullWebDavFallback()) {
            s3Fallback = path
            return
        }
        BackupRestoreController.startBackup(
            backupPath = path,
            uploadCloud = uploadCloud,
            uploadWebDavFallback = uploadWebDavFallback,
        ) { error ->
            if (error == null) {
                AppToast.show(context.getString(R.string.backup_success))
            } else if (BackupRestoreController.isS3CapacityFull(error)
                && BackupRestoreController.shouldShowS3FullFallbackAfterFailure()
            ) {
                s3Fallback = path
            } else {
                AppLog.put("备份出错\n${error.localizedMessage}", error)
                AppToast.show(
                    context.getString(R.string.backup_fail, error.localizedMessage)
                )
            }
        }
    }

    /** 备份入口：路径缺失/不可写时先选目录（选完自动开始），Content 路径校验可写性，其余走存储权限。 */
    fun requestBackup() {
        val path = settings.backupPath
        if (path.isBlank()) {
            backupDirPicker.pickDir(context.getString(R.string.select_backup_path))
            return
        }
        if (path.isContentScheme()) {
            scope.launch {
                val canWrite = withContext(Dispatchers.IO) {
                    runCatching { FileDoc.fromDir(android.net.Uri.parse(path)).checkWrite() }
                        .getOrDefault(false)
                }
                if (canWrite) {
                    startBackup(path)
                } else {
                    backupDirPicker.pickDir(context.getString(R.string.select_backup_path))
                }
            }
        } else {
            io.legado.app.lib.permission.PermissionsCompat.Builder()
                .addPermissions(*io.legado.app.lib.permission.Permissions.Group.STORAGE)
                .rationale(R.string.tip_perm_request_storage)
                .onGranted { startBackup(path) }
                .request()
        }
    }

    /** 云端恢复：列备份 → 选包 → 问资源包 → 执行。 */
    fun restoreFromCloud() {
        scope.launch {
            busy = true
            val names = runCatching { BackupRestoreController.listCloudBackups() }
                .onFailure {
                    AppLog.put("恢复备份出错\n${it.localizedMessage}", it)
                }
                .getOrDefault(emptyList())
            busy = false
            if (names.isEmpty()) {
                AppToast.show(context.getString(R.string.cloud_storage_config_required))
            } else {
                restoreNames = names
            }
        }
    }

    fun restoreFromLocalPick() {
        filePicker.pick(
            title = context.getString(R.string.select_restore_file),
            allowExtensions = arrayOf("zip"),
            requestCode = REQUEST_LOCAL_RESTORE,
        )
    }

    fun importOldData() {
        filePicker.pick(
            title = context.getString(R.string.menu_import_old_version),
            requestCode = REQUEST_IMPORT_OLD,
        )
    }

    // 选完备份目录 → 自动开始备份
    androidx.compose.runtime.LaunchedEffect(pendingBackupPath) {
        pendingBackupPath?.let {
            pendingBackupPath = null
            startBackup(it)
        }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    HostV5Page(
        title = stringResource(R.string.backup_restore),
        onBack = { activity.finish() },
    ) {
        io.legado.app.uikit.v5.components.AppFastScroller(
            listState = listState,
            bottomContentPadding = density.screenPadding,
            endContentPadding = density.screenPadding,
        ) {
            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = density.screenPadding,
                    end = density.screenPadding,
                    top = density.blockGap / 2,
                    bottom = density.screenPadding,
                ),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(density.blockGap / 2),
            ) {
                item { AppSectionHeader(title = stringResource(R.string.web_dav_set)) }

                // 云存储类型
                item {
                    val entries = context.resources.getStringArray(R.array.cloud_storage_types)
                    val values = context.resources.getStringArray(R.array.cloud_storage_type_values)
                    AppOptionRow(
                        title = stringResource(R.string.cloud_storage),
                        options = entries.toList(),
                        selectedIndex = values.indexOf(settings.cloudType.name).coerceAtLeast(0),
                        onSelect = { index ->
                            values.getOrNull(index)?.let {
                                setString(PreferKey.cloudStorageType, it)
                                upCloudConfig()
                            }
                        },
                    )
                }

                if (settings.cloudType == CloudStorageType.WEBDAV) {
                    item {
                        io.legado.app.uikit.v5.components.AppListItem(
                            headline = stringResource(R.string.webdav_account_manage),
                            supporting = webDavSummary(settings),
                            onClick = { webDavDialog = true },
                        )
                    }
                    item {
                        io.legado.app.uikit.v5.components.AppListItem(
                            headline = stringResource(R.string.sub_dir),
                            supporting = settings.webDavDir,
                            onClick = {
                                textDialog = Triple(
                                    PreferKey.webDavDir,
                                    context.getString(R.string.sub_dir),
                                    settings.webDavDir,
                                )
                            },
                        )
                    }
                    item {
                        io.legado.app.uikit.v5.components.AppListItem(
                            headline = stringResource(R.string.webdav_device_name),
                            supporting = settings.webDavDeviceName,
                            onClick = {
                                textDialog = Triple(
                                    PreferKey.webDavDeviceName,
                                    context.getString(R.string.webdav_device_name),
                                    settings.webDavDeviceName,
                                )
                            },
                        )
                    }
                }

                item {
                    AppRowWithSwitch(
                        title = stringResource(R.string.webdav_delete_old_backup_t),
                        summary = stringResource(R.string.webdav_delete_old_backup_s),
                        checked = settings.booleans[PreferKey.webDavDeleteOldBackup] ?: false,
                        onCheckedChange = { setBoolean(PreferKey.webDavDeleteOldBackup, it) },
                    )
                }
                item {
                    AppRowWithSwitch(
                        title = stringResource(R.string.webdav_backup_cover_t),
                        summary = stringResource(R.string.webdav_backup_cover_s),
                        checked = settings.booleans[PreferKey.webDavBackupCover] ?: false,
                        onCheckedChange = { setBoolean(PreferKey.webDavBackupCover, it) },
                    )
                }

                if (settings.cloudType == CloudStorageType.S3) {
                    item {
                        io.legado.app.uikit.v5.components.AppListItem(
                            headline = stringResource(R.string.s3_container_manage),
                            supporting = stringResource(R.string.s3_container_manage_summary),
                            onClick = { activity.startActivity<S3ContainerManageActivity>() },
                        )
                    }
                    item {
                        AppRowWithSwitch(
                            title = stringResource(R.string.s3_auto_switch_container),
                            summary = stringResource(R.string.s3_auto_switch_container_summary),
                            checked = settings.booleans[PreferKey.autoSwitchS3Container] ?: true,
                            onCheckedChange = { setBoolean(PreferKey.autoSwitchS3Container, it) },
                        )
                    }
                }

                item {
                    io.legado.app.uikit.v5.components.AppListItem(
                        headline = "书库容器",
                        supporting = "管理用于同步阅读章节缓存的独立 S3 容器",
                        onClick = { activity.startActivity<LibraryContainerManageActivity>() },
                    )
                }
                item {
                    AppRowWithSwitch(
                        title = stringResource(R.string.sync_theme_packages),
                        summary = stringResource(R.string.sync_theme_packages_summary),
                        checked = settings.booleans[PreferKey.syncThemePackages] ?: false,
                        onCheckedChange = { checked ->
                            if (checked && !BackupRestoreController.hasCloudStorageAccount()) {
                                AppToast.show(context.getString(R.string.cloud_storage_config_required))
                            } else {
                                setBoolean(PreferKey.syncThemePackages, checked)
                            }
                        },
                    )
                }
                item {
                    AppRowWithSwitch(
                        title = stringResource(R.string.sync_book_progress_t),
                        summary = stringResource(R.string.sync_book_progress_s),
                        checked = settings.booleans[PreferKey.syncBookProgress] ?: true,
                        onCheckedChange = { setBoolean(PreferKey.syncBookProgress, it) },
                    )
                }
                item {
                    AppRowWithSwitch(
                        title = stringResource(R.string.sync_book_progress_plus_t),
                        summary = stringResource(R.string.sync_book_progress_plus_s),
                        checked = settings.booleans[PreferKey.syncBookProgressPlus] ?: false,
                        enabled = settings.booleans[PreferKey.syncBookProgress] ?: true,
                        onCheckedChange = { setBoolean(PreferKey.syncBookProgressPlus, it) },
                    )
                }
                item { AppSectionHeader(title = stringResource(R.string.backup_restore)) }

                item {
                    io.legado.app.uikit.v5.components.AppListItem(
                        headline = stringResource(R.string.backup_path),
                        supporting = settings.backupPath.ifBlank {
                            context.getString(R.string.select_backup_path)
                        },
                        onClick = { pathPicker.pickDir(context.getString(R.string.select_backup_path)) },
                    )
                }
                item {
                    io.legado.app.uikit.v5.components.AppListItem(
                        headline = stringResource(R.string.backup),
                        supporting = stringResource(R.string.backup_summary),
                        onClick = { requestBackup() },
                    )
                }
                item {
                    io.legado.app.uikit.v5.components.AppListItem(
                        headline = stringResource(R.string.restore),
                        supporting = stringResource(R.string.restore_summary),
                        onClick = { restoreFromCloud() },
                    )
                }
                item {
                    io.legado.app.uikit.v5.components.AppListItem(
                        headline = context.getString(R.string.restore) + "（本地）",
                        supporting = context.getString(R.string.select_restore_file),
                        onClick = { restoreFromLocalPick() },
                    )
                }
                item {
                    io.legado.app.uikit.v5.components.AppListItem(
                        headline = stringResource(R.string.restore_ignore),
                        supporting = stringResource(R.string.restore_ignore_summary),
                        onClick = { ignoreDialog = true },
                    )
                }
                item {
                    io.legado.app.uikit.v5.components.AppListItem(
                        headline = stringResource(R.string.menu_import_old_version),
                        supporting = stringResource(R.string.import_old_summary),
                        onClick = { importOldData() },
                    )
                }
                item {
                    AppRowWithSwitch(
                        title = stringResource(R.string.backup_book_files_t),
                        summary = stringResource(R.string.backup_book_files_s),
                        checked = settings.booleans[PreferKey.backupBookFiles] ?: false,
                        onCheckedChange = { setBoolean(PreferKey.backupBookFiles, it) },
                    )
                }
                item {
                    AppRowWithSwitch(
                        title = stringResource(R.string.backup_assets_separately_t),
                        summary = stringResource(R.string.backup_assets_separately_s),
                        checked = settings.booleans[PreferKey.backupAssetsSeparately] ?: false,
                        onCheckedChange = { setBoolean(PreferKey.backupAssetsSeparately, it) },
                    )
                }
                item {
                    AppRowWithSwitch(
                        title = stringResource(R.string.only_latest_backup_t),
                        summary = stringResource(R.string.only_latest_backup_s),
                        checked = settings.booleans[PreferKey.onlyLatestBackup] ?: true,
                        onCheckedChange = { setBoolean(PreferKey.onlyLatestBackup, it) },
                    )
                }
                item {
                    AppRowWithSwitch(
                        title = stringResource(R.string.auto_check_new_backup_t),
                        summary = stringResource(R.string.auto_check_new_backup_s),
                        checked = settings.booleans[PreferKey.autoCheckNewBackup] ?: true,
                        onCheckedChange = { setBoolean(PreferKey.autoCheckNewBackup, it) },
                    )
                }
                item {
                    AppRowWithSwitch(
                        title = stringResource(R.string.auto_backup_on_shelf_change_t),
                        summary = stringResource(R.string.auto_backup_on_shelf_change_s),
                        checked = settings.booleans[PreferKey.autoBackupOnShelfChange] ?: false,
                        onCheckedChange = { setBoolean(PreferKey.autoBackupOnShelfChange, it) },
                    )
                }
                item {
                    AppRowWithSwitch(
                        title = stringResource(R.string.restore_overwrite_shelf_t),
                        summary = stringResource(R.string.restore_overwrite_shelf_s),
                        checked = settings.booleans[PreferKey.overwriteShelfOnRestore] ?: false,
                        onCheckedChange = { setBoolean(PreferKey.overwriteShelfOnRestore, it) },
                    )
                }
            }
        }
    }
    // 单行文本输入（子目录 / 设备名）
    textDialog?.let { (key, title, initial) ->
        var value by remember(title) { mutableStateOf(initial) }
        AppInputDialog(
            title = title,
            value = value,
            onValueChange = { value = it },
            placeholder = title,
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = { text ->
                textDialog = null
                setString(key, text)
                upCloudConfig()
            },
            onDismiss = { textDialog = null },
        )
    }

    // WebDav 账号三字段
    if (webDavDialog) {
        AppFormDialog(
            title = stringResource(R.string.webdav_account_manage),
            fields = listOf(
                AppFormField(
                    key = PreferKey.webDavUrl,
                    label = stringResource(R.string.web_dav_url),
                    initial = settings.webDavUrl,
                ),
                AppFormField(
                    key = PreferKey.webDavAccount,
                    label = stringResource(R.string.web_dav_account),
                    initial = settings.webDavAccount,
                ),
                AppFormField(
                    key = PreferKey.webDavPassword,
                    label = stringResource(R.string.web_dav_pw),
                    initial = settings.webDavPassword,
                ),
            ),
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = { values ->
                webDavDialog = false
                appCtx.defaultSharedPreferences.edit {
                    putString(PreferKey.webDavUrl, values[PreferKey.webDavUrl].orEmpty().trim())
                    putString(PreferKey.webDavAccount, values[PreferKey.webDavAccount].orEmpty().trim())
                    putString(PreferKey.webDavPassword, values[PreferKey.webDavPassword].orEmpty())
                }
                revision++
                upCloudConfig()
            },
            onDismiss = { webDavDialog = false },
        )
    }

    // 恢复忽略项（多选）
    if (ignoreDialog) {
        val keys = BackupConfig.ignoreKeys
        io.legado.app.uikit.v5.components.AppMultiFilterDialog(
            title = stringResource(R.string.restore_ignore),
            options = BackupConfig.ignoreTitle.toList(),
            selectedIndices = keys.indices
                .filter { BackupConfig.ignoreConfig[keys[it]] ?: false }
                .toSet(),
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = { selected ->
                ignoreDialog = false
                keys.forEachIndexed { index, key ->
                    BackupConfig.ignoreConfig[key] = index in selected
                }
                BackupConfig.saveIgnoreConfig()
            },
            onDismiss = { ignoreDialog = false },
        )
    }

    // 云端备份列表选择
    restoreNames?.let { names ->
        io.legado.app.uikit.v5.components.AppFilterDialog(
            title = stringResource(R.string.select_restore_file),
            options = names,
            selectedIndex = -1,
            onSelect = { index ->
                val name = names.getOrNull(index) ?: return@AppFilterDialog
                restoreNames = null
                scope.launch {
                    busy = true
                    val assets = runCatching { BackupRestoreController.listCloudAssetNames() }
                        .getOrDefault(emptyList())
                    busy = false
                    if (assets.isEmpty()) {
                        startCloudRestore(activity, name, emptyList()) { msg -> message = msg }
                    } else {
                        cloudAssets = name to assets
                    }
                }
            },
            onDismiss = { restoreNames = null },
        )
    }

    // 云端资源包是否一并恢复
    cloudAssets?.let { (name, assets) ->
        AppConfirmDialog(
            title = stringResource(R.string.restore),
            message = "检测到资源文件包\n${assets.joinToString("\n")}\n是否一并恢复字体、背景图等资源？",
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = {
                cloudAssets = null
                startCloudRestore(activity, name, assets) { msg -> message = msg }
            },
            onDismiss = {
                cloudAssets = null
                startCloudRestore(activity, name, emptyList()) { msg -> message = msg }
            },
        )
    }

    // 本地资源包是否一并恢复
    localRestoreAssets?.let { (uriText, assets) ->
        AppConfirmDialog(
            title = stringResource(R.string.restore),
            message = "备份路径下检测到资源文件包\n${assets.joinToString("\n") { it.second }}\n是否一并恢复字体、背景图等资源？",
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = {
                localRestoreAssets = null
                startLocalRestore(activity, uriText, assets.map { it.first }) { msg -> message = msg }
            },
            onDismiss = {
                localRestoreAssets = null
                startLocalRestore(activity, uriText, emptyList()) { msg -> message = msg }
            },
        )
    }

    // S3 全部容器已满：改用 WebDav / 仅本地备份 / 不再提醒
    s3Fallback?.let { path ->
        io.legado.app.uikit.v5.components.AppMenuSheet(
            title = stringResource(R.string.s3_full_webdav_fallback_title),
            actions = buildList {
                if (BackupRestoreController.hasWebDavAccount()) {
                    add(stringResource(R.string.s3_full_webdav_fallback_upload) to {
                        s3Fallback = null
                        startBackup(path, uploadCloud = true, uploadWebDavFallback = true)
                    })
                }
                add(stringResource(R.string.s3_full_webdav_fallback_ignore) to {
                    s3Fallback = null
                    startBackup(path, uploadCloud = false)
                })
                add(stringResource(R.string.s3_full_webdav_fallback_never) to {
                    s3Fallback = null
                    BackupRestoreController.setS3FullWebDavFallbackNeverRemind()
                    startBackup(path, uploadCloud = false)
                })
            },
            onDismiss = { s3Fallback = null },
        )
    }

    // 备份进度 / 等待中
    backupProgress?.let { progress ->
        AppProgressDialog(
            title = stringResource(R.string.backup),
            message = stageText(context, progress),
            cancelLabel = null,
            onCancel = null,
            onDismiss = {},
            progress = progress.fraction,
        )
    }
    if (busy || restoring) {
        AppProgressDialog(
            title = stringResource(R.string.restore),
            message = stringResource(R.string.loading),
            cancelLabel = null,
            onCancel = null,
            onDismiss = {},
        )
    }

    message?.let {
        AppToast.show(it)
        message = null
    }
}

private const val REQUEST_LOCAL_RESTORE = 1
private const val REQUEST_IMPORT_OLD = 2

private fun webDavSummary(settings: BackupSettings): String = when {
    settings.webDavUrl.isBlank() && settings.webDavAccount.isBlank() -> ""
    settings.webDavAccount.isBlank() -> settings.webDavUrl
    settings.webDavUrl.isBlank() -> settings.webDavAccount
    else -> "${settings.webDavUrl} / ${settings.webDavAccount}"
}

private fun stageText(context: Context, progress: BackupProgress): String {
    val res = when (progress.stage) {
        BackupStage.PREPARING -> R.string.loading
        BackupStage.PACKING -> R.string.backup
        BackupStage.SAVING -> R.string.backup
        BackupStage.UPLOADING -> R.string.backup
        BackupStage.FINISHED -> R.string.backup_success
    }
    return context.getString(res)
}

/** 云端恢复（全局协程：离开页面不中断）。 */
private fun startCloudRestore(
    activity: HostBackupActivity,
    name: String,
    assets: List<String>,
    onMessage: (String) -> Unit,
) {
    io.legado.app.help.coroutine.Coroutine.async(io.legado.app.help.coroutine.Coroutine.defaultScope) {
        BackupRestoreController.restoreFromCloud(name, assets)
    }.onError {
        AppLog.put("云端恢复出错\n${it.localizedMessage}", it)
        onMessage("云端恢复出错\n${it.localizedMessage}")
    }
}

/** 本地恢复（全局协程：离开页面不中断）。 */
private fun startLocalRestore(
    activity: HostBackupActivity,
    uriText: String,
    assets: List<String>,
    onMessage: (String) -> Unit,
) {
    io.legado.app.help.coroutine.Coroutine.async(io.legado.app.help.coroutine.Coroutine.defaultScope) {
        BackupRestoreController.restoreFromLocal(
            android.net.Uri.parse(uriText),
            assets.map { android.net.Uri.parse(it) },
        )
    }.onError {
        AppLog.put("本地恢复出错\n${it.localizedMessage}", it)
        onMessage("本地恢复出错\n${it.localizedMessage}")
    }
}

