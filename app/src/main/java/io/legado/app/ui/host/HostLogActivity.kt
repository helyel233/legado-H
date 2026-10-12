package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.ui.about.CrashLogsDialog
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.uikit.v5.components.AppBottomSheet
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFilterChip
import io.legado.app.uikit.v5.components.AppFilterChipRow
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppListItem
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppSelectionRow
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.LogUtils
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P4-3：统一日志页 V5 化（2026-10-12，优先迁移——迁移期核查依赖）。
 *
 * 两个 Tab：运行日志（[AppLog] 内存日志，实时刷新）+ 崩溃日志
 * （[CrashLogsDialog.CrashViewModel]，本地 crash 目录 + 备份路径）。
 * 点条目弹详情（SelectionContainer 可选中复制）。
 */
class HostLogActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, HostLogActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostLogScreen() }
    }
}

private enum class LogTab { RUN, CRASH }

@Composable
private fun HostLogScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    val density = visual.density

    var tab by remember { mutableStateOf(LogTab.RUN) }
    var menuOpen by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Pair<String, String>?>(null) }

    // 运行日志（revision 驱动实时刷新）
    val appLogRevision by AppLog.revision
    val logs = remember(appLogRevision) { AppLog.logs }

    // 崩溃日志
    val crashVm = remember {
        ViewModelProvider(activity)[CrashLogsDialog.CrashViewModel::class.java]
    }
    val crashLogs by crashVm.logFlow.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { crashVm.initData() }

    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    HostV5Page(
        title = stringResource(R.string.log),
        subtitle = null,
        onBack = { activity.finish() },
        trailing = {
            AppOverflowMenuButton(contentDescription = stringResource(R.string.v5_more)) { menuOpen = true }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            AppFilterChipRow(
                chips = listOf(
                    AppFilterChip(stringResource(R.string.log), tab == LogTab.RUN),
                    AppFilterChip(stringResource(R.string.crash_log), tab == LogTab.CRASH),
                ),
                onChipClick = { index -> tab = LogTab.entries[index] },
            )
            Spacer(Modifier.height(density.blockGap / 2))

            when (tab) {
                LogTab.RUN -> {
                    if (logs.isEmpty()) {
                        AppEmpty(title = stringResource(R.string.empty))
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = density.screenPadding,
                                end = density.screenPadding,
                                bottom = density.screenPadding,
                            ),
                            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                        ) {
                            items(logs) { (time, msg, throwable) ->
                                LogEntryRow(
                                    headline = timeFormat.format(Date(time)),
                                    supporting = msg,
                                    onClick = {
                                        detail = timeFormat.format(Date(time)) to
                                            buildString {
                                                append(msg)
                                                throwable?.let { append("\n\n").append(it.stackTraceToString()) }
                                            }
                                    },
                                )
                            }
                        }
                    }
                }

                LogTab.CRASH -> {
                    if (crashLogs.isEmpty()) {
                        AppEmpty(title = stringResource(R.string.empty))
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = density.screenPadding,
                                end = density.screenPadding,
                                bottom = density.screenPadding,
                            ),
                            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                        ) {
                            items(crashLogs) { fileDoc ->
                                AppSelectionRow(
                                    title = fileDoc.name,
                                    selected = false,
                                    selectionMode = false,
                                    onClick = {
                                        crashVm.readFile(fileDoc) { text ->
                                            detail = fileDoc.name to text
                                        }
                                    },
                                    onLongClick = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ⋮ 菜单：清空当前 Tab 日志
    if (menuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.log),
            actions = listOf(
                stringResource(R.string.v5_search_clear) to {
                    if (tab == LogTab.RUN) {
                        AppLog.clear()
                    } else {
                        crashVm.clearCrashLog()
                    }
                    menuOpen = false
                },
            ),
            onDismiss = { menuOpen = false },
        )
    }

    // 日志详情（底部抽屉 + 可选中复制）
    detail?.let { (titleText, body) ->
        AppBottomSheet(onDismiss = { detail = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(density.screenPadding),
            ) {
                Text(
                    text = titleText,
                    style = visual.type.title,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(density.blockGap / 2))
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text = body,
                        style = visual.type.caption,
                        color = colors.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(density.blockGap))
                AppGhostButton(
                    text = stringResource(R.string.copy_text),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        context.sendToClip(body)
                        detail = null
                    },
                )
            }
        }
    }
}

@Composable
private fun LogEntryRow(
    headline: String,
    supporting: String,
    onClick: () -> Unit,
) {
    AppListItem(
        headline = headline,
        supporting = supporting.take(120),
        dense = false,
        onClick = onClick,
    )
}
