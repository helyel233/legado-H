package io.legado.app.ui.book.read.config

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.CacheManager
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.help.config.AdvancedTitlePackageManager
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.toastOnUi

/**
 * 高级标题模板编辑（P3-d 面板化：原程序化 View 构建迁移为 Compose）
 */
class AdvancedTitleConfigDialog : ComposeDialogFragment() {

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

    override val dialogSize: AppDialogSize? = AppDialogSize.Form

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
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
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    AdvancedTitleContent(style)
                }
            }
        }
    }

    @Composable
    private fun AdvancedTitleContent(style: AppDialogStyle) {
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.72f).dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.advanced_title_edit_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = style.primaryText,
                fontFamily = style.titleFontFamily
            )
            fieldLabel(stringResource(R.string.advanced_title_name), style)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.advanced_title_name)) },
                colors = outlinedColors(style)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.advanced_title_rule_label),
                    color = style.secondaryText,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.advanced_title_use_regex),
                    color = style.secondaryText,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixSwitch(
                    checked = useRegex,
                    onCheckedChange = { checked ->
                        useRegex = checked
                        ruleText = if (checked) startRegex else startDelimiter
                    },
                    palette = style.toMiuixPalette()
                )
            }
            OutlinedTextField(
                value = ruleText,
                onValueChange = { ruleText = it },
                modifier = Modifier.fillMaxWidth(),
                colors = outlinedColors(style)
            )
            fieldLabel(stringResource(R.string.preview), style)
            OutlinedTextField(
                value = sampleText,
                onValueChange = { sampleText = it },
                modifier = Modifier.fillMaxWidth(),
                colors = outlinedColors(style)
            )
            Text(
                text = previewText,
                color = style.accent,
                fontSize = 13.sp
            )
            fieldLabel(stringResource(R.string.advanced_title_height_factor_label), style)
            OutlinedTextField(
                value = heightText,
                onValueChange = { heightText = it.filter(Char::isDigit).take(3) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.advanced_title_height_factor_hint)) },
                colors = outlinedColors(style)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.advanced_title_json_label),
                    color = style.secondaryText,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.advanced_title_open_editor),
                    color = style.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(style.actionRadius))
                        .clickable { openJsonEditor() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            Text(
                text = stringResource(R.string.advanced_title_json_hint),
                color = style.secondaryText.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                subtleButton(stringResource(R.string.cancel), style, Modifier.weight(1f)) {
                    dismissAllowingStateLoss()
                }
                subtleButton(stringResource(R.string.confirm), style, Modifier.weight(1f)) {
                    val trimmedName = name.trim()
                    if (trimmedName.isEmpty()) {
                        requireContext().toastOnUi(getString(R.string.advanced_title_name_required))
                        return@subtleButton
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
                        return@subtleButton
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

    @Composable
    private fun fieldLabel(text: String, style: AppDialogStyle) {
        Text(
            text = text,
            color = style.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
    }

    @Composable
    private fun outlinedColors(style: AppDialogStyle) = OutlinedTextFieldDefaults.colors(
        focusedTextColor = style.primaryText,
        unfocusedTextColor = style.primaryText,
        cursorColor = style.accent,
        focusedBorderColor = style.accent,
        unfocusedBorderColor = style.stroke,
        focusedContainerColor = style.fieldSurface,
        unfocusedContainerColor = style.fieldSurface
    )

    @Composable
    private fun subtleButton(
        text: String,
        style: AppDialogStyle,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Text(
            text = text,
            color = style.primaryText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = modifier
                .clip(RoundedCornerShape(style.actionRadius))
                .background(style.fieldSurface, RoundedCornerShape(style.actionRadius))
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp)
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
