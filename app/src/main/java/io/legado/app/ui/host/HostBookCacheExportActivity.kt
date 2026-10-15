package io.legado.app.ui.host

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.ui.book.cache.BookCacheExportScreen
import io.legado.app.ui.book.cache.BookCacheExportViewModel
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.utils.isContentScheme

/**
 * 书籍缓存单独导出（V5 换壳）。
 *
 * 内容 UI 本就是 Compose（[BookCacheExportScreen]），导出/导入的两个文件选择器属于
 * ActivityResult 契约，必须留在宿主上，故只换壳、不动业务。
 */
class HostBookCacheExportActivity : AppCompatActivity() {

    private val viewModel by viewModels<BookCacheExportViewModel>()

    private val selectDir = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            if (it.isContentScheme()) {
                val modeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { contentResolver.takePersistableUriPermission(it, modeFlags) }
            }
            viewModel.exportSelectedBooks(it)
        }
    }

    private val selectZip = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            if (it.isContentScheme()) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            viewModel.importFromZip(this, it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostBookCacheExportScreen() }
    }

    @Composable
    private fun HostBookCacheExportScreen() {
        val activity = LocalContext.current as AppCompatActivity
        val items by viewModel.items.collectAsStateWithLifecycle()
        val state by viewModel.state.collectAsStateWithLifecycle()

        HostV5Page(
            title = stringResource(R.string.export_book),
            subtitle = stringResource(R.string.cache_export),
            onBack = { activity.finish() },
        ) {
            LegadoComposeTheme {
                BookCacheExportScreen(
                    items = items,
                    state = state,
                    onToggle = { viewModel.toggleSelect(it.book) },
                    onToggleAll = { viewModel.setAllSelected(!viewModel.isAllSelected()) },
                    onExport = { selectDir.launch(null) },
                    onImport = {
                        selectZip.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    onDismiss = { activity.finish() },
                )
            }
        }
    }
}
