package io.legado.app.ui.book.read.config

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.CacheManager
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.help.config.AdvancedTitlePackageManager
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.toastOnUi

/**
 * 高级标题模板编辑（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Form` 映射为 widthFraction 0.94f / maxWidthDp 660；
 * `onStart` 里的 `SOFT_INPUT_ADJUST_RESIZE` 必须保留（表单页要顶起输入法）；
 * 内容高度上限 `screenHeightDp * 0.72` + verticalScroll 保留；
 * 组件：OutlinedTextField(自定义 outlinedColors) → AppTextField，LegadoMiuixSwitch → AppRowWithSwitch(card=false)，
 * 自定义 subtleButton → AppGhostButton/AppFilledButton。
 * **行为保留**：JSON 走 CodeEditActivity 契约（CacheManager 传大文本 + cacheKey + cursorPosition 回写 jsonStateRef）、
 * 名称必填校验、JSON validateJson 失败 toast 不关闭、heightFactor coerceIn(30,120) 兜底、保存回调 Host。
 */
internal class AdvancedTitleConfigDialog : V5DialogFragment() {

    companion object {
        private const val ARG_ENTRY_ID = "entryId"
        private const val ARG_NAME = "name"
        private const val ARG_SPLIT_MODE = "splitMode"
        private const val ARG_DELIMITER = "delimiter"
        private const val ARG_REGEX = "regex"
        private const val ARG_HEIGHT_FACTOR = "heightFactor"

        fun edit(
            entryId: String,
            name: String,
            json: String,
            splitRule: AdvancedTitleConfig.SplitRule,
            heightFactor: Int
        ) = AdvancedTitleConfigDialog().apply {
            initialJson = json
            arguments = Bundle().apply {
                putString(ARG_ENTRY_ID, entryId)
                putString(ARG_NAME, name)
                putInt(ARG_SPLIT_MODE, splitRule.mode)
                putString(ARG_DELIMITER, splitRule.delimiter)
                putString(ARG_REGEX, splitRule.regex)
                putInt(ARG_HEIGHT_FACTOR, heightFactor.coerceIn(30, 120))
            }
        }
    }

    /** 旧 AppDialogSize.Form(widthFraction = 0.94f, maxWidthDp = 660) */
    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int = 660

    private var initialJson: String = ""
    private var jsonCursorPosition: Int = 0
    private var currentJson: String = ""
    private var jsonStateRef: MutableState<String>? = null

    interface Host {
        fun onAdvancedTitleSaved(
            entryId: String,
            name: String,
            json: String,
            splitRule: AdvancedTitleConfig.SplitRule,
            heightFactor: Int
        )
    }

    private val jsonEditor = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val cacheKey = data.getStringExtra("cacheKey")
        val text = if (cacheKey != null) {
            CacheManager.getFromMemory(cacheKey) as? String
        } else {
            data.getStringExtra("text")
        } ?: return@registerForActivityResult
        currentJson = text
        jsonStateRef?.value = text
        jsonCursorPosition = data.getIntExtra("cursorPosition", text.length)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (currentJson.isBlank()) {
            currentJson = if (initialJson.isNotBlank()) {
                initialJson
            } else {
                runCatching {
                    AdvancedTitlePackageManager.readTemplate(
                        requireArguments().getString(ARG_ENTRY_ID).orEmpty()
                    )
                }.getOrDefault("")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density
        val args = requireArguments()
        val entryId = args.getString(ARG_ENTRY_ID).orEmpty()
        val startMode = args.getInt(ARG_SPLIT_MODE, AdvancedTitleConfig.SPLIT_DELIMITER)
        val startDelimiter = args.getString(ARG_DELIMITER) ?: " "
        val startRegex = args.getString(ARG_REGEX) ?: AdvancedTitleConfig.DEFAULT_REGEX
        var name by remember { mutableStateOf(args.getString(ARG_NAME).orEmpty()) }
        var useRegex by remember { mutableStateOf(startMode == AdvancedTitleConfig.SPLIT_REGEX) }
        var ruleText by remember {
            mutableStateOf(if (startMode == AdvancedTitleConfig.SPLIT_REGEX) startRegex else startDelimiter)
        }
        var sampleText by remember { mutableStateOf(getString(R.string.advanced_title_sample_default)) }
        var heightText by remember {
            mutableStateOf(
                args.getInt(ARG_HEIGHT_FACTOR, AdvancedTitleConfig.DEFAULT_HEIGHT_FACTOR)
                    .coerceIn(30, 120).toString()
            )
        }
        val jsonState = remember { mutableStateOf(currentJson) }
        jsonStateRef = jsonState
        val json by jsonState

        val emptyText = stringResource(R.string.empty)
        val previewText = runCatching {
            val parts = AdvancedTitleConfig.split(
                sampleText,
                buildRule(useRegex, ruleText, startDelimiter, startRegex)
            )
            getString(
                R.string.advanced_title_preview_template,
                parts.s1.ifBlank { emptyText },
                parts.s2.ifBlank { emptyText }
            )
        }.getOrElse {
            getString(R.string.advanced_title_rule_error, it.localizedMessage.orEmpty())
        }

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.72f).dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                Text(
                    text = stringResource(R.string.advanced_title_edit_title),
                    style = visual.type.title,
                    color = colors.onSurface
                )
                Text(
                    text = stringResource(R.string.advanced_title_name),
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant
                )
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.advanced_title_name),
                    singleLine = true,
                )
                AppRowWithSwitch(
                    title = stringResource(R.string.advanced_title_use_regex),
                    checked = useRegex,
                    onCheckedChange = { checked ->
                        useRegex = checked
                        ruleText = if (checked) startRegex else startDelimiter
                    },
                    card = false,
                )
                AppTextField(
                    value = ruleText,
                    onValueChange = { ruleText = it },
                    placeholder = stringResource(R.string.advanced_title_rule_label),
                    singleLine = false,
                    maxLines = 3,
                )
                Text(
                    text = stringResource(R.string.preview),
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant
                )
                AppTextField(
                    value = sampleText,
                    onValueChange = { sampleText = it },
                    singleLine = false,
                    maxLines = 3,
                )
                Text(
                    text = previewText,
                    style = visual.type.caption,
                    color = colors.primary
                )
                Text(
                    text = stringResource(R.string.advanced_title_height_factor_label),
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant
                )
                AppTextField(
                    value = heightText,
                    onValueChange = { heightText = it.filter(Char::isDigit).take(3) },
                    placeholder = stringResource(R.string.advanced_title_height_factor_hint),
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.advanced_title_json_label),
                        style = visual.type.caption,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    AppGhostButton(
                        text = stringResource(R.string.advanced_title_open_editor),
                        onClick = { openJsonEditor() },
                    )
                }
                Text(
                    text = stringResource(R.string.advanced_title_json_hint),
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { dismissAllowingStateLoss() },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.confirm),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val trimmedName = name.trim()
                            if (trimmedName.isEmpty()) {
                                requireContext().toastOnUi(
                                    getString(R.string.advanced_title_name_required)
                                )
                                return@AppFilledButton
                            }
                            val jsonText = json.trim()
                            val jsonError = runCatching {
                                AdvancedTitlePackageManager.validateJson(jsonText)
                            }.exceptionOrNull()
                            if (jsonError != null) {
                                requireContext().toastOnUi(
                                    jsonError.localizedMessage
                                        ?: getString(R.string.advanced_title_invalid_json)
                                )
                                return@AppFilledButton
                            }
                            val heightFactor = heightText.trim().toIntOrNull()
                                ?.coerceIn(30, 120)
                                ?: AdvancedTitleConfig.DEFAULT_HEIGHT_FACTOR
                            dismissAllowingStateLoss()
                            (activity as? Host)?.onAdvancedTitleSaved(
                                entryId,
                                trimmedName,
                                jsonText,
                                buildRule(useRegex, ruleText, startDelimiter, startRegex),
                                heightFactor
                            )
                        },
                    )
                }
            }
        }
    }

    private fun buildRule(
        useRegex: Boolean,
        ruleText: String,
        startDelimiter: String,
        startRegex: String
    ): AdvancedTitleConfig.SplitRule {
        return AdvancedTitleConfig.SplitRule(
            mode = if (useRegex) {
                AdvancedTitleConfig.SPLIT_REGEX
            } else {
                AdvancedTitleConfig.SPLIT_DELIMITER
            },
            delimiter = if (useRegex) startDelimiter else ruleText,
            regex = if (useRegex) ruleText else startRegex
        )
    }

    private fun openJsonEditor() {
        val bytes = AdvancedTitlePackageManager.utf8SizeUpTo(
            currentJson,
            AdvancedTitlePackageManager.MAX_EDITABLE_JSON_BYTES
        )
        if (bytes > AdvancedTitlePackageManager.MAX_EDITABLE_JSON_BYTES) {
            context?.toastOnUi(R.string.advanced_title_json_too_large_to_edit)
            return
        }
        jsonEditor.launch(Intent(requireContext(), CodeEditActivity::class.java).apply {
            val key = "advanced_title_edit_" + System.nanoTime()
            CacheManager.putMemory(key, currentJson)
            putExtra("cacheKey", key)
            putExtra("writable", true)
            putExtra("title", getString(R.string.advanced_title_json_label))
            putExtra("cursorPosition", jsonCursorPosition.coerceIn(0, currentJson.length))
        })
    }
}
