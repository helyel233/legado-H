package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.help.http.importHttpClient as okHttpClient
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.ai.compose.AiWorldBookImportPayload
import io.legado.app.ui.main.ai.compose.AiWorldBookManageRoute
import io.legado.app.uikit.facade.AppToast
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.utils.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 世界书管理（V5 壳）。
 * 自旧 AiWorldBookManageActivity（BaseActivity + ViewBinding 薄壳）迁来：
 * 渲染仍复用自包含的 [AiWorldBookManageRoute]（零拷贝），外壳换 setV5Content，
 * 网络导入弹窗由旧 showComposeTextInputDialog 换 V5 AppInputDialog。
 */
class HostAiWorldBookManageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostAiWorldBookManageScreen() }
    }

    companion object {
        const val EXTRA_TARGET_TYPE = "targetType"
        const val EXTRA_TARGET_KEY = "targetKey"

        fun start(context: Context, targetType: String = "", targetKey: String = "") {
            context.startActivity(
                Intent(context, HostAiWorldBookManageActivity::class.java)
                    .putExtra(EXTRA_TARGET_TYPE, targetType)
                    .putExtra(EXTRA_TARGET_KEY, targetKey)
            )
        }
    }
}

@Composable
private fun HostAiWorldBookManageScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val scope = activity.lifecycleScope

    var importPayload by remember { mutableStateOf<AiWorldBookImportPayload?>(null) }
    var importRequestId by remember { mutableStateOf(0L) }
    var importUrlDialog by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }

    fun emitImportPayload(raw: String) {
        if (raw.isBlank()) {
            AppToast.show("世界书内容为空")
            return
        }
        importPayload = AiWorldBookImportPayload(++importRequestId, raw)
    }

    val importWorldBook = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            scope.launch {
                runCatching { uri.readText(context) }
                    .onSuccess(::emitImportPayload)
                    .onFailure { AppToast.show(it.localizedMessage ?: "世界书文件读取失败") }
            }
        }
    }

    fun launchImportFile() {
        importWorldBook.launch {
            mode = HandleFileContract.FILE
            title = "导入世界书"
            allowExtensions = arrayOf("json")
        }
    }

    fun importWorldBookFromUrl(url: String) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    okHttpClient.newCallResponseBody { url(url) }.use { it.string() }
                }
            }.onSuccess(::emitImportPayload)
                .onFailure { AppToast.show(it.localizedMessage ?: "世界书下载失败") }
        }
    }

    AiWorldBookManageRoute(
        initialTargetType = activity.intent.getStringExtra(HostAiWorldBookManageActivity.EXTRA_TARGET_TYPE).orEmpty(),
        initialTargetKey = activity.intent.getStringExtra(HostAiWorldBookManageActivity.EXTRA_TARGET_KEY).orEmpty(),
        importPayload = importPayload,
        onImportConsumed = { importPayload = null },
        onRequestImportLocal = ::launchImportFile,
        onRequestImportNetwork = { importUrlDialog = true },
        onBack = { activity.finish() },
    )

    if (importUrlDialog) {
        AppInputDialog(
            title = "网络导入世界书",
            value = importUrl,
            onValueChange = { importUrl = it },
            confirmLabel = activity.getString(R.string.ok),
            cancelLabel = activity.getString(R.string.cancel),
            placeholder = "https://...",
            onConfirm = { text ->
                importUrlDialog = false
                val url = text.trim()
                if (url.isNotEmpty()) importWorldBookFromUrl(url)
                importUrl = ""
            },
            onDismiss = {
                importUrlDialog = false
                importUrl = ""
            },
        )
    }
}
