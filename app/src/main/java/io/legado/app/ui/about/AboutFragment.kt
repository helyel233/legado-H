package io.legado.app.ui.about

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppMenuSheet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.CrashHandler
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.update.AppUpdate
import io.legado.app.help.update.AppUpdateConfig
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.debuglog.DebugFloatingBallManager
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.externalCache
import io.legado.app.utils.find
import io.legado.app.utils.list
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import splitties.init.appCtx
import java.io.File

class AboutFragment : ComposeSettingFragment() {

    private val waitDialog by lazy {
        WaitDialog(requireContext())
    }

    override val titleRes: Int = R.string.about

    override val autoOpenTargetItem: Boolean = false

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        action(
                            key = KEY_CONTRIBUTORS,
                            title = getString(R.string.contributors),
                            summary = getString(R.string.contributors_summary_sigma)
                        ) {
                            openUrl(R.string.contributors_url)
                        },
                        action(
                            key = KEY_UPDATE_LOG,
                            title = getString(R.string.update_log),
                            summary = "${getString(R.string.version)} ${appInfo.versionName}"
                        ) {
                            showMdFile(getString(R.string.update_log), "updateLog.md")
                        },
                        action(
                            key = KEY_CHECK_UPDATE,
                            title = getString(R.string.check_update)
                        ) {
                            checkUpdate()
                        },
                        action(
                            key = KEY_INTERNAL_BETA,
                            title = "内测版",
                            summary = internalBetaSummary()
                        ) {
                            showInternalBetaDialog()
                        },
                        action(
                            key = KEY_UPDATE_ACCELERATION,
                            title = getString(R.string.update_acceleration_manage),
                            summary = AppUpdateConfig.summary(requireContext())
                        ) {
                            showUpdateAccelerationManage()
                        }
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.other),
                    items = listOf(
                        action(
                            key = KEY_CRASH_LOG,
                            title = getString(R.string.crash_log)
                        ) {
                            showDialogFragment<CrashLogsDialog>()
                        },
                        action(
                            key = KEY_DEBUG_LOG,
                            title = getString(R.string.debug_log)
                        ) {
                            showDialogFragment<DebugLogDialog>()
                        },
                        SettingSwitchSpec(
                            key = PreferKey.debugLogFloatingBall,
                            title = getString(R.string.debug_floating_ball),
                            summary = getString(R.string.debug_floating_ball_summary),
                            checked = AppConfig.debugLogFloatingBall,
                            onCheckedChange = { checked ->
                                requireContext().putPrefBoolean(
                                    PreferKey.debugLogFloatingBall, checked
                                )
                                DebugFloatingBallManager.updateFloatingBallState(checked)
                            }
                        ),
                        action(
                            key = KEY_SAVE_LOG,
                            title = getString(R.string.save_log)
                        ) {
                            saveLog()
                        },
                        action(
                            key = KEY_CREATE_HEAP_DUMP,
                            title = getString(R.string.create_heap_dump)
                        ) {
                            createHeapDump()
                        },
                        action(
                            key = KEY_PRIVACY_POLICY,
                            title = getString(R.string.privacy_policy)
                        ) {
                            showMdFile(getString(R.string.privacy_policy), "privacyPolicy.md")
                        },
                        action(
                            key = KEY_LICENSE,
                            title = getString(R.string.license)
                        ) {
                            showMdFile(getString(R.string.license), "LICENSE.md")
                        },
                        action(
                            key = KEY_DISCLAIMER,
                            title = getString(R.string.disclaimer)
                        ) {
                            showMdFile(getString(R.string.disclaimer), "disclaimer.md")
                        }
                    )
                )
            )
        )
    }

    private fun action(
        key: String,
        title: CharSequence,
        summary: CharSequence? = null,
        onClick: () -> Unit
    ): SettingActionSpec {
        return SettingActionSpec(
            key = key,
            title = title,
            summary = summary,
            onClick = onClick
        )
    }

    private fun showUpdateAccelerationManage() {
        UpdateAcceleratorDialog.show(this) {
            refreshSettings()
        }
    }

    private fun internalBetaSummary(): String {
        val enabled = AppUpdateConfig.internalBetaConfigured
        val url = AppUpdateConfig.internalBetaUrl.orEmpty()
        return if (enabled) {
            "已启用 · $url"
        } else {
            "未启用"
        }
    }

    private fun showInternalBetaDialog() {
        betaKeyText = AppUpdateConfig.internalBetaKey.orEmpty()
        betaUrlText = AppUpdateConfig.internalBetaUrl.orEmpty()
        betaEnabled = AppUpdateConfig.internalBetaEnabled
        betaDialogOpen = true
    }

    internal var betaDialogOpen by mutableStateOf(false)
    internal var betaKeyText by mutableStateOf("")
    internal var betaUrlText by mutableStateOf("")
    internal var betaEnabled by mutableStateOf(false)

    override val extraDialogs: @Composable () -> Unit = {
        if (betaDialogOpen) {
            val density = LocalVisualSpec.current.density
            AppDialog(onDismiss = { betaDialogOpen = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(density.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    Text(
                        text = "内测版更新",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "启用后，检测更新会优先比较正式版和内测版。正式版版本高于内测版时更新正式版，否则在没有更高正式版时更新内测版。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AppTextField(
                        value = betaKeyText,
                        onValueChange = { betaKeyText = it },
                        placeholder = "下载密钥",
                        singleLine = true,
                    )
                    AppTextField(
                        value = betaUrlText,
                        onValueChange = { betaUrlText = it },
                        placeholder = "下载地址",
                        singleLine = true,
                    )
                    AppRowWithSwitch(
                        title = "启用内测版更新",
                        checked = betaEnabled,
                        onCheckedChange = { betaEnabled = it },
                        card = false,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                        AppGhostButton(
                            text = stringResource(R.string.cancel),
                            modifier = Modifier.weight(1f),
                            onClick = { betaDialogOpen = false },
                        )
                        AppFilledButton(
                            text = stringResource(R.string.ok),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val key = betaKeyText.trim()
                                val url = betaUrlText.trim()
                                val valid = key.isNotBlank() &&
                                    (url.startsWith("https://") || url.startsWith("http://"))
                                val hasAny = key.isNotBlank() || url.isNotBlank()
                                if (hasAny && !valid) {
                                    return@AppFilledButton
                                }
                                betaDialogOpen = false
                                AppUpdateConfig.internalBetaKey = key
                                AppUpdateConfig.internalBetaUrl = url
                                AppUpdateConfig.internalBetaEnabled = betaEnabled && valid
                                refreshSettings()
                            },
                        )
                    }
                }
            }
        }
    }

    @Suppress("SameParameterValue")
    private fun openUrl(@StringRes addressID: Int) {
        requireContext().openUrl(getString(addressID))
    }

    /**
     * 显示md文件
     */
    private fun showMdFile(title: String, fileName: String) {
        val mdText = String(requireContext().assets.open(fileName).readBytes())
        showDialogFragment(TextDialog(title, mdText, TextDialog.Mode.MD))
    }

    /**
     * 检测更新
     */
    private fun checkUpdate() {
        waitDialog.show()
        AppUpdate.preferredUpdate.run {
            check(lifecycleScope)
                .onSuccess {
                    showDialogFragment(
                        UpdateDialog(it)
                    )
                }.onError {
                    showDialogFragment(
                        TextDialog(
                            getString(R.string.check_update),
                            if (AppUpdate.isLatestVersionError(it)) {
                                getString(R.string.update_no_new_version)
                            } else {
                                it.localizedMessage ?: getString(R.string.check_update)
                            },
                            TextDialog.Mode.TEXT
                        )
                    )
                }.onFinally {
                    waitDialog.dismiss()
                }
        }
    }

    private fun saveLog() {
        Coroutine.async(Coroutine.defaultScope) {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordLog) {
                appCtx.toastOnUi("未开启日志记录，请去其他设置里打开记录日志")
                delay(3000)
            }
            val doc = FileDoc.fromUri(Uri.parse(backupPath), true)
            copyLogs(doc)
            copyHeapDump(doc)
            appCtx.toastOnUi("已保存至备份目录")
        }.onError {
            AppLog.put("保存日志出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun createHeapDump() {
        Coroutine.async(Coroutine.defaultScope) {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordHeapDump) {
                appCtx.toastOnUi("未开启堆转储记录，请去其他设置里打开记录堆转储")
                delay(3000)
            }
            appCtx.toastOnUi("开始创建堆转储")
            System.gc()
            CrashHandler.doHeapDump(true)
            val doc = FileDoc.fromUri(Uri.parse(backupPath), true)
            if (!copyHeapDump(doc)) {
                appCtx.toastOnUi("未找到堆转储文件")
            } else {
                appCtx.toastOnUi("已保存至备份目录")
            }
        }.onError {
            AppLog.put("保存堆转储失败\n${it.localizedMessage}", it)
        }
    }

    private fun copyLogs(doc: FileDoc) {
        val cacheDir = appCtx.externalCache
        val logFiles = File(cacheDir, "logs")
        val crashFiles = File(cacheDir, "crash")
        val logcatFile = File(cacheDir, "logcat.txt")

        dumpLogcat(logcatFile)

        val zipFile = File(cacheDir, "logs.zip")
        ZipUtils.zipFiles(arrayListOf(logFiles, crashFiles, logcatFile), zipFile)

        doc.find("logs.zip")?.delete()

        zipFile.inputStream().use { input ->
            doc.createFileIfNotExist("logs.zip").openOutputStream().getOrNull()
                ?.use {
                    input.copyTo(it)
                }
        }
        zipFile.delete()
    }

    private fun copyHeapDump(doc: FileDoc): Boolean {
        val heapFile = FileDoc.fromFile(File(appCtx.externalCache, "heapDump")).list()
            ?.firstOrNull() ?: return false
        doc.find("heapDump")?.delete()
        val heapDumpDoc = doc.createFolderIfNotExist("heapDump")
        heapFile.openInputStream().getOrNull()?.use { input ->
            heapDumpDoc.createFileIfNotExist(heapFile.name).openOutputStream().getOrNull()
                ?.use {
                    input.copyTo(it)
                }
        }
        return true
    }

    private fun dumpLogcat(file: File) {
        try {
            val process = Runtime.getRuntime().exec("logcat -d")
            file.outputStream().use {
                process.inputStream.copyTo(it)
            }
        } catch (e: Exception) {
            AppLog.put("保存Logcat失败\n$e", e)
        }
    }

    companion object {
        private const val KEY_CONTRIBUTORS = "contributors"
        private const val KEY_UPDATE_LOG = "update_log"
        private const val KEY_CHECK_UPDATE = "check_update"
        private const val KEY_INTERNAL_BETA = "internal_beta"
        private const val KEY_UPDATE_ACCELERATION = "update_acceleration_manage"
        private const val KEY_LICENSE = "license"
        private const val KEY_DISCLAIMER = "disclaimer"
        private const val KEY_PRIVACY_POLICY = "privacyPolicy"
        private const val KEY_CRASH_LOG = "crashLog"
        private const val KEY_SAVE_LOG = "saveLog"
        private const val KEY_DEBUG_LOG = "debugLog"
        private const val KEY_CREATE_HEAP_DUMP = "createHeapDump"
    }

}
