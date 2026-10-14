package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.CrashHandler
import io.legado.app.help.config.AppConfig
import io.legado.app.help.update.AppUpdate
import io.legado.app.help.update.AppUpdateConfig
import io.legado.app.ui.about.DebugLogDialog
import io.legado.app.ui.about.UpdateAcceleratorDialog
import io.legado.app.ui.debuglog.DebugFloatingBallManager
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.uikit.v5.components.AppListItem
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppSectionHeader
import io.legado.app.utils.FileDoc
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.externalCache
import io.legado.app.utils.find
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.list
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * P4-3：关于页 V5 化（2026-10-12）。
 *
 * 对齐旧 AboutFragment 全部条目；升级点：崩溃日志入口 → V5 统一日志页
 * （HostLogActivity，含本地+备份路径）；调试悬浮球开关用 AppRowWithSwitch。
 */
class HostAboutActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, HostAboutActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostAboutScreen() }
    }
}

@Composable
private fun HostAboutScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val visual = io.legado.app.uikit.v5.runtime.LocalVisualSpec.current
    val density = visual.density
    val appInfo = io.legado.app.constant.AppConst.appInfo

    // 调试悬浮球开关（#16 a11y 之后仍沿用 pref 键，与旧页互通）
    var betaDialogOpen by remember { mutableStateOf(false) }
    var betaKeyText by remember { mutableStateOf("") }
    var betaUrlText by remember { mutableStateOf("") }
    var betaEnabled by remember { mutableStateOf(false) }
    var floatingBall by remember {
        mutableStateOf(context.getPrefBoolean(PreferKey.debugLogFloatingBall))
    }

    fun showMdFile(title: String, fileName: String) {
        val mdText = String(context.assets.open(fileName).readBytes())
        TextDialog(title, mdText, TextDialog.Mode.MD)
            .show(activity.supportFragmentManager, "textDialog")
    }

    fun checkUpdate() {
        val result = AppUpdate.preferredUpdate.run { check(activity.lifecycleScope) }
        result.onSuccess {
            io.legado.app.ui.about.UpdateDialog(it)
                .show(activity.supportFragmentManager, "update")
        }.onError {
            TextDialog(
                context.getString(R.string.check_update),
                if (AppUpdate.isLatestVersionError(it)) {
                    context.getString(R.string.update_no_new_version)
                } else {
                    it.localizedMessage ?: context.getString(R.string.check_update)
                },
                TextDialog.Mode.TEXT,
            ).show(activity.supportFragmentManager, "textDialog")
        }
    }

    fun saveLog() {
        activity.lifecycleScope.launch {
            val backupPath = AppConfig.backupPath ?: run {
                context.toastOnUi("未设置备份目录")
                return@launch
            }
            if (!AppConfig.recordLog) {
                context.toastOnUi("未开启日志记录，请去其他设置里打开记录日志")
                delay(3000)
            }
            val doc = FileDoc.fromUri(Uri.parse(backupPath), true)
            copyLogs(doc)
            copyHeapDump(doc)
            context.toastOnUi("已保存至备份目录")
        }
    }

    fun createHeapDump() {
        activity.lifecycleScope.launch {
            val backupPath = AppConfig.backupPath ?: run {
                context.toastOnUi("未设置备份目录")
                return@launch
            }
            if (!AppConfig.recordHeapDump) {
                context.toastOnUi("未开启堆转储记录，请去其他设置里打开记录堆转储")
                delay(3000)
            }
            context.toastOnUi("开始创建堆转储")
            System.gc()
            CrashHandler.doHeapDump(true)
            val doc = FileDoc.fromUri(Uri.parse(backupPath), true)
            if (!copyHeapDump(doc)) {
                context.toastOnUi("未找到堆转储文件")
            } else {
                context.toastOnUi("已保存至备份目录")
            }
        }
    }

    fun internalBetaSummary(): String {
        val enabled = AppUpdateConfig.internalBetaConfigured
        val url = AppUpdateConfig.internalBetaUrl.orEmpty()
        return if (enabled) "已启用 · $url" else "未启用"
    }

    fun showInternalBetaDialog() {
        betaKeyText = AppUpdateConfig.internalBetaKey.orEmpty()
        betaUrlText = AppUpdateConfig.internalBetaUrl.orEmpty()
        betaEnabled = AppUpdateConfig.internalBetaEnabled
        betaDialogOpen = true
    }

    fun onBetaConfirm() {
        betaDialogOpen = false
        val key = betaKeyText.trim()
        val url = betaUrlText.trim()
        val valid = key.isNotBlank() && (url.startsWith("https://") || url.startsWith("http://"))
        AppUpdateConfig.internalBetaKey = key
        AppUpdateConfig.internalBetaUrl = url
        AppUpdateConfig.internalBetaEnabled = betaEnabled && valid
    }

    HostV5Page(
        title = stringResource(R.string.about),
        subtitle = "${stringResource(R.string.version)} ${appInfo.versionName}",
        onBack = { activity.finish() },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = density.screenPadding,
                end = density.screenPadding,
                top = density.blockGap / 2,
                bottom = density.screenPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(density.blockGap / 3),
        ) {
            item {
                AppListItem(
                    headline = stringResource(R.string.contributors),
                    supporting = stringResource(R.string.contributors_summary_sigma),
                    onClick = { context.openUrl(context.getString(R.string.contributors_url)) },
                )
            }
            item {
                AppListItem(
                    headline = stringResource(R.string.update_log),
                    supporting = "${stringResource(R.string.version)} ${appInfo.versionName}",
                    onClick = { showMdFile(context.getString(R.string.update_log), "updateLog.md") },
                )
            }
            item {
                AppListItem(
                    headline = stringResource(R.string.check_update),
                    onClick = { checkUpdate() },
                )
            }
            item {
                AppListItem(
                    headline = "内测版",
                    supporting = internalBetaSummary(),
                    onClick = { showInternalBetaDialog() },
                )
            }
            item {
                AppListItem(
                    headline = stringResource(R.string.update_acceleration_manage),
                    supporting = AppUpdateConfig.summary(context),
                    onClick = {
                        UpdateAcceleratorDialog.show(activity) { }
                    },
                )
            }

            item { AppSectionHeader(title = stringResource(R.string.other)) }

            item {
                AppListItem(
                    headline = stringResource(R.string.crash_log),
                    onClick = { HostLogActivity.start(context) },
                )
            }
            item {
                AppListItem(
                    headline = stringResource(R.string.debug_log),
                    onClick = { DebugLogDialog().show(activity.supportFragmentManager, "debugLog") },
                )
            }
            item {
                AppRowWithSwitch(
                    title = stringResource(R.string.debug_floating_ball),
                    summary = stringResource(R.string.debug_floating_ball_summary),
                    checked = floatingBall,
                    onCheckedChange = { checked ->
                        floatingBall = checked
                        context.putPrefBoolean(PreferKey.debugLogFloatingBall, checked)
                        DebugFloatingBallManager.updateFloatingBallState(checked)
                    },
                )
            }
            item {
                AppListItem(
                    headline = stringResource(R.string.save_log),
                    onClick = { saveLog() },
                )
            }
            item {
                AppListItem(
                    headline = stringResource(R.string.create_heap_dump),
                    onClick = { createHeapDump() },
                )
            }
            item {
                AppListItem(
                    headline = stringResource(R.string.privacy_policy),
                    onClick = { showMdFile(context.getString(R.string.privacy_policy), "privacyPolicy.md") },
                )
            }
            item {
                AppListItem(
                    headline = stringResource(R.string.license),
                    onClick = { showMdFile(context.getString(R.string.license), "LICENSE.md") },
                )
            }
            item {
                AppListItem(
                    headline = stringResource(R.string.disclaimer),
                    onClick = { showMdFile(context.getString(R.string.disclaimer), "disclaimer.md") },
                )
            }
        }

        if (betaDialogOpen) {
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
                            onClick = { onBetaConfirm() },
                        )
                    }
                }
            }
        }
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
            ?.use { input.copyTo(it) }
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
            ?.use { input.copyTo(it) }
    }
    return true
}

private fun dumpLogcat(file: File) {
    try {
        val process = Runtime.getRuntime().exec("logcat -d")
        file.outputStream().use { process.inputStream.copyTo(it) }
    } catch (e: Exception) {
        AppLog.put("保存Logcat失败\n$e", e)
    }
}
