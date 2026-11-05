package io.legado.app.ui.config

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAiWorldBookManageBinding
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.importHttpClient as okHttpClient
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.ai.compose.AiWorldBookImportPayload
import io.legado.app.ui.main.ai.compose.AiWorldBookManageRoute
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.readText
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.legado.app.R
import splitties.init.appCtx

class AiWorldBookManageActivity : BaseActivity<ActivityAiWorldBookManageBinding>(
    fullScreen = false,
    imageBg = false
) {

    override val binding by viewBinding(ActivityAiWorldBookManageBinding::inflate)
    private var importPayload by mutableStateOf<AiWorldBookImportPayload?>(null)
    private var importRequestId = 0L
    private val importWorldBook = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            // Activity 结果回调在主线程，读文件放 IO，处理回主线程
            lifecycleScope.launch(Dispatchers.IO) {
                val text = kotlin.runCatching {
                    uri.readText(this@AiWorldBookManageActivity)
                }.getOrElse { error ->
                    toastOnUi(error.localizedMessage ?: appCtx.getString(R.string.ai_ui_failed_read_world_book_file))
                    return@launch
                }
                withContext(Dispatchers.Main) { emitImportPayload(text) }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeRoot.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeRoot.setContent {
            AiWorldBookManageRoute(
                initialTargetType = intent.getStringExtra(EXTRA_TARGET_TYPE).orEmpty(),
                initialTargetKey = intent.getStringExtra(EXTRA_TARGET_KEY).orEmpty(),
                importPayload = importPayload,
                onImportConsumed = { importPayload = null },
                onRequestImportLocal = ::launchImportFile,
                onRequestImportNetwork = ::showImportUrlDialog,
                onBack = ::finish
            )
        }
    }

    private fun launchImportFile() {
        importWorldBook.launch {
            mode = HandleFileContract.FILE
            title = appCtx.getString(R.string.ai_ui_import_world_book)
            allowExtensions = arrayOf("json")
        }
    }

    private fun showImportUrlDialog() {
        showComposeTextInputDialog(
            title = appCtx.getString(R.string.ai_ui_import_world_book_from_network),
            hint = "https://...",
            onPositive = { value ->
                val url = value.trim()
                if (url.isNotEmpty()) importWorldBookFromUrl(url)
            }
        )
    }

    private fun importWorldBookFromUrl(url: String) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    okHttpClient.newCallResponseBody { url(url) }.use { it.string() }
                }
            }.onSuccess(::emitImportPayload)
                .onFailure {
                    toastOnUi(it.localizedMessage ?: appCtx.getString(R.string.ai_ui_world_book_download_failed))
                }
        }
    }

    private fun emitImportPayload(raw: String) {
        if (raw.isBlank()) {
            toastOnUi(appCtx.getString(R.string.ai_ui_world_book_empty))
            return
        }
        importPayload = AiWorldBookImportPayload(++importRequestId, raw)
    }

    companion object {
        const val EXTRA_TARGET_TYPE = "targetType"
        const val EXTRA_TARGET_KEY = "targetKey"
    }
}
