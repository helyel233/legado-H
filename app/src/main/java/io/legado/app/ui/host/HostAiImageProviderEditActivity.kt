package io.legado.app.ui.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi

/**
 * 生图供应商编辑（V5）。
 *
 * 业务逻辑从旧 AiImageProviderEditActivity 逐行搬运；表单 UI 复用
 * [AiImageProviderEditForm]（已去掉自带顶栏/保存按钮）。
 * 顶栏标题/返回/保存与 dirty 返回确认由 [HostV5EditPage] 提供。
 */
class HostAiImageProviderEditActivity : AppCompatActivity() {

    private var providerId: String? = null

    private var nameText by mutableStateOf("")
    private var baseUrlText by mutableStateOf("")
    private var apiKeyText by mutableStateOf("")
    private var modelText by mutableStateOf("")
    private var headersText by mutableStateOf("")
    private var timeoutText by mutableStateOf("300000")
    private var enabledState by mutableStateOf(true)
    private var providerType by mutableStateOf(AiImageProviderConfig.TYPE_OPENAI)
    private var paramsText by mutableStateOf("")
    private var stylePromptText by mutableStateOf("")
    private var scriptText by mutableStateOf("")
    private var jsLibText by mutableStateOf("")
    private var dirty by mutableStateOf(false)
    private var editingField: Field = Field.PARAMS

    private val codeEditLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val text = result.data?.getStringExtra("text") ?: return@registerForActivityResult
        dirty = true
        when (editingField) {
            Field.PARAMS -> paramsText = text
            Field.STYLE_PROMPT -> stylePromptText = text
            Field.SCRIPT -> scriptText = text
            Field.JS_LIB -> jsLibText = text
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        providerId = intent.getStringExtra(EXTRA_PROVIDER_ID)
        providerType = intent.getStringExtra(EXTRA_PROVIDER_TYPE)
            ?: AiImageProviderConfig.TYPE_OPENAI
        val provider = currentProvider()
        if (provider != null) providerType = provider.type
        bind(provider)
        setV5Content { HostAiImageProviderEditScreen() }
    }

    @Composable
    private fun HostAiImageProviderEditScreen() {
        val isOpenAi = providerType == AiImageProviderConfig.TYPE_OPENAI
        HostV5EditPage(
            title = stringResource(R.string.ai_image_provider_manage),
            dirty = dirty,
            onSave = { save() },
            onDiscard = { finish() },
        ) {
            AiImageProviderEditForm(
                name = nameText,
                onNameChange = { nameText = it; dirty = true },
                baseUrl = baseUrlText,
                onBaseUrlChange = { baseUrlText = it; dirty = true },
                apiKey = apiKeyText,
                onApiKeyChange = { apiKeyText = it; dirty = true },
                model = modelText,
                onModelChange = { modelText = it; dirty = true },
                headers = headersText,
                onHeadersChange = { headersText = it; dirty = true },
                timeout = timeoutText,
                onTimeoutChange = { timeoutText = it; dirty = true },
                enabled = enabledState,
                onEnabledChange = { enabledState = it; dirty = true },
                isOpenAi = isOpenAi,
                onTypeClick = { selectType() },
                stylePromptSummary = "${
                    stringResource(R.string.ai_image_style_prompt)
                }: ${summary(stylePromptText)}",
                onStylePromptClick = {
                    openCodeEditor(
                        Field.STYLE_PROMPT,
                        getString(R.string.ai_image_style_prompt),
                        stylePromptText,
                        "text.html.markdown"
                    )
                },
                paramsSummary = "${
                    stringResource(R.string.ai_image_params)
                }: ${summary(paramsText.ifBlank { defaultParams() })}",
                onParamsClick = {
                    openCodeEditor(
                        Field.PARAMS,
                        getString(R.string.ai_image_params),
                        paramsText.ifBlank { defaultParams() }
                    )
                },
                scriptSummary = "${
                    stringResource(R.string.ai_image_script)
                }: ${summary(scriptText)}",
                onScriptClick = {
                    openCodeEditor(
                        Field.SCRIPT,
                        getString(R.string.ai_image_script),
                        scriptText
                    )
                },
                jsLibSummary = "jsLib: ${summary(jsLibText)}",
                onJsLibClick = {
                    openCodeEditor(Field.JS_LIB, "jsLib", jsLibText)
                },
            )
        }
    }

    private fun bind(provider: AiImageProviderConfig?) {
        nameText = provider?.name.orEmpty()
        baseUrlText = provider?.baseUrl.orEmpty()
        apiKeyText = provider?.apiKey.orEmpty()
        modelText = provider?.model.orEmpty()
        headersText = provider?.headers.orEmpty()
        timeoutText = (provider?.validTimeout() ?: 300_000L).toString()
        enabledState = provider?.enabled ?: true
        paramsText = provider?.defaultParamsJson.orEmpty()
        stylePromptText = provider?.stylePrompt.orEmpty()
        scriptText = provider?.script.orEmpty()
        jsLibText = provider?.jsLib.orEmpty()
    }

    private fun selectType() {
        showComposeActionListDialog(
            title = getString(R.string.ai_image_provider_type),
            labels = listOf(
                getString(R.string.ai_image_provider_openai),
                getString(R.string.ai_image_provider_js)
            )
        ) { index ->
            providerType = if (index == 0) {
                AiImageProviderConfig.TYPE_OPENAI
            } else {
                AiImageProviderConfig.TYPE_JS
            }
            dirty = true
        }
    }

    private fun openCodeEditor(
        field: Field,
        title: String,
        text: String,
        languageName: String = "source.js"
    ) {
        editingField = field
        codeEditLauncher.launch(Intent(this, CodeEditActivity::class.java).apply {
            putExtra("title", title)
            putExtra("text", text)
            putExtra("languageName", languageName)
        })
    }

    private fun save() {
        if (nameText.isBlank()) {
            toastOnUi(R.string.ai_provider_name_required)
            return
        }
        if (providerType == AiImageProviderConfig.TYPE_OPENAI && baseUrlText.isBlank()) {
            toastOnUi(R.string.ai_provider_url_required)
            return
        }
        val old = currentProvider()
        val needDefaultProvider = old == null && AppConfig.aiCurrentImageProvider == null
        val updated = (old ?: AiImageProviderConfig(name = nameText, type = providerType)).copy(
            name = nameText,
            type = providerType,
            baseUrl = baseUrlText,
            apiKey = apiKeyText,
            headers = headersText,
            model = modelText,
            defaultParamsJson = paramsText.ifBlank { defaultParams() },
            stylePrompt = stylePromptText.trim(),
            jsLib = jsLibText,
            script = scriptText,
            timeoutMillisecond = timeoutText.toLongOrNull() ?: 300_000L,
            enabled = if (needDefaultProvider) true else enabledState
        )
        AppConfig.aiImageProviderList = AppConfig.aiImageProviderList
            .filterNot { it.id == updated.id } + updated
        if (needDefaultProvider) {
            AppConfig.ensureCurrentImageProvider(updated.id)
        }
        postEvent(EventBus.AI_CONFIG_CHANGED, true)
        finish()
    }

    private fun currentProvider(): AiImageProviderConfig? {
        val id = providerId ?: return null
        return AppConfig.aiImageProviderList.firstOrNull { it.id == id }
    }

    private fun defaultParams(): String {
        return if (providerType == AiImageProviderConfig.TYPE_OPENAI) {
            "{\n  \"size\": \"1024x1024\"\n}"
        } else {
            ""
        }
    }

    private fun summary(value: String): String {
        return value.trim().lineSequence().firstOrNull()?.take(36)?.ifBlank { null } ?: "未设置"
    }

    private enum class Field {
        PARAMS,
        STYLE_PROMPT,
        SCRIPT,
        JS_LIB
    }

    companion object {
        private const val EXTRA_PROVIDER_ID = "providerId"
        private const val EXTRA_PROVIDER_TYPE = "providerType"

        fun newIntent(context: Context, providerId: String?, type: String): Intent {
            return Intent(context, HostAiImageProviderEditActivity::class.java).apply {
                putExtra(EXTRA_PROVIDER_TYPE, type)
                providerId?.let { putExtra(EXTRA_PROVIDER_ID, it) }
            }
        }
    }
}
