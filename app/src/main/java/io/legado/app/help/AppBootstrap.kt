package io.legado.app.help

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.update.AppUpdate
import io.legado.app.help.AppCloudStorage
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.ui.about.CrashLogsDialog
import io.legado.app.ui.about.UpdateDialog
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.ImportDictRuleDialog
import io.legado.app.ui.association.ImportHttpTtsDialog
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.ShibbolethCodec
import io.legado.app.utils.clearClip
import io.legado.app.utils.getClipText
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.applyTint
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 启动职责搬运（P4-5a 转正切换）：原 [MainActivity.onPostCreate]/onResume 的
 * 应用级启动职责，逐行搬运并参数化到任意 [AppCompatActivity] —— 新壳
 * [io.legado.app.ui.host.AppHostActivity] 转正后由它承担：
 * 隐私协议 / 版本更新日志 / 本地密码 / 崩溃报告 / 备份同步 / 剪贴板口令导入 /
 * 书源自动更新 / 自动刷新书架 / 延迟加载。
 *
 * 与旧实现仅有的差异：viewModel 经 ViewModelProvider 获取；binding.root.postDelayed
 * 改为 Handler；showDialogFragment 直接走 supportFragmentManager。
 */
class AppBootstrap(private val activity: AppCompatActivity) {

    companion object {
        /** singleTask 重建时避免重复跑首启职责（与旧壳每次 onCreate 都跑等价：实例级状态即可） */
        private const val KEY_AUTO_REFRESHED = "isAutoRefreshedBook"
    }

    private val viewModel: MainViewModel =
        ViewModelProvider(activity)[MainViewModel::class.java]
    private val handler = Handler(Looper.getMainLooper())
    private var clipboardImportEnabled = false
    private var rejectedShibbolethHash: Int? = null
    private var shibbolethRunnable: Runnable? = null

    private fun show(dialog: androidx.fragment.app.DialogFragment) {
        if (!activity.isFinishing && !activity.isDestroyed) {
            dialog.show(activity.supportFragmentManager, dialog::class.simpleName)
        }
    }

    /** = MainActivity.onPostCreate 协程体。 */
    fun onCreated(savedInstanceState: Bundle?) {
        activity.lifecycleScope.launch {
            // 隐私协议
            if (!privacyPolicy()) return@launch
            clipboardImportEnabled = true
            scheduleShibbolethImport(500L)
            // 版本更新
            upVersion()
            // 设置本地密码
            setLocalPassword()
            notifyAppCrash()
            // 备份同步
            backupSync()
            // 设置回调
            viewModel.setActivityCallback(object : MainViewModel.CallBack {
                override fun openImportUi(type: Int, source: String) {
                    when (type) {
                        0 -> show(ImportBookSourceDialog(source))
                        1 -> show(ImportRssSourceDialog(source))
                        2 -> show(ImportReplaceRuleDialog(source))
                    }
                }
            })
            // 自动更新书源
            handler.postDelayed({ viewModel.ruleSubsUp() }, 1000)
            // 自动更新书籍
            val isAutoRefreshedBook =
                savedInstanceState?.getBoolean(KEY_AUTO_REFRESHED) ?: false
            if (AppConfig.autoRefreshBook && !isAutoRefreshedBook) {
                handler.postDelayed({ viewModel.upAllBookToc() }, 2000)
            }
            handler.postDelayed({ viewModel.postLoad() }, 3000)
        }
    }

    /** = MainActivity.onResume 的剪贴板部分。 */
    fun onResumed() {
        if (clipboardImportEnabled) {
            scheduleShibbolethImport(500L)
        }
    }

    /** = MainActivity.onPause 的剪贴板部分。 */
    fun onPaused() {
        shibbolethRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun scheduleShibbolethImport(delayMillis: Long) {
        shibbolethRunnable?.let { handler.removeCallbacks(it) }
        shibbolethRunnable = Runnable { readShibbolethFromClipboard() }
        handler.postDelayed(requireNotNull(shibbolethRunnable), delayMillis)
    }

    private fun readShibbolethFromClipboard() {
        if (!clipboardImportEnabled ||
            !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ||
            activity.supportFragmentManager.isStateSaved
        ) {
            return
        }
        val text = activity.getClipText()
        if (!ShibbolethCodec.looksLikeCode(text)) {
            rejectedShibbolethHash = null
            return
        }
        val codeText = requireNotNull(text)
        val payload = ShibbolethCodec.decode(codeText).getOrElse {
            notifyRejectedShibboleth(codeText)
            return
        }
        if (payload.isExpired()) {
            rejectedShibbolethHash = null
            activity.clearClip()
            activity.toastOnUi(R.string.shibboleth_expired)
            return
        }
        val dialog = when (payload.type) {
            ShibbolethCodec.BOOK_SOURCE -> ImportBookSourceDialog(payload.url)
            ShibbolethCodec.RSS_SOURCE -> ImportRssSourceDialog(payload.url)
            ShibbolethCodec.DICT_RULE -> ImportDictRuleDialog(payload.url)
            ShibbolethCodec.REPLACE_RULE -> ImportReplaceRuleDialog(payload.url)
            ShibbolethCodec.TOC_RULE -> ImportTxtTocRuleDialog(payload.url)
            ShibbolethCodec.TTS_RULE -> ImportHttpTtsDialog(payload.url)
            else -> {
                notifyRejectedShibboleth(codeText)
                return
            }
        }
        rejectedShibbolethHash = null
        activity.clearClip()
        activity.alert(R.string.shibboleth, R.string.shibboleth_import_confirm) {
            positiveButton(R.string.sure) { show(dialog) }
            cancelButton()
        }
    }

    private fun notifyRejectedShibboleth(text: String) {
        val hash = text.hashCode()
        if (rejectedShibbolethHash == hash) return
        rejectedShibbolethHash = hash
        activity.toastOnUi(R.string.shibboleth_invalid)
    }

    private suspend fun privacyPolicy(): Boolean = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.privacyPolicyOk) {
            block.resume(true)
            return@sc
        }
        val privacyPolicy = String(activity.assets.open("privacyPolicy.md").readBytes())
        activity.alert(activity.getString(R.string.privacy_policy), privacyPolicy) {
            positiveButton(R.string.agree) {
                LocalConfig.privacyPolicyOk = true
                block.resume(true)
            }
            negativeButton(R.string.refuse) {
                activity.finish()
                block.resume(false)
            }
        }
    }

    private suspend fun upVersion() = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.versionCode == appInfo.versionCode) {
            if (AppConfig.autoUpdateVariant) {
                if (LocalConfig.lastCheckUpdate + TimeUnit.HOURS.toMillis(24) <
                    System.currentTimeMillis()
                ) {
                    AppUpdate.preferredUpdate.check(activity.lifecycleScope)
                        .onSuccess {
                            show(UpdateDialog(it))
                        }.onError {
                            if (!AppUpdate.isLatestVersionError(it)) {
                                show(
                                    TextDialog(
                                        activity.getString(R.string.check_update),
                                        it.localizedMessage ?: activity.getString(R.string.check_update),
                                        TextDialog.Mode.TEXT
                                    )
                                )
                            }
                        }
                    LocalConfig.lastCheckUpdate = System.currentTimeMillis()
                }
            }
            block.resume(null)
            return@sc
        }
        LocalConfig.versionCode = appInfo.versionCode
        if (LocalConfig.isFirstOpenApp) {
            val help = String(activity.assets.open("web/help/md/appHelp.md").readBytes())
            val dialog = TextDialog(activity.getString(R.string.help), help, TextDialog.Mode.MD)
            dialog.setOnDismissListener {
                block.resume(null)
            }
            show(dialog)
        } else if (!io.legado.app.BuildConfig.DEBUG) {
            val log = String(activity.assets.open("updateLog.md").readBytes())
            val dialog = TextDialog(activity.getString(R.string.update_log), log, TextDialog.Mode.MD)
            dialog.setOnDismissListener {
                block.resume(null)
            }
            show(dialog)
        } else {
            block.resume(null)
        }
    }

    private suspend fun setLocalPassword() = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.password != null) {
            block.resume(null)
            return@sc
        }
        activity.alert(R.string.set_local_password, R.string.set_local_password_summary) {
            val editTextBinding = DialogEditTextBinding.inflate(activity.layoutInflater).apply {
                editView.hint = "password"
            }
            customView {
                editTextBinding.root
            }
            onDismiss {
                block.resume(null)
            }
            okButton {
                LocalConfig.password = editTextBinding.editView.text.toString()
            }
            cancelButton {
                LocalConfig.password = ""
            }
        }
    }

    private fun notifyAppCrash() {
        if (!LocalConfig.appCrash || io.legado.app.BuildConfig.DEBUG) {
            return
        }
        LocalConfig.appCrash = false
        activity.alert(activity.getString(R.string.draw), "检测到阅读发生了崩溃，是否打开崩溃日志以便报告问题？") {
            yesButton {
                show(io.legado.app.ui.about.CrashLogsDialog())
            }
            noButton()
        }
    }

    private fun backupSync() {
        if (!AppConfig.autoCheckNewBackup) {
            return
        }
        activity.lifecycleScope.launch {
            val lastBackupFile =
                withContext(Dispatchers.IO) { AppCloudStorage.lastBackup().getOrNull() }
                    ?: return@launch
            if (lastBackupFile.lastModify - LocalConfig.lastBackup >
                android.text.format.DateUtils.MINUTE_IN_MILLIS
            ) {
                LocalConfig.lastBackup = lastBackupFile.lastModify
                activity.alert(R.string.restore, R.string.webdav_after_local_restore_confirm) {
                    cancelButton()
                    okButton {
                        activity.lifecycleScope.launch {
                            val assetsNames = withContext(Dispatchers.IO) {
                                runCatching { AppCloudStorage.listAssetsBackupNames() }
                                    .getOrDefault(emptyList())
                            }
                            if (assetsNames.isEmpty()) {
                                viewModel.restoreWebDav(lastBackupFile.displayName)
                                return@launch
                            }
                            activity.alert(
                                activity.getString(R.string.restore),
                                "检测到资源文件包，是否一并恢复字体、背景图等资源？"
                            ) {
                                positiveButton("全部恢复") {
                                    viewModel.restoreWebDav(lastBackupFile.displayName, assetsNames)
                                }
                                negativeButton("仅主包") {
                                    viewModel.restoreWebDav(lastBackupFile.displayName)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
