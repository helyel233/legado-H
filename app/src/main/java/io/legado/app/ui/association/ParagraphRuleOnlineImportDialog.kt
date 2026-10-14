package io.legado.app.ui.association

import android.content.DialogInterface
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppSelectionRow
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec

/**
 * 段落规则在线导入确认（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Management` 映射为 widthFraction 0.96f / maxWidthDp 700；
 * 内容由旧 AppDialogFrame + LegadoMiuixChoiceRow + LegadoMiuixActionButton 换为
 * V5 AppDialog + AppSelectionRow（selectionMode=true 单选高亮）+ AppGhostButton/AppFilledButton。
 * 冲突策略选择与「未确认即取消」的 onDismiss 回调语义不变。
 */
internal class ParagraphRuleOnlineImportDialog : V5DialogFragment() {

    interface Callback {
        fun onParagraphRuleImportConfirmed(strategy: ParagraphRuleConflictStrategy)
        fun onParagraphRuleImportCancelled()
    }

    /** 旧 AppDialogSize.Management(widthFraction = 0.96f, maxWidthDp = 700) */
    override val widthFraction: Float = 0.96f
    override val maxWidthDp: Int = 700

    private var confirmed = false

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!confirmed) (activity as? Callback)?.onParagraphRuleImportCancelled()
    }

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val args = arguments ?: Bundle()
        val conflictCount = args.getInt(ARG_CONFLICT_COUNT)
        val message = args.getString(ARG_MESSAGE)
        var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
        val strategies = listOf(
            ParagraphRuleConflictStrategy.RENAME,
            ParagraphRuleConflictStrategy.OVERWRITE,
            ParagraphRuleConflictStrategy.SKIP
        )
        val labels = listOf(
            stringResource(R.string.paragraph_import_conflict_rename),
            stringResource(R.string.paragraph_import_conflict_overwrite),
            stringResource(R.string.paragraph_import_conflict_skip)
        )
        val descriptions = listOf(
            stringResource(R.string.paragraph_import_conflict_rename_description),
            stringResource(R.string.paragraph_import_conflict_overwrite_description),
            stringResource(R.string.paragraph_import_conflict_skip_description)
        )

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(visual.density.blockGap),
            ) {
                Text(
                    text = stringResource(R.string.paragraph_import_title),
                    style = visual.type.title,
                    color = colors.onSurface,
                )
                if (!message.isNullOrBlank()) {
                    Text(
                        text = message,
                        style = visual.type.body,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (conflictCount > 0) {
                    Text(
                        text = stringResource(
                            R.string.paragraph_import_conflict_message,
                            conflictCount
                        ),
                        style = visual.type.body,
                        color = colors.onSurface,
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(visual.density.blockGap / 3),
                    ) {
                        labels.forEachIndexed { index, label ->
                            AppSelectionRow(
                                title = label,
                                supporting = descriptions[index],
                                selected = selectedIndex == index,
                                selectionMode = true,
                                onClick = { selectedIndex = index },
                                onLongClick = {},
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
                    if (activity is Callback) {
                        AppFilledButton(
                            text = stringResource(R.string.import_),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val strategy = if (conflictCount > 0) {
                                    strategies[selectedIndex]
                                } else {
                                    ParagraphRuleConflictStrategy.RENAME
                                }
                                confirmed = true
                                (activity as? Callback)
                                    ?.onParagraphRuleImportConfirmed(strategy)
                                dismissAllowingStateLoss()
                            },
                        )
                    }
                }
            }
        }
    }

    companion object {
        fun create(message: String, conflictCount: Int) =
            ParagraphRuleOnlineImportDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_MESSAGE, message)
                    putInt(ARG_CONFLICT_COUNT, conflictCount.coerceAtLeast(0))
                }
            }

        private const val ARG_MESSAGE = "message"
        private const val ARG_CONFLICT_COUNT = "conflictCount"
    }
}
