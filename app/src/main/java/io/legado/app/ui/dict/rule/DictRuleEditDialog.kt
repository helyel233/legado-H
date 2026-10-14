package io.legado.app.ui.dict.rule

import android.app.Activity.RESULT_OK
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.DictRule
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.*

/**
 * 词典规则编辑弹窗（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Management` 映射为 widthFraction 0.96f / maxWidthDp 700；
 * 内容由旧 AppDialogFrame + AppRuleTextField + LegadoMiuixActionButton 换为
 * V5 AppDialog + AppTextField + AppGhostButton/AppFilledButton。
 * 已知降级：AppTextField 以 String 为值，全屏编辑（CodeEditActivity）回填后光标落于文末
 * （旧版可精确回填 cursorPosition）；粘贴/复制/保存/退出确认语义不变。
 */
internal class DictRuleEditDialog() : V5DialogFragment() {

    val viewModel by viewModels<DictRuleEditViewModel>()

    /** 旧 AppDialogSize.Management(widthFraction = 0.96f, maxWidthDp = 700) */
    override val widthFraction: Float = 0.96f
    override val maxWidthDp: Int = 700

    enum class EditField {
        Name, UrlRule, ShowRule
    }

    private var nameValue by mutableStateOf(TextFieldValue(""))
    private var urlRuleValue by mutableStateOf(TextFieldValue(""))
    private var showRuleValue by mutableStateOf(TextFieldValue(""))
    private var focusedField: EditField? = null
    private var loaded by mutableStateOf(false)
    private var forceDismiss = false

    // ── V5 弹窗状态 ─────────────────────────────────────────────
    private var exitConfirmOpen by mutableStateOf(false)

    constructor(name: String) : this() {
        arguments = Bundle().apply {
            putString("name", name)
        }
    }

    @Composable
    override fun Content() {
        LaunchedEffect(Unit) {
            viewModel.initData(arguments?.getString("name")) {
                upRuleView(viewModel.dictRule)
                loaded = true
            }
        }
        if (loaded) {
            DictRuleEditContent(
                name = nameValue.text,
                onNameChange = { nameValue = TextFieldValue(it) },
                urlRule = urlRuleValue.text,
                onUrlRuleChange = { urlRuleValue = TextFieldValue(it) },
                showRule = showRuleValue.text,
                onShowRuleChange = { showRuleValue = TextFieldValue(it) },
                onFieldFocused = { focusedField = it },
                onFullEdit = ::onFullEditClicked,
                onSave = {
                    viewModel.save(getDictRule()) {
                        forceDismiss = true
                        dismissAllowingStateLoss()
                    }
                },
                onCopyRule = { viewModel.copyRule(getDictRule()) },
                onPasteRule = {
                    viewModel.pasteRule {
                        upRuleView(it)
                    }
                },
                onDismiss = { dismiss() }
            )
        }
        DictRuleExitDialogs()
    }

    private val textEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val field = focusedField
            if (field == null) {
                toastOnUi(R.string.focus_lost_on_textbox)
                return@registerForActivityResult
            }
            result.data?.getStringExtra("text")?.let {
                val cursor = result.data?.getIntExtra("cursorPosition", -1)
                    ?.takeIf { position -> position in 0..it.length }
                    ?: it.length
                setFieldValue(field, TextFieldValue(it, TextRange(cursor)))
            }
        }
    }

    private fun onFullEditClicked() {
        val field = focusedField
        if (field != null) {
            val value = getFieldValue(field)
            val intent = Intent(requireActivity(), CodeEditActivity::class.java).apply {
                putExtra("text", value.text)
                putExtra("title", fieldTitle(field))
                putExtra("cursorPosition", value.selection.start)
            }
            textEditLauncher.launch(intent)
        } else {
            toastOnUi(R.string.please_focus_cursor_on_textbox)
        }
    }

    private fun getFieldValue(field: EditField): TextFieldValue {
        return when (field) {
            EditField.Name -> nameValue
            EditField.UrlRule -> urlRuleValue
            EditField.ShowRule -> showRuleValue
        }
    }

    private fun setFieldValue(field: EditField, value: TextFieldValue) {
        when (field) {
            EditField.Name -> nameValue = value
            EditField.UrlRule -> urlRuleValue = value
            EditField.ShowRule -> showRuleValue = value
        }
    }

    private fun fieldTitle(field: EditField): String {
        return when (field) {
            EditField.Name -> getString(R.string.name)
            EditField.UrlRule -> getString(R.string.url_rule)
            EditField.ShowRule -> getString(R.string.show_rule)
        }
    }

    private fun upRuleView(dictRule: DictRule?) {
        nameValue = TextFieldValue(dictRule?.name.orEmpty())
        urlRuleValue = TextFieldValue(dictRule?.urlRule.orEmpty())
        showRuleValue = TextFieldValue(dictRule?.showRule.orEmpty())
    }

    private fun getDictRule(): DictRule {
        val dictRule = viewModel.dictRule?.copy() ?: DictRule()
        dictRule.name = nameValue.text
        dictRule.urlRule = urlRuleValue.text
        dictRule.showRule = showRuleValue.text
        return dictRule
    }

    private fun isSame(): Boolean{
        val dictRule = viewModel.dictRule ?: return nameValue.text.isEmpty()
        return dictRule.name == nameValue.text &&
            dictRule.urlRule == urlRuleValue.text &&
            dictRule.showRule == showRuleValue.text
    }

    override fun dismiss() {
        if (forceDismiss || isSame()) {
            super.dismiss()
        } else {
            exitConfirmOpen = true
        }
    }

    @Composable
    private fun DictRuleExitDialogs() {
        val density = LocalVisualSpec.current.density
        val colors = LocalSemanticColors.current
        if (exitConfirmOpen) {
            AppDialog(onDismiss = { exitConfirmOpen = false }) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    Text(
                        text = stringResource(R.string.exit),
                        style = LocalVisualSpec.current.type.title,
                        color = colors.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.exit_no_save),
                        style = LocalVisualSpec.current.type.caption,
                        color = colors.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                        AppFilledButton(
                            text = stringResource(R.string.yes),
                            modifier = Modifier.weight(1f),
                            onClick = { exitConfirmOpen = false },
                        )
                        AppGhostButton(
                            text = stringResource(R.string.no),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                exitConfirmOpen = false
                                dismissWithoutConfirm()
                            },
                        )
                    }
                }
            }
        }
    }

    private fun dismissWithoutConfirm() {
        forceDismiss = true
        super.dismiss()
    }

    class DictRuleEditViewModel(application: Application) : BaseViewModel(application) {

        var dictRule: DictRule? = null

        fun initData(name: String?, onFinally: () -> Unit) {
            execute {
                if (dictRule == null && name != null) {
                    dictRule = appDb.dictRuleDao.getByName(name)
                }
            }.onFinally {
                onFinally.invoke()
            }
        }

        fun save(newDictRule: DictRule, onFinally: () -> Unit) {
            execute {
                dictRule?.let {
                    appDb.dictRuleDao.delete(it)
                }
                appDb.dictRuleDao.insert(newDictRule)
                dictRule = newDictRule
            }.onFinally {
                onFinally.invoke()
            }
        }

        fun copyRule(dictRule: DictRule) {
            context.sendToClip(GSON.toJson(dictRule))
        }

        fun pasteRule(success: (DictRule) -> Unit) {
            val text = context.getClipText()
            if (text.isNullOrBlank()) {
                context.toastOnUi("剪贴板没有内容")
                return
            }
            execute {
                GSON.fromJsonObject<DictRule>(text).getOrThrow()
            }.onSuccess {
                success.invoke(it)
            }.onError {
                context.toastOnUi("格式不对")
            }
        }

    }

}

@Composable
private fun DictRuleEditContent(
    name: String,
    onNameChange: (String) -> Unit,
    urlRule: String,
    onUrlRuleChange: (String) -> Unit,
    showRule: String,
    onShowRuleChange: (String) -> Unit,
    onFieldFocused: (DictRuleEditDialog.EditField) -> Unit,
    onFullEdit: () -> Unit,
    onSave: () -> Unit,
    onCopyRule: () -> Unit,
    onPasteRule: () -> Unit,
    onDismiss: () -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(visual.density.blockGap),
        ) {
            Text(
                text = stringResource(R.string.dict_rule),
                style = visual.type.title,
                color = colors.onSurface,
            )
            AppTextField(
                value = name,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.name),
                singleLine = true,
                modifier = Modifier.onFocusChanged {
                    if (it.isFocused) onFieldFocused(DictRuleEditDialog.EditField.Name)
                },
            )
            AppTextField(
                value = urlRule,
                onValueChange = onUrlRuleChange,
                placeholder = stringResource(R.string.url_rule),
                singleLine = true,
                modifier = Modifier.onFocusChanged {
                    if (it.isFocused) onFieldFocused(DictRuleEditDialog.EditField.UrlRule)
                },
            )
            AppTextField(
                value = showRule,
                onValueChange = onShowRuleChange,
                placeholder = stringResource(R.string.show_rule),
                singleLine = false,
                minLines = 5,
                maxLines = 12,
                modifier = Modifier.onFocusChanged {
                    if (it.isFocused) onFieldFocused(DictRuleEditDialog.EditField.ShowRule)
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(visual.density.blockGap / 2)) {
                AppGhostButton(
                    text = stringResource(R.string.edit_content),
                    modifier = Modifier.weight(1f),
                    onClick = onFullEdit,
                )
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
            }
            Row(horizontalArrangement = Arrangement.spacedBy(visual.density.blockGap / 2)) {
                AppGhostButton(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                AppFilledButton(
                    text = stringResource(R.string.action_save),
                    modifier = Modifier.weight(1f),
                    onClick = onSave,
                )
            }
        }
    }
}
