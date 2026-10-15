package io.legado.app.ui.book.toc.rule

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.GSON
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * TXT 目录规则编辑（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Management` 映射为 widthFraction 0.96f / maxWidthDp 700；
 * 内容由旧 AppDialogFrame + 自定义 OutlinedTextField(style 配色) + LegadoMiuixActionButton
 * 换为 V5 AppDialog + `AppTextField`（label 降级为 placeholder）+ AppGhostButton/AppFilledButton。
 * **按钮排布调整**：旧的 4 个动作按钮（粘贴/复制/取消/保存）在手机上一行放不下，
 * 改为「粘贴/复制/取消」一行 + 「保存」整行（与 DictRuleEditDialog 的处理一致）。
 * 业务保留：保存前名称非空 + `Pattern.compile(…, MULTILINE)` 正则语法校验（失败记 AppLog 且不关闭）、
 * 复制/粘贴走剪贴板 JSON、ViewModel 复用 `TxtTocRuleEditDialog.ViewModel`。
 */
internal class TxtTocRuleEditComposeDialog : V5DialogFragment() {

    /** 旧 AppDialogSize.Management(widthFraction = 0.96f, maxWidthDp = 700) */
    override val widthFraction: Float = 0.96f
    override val maxWidthDp: Int = 700

    private val viewModel by viewModels<TxtTocRuleEditDialog.ViewModel>()

    private val callback: Callback?
        get() = (parentFragment as? Callback) ?: activity as? Callback

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val ruleId = arguments?.getLong(ARG_ID, 0L)?.takeIf { it != 0L }
        var loaded by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        var rule by remember { mutableStateOf("") }
        var replacement by remember { mutableStateOf("") }
        var example by remember { mutableStateOf("") }

        LaunchedEffect(ruleId) {
            viewModel.initData(ruleId) { tocRule ->
                if (tocRule != null) {
                    name = tocRule.name
                    rule = tocRule.rule
                    replacement = tocRule.replacement
                    example = tocRule.example.orEmpty()
                }
                loaded = true
            }
        }

        if (!loaded) return

        val fillRule: (TxtTocRule) -> TxtTocRule = { tocRule ->
            tocRule.apply {
                this.name = name
                this.rule = rule
                this.replacement = replacement
                this.example = example
            }
        }

        TxtTocRuleEditContent(
            name = name,
            onNameChange = { name = it },
            rule = rule,
            onRuleChange = { rule = it },
            replacement = replacement,
            onReplacementChange = { replacement = it },
            example = example,
            onExampleChange = { example = it },
            onSave = {
                val tocRule = viewModel.tocRule ?: TxtTocRule().also {
                    viewModel.tocRule = it
                }
                fillRule(tocRule)
                if (checkValid(tocRule)) {
                    callback?.saveTxtTocRule(tocRule)
                    dismissAllowingStateLoss()
                }
            },
            onCopyRule = {
                val tocRule = viewModel.tocRule ?: TxtTocRule().also {
                    viewModel.tocRule = it
                }
                context.sendToClip(GSON.toJson(fillRule(tocRule)))
            },
            onPasteRule = {
                viewModel.pasteRule { pasted ->
                    name = pasted.name
                    rule = pasted.rule
                    replacement = pasted.replacement
                    example = pasted.example.orEmpty()
                }
            },
            onDismiss = { dismissAllowingStateLoss() }
        )
    }

    private fun checkValid(tocRule: TxtTocRule): Boolean {
        if (tocRule.name.isEmpty()) {
            context?.toastOnUi("名称不能为空")
            return false
        }
        try {
            Pattern.compile(tocRule.rule, Pattern.MULTILINE)
        } catch (ex: PatternSyntaxException) {
            AppLog.put("正则语法错误或不支持(txt)：${ex.localizedMessage}", ex, true)
            return false
        }
        return true
    }

    companion object {
        private const val ARG_ID = "id"

        internal fun create(id: Long? = null): TxtTocRuleEditComposeDialog {
            return TxtTocRuleEditComposeDialog().apply {
                if (id != null) {
                    arguments = Bundle().apply {
                        putLong(ARG_ID, id)
                    }
                }
            }
        }
    }

    interface Callback {
        fun saveTxtTocRule(txtTocRule: TxtTocRule)
    }
}

@Composable
private fun TxtTocRuleEditContent(
    name: String,
    onNameChange: (String) -> Unit,
    rule: String,
    onRuleChange: (String) -> Unit,
    replacement: String,
    onReplacementChange: (String) -> Unit,
    example: String,
    onExampleChange: (String) -> Unit,
    onSave: () -> Unit,
    onCopyRule: () -> Unit,
    onPasteRule: () -> Unit,
    onDismiss: () -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density

    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
        ) {
            Text(
                text = stringResource(R.string.txt_toc_rule),
                style = visual.type.title,
                color = colors.onSurface
            )
            AppTextField(
                value = name,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.name),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = rule,
                onValueChange = onRuleChange,
                placeholder = stringResource(R.string.regex),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = replacement,
                onValueChange = onReplacementChange,
                placeholder = stringResource(R.string.replace_to_js),
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = example,
                onValueChange = onExampleChange,
                placeholder = stringResource(R.string.example),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 3)) {
                AppGhostButton(
                    text = stringResource(R.string.paste_rule),
                    modifier = Modifier.weight(1f),
                    onClick = onPasteRule,
                )
                AppGhostButton(
                    text = stringResource(R.string.copy_rule),
                    modifier = Modifier.weight(1f),
                    onClick = onCopyRule,
                )
                AppGhostButton(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
            }
            AppFilledButton(
                text = stringResource(R.string.action_save),
                modifier = Modifier.fillMaxWidth(),
                onClick = onSave,
            )
        }
    }
}
