package io.legado.app.ui.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.legado.app.R
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.debug.RegexTestActivity
import io.legado.app.ui.replace.edit.ReplaceEditViewModel
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.GSON
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi

/**
 * 替换规则编辑（V5）。
 *
 * 业务逻辑从旧 ReplaceEditActivity 搬运；表单改用 V5 的 [AppTextField] / [AppRowWithSwitch]，
 * 旧 optionsMenu 的四个动作（全屏编辑/复制/粘贴/正则测试）迁到顶栏溢出菜单，并顺带保留正则帮助入口。
 *
 * **已知降级**：旧版软键盘工具条 `KeyboardToolPop`（撤销/重做/插入符号）依赖 View 层 EditText
 * 的焦点查找，Compose TextField 下无法工作，故不再接入。
 */
class HostReplaceEditActivity : AppCompatActivity() {

    private val viewModel by viewModels<ReplaceEditViewModel>()

    private var name by mutableStateOf("")
    private var group by mutableStateOf("")
    private var pattern by mutableStateOf("")
    private var useRegex by mutableStateOf(false)
    private var replacement by mutableStateOf("")
    private var scopeTitle by mutableStateOf(false)
    private var scopeContent by mutableStateOf(true)
    private var scope by mutableStateOf("")
    private var excludeScope by mutableStateOf("")
    private var timeout by mutableStateOf("")
    private var dirty by mutableStateOf(false)
    private var lastFocused: Field? = null
    private var editingField: Field? = null

    private val codeEditLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val text = result.data?.getStringExtra("text") ?: return@registerForActivityResult
        dirty = true
        when (editingField) {
            Field.NAME -> name = text
            Field.GROUP -> group = text
            Field.PATTERN -> pattern = text
            Field.REPLACEMENT -> replacement = text
            Field.SCOPE -> scope = text
            Field.EXCLUDE_SCOPE -> excludeScope = text
            Field.TIMEOUT -> timeout = text
            null -> Unit
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initData(intent) { rule -> upRuleView(rule) }
        setV5Content { HostReplaceEditScreen() }
    }

    @Composable
    private fun HostReplaceEditScreen() {
        val density = LocalVisualSpec.current.density
        HostV5EditPage(
            title = stringResource(R.string.replace_rule_edit),
            dirty = dirty,
            onSave = { save() },
            onDiscard = { finish() },
            menuActions = listOf(
                stringResource(R.string.edit_content) to { openFullEdit() },
                stringResource(R.string.copy_rule) to { sendToClip(GSON.toJson(buildRule())) },
                stringResource(R.string.paste_rule) to { viewModel.pasteRule { upRuleView(it) } },
                stringResource(R.string.regex_test) to { openRegexTest() },
                stringResource(R.string.help) to { showHelp("regexHelp") },
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = density.screenPadding,
                        vertical = density.blockGap / 2,
                    ),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                AppTextField(
                    value = name,
                    onValueChange = { name = it; dirty = true },
                    placeholder = stringResource(R.string.replace_rule_summary),
                    singleLine = true,
                    modifier = Modifier.onFocusChanged {
                        if (it.isFocused) lastFocused = Field.NAME
                    },
                )
                AppTextField(
                    value = group,
                    onValueChange = { group = it; dirty = true },
                    placeholder = stringResource(R.string.group),
                    singleLine = true,
                    modifier = Modifier.onFocusChanged {
                        if (it.isFocused) lastFocused = Field.GROUP
                    },
                )
                AppTextField(
                    value = pattern,
                    onValueChange = { pattern = it; dirty = true },
                    placeholder = stringResource(R.string.replace_rule),
                    singleLine = false,
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.onFocusChanged {
                        if (it.isFocused) lastFocused = Field.PATTERN
                    },
                )
                AppRowWithSwitch(
                    title = stringResource(R.string.use_regex),
                    checked = useRegex,
                    onCheckedChange = { useRegex = it; dirty = true },
                )
                AppTextField(
                    value = replacement,
                    onValueChange = { replacement = it; dirty = true },
                    placeholder = stringResource(R.string.replace_to),
                    singleLine = false,
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.onFocusChanged {
                        if (it.isFocused) lastFocused = Field.REPLACEMENT
                    },
                )
                AppRowWithSwitch(
                    title = stringResource(R.string.scope_title),
                    checked = scopeTitle,
                    onCheckedChange = { scopeTitle = it; dirty = true },
                )
                AppRowWithSwitch(
                    title = stringResource(R.string.scope_content),
                    checked = scopeContent,
                    onCheckedChange = { scopeContent = it; dirty = true },
                )
                AppTextField(
                    value = scope,
                    onValueChange = { scope = it; dirty = true },
                    placeholder = stringResource(R.string.replace_scope),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.onFocusChanged {
                        if (it.isFocused) lastFocused = Field.SCOPE
                    },
                )
                AppTextField(
                    value = excludeScope,
                    onValueChange = { excludeScope = it; dirty = true },
                    placeholder = stringResource(R.string.replace_exclude_scope),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.onFocusChanged {
                        if (it.isFocused) lastFocused = Field.EXCLUDE_SCOPE
                    },
                )
                AppTextField(
                    value = timeout,
                    onValueChange = { timeout = it; dirty = true },
                    placeholder = stringResource(R.string.timeout_millisecond),
                    singleLine = true,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.onFocusChanged {
                        if (it.isFocused) lastFocused = Field.TIMEOUT
                    },
                )
            }
        }
    }

    private fun upRuleView(rule: ReplaceRule) {
        name = rule.name
        group = rule.group.orEmpty()
        pattern = rule.pattern
        useRegex = rule.isRegex
        replacement = rule.replacement
        scopeTitle = rule.scopeTitle
        scopeContent = rule.scopeContent
        scope = rule.scope.orEmpty()
        excludeScope = rule.excludeScope.orEmpty()
        timeout = rule.timeoutMillisecond.toString()
        dirty = false
    }

    private fun buildRule(): ReplaceRule {
        val rule = viewModel.replaceRule ?: ReplaceRule()
        rule.name = name
        rule.group = group
        rule.pattern = pattern
        rule.isRegex = useRegex
        rule.replacement = replacement
        rule.scopeTitle = scopeTitle
        rule.scopeContent = scopeContent
        rule.scope = scope
        rule.excludeScope = excludeScope
        rule.timeoutMillisecond = timeout.toLongOrNull() ?: 3000L
        return rule
    }

    private fun save() {
        viewModel.save(buildRule()) {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    /**
     * 全屏编辑：作用于**最近聚焦**的字段（等价于旧版按当前 EditText 焦点判断）。
     */
    private fun openFullEdit() {
        val target = lastFocused
        if (target == null) {
            toastOnUi(R.string.please_focus_cursor_on_textbox)
            return
        }
        val title = when (target) {
            Field.NAME -> getString(R.string.replace_rule_summary)
            Field.GROUP -> getString(R.string.group)
            Field.PATTERN -> getString(R.string.replace_rule)
            Field.REPLACEMENT -> getString(R.string.replace_to)
            Field.SCOPE -> getString(R.string.replace_scope)
            Field.EXCLUDE_SCOPE -> getString(R.string.replace_exclude_scope)
            Field.TIMEOUT -> getString(R.string.timeout_millisecond)
        }
        val text = when (target) {
            Field.NAME -> name
            Field.GROUP -> group
            Field.PATTERN -> pattern
            Field.REPLACEMENT -> replacement
            Field.SCOPE -> scope
            Field.EXCLUDE_SCOPE -> excludeScope
            Field.TIMEOUT -> timeout
        }
        editingField = target
        codeEditLauncher.launch(Intent(this, CodeEditActivity::class.java).apply {
            putExtra("text", text)
            putExtra("title", title)
        })
    }

    private fun openRegexTest() {
        startActivity(
            RegexTestActivity.startIntent(
                this,
                pattern = pattern,
                replacement = replacement,
                isRegex = useRegex,
            )
        )
    }

    private enum class Field {
        NAME,
        GROUP,
        PATTERN,
        REPLACEMENT,
        SCOPE,
        EXCLUDE_SCOPE,
        TIMEOUT,
    }

    companion object {
        fun startIntent(
            context: Context,
            id: Long = -1,
            pattern: String? = null,
            isRegex: Boolean = false,
            scope: String? = null,
        ): Intent {
            return Intent(context, HostReplaceEditActivity::class.java).apply {
                putExtra("id", id)
                putExtra("pattern", pattern)
                putExtra("isRegex", isRegex)
                putExtra("scope", scope)
            }
        }
    }
}
