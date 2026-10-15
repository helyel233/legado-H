package io.legado.app.ui.host

import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import androidx.compose.material3.Surface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.widget.dialog.rememberV5DialogColors
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppTextField
/**
 * AI 绘图服务商编辑的**内容区**（V5）。
 *
 * 顶栏（标题/返回/保存）和系统栏 padding 由 [HostV5EditPage] 统一提供，
 * 所以这里不再自带顶栏与保存按钮，也不处理 statusBars/navigationBars。
 */
@Composable
internal fun AiImageProviderEditForm(
    name: String,
    onNameChange: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    headers: String,
    onHeadersChange: (String) -> Unit,
    timeout: String,
    onTimeoutChange: (String) -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    isOpenAi: Boolean,
    onTypeClick: () -> Unit,
    stylePromptSummary: String,
    onStylePromptClick: () -> Unit,
    paramsSummary: String,
    onParamsClick: () -> Unit,
    scriptSummary: String,
    onScriptClick: () -> Unit,
    jsLibSummary: String,
    onJsLibClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = rememberV5DialogColors()
    val visual = LocalVisualSpec.current
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 服务商类型
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onTypeClick),
                shape = visual.shape(ShapeKind.BUTTON),
                color = style.fieldSurface,
                contentColor = style.primaryText,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = "${stringResource(R.string.ai_image_provider_type)}: " +
                        stringResource(
                            if (isOpenAi) R.string.ai_image_provider_openai
                            else R.string.ai_image_provider_js
                        ),
                    color = style.primaryText,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            }

            // 名称
            EditTextField(
                value = name,
                onValueChange = onNameChange,
                label = stringResource(R.string.name),
            )

            // OpenAI 类型专属字段
            if (isOpenAi) {
                EditTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = stringResource(R.string.ai_base_url),
                    keyboardType = KeyboardType.Uri,
                )
                EditTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    label = stringResource(R.string.ai_api_key),
                    isPassword = true,
                )
                EditTextField(
                    value = model,
                    onValueChange = onModelChange,
                    label = stringResource(R.string.ai_model),
                )
                EditTextField(
                    value = headers,
                    onValueChange = onHeadersChange,
                    label = stringResource(R.string.ai_custom_headers),
                    minLines = 3,
                    maxLines = 5,
                )
            }

            // 超时（两种类型通用）
            EditTextField(
                value = timeout,
                onValueChange = onTimeoutChange,
                label = stringResource(R.string.timeout_millisecond),
                keyboardType = KeyboardType.Number,
            )

            // 启用开关
            AppRowWithSwitch(
                title = stringResource(R.string.enable),
                checked = enabled,
                onCheckedChange = onEnabledChange,
                onRowClick = { onEnabledChange(!enabled) },
                card = false,
            )

            // 风格提示词（两种类型都有）
            EditCodeButton(label = stylePromptSummary, onClick = onStylePromptClick)

            // OpenAI：默认请求参数
            if (isOpenAi) {
                EditCodeButton(label = paramsSummary, onClick = onParamsClick)
            }

            // JS：脚本 与 jsLib
            if (!isOpenAi) {
                EditCodeButton(label = scriptSummary, onClick = onScriptClick)
                EditCodeButton(label = jsLibSummary, onClick = onJsLibClick)
            }

            Spacer(modifier = Modifier.height(8.dp))
                }
    }
}

@Composable
private fun EditTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 1,
) {
    val style = rememberV5DialogColors()
    val visual = LocalVisualSpec.current
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = label,
        singleLine = maxLines == 1,
        minLines = minLines,
        maxLines = maxLines,
        keyboardType = keyboardType,
        visualTransformation = if (isPassword) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        textStyle = visual.type.body.copy(
            color = style.primaryText,
            fontFamily = style.bodyFontFamily,
        ),
    )
}

@Composable
private fun EditCodeButton(
    label: String,
    onClick: () -> Unit,
) {
    val style = rememberV5DialogColors()
    val visual = LocalVisualSpec.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = visual.shape(ShapeKind.BUTTON),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Text(
            text = label,
            color = style.primaryText,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}
