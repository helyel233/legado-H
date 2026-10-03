package io.legado.app.ui.book.cache

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityBookCacheExportBinding
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 书籍缓存单独导出（移植自 Legado_Max）
 */
class BookCacheExportActivity :
    BaseActivity<ActivityBookCacheExportBinding>() {

    override val binding by viewBinding(ActivityBookCacheExportBinding::inflate)
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

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeView.setContent {
            LegadoComposeTheme {
                val items by viewModel.items.collectAsState()
                val state by viewModel.state.collectAsState()
                BookCacheExportScreen(
                    items = items,
                    state = state,
                    onToggle = { viewModel.toggleSelect(it.book) },
                    onToggleAll = { viewModel.setAllSelected(!viewModel.isAllSelected()) },
                    onExport = { selectDir.launch(null) },
                    onImport = {
                        selectZip.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }
}
