package io.legado.app.receiver

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.help.book.TextBookImporter
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.host.HostSearchActivity
import io.legado.app.ui.host.AppHostActivity
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SharedReceiverActivity : AppCompatActivity() {

    private val receivingType = "text/plain"

    // ── V5 弹窗状态 ─────────────────────────────────────────────
    private var textToBookTarget by mutableStateOf<TextToBookRequest?>(null)

    private data class TextToBookRequest(val text: String, val bookName: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { SharedReceiverDialogs() }
        // 文本成书弹窗展示时不能立即 finish，否则 dialog 随之销毁
        if (!initIntent()) {
            finish()
        }
    }

    @Composable
    private fun SharedReceiverDialogs() {
        textToBookTarget?.let { req ->
            AppConfirmDialog(
                title = stringResource(R.string.text_to_book),
                message = stringResource(
                    R.string.text_to_book_message,
                    req.text.length,
                    req.bookName
                ),
                confirmLabel = stringResource(R.string.text_to_book_confirm),
                cancelLabel = stringResource(R.string.cancel),
                onConfirm = {
                    textToBookTarget = null
                    importingAsBook = true
                    importAsBook(req.text, req.bookName)
                },
                onDismiss = {
                    textToBookTarget = null
                    if (!importingAsBook) {
                        finish()
                    }
                },
            )
        }
    }

    /**
     * @return true 表示已弹出待交互的对话框，由后续流程负责 finish
     */
    @SuppressLint("ObsoleteSdkInt")
    private fun initIntent(): Boolean {
        when {
            intent.action == Intent.ACTION_SEND && intent.type == receivingType -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                    return dispose(it)
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && intent.action == Intent.ACTION_PROCESS_TEXT
                    && intent.type == receivingType -> {
                intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)?.let {
                    return dispose(it)
                }
            }
            intent.getStringExtra("action") == "readAloud" -> {
                MediaButtonReceiver.readAloud(appCtx, false)
            }
        }
        return false
    }

    private fun dispose(text: String): Boolean {
        if (text.isBlank()) {
            return false
        }
        val urls = text.split("\\s".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val result = StringBuilder()
        for (url in urls) {
            if (url.matches("http.+".toRegex()))
                result.append("\n").append(url.trim { it <= ' ' })
        }
        if (result.length > 1) {
            startActivity<AppHostActivity>()
            return false
        }
        if (text.length >= textToBookMinLength) {
            showImportAsBookDialog(text)
            return true
        }
        HostSearchActivity.start(this, text)
        return false
    }

    private var importingAsBook = false

    private fun showImportAsBookDialog(text: String) {
        val firstLine = text.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val defaultName = firstLine.takeIf { it.isNotBlank() && it.length <= 30 }
            ?: "分享文本_${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}"
        textToBookTarget = TextToBookRequest(text, defaultName)
    }

    private fun importAsBook(text: String, bookName: String) {
        lifecycleScope.launch {
            val book = withContext(IO) {
                TextBookImporter.import(text, bookName)
            }
            if (book != null) {
                toastOnUi(R.string.text_to_book_done)
                startActivity<ReadBookActivity> {
                    putExtra("bookUrl", book.bookUrl)
                }
            } else {
                toastOnUi(R.string.text_to_book_failed)
            }
            finish()
        }
    }

    companion object {
        /** 非链接文本达到该长度时提供存为本地书籍 */
        const val textToBookMinLength = 300
    }
}