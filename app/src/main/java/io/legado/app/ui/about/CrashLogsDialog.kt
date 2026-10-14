package io.legado.app.ui.about

import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.delete
import io.legado.app.utils.find
import io.legado.app.utils.getFile
import io.legado.app.utils.list
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileFilter

/**
 * 崩溃日志列表弹窗（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Management` 映射为 widthFraction 0.96f / maxWidthDp 700；
 * 内容由旧 AppDialogFrame + LegadoMiuixCard + LegadoMiuixActionButton 换为 V5 AppDialog + AppCard + 按钮行。
 * **行为新增**：「清空」按 V5 危险操作红线补 `AppConfirmDialog(danger = true)`（旧版点击即清，无确认，
 * 与 CoverCollectionManageActivity 删除 tgt时的处理保持一致）；ViewModel 的全部异步逻辑逐行保留。
 */
internal class CrashLogsDialog : V5DialogFragment() {

    /** 旧 AppDialogSize.Management(widthFraction = 0.96f, maxWidthDp = 700) */
    override val widthFraction: Float = 0.96f
    override val maxWidthDp: Int = 700

    private val viewModel by viewModels<CrashViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initData()
    }

    private fun showLogFile(fileDoc: FileDoc) {
        viewModel.readFile(fileDoc) {
            if (lifecycleScope.isActive) {
                showDialogFragment(TextDialog(fileDoc.name, it))
            }
        }
    }

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density
        val logFiles by viewModel.logFlow.collectAsState()
        var clearConfirmOpen by remember { mutableStateOf(false) }

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                Text(
                    text = stringResource(R.string.crash_log),
                    style = visual.type.title,
                    color = colors.onSurface
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logFiles, key = { it.name }) { fileDoc ->
                        AppCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showLogFile(fileDoc) },
                        ) {
                            Text(
                                text = fileDoc.name,
                                style = visual.type.body,
                                color = colors.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    AppFilledButton(
                        text = stringResource(R.string.clear),
                        modifier = Modifier.weight(1f),
                        onClick = { clearConfirmOpen = true },
                    )
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { dismissAllowingStateLoss() },
                    )
                }
            }
        }

        if (clearConfirmOpen) {
            AppConfirmDialog(
                title = stringResource(R.string.crash_log),
                message = "确定清空全部崩溃日志？",
                confirmLabel = stringResource(R.string.clear),
                cancelLabel = stringResource(R.string.cancel),
                danger = true,
                onConfirm = {
                    clearConfirmOpen = false
                    viewModel.clearCrashLog()
                },
                onDismiss = { clearConfirmOpen = false },
            )
        }
    }

    class CrashViewModel(application: android.app.Application) : AndroidViewModel(application) {

        private val appCtx: android.content.Context = application

        private val _logFlow = MutableStateFlow<List<FileDoc>>(emptyList())
        val logFlow = _logFlow.asStateFlow()

        fun initData() {
            viewModelScope.launch {
                try {
                    val list = withContext(Dispatchers.IO) {
                        val result = arrayListOf<FileDoc>()
                        appCtx.externalCacheDir
                            ?.getFile("crash")
                            ?.listFiles(FileFilter { it.isFile })
                            ?.forEach {
                                result.add(FileDoc.fromFile(it))
                            }
                        val backupPath = AppConfig.backupPath
                        if (!backupPath.isNullOrEmpty()) {
                            val uri = Uri.parse(backupPath)
                            FileDoc.fromUri(uri, true)
                                .find("crash")
                                ?.list {
                                    !it.isDir
                                }?.let {
                                    result.addAll(it)
                                }
                        }
                        result.sortedByDescending { it.name }.distinctBy { it.name }
                    }
                    _logFlow.value = list
                } catch (e: Exception) {
                    appCtx.toastOnUi(e.localizedMessage)
                }
            }
        }

        fun readFile(fileDoc: FileDoc, success: (String) -> Unit) {
            viewModelScope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        String(fileDoc.readBytes())
                    }
                    success.invoke(text)
                } catch (e: Exception) {
                    appCtx.toastOnUi(e.localizedMessage)
                }
            }
        }

        fun clearCrashLog() {
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        appCtx.externalCacheDir
                            ?.getFile("crash")
                            ?.let {
                                FileUtils.delete(it, false)
                            }
                        val backupPath = AppConfig.backupPath
                        if (!backupPath.isNullOrEmpty()) {
                            val uri = Uri.parse(backupPath)
                            FileDoc.fromUri(uri, true)
                                .find("crash")
                                ?.delete()
                        }
                    }
                } catch (e: Exception) {
                    appCtx.toastOnUi(e.localizedMessage)
                } finally {
                    initData()
                }
            }
        }

    }

}
