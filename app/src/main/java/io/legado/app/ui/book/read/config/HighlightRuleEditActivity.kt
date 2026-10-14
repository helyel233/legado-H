package io.legado.app.ui.book.read.config

import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import io.legado.app.R
import io.legado.app.help.book.highlight.HighlightDocument
import io.legado.app.help.book.highlight.HighlightMatcher
import io.legado.app.help.book.highlight.HighlightRule
import io.legado.app.help.book.highlight.HighlightRules
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.reader.ReaderAssetReferences
import io.legado.app.help.reader.ReaderAssets
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppListItem
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream

class HighlightRuleEditActivity : AppCompatActivity() {

    internal var rule by mutableStateOf(HighlightRule())
    internal var cssText by mutableStateOf("")
    internal var sample by mutableStateOf("")
    internal var loaded by mutableStateOf(false)
    internal var saving by mutableStateOf(false)
    internal var selectingAsset by mutableStateOf(false)
    internal var fontLabel by mutableStateOf("")
    internal var imageLabel by mutableStateOf("")
    internal var typeMenuOpen by mutableStateOf(false)
    internal var colorMenuOpen by mutableStateOf(false)

    private var pendingFont: String? = null
    private var pendingImage: String? = null
    private var previewJob: Job? = null
    private var assetLabelJob: Job? = null
    private val editingId get() = intent.getStringExtra("id")
    private val types = listOf("textColor", "background", "underline", "doubleLine", "wavyLine", "strikethrough")
    private val typeLabels = listOf("文字上色", "背景上色", "下划线", "双下划线", "波浪线", "删除线")
    private val colors = listOf("accent", "red", "orange", "yellow", "green", "teal", "blue", "purple", "pink", "brown", "gray")
    private val colorLabels = listOf("阅读强调色", "红色", "橙色", "黄色", "绿色", "青色", "蓝色", "紫色", "粉色", "棕色", "灰色")

    internal val formEnabled: Boolean
        get() = loaded && !saving && !selectingAsset

    internal val typeLabelText: String
        get() = typeLabels.getOrNull(types.indexOf(rule.styleType)) ?: rule.styleType

    internal val colorLabelText: String
        get() = colorLabels.getOrNull(colors.indexOf(rule.styleColorType)) ?: rule.styleColorType

    internal fun typeLabelTexts() = typeLabels

    internal fun typeIndex() = types.indexOf(rule.styleType).coerceAtLeast(0)

    internal fun colorLabelTexts() = colorLabels

    internal fun colorIndex() = colors.indexOf(rule.styleColorType).coerceAtLeast(0)

    internal val webView: WebView by lazy {
        WebView(this).apply {
            settings.apply {
                javaScriptEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val resource = runCatching {
                        if (request.method != "GET" && request.method != "HEAD") null
                        else if (request.url.host == "highlight-preview.epub.local")
                            HighlightRules.resource(request.url.path.orEmpty().removePrefix("/"), request.method == "HEAD")
                        else ReaderAssets.resource(request.url.toString(), request.method == "HEAD")
                    }.getOrNull()
                    return resource?.let {
                        WebResourceResponse(it.mimeType, it.encoding, it.statusCode, it.reasonPhrase, it.headers, it.stream)
                    } ?: WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                }
            }
        }
    }

    private val fontPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingFont = result.data?.getStringExtra("assetId")
            applyAssetSelection()
        }
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingImage = result.data?.getStringExtra("assetId")
            applyAssetSelection()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (pendingFont == null) pendingFont = savedInstanceState?.getString("pendingFont")
        if (pendingImage == null) pendingImage = savedInstanceState?.getString("pendingImage")
        setV5Content { HighlightRuleEditContent(this@HighlightRuleEditActivity) }
        lifecycleScope.launch {
            try {
                val saved = savedInstanceState?.getString("draft")
                rule = if (saved != null) Gson().fromJson(saved, HighlightRule::class.java) else {
                    val id = editingId
                    if (id == null) HighlightRule() else withContext(Dispatchers.IO) {
                        HighlightRules.store.all().find { it.id == id }
                            ?: error("此高亮规则已被删除，请返回管理页刷新")
                    }
                }
                cssText = rule.styleCssText
                sample = savedInstanceState?.getString("sample")
                    ?: getString(R.string.highlight_rule_sample_text)
                loaded = true
                updateAssetLabels()
                applyAssetSelection()
                preview(showError = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toastOnUi(error.localizedMessage)
            }
        }
    }

    internal fun onCssChanged(value: String) {
        cssText = value
        if (loaded) updateAssetLabels()
    }

    internal fun onTypeSelected(index: Int) {
        typeMenuOpen = false
        rule = rule.copy(styleType = types[index])
    }

    internal fun onColorSelected(index: Int) {
        colorMenuOpen = false
        rule = rule.copy(styleColorType = colors[index])
    }

    internal fun launchFontPicker() {
        if (!formEnabled) return
        fontPicker.launch(
            Intent(this, ReaderAssetManageActivity::class.java)
                .putExtra("pickKind", "font")
                .putExtra("selectedId", ReaderAssetReferences.selectedFont(cssText))
        )
    }

    internal fun launchImagePicker() {
        if (!formEnabled) return
        imagePicker.launch(
            Intent(this, ReaderAssetManageActivity::class.java)
                .putExtra("pickKind", "image")
                .putExtra("selectedId", rule.asset)
        )
    }

    private fun updateAssetLabels() {
        assetLabelJob?.cancel()
        val css = cssText
        val fontId = ReaderAssetReferences.selectedFont(css)
        val imageId = rule.asset
        assetLabelJob = lifecycleScope.launch {
            val names = withContext(Dispatchers.IO) {
                runCatching { fontId?.let { ReaderAssets.store.find(it)?.name } to imageId?.let { ReaderAssets.store.find(it)?.name } }
                    .getOrDefault(null to null)
            }
            fontLabel = names.first ?: when {
                fontId != null -> getString(R.string.reader_assets_missing)
                Regex("(?i)font-family\\s*:").containsMatchIn(css) && !css.contains("reeden-font:", true) -> "自定义 CSS 字体"
                else -> getString(R.string.reader_assets_follow_font)
            }
            imageLabel = names.second ?: if (imageId == null) {
                getString(R.string.reader_assets_no_image)
            } else {
                "原规则背景"
            }
        }
    }

    private fun applyAssetSelection() {
        if (!loaded || saving || selectingAsset) return
        pendingFont?.let { id ->
            pendingFont = null
            cssText = ReaderAssetReferences.withFont(cssText, id.ifEmpty { null })
            updateAssetLabels()
            preview(showError = false)
        }
        pendingImage?.let { id ->
            selectingAsset = true
            lifecycleScope.launch {
                try {
                    val asset = withContext(Dispatchers.IO) {
                        if (id.isEmpty()) null else
                            ReaderAssets.store.find(id)?.takeIf { it.kind == "image" }
                                ?: error(getString(R.string.reader_assets_missing))
                    }
                    rule = rule.copy(asset = asset?.id, imageWidth = asset?.width ?: 0, imageHeight = asset?.height ?: 0)
                    pendingImage = null
                    updateAssetLabels()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    toastOnUi(error.localizedMessage)
                } finally {
                    selectingAsset = false
                    if (!isDestroyed) preview(showError = false)
                }
            }
        }
    }

    internal fun draft(): HighlightRule = rule.copy(
        name = rule.name.trim(),
        groupName = rule.groupName.trim(),
        keyword = rule.keyword,
        styleCssText = cssText,
        styleMode = if (cssText.isBlank()) "" else "advanced",
        bookUrl = if (rule.global) null else rule.bookUrl ?: intent.getStringExtra("bookUrl")
    )

    internal fun save() {
        if (!formEnabled) return
        val value = draft()
        val invalid = HighlightMatcher.validationError(value)
        if (value.enabled && invalid != null) { toastOnUi(invalid); return }
        if (!value.global && value.bookUrl == null) { toastOnUi(R.string.highlight_rule_bind_hint); return }
        val warnings = value.importWarning.split("；").filter { it.startsWith("背景图片未导入") } +
            listOfNotNull(invalid?.let { "表达式不可用：" + it },
                if (value.styleCssText.contains("reeden-font:", true)) "未附带 Reeden 字体，使用当前阅读字体" else null)
        saving = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val updated = value.copy(importWarning = warnings.joinToString("；"))
                    val id = editingId
                    if (id == null) HighlightRules.store.put(updated)
                    else HighlightRules.store.update(id) { updated }
                }
                setResult(RESULT_OK)
                finish()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toastOnUi(error.localizedMessage)
            } finally {
                saving = false
            }
        }
    }

    internal fun preview(showError: Boolean = true) {
        if (!formEnabled) return
        previewJob?.cancel()
        val value = draft().copy(enabled = true)
        HighlightMatcher.validationError(value)?.let {
            if (showError) toastOnUi(it)
            return
        }
        val palette = HighlightRules.palette()
        val alignment = if (ReadBookConfig.textFullJustify) "justify" else "start"
        val title = getString(R.string.highlight_rule_sample_title)
        val sampleText = sample
        previewJob = lifecycleScope.launch {
            try {
                val html = withContext(Dispatchers.Default) {
                    val document = Document.createShell("")
                    document.head().appendElement("meta").attr("charset", "UTF-8")
                    document.head().appendElement("meta").attr("name", "viewport").attr("content", "width=device-width,initial-scale=1")
                    document.head().appendElement("style").appendText(
                        "body{--legado-highlight-room-left:20px;--legado-highlight-room-right:20px;" +
                            "font:18px/1.8 serif;padding:12px;overflow-wrap:break-word;text-align:$alignment;" +
                            "color:${palette.text};background:${palette.background}}h2{font-size:1.15em}p{margin:.8em 0}")
                    document.body().appendElement("h2").text(title)
                    sampleText.lineSequence().forEach { document.body().appendElement("p").text(it) }
                    HighlightDocument.apply(document.outerHtml(), listOf(value), palette) {
                        "https://highlight-preview.epub.local/highlight-asset/" + it
                    }
                }
                webView.loadDataWithBaseURL("https://highlight-preview.epub.local/", html, "text/html", "UTF-8", null)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (showError) toastOnUi(error.localizedMessage)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingFont?.let { outState.putString("pendingFont", it) }
        pendingImage?.let { outState.putString("pendingImage", it) }
        if (loaded) {
            outState.putString("draft", Gson().toJson(draft()))
            outState.putString("sample", sample)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        previewJob?.cancel()
        webView.stopLoading()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

}

@Composable
private fun HighlightRuleEditContent(activity: HighlightRuleEditActivity) {
    val density = LocalVisualSpec.current.density
    val enabled = activity.formEnabled

    HostV5Page(
        title = stringResource(R.string.highlight_rule_edit),
        onBack = { activity.finish() },
        trailing = {
            Row {
                AppFilledButton(
                    text = stringResource(R.string.action_save),
                    enabled = enabled,
                    onClick = { activity.save() },
                )
                AppOverflowMenuButton(
                    contentDescription = stringResource(R.string.highlight_rule_preview),
                    glyph = "▶",
                    onClick = { activity.preview() },
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
        ) {
            AppTextField(
                value = activity.rule.name,
                onValueChange = { activity.rule = activity.rule.copy(name = it) },
                placeholder = "名称",
                enabled = enabled,
                singleLine = true,
            )
            AppTextField(
                value = activity.rule.groupName,
                onValueChange = { activity.rule = activity.rule.copy(groupName = it) },
                placeholder = stringResource(R.string.highlight_rule_group),
                enabled = enabled,
                singleLine = true,
            )
            AppTextField(
                value = activity.rule.keyword,
                onValueChange = { activity.rule = activity.rule.copy(keyword = it) },
                placeholder = stringResource(R.string.highlight_rule_keyword),
                enabled = enabled,
                singleLine = false,
            )
            AppListItem(
                headline = stringResource(R.string.highlight_rule_type),
                supporting = activity.typeLabelText,
                card = false,
                onClick = { if (enabled) activity.typeMenuOpen = true },
            )
            AppListItem(
                headline = stringResource(R.string.highlight_rule_color),
                supporting = activity.colorLabelText,
                card = false,
                onClick = { if (enabled) activity.colorMenuOpen = true },
            )
            AppListItem(
                headline = stringResource(R.string.reader_assets_font_label),
                supporting = activity.fontLabel.ifEmpty {
                    stringResource(R.string.reader_assets_follow_font)
                },
                card = false,
                onClick = { activity.launchFontPicker() },
            )
            AppListItem(
                headline = stringResource(R.string.reader_assets_background_label),
                supporting = activity.imageLabel.ifEmpty {
                    stringResource(R.string.reader_assets_no_image)
                },
                card = false,
                onClick = { activity.launchImagePicker() },
            )
            AppRowWithSwitch(
                title = stringResource(R.string.enable),
                checked = activity.rule.enabled,
                onCheckedChange = { activity.rule = activity.rule.copy(enabled = it) },
                card = false,
                enabled = enabled,
            )
            AppRowWithSwitch(
                title = stringResource(R.string.highlight_rule_global),
                checked = activity.rule.global,
                onCheckedChange = { activity.rule = activity.rule.copy(global = it) },
                card = false,
                enabled = enabled,
            )
            AppRowWithSwitch(
                title = stringResource(R.string.highlight_rule_regex),
                checked = activity.rule.isRegex,
                onCheckedChange = { activity.rule = activity.rule.copy(isRegex = it) },
                card = false,
                enabled = enabled,
            )
            AppRowWithSwitch(
                title = stringResource(R.string.highlight_rule_multiline),
                checked = activity.rule.isMultiline,
                onCheckedChange = { activity.rule = activity.rule.copy(isMultiline = it) },
                card = false,
                enabled = enabled,
            )
            AppRowWithSwitch(
                title = stringResource(R.string.highlight_rule_title),
                checked = activity.rule.titleOnly,
                onCheckedChange = { activity.rule = activity.rule.copy(titleOnly = it) },
                card = false,
                enabled = enabled,
            )
            AppRowWithSwitch(
                title = stringResource(R.string.highlight_rule_styled),
                checked = activity.rule.applyToStyledBooks,
                onCheckedChange = { activity.rule = activity.rule.copy(applyToStyledBooks = it) },
                card = false,
                enabled = enabled,
            )
            AppTextField(
                value = activity.cssText,
                onValueChange = { activity.onCssChanged(it) },
                placeholder = stringResource(R.string.highlight_rule_css),
                enabled = enabled,
                singleLine = false,
                minLines = 2,
            )
            AppTextField(
                value = activity.sample,
                onValueChange = { activity.sample = it },
                placeholder = stringResource(R.string.highlight_rule_sample),
                enabled = enabled,
                singleLine = false,
                minLines = 2,
            )
            Text(
                text = stringResource(R.string.highlight_rule_preview),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AndroidView(
                factory = { activity.webView },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
            )
        }
    }

    if (activity.typeMenuOpen) {
        AppFilterDialog(
            title = stringResource(R.string.highlight_rule_type),
            options = activity.typeLabelTexts(),
            selectedIndex = activity.typeIndex(),
            onSelect = { activity.onTypeSelected(it) },
            onDismiss = { activity.typeMenuOpen = false },
        )
    }

    if (activity.colorMenuOpen) {
        AppFilterDialog(
            title = stringResource(R.string.highlight_rule_color),
            options = activity.colorLabelTexts(),
            selectedIndex = activity.colorIndex(),
            onSelect = { activity.onColorSelected(it) },
            onDismiss = { activity.colorMenuOpen = false },
        )
    }
}
