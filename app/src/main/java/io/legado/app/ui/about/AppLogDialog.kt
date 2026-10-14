package io.legado.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.LogUtils
import io.legado.app.utils.showDialogFragment
import java.util.Date

/**
 * 运行日志弹窗（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Wide` 映射为 widthFraction 0.98f / maxWidthDp 760；
 * 内容由旧 AppDialogFrame + LegadoMiuixCard 换为 V5 AppDialog + AppCard（日志明细可选中复制），
 * 堆栈仍走 TextDialog；清空后关闭的行为不变。
 */
internal class AppLogDialog : V5DialogFragment() {

    /** 旧 AppDialogSize.Wide(widthFraction = 0.98f, maxWidthDp = 760) */
    override val widthFraction: Float = 0.98f
    override val maxWidthDp: Int = 760

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val logs = AppLog.logs

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(visual.density.blockGap),
            ) {
                Text(
                    text = stringResource(R.string.log),
                    style = visual.type.title,
                    color = colors.onSurface,
                )
                if (logs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.empty),
                        style = visual.type.body,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(
                            items = logs,
                            key = { index, item -> "${item.first}_$index" },
                        ) { _, item ->
                            LogItem(
                                time = LogUtils.logTimeFormat.format(Date(item.first)),
                                message = item.second,
                                onClick = {
                                    item.third?.let { throwable ->
                                        showDialogFragment(
                                            TextDialog("Log", throwable.stackTraceToString())
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(visual.density.blockGap / 2)) {
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { dismissAllowingStateLoss() },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.clear),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            AppLog.clear()
                            dismissAllowingStateLoss()
                        },
                    )
                }
            }
        }
    }

}

@Composable
private fun LogItem(
    time: String,
    message: String,
    onClick: () -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Text(
            text = time,
            style = visual.type.caption,
            color = colors.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = message,
                style = visual.type.body,
                color = colors.onSurface,
            )
        }
    }
}
