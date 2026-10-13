package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.importHttpClient as okHttpClient
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.ui.config.AiImageProviderEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.uikit.facade.AppToast
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppListItem
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.GSON
import io.legado.app.utils.postEvent
import io.legado.app.utils.readText
import io.legado.app.utils.sendToClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * AI 生图供应商管理（V5）。
 * 业务逻辑从旧 AiImageProviderManageActivity 搬运；编辑仍指向旧 AiImageProviderEditActivity（待迁）。
 */
class HostAiImageProviderManageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostAiImageProviderManageScreen() }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, HostAiImageProviderManageActivity::class.java))
        }
    }
}

@Composable
private fun HostAiImageProviderManageScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val density = LocalVisualSpec.current.density
    val scope = rememberCoroutineScope()

    var providers by remember { mutableStateOf<List<AiImageProviderConfig>>(emptyList()) }
    var currentProviderId by remember { mutableStateOf("") }
    var globalMenu by remember { mutableStateOf(false) }
    var addSheet by remember { mutableStateOf(false) }
    var importSheet by remember { mutableStateOf(false) }
    var importUrlDialog by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<AiImageProviderConfig?>(null) }
    var confirmDelete by remember { mutableStateOf<AiImageProviderConfig?>(null) }
    var exportUrl by remember { mutableStateOf<String?>(null) }

    fun reload() {
        AppConfig.ensureCurrentImageProvider()
        providers = AppConfig.aiImageProviderList.sortedBy { it.order }
        currentProviderId = AppConfig.aiCurrentImageProvider?.id.orEmpty()
    }

    fun notifyAiConfigChanged() {
        postEvent(EventBus.AI_CONFIG_CHANGED, true)
    }

    fun openEdit(providerId: String?, type: String) {
        context.startActivity(AiImageProviderEditActivity.newIntent(context, providerId, type))
    }

    fun importRules(rules: List<AiImageProviderConfig>) {
        val validRules = rules.filter { it.type == AiImageProviderConfig.TYPE_JS && it.script.isNotBlank() }
        if (validRules.isEmpty()) {
            AppToast.show(context.getString(R.string.wrong_format))
            return
        }
        val needDefaultProvider = AppConfig.aiCurrentImageProvider == null
        val startOrder = (AppConfig.aiImageProviderList.maxOfOrNull { it.order } ?: -1) + 1
        val importedRules = validRules.mapIndexed { index, rule ->
            rule.copy(
                id = UUID.randomUUID().toString(),
                name = rule.name.trim().ifBlank { "生图规则" },
                type = AiImageProviderConfig.TYPE_JS,
                baseUrl = "",
                apiKey = "",
                headers = "",
                defaultParamsJson = "",
                order = startOrder + index,
                enabled = if (needDefaultProvider && index == 0) true else rule.enabled
            )
        }
        AppConfig.aiImageProviderList = AppConfig.aiImageProviderList + importedRules
        if (needDefaultProvider) {
            AppConfig.ensureCurrentImageProvider(importedRules.firstOrNull()?.id)
        }
        notifyAiConfigChanged()
        reload()
        AppToast.show("已导入 ${validRules.size} 个生图规则")
    }

    fun removeProvider(provider: AiImageProviderConfig) {
        AppConfig.aiImageProviderList = AppConfig.aiImageProviderList.filterNot { it.id == provider.id }
        notifyAiConfigChanged()
        reload()
        AppToast.show(context.getString(R.string.delete))
    }

    val exportRuleResult = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            val value = uri.toString()
            if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
                exportUrl = value
            } else {
                AppToast.show(context.getString(R.string.export_success))
            }
        }
    }

    fun exportRule(provider: AiImageProviderConfig) {
        exportRuleResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "ai-image-rule-${safeFileName(provider.displayName())}.json",
                serializeRule(provider).toByteArray(),
                "application/json"
            )
        }
    }

    fun exportRules(providers: List<AiImageProviderConfig>) {
        exportRuleResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "ai-image-rules.json",
                serializeRules(providers).toByteArray(),
                "application/json"
            )
        }
    }

    fun exportAllRules() {
        val rules = AppConfig.aiImageProviderList
            .filter { it.type == AiImageProviderConfig.TYPE_JS && it.script.isNotBlank() }
            .sortedBy { it.order }
        if (rules.isEmpty()) {
            AppToast.show(context.getString(R.string.wrong_format))
            return
        }
        exportRules(rules)
    }

    fun providerMenuActions(provider: AiImageProviderConfig): List<Pair<String, () -> Unit>> {
        val isJsRule = provider.type == AiImageProviderConfig.TYPE_JS
        val isCurrent = provider.id == AppConfig.aiCurrentImageProviderId
        return buildList {
            if (!isCurrent) {
                add("设为当前生图模型" to {
                    if (!provider.enabled) {
                        AppToast.show("请先启用该生图模型")
                    } else {
                        AppConfig.aiCurrentImageProviderId = provider.id
                        notifyAiConfigChanged()
                        reload()
                        AppToast.show("已设为当前生图模型")
                    }
                })
            }
            add(context.getString(if (provider.enabled) R.string.disable else R.string.enable) to {
                AppConfig.aiImageProviderList = AppConfig.aiImageProviderList.map {
                    if (it.id == provider.id) it.copy(enabled = !it.enabled) else it
                }
                notifyAiConfigChanged()
                reload()
            })
            add(context.getString(R.string.edit) to {
                openEdit(provider.id, provider.type)
            })
            if (isJsRule) {
                add("导出规则" to { exportRule(provider) })
                add("复制规则" to {
                    context.sendToClip(serializeRule(provider))
                })
            }
            add(context.getString(R.string.delete) to { confirmDelete = provider })
        }
    }

    val importRule = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            scope.launch {
                runCatching {
                    parseImportedRules(uri.readText(context), context)
                }.onSuccess { rules ->
                    importRules(rules)
                }.onFailure {
                    AppToast.show(it.localizedMessage ?: context.getString(R.string.wrong_format))
                }
            }
        }
    }

    fun showAddSelector() {
        addSheet = true
    }

    fun showImportActions() {
        importSheet = true
    }

    fun launchImportFile() {
        importRule.launch {
            mode = HandleFileContract.FILE
            title = context.getString(R.string.import_str)
            allowExtensions = arrayOf("json")
        }
    }

    fun importRulesFromUrl(url: String) {
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    okHttpClient.newCallResponseBody { url(url) }.use { it.string() }
                }
                parseImportedRules(text, context)
            }.onSuccess { rules ->
                importRules(rules)
            }.onFailure {
                AppToast.show(it.localizedMessage ?: context.getString(R.string.wrong_format))
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    HostV5Page(
        title = context.getString(R.string.ai_image_provider_manage),
        subtitle = context.getString(R.string.ai_image_provider_manage_summary),
        onBack = { activity.finish() },
        trailing = {
            AppOverflowMenuButton(
                contentDescription = context.getString(R.string.more),
                onClick = { globalMenu = true },
            )
        },
        bottomBar = {
            AppFilledButton(
                text = context.getString(R.string.add),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap),
                onClick = { showAddSelector() },
            )
        },
    ) {
        if (providers.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                AppEmpty(
                    title = context.getString(R.string.ai_image_provider_summary_empty),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = density.screenPadding,
                    end = density.screenPadding,
                    top = density.blockGap / 2,
                    bottom = density.screenPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                items(providers, key = { it.id }) { provider ->
                    val current = provider.id == currentProviderId
                    AppListItem(
                        headline = provider.displayName(),
                        overline = if (current) context.getString(R.string.ai_current_provider) else null,
                        supporting = context.getString(if (provider.enabled) R.string.enabled else R.string.disabled),
                        trailing = {
                            AppOverflowMenuButton(
                                contentDescription = context.getString(R.string.more),
                                onClick = { menuFor = provider },
                            )
                        },
                        onClick = { openEdit(provider.id, provider.type) },
                    )
                }
            }
        }
    }

    if (globalMenu) {
        AppMenuSheet(
            title = context.getString(R.string.more),
            actions = listOf(
                "导入规则" to { showImportActions() },
                "导出规则" to { exportAllRules() },
            ),
            onDismiss = { globalMenu = false },
        )
    }

    if (addSheet) {
        AppMenuSheet(
            title = context.getString(R.string.add),
            actions = listOf(
                context.getString(R.string.ai_image_provider_openai) to {
                    openEdit(null, AiImageProviderConfig.TYPE_OPENAI)
                },
                context.getString(R.string.ai_image_provider_js) to {
                    openEdit(null, AiImageProviderConfig.TYPE_JS)
                },
                "导入 JS 生图规则" to { showImportActions() },
            ),
            onDismiss = { addSheet = false },
        )
    }

    if (importSheet) {
        AppMenuSheet(
            title = context.getString(R.string.import_str),
            actions = listOf(
                context.getString(R.string.import_str) to { launchImportFile() },
                context.getString(R.string.import_on_line) to { importUrlDialog = true },
            ),
            onDismiss = { importSheet = false },
        )
    }

    if (importUrlDialog) {
        AppInputDialog(
            title = context.getString(R.string.import_on_line),
            value = importUrl,
            onValueChange = { importUrl = it },
            confirmLabel = context.getString(R.string.ok),
            cancelLabel = context.getString(R.string.cancel),
            onConfirm = { text ->
                importUrlDialog = false
                val url = text.trim()
                if (url.isNotEmpty()) importRulesFromUrl(url)
                importUrl = ""
            },
            onDismiss = {
                importUrlDialog = false
                importUrl = ""
            },
            placeholder = "https://...",
        )
    }

    menuFor?.let { provider ->
        AppMenuSheet(
            title = provider.displayName(),
            actions = providerMenuActions(provider),
            onDismiss = { menuFor = null },
        )
    }

    confirmDelete?.let { provider ->
        AppConfirmDialog(
            title = provider.displayName(),
            message = context.getString(R.string.delete),
            confirmLabel = context.getString(R.string.delete),
            cancelLabel = context.getString(R.string.cancel),
            danger = true,
            onConfirm = {
                confirmDelete = null
                removeProvider(provider)
            },
            onDismiss = { confirmDelete = null },
        )
    }

    exportUrl?.let { value ->
        AppConfirmDialog(
            title = context.getString(R.string.upload_url),
            message = value,
            confirmLabel = context.getString(R.string.copy_text),
            cancelLabel = context.getString(R.string.cancel),
            onConfirm = {
                context.sendToClip(value)
                exportUrl = null
            },
            onDismiss = { exportUrl = null },
        )
    }
}

private fun parseImportedRules(raw: String, context: Context): List<AiImageProviderConfig> {
    val text = raw.trim()
    if (text.isBlank()) return emptyList()
    val root = if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
    val array = when (root) {
        is JSONArray -> root
        is JSONObject -> root.optJSONArray("rules") ?: JSONArray().put(root)
        else -> JSONArray()
    }
    return buildList {
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            parseRule(json, context)?.let { add(it) }
        }
    }
}

private fun parseRule(json: JSONObject, context: Context): AiImageProviderConfig? {
    val type = json.optString("type").ifBlank {
        when {
            json.optString("script").isNotBlank() -> AiImageProviderConfig.TYPE_JS
            json.optString("showRule").isNotBlank() || json.optString("urlRule").isNotBlank() -> AiImageProviderConfig.TYPE_JS
            else -> ""
        }
    }
    if (type != AiImageProviderConfig.TYPE_JS) return null
    val script = json.optString("script")
        .ifBlank { json.optString("rule") }
        .ifBlank { legacyAiImageRuleScript(json) }
        .trim()
    if (script.isBlank()) return null
    return AiImageProviderConfig(
        name = json.optString("name").ifBlank { context.getString(R.string.ai_image_provider_js) },
        type = AiImageProviderConfig.TYPE_JS,
        model = json.optString("model").ifBlank { "JS" },
        stylePrompt = json.optString("stylePrompt"),
        jsLib = json.optString("jsLib"),
        loginUrl = json.optString("loginUrl"),
        loginUi = json.optString("loginUi"),
        enabledCookieJar = json.optBoolean("enabledCookieJar", false),
        script = script,
        timeoutMillisecond = json.optLong("timeoutMillisecond", 300_000L).takeIf { it > 0L } ?: 300_000L,
        order = json.optInt("order", 0),
        enabled = json.optBoolean("enabled", true)
    )
}

private fun legacyAiImageRuleScript(json: JSONObject): String {
    val showRule = json.optString("showRule").trim()
    val urlRule = json.optString("urlRule").trim()
    if (showRule.isBlank() && urlRule.isBlank()) return ""
    return buildString {
        appendLine("/* Converted from legacy AI image showRule/urlRule. */")
        appendLine("var __legacyUrlRule = ${JSONObject.quote(urlRule)};")
        appendLine("var __legacyShowRule = ${JSONObject.quote(showRule)};")
        appendLine(
            """
            function __legacyEvalRule(rule) {
                if (!rule) return result;
                var code = String(rule);
                if (code.indexOf('@js:') === 0) code = code.substring(4);
                return eval(code);
            }
            function __legacyFirstImage(value) {
                if (value == null) return '';
                var text = String(value).trim();
                if (/^(https?:|data:image\/)/i.test(text)) return text;
                try {
                    if (text.charAt(0) === '{') {
                        var objectValue = JSON.parse(text);
                        return objectValue.url || objectValue.src || objectValue.image || objectValue.data || '';
                    }
                    if (text.charAt(0) === '[') {
                        var arrayValue = JSON.parse(text);
                        if (arrayValue.length > 0) return __legacyFirstImage(arrayValue[0]);
                    }
                } catch (e) {}
                var match = text.match(/<img[^>]+src\s*=\s*["']([^"']+)["']/i);
                if (match && match[1]) return match[1];
                match = text.match(/https?:\/\/[^\s"'<>]+/i);
                if (match && match[0]) return match[0];
                match = text.match(/data:image\/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+/i);
                if (match && match[0]) return match[0];
                return text;
            }
            var __legacyPrompt = prompt;
            var key = __legacyPrompt;
            var result = __legacyPrompt;
            if (__legacyUrlRule) {
                var __legacyUrlResult = __legacyEvalRule(__legacyUrlRule);
                if (typeof __legacyUrlResult !== 'undefined') result = __legacyUrlResult;
            }
            if (__legacyShowRule) {
                var __legacyShowResult = __legacyEvalRule(__legacyShowRule);
                if (typeof __legacyShowResult !== 'undefined') result = __legacyShowResult;
            }
            result = __legacyFirstImage(result);
            """.trimIndent()
        )
    }
}

private fun serializeRule(provider: AiImageProviderConfig): String {
    return GSON.toJson(
        mapOf(
            "type" to "ai_image_rule",
            "version" to 1,
            "rules" to listOf(sanitizedRule(provider))
        )
    )
}

private fun serializeRules(providers: List<AiImageProviderConfig>): String {
    return GSON.toJson(
        mapOf(
            "type" to "ai_image_rule",
            "version" to 1,
            "rules" to providers.map(::sanitizedRule)
        )
    )
}

private fun sanitizedRule(provider: AiImageProviderConfig): AiImageProviderConfig {
    return provider.copy(
        type = AiImageProviderConfig.TYPE_JS,
        baseUrl = "",
        apiKey = "",
        headers = "",
        defaultParamsJson = ""
    )
}

private fun safeFileName(value: String): String {
    return value.trim().ifBlank { "ai-image-rule" }
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
}
