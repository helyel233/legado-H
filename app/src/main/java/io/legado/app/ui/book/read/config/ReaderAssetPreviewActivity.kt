package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.text.format.Formatter
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import io.legado.app.R
import io.legado.app.help.reader.ReaderAssetReferences
import io.legado.app.help.reader.ReaderAssets
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream

/**
 * 素材预览 V5 化：BaseActivity + ViewBinding + TitleBar → AppCompatActivity +
 * setV5Content + HostV5Page（标题随素材名）+ AndroidView 承载 WebView。
 * 资源拦截、离线策略、字体样张生成逻辑逐行保留。
 */
class ReaderAssetPreviewActivity : AppCompatActivity() {

    internal var titleText by mutableStateOf("")
    internal var infoText by mutableStateOf("")

    internal val webView: WebView by lazy {
        WebView(this).apply {
            settings.apply {
                javaScriptEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse {
                    val resource = if (request.method == "GET" || request.method == "HEAD") runCatching {
                        ReaderAssets.resource(request.url.toString(), request.method == "HEAD")
                    }.getOrNull() else null
                    return resource?.let {
                        WebResourceResponse(
                            it.mimeType, it.encoding, it.statusCode, it.reasonPhrase, it.headers, it.stream
                        )
                    } ?: WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ) = true
            }
        }
    }

// __PART2__

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { ReaderAssetPreviewContent(this@ReaderAssetPreviewActivity) }
        lifecycleScope.launch {
            try {
                val asset = withContext(Dispatchers.IO) {
                    val id = intent.getStringExtra("assetId").orEmpty()
                    ReaderAssets.store.find(id)?.also { ReaderAssets.store.verifiedBytes(id) }
                        ?: error(getString(R.string.reader_assets_missing))
                }
                titleText = asset.name
                infoText = listOf(
                    asset.extension.uppercase(),
                    Formatter.formatFileSize(this@ReaderAssetPreviewActivity, asset.size),
                    if (asset.kind == "image") "${asset.width} × ${asset.height}"
                    else getString(R.string.reader_assets_fonts)
                ).joinToString(" · ")
                val doc = Document.createShell("")
                doc.head().appendElement("meta").attr("name", "viewport")
                    .attr("content", "width=device-width,initial-scale=1")
                val family = ReaderAssetReferences.fontFamily(asset.id)
                val background = "#%06x".format(themeCardColorOrDefault() and 0xffffff)
                val foreground = "#%06x".format(primaryTextColor and 0xffffff)
                doc.head().appendElement("style").appendText(
                    "html,body{margin:0;min-height:100%;box-sizing:border-box;}body{padding:24px;background:$background;color:$foreground;}" +
                        "img{display:block;max-width:100%;max-height:80vh;margin:auto;object-fit:contain;}" +
                        ReaderAssetReferences.fontCss(family) +
                        "p{font-family:'$family',serif;font-size:24px;line-height:1.8;white-space:pre-wrap;}"
                )
                if (asset.kind == "image") {
                    doc.body().appendElement("img")
                        .attr("src", ReaderAssetReferences.url(asset.id))
                        .attr("alt", asset.name)
                } else {
                    doc.body().appendElement("p").text(getString(R.string.reader_assets_font_sample))
                }
                webView.loadDataWithBaseURL(
                    "https://reader-assets-preview.epub.local/",
                    doc.outerHtml(), "text/html", "UTF-8", null
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toastOnUi(error.localizedMessage)
                finish()
            }
        }
    }

    override fun onDestroy() {
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}

@Composable
private fun ReaderAssetPreviewContent(activity: ReaderAssetPreviewActivity) {
    val density = LocalVisualSpec.current.density

    HostV5Page(
        title = activity.titleText.ifEmpty { stringResource(R.string.reader_assets_title) },
        onBack = { activity.finish() },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (activity.infoText.isNotEmpty()) {
                Text(
                    text = activity.infoText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = density.screenPadding,
                            vertical = density.blockGap / 2,
                        ),
                )
            }
            AndroidView(
                factory = { activity.webView },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}
