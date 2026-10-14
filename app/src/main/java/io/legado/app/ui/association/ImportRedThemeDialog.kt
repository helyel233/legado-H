package io.legado.app.ui.association

import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.ImportResult
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 红色主题包导入（V5 基座版）。
 *
 * 构造函数入口与 dismiss 后 finish 语义保持不变；内容由旧 AppDialogFrame 桥换 V5 AppDialog。
 */
internal class ImportRedThemeDialog() : V5DialogFragment() {

    constructor(uri: Uri, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putParcelable("uri", uri)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int = 660

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (arguments?.getBoolean("finishOnDismiss") == true) {
            activity?.finish()
        }
    }

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        var message by remember { mutableStateOf(getString(R.string.loading)) }
        var finished by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            val uri = arguments?.getParcelable<Uri>("uri")
            if (uri == null) {
                message = getString(R.string.wrong_format)
                finished = true
                return@LaunchedEffect
            }
            runCatching {
                importRedTheme(uri)
            }.onSuccess { result ->
                message = "${getString(R.string.import_str)}: ${result.total}"
            }.onFailure {
                message = it.localizedMessage ?: getString(R.string.wrong_format)
            }
            finished = true
        }

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(visual.density.blockGap),
            ) {
                Text(
                    text = stringResource(R.string.import_theme),
                    style = visual.type.title,
                    color = colors.onSurface,
                )
                Text(
                    text = message,
                    style = visual.type.body,
                    color = colors.onSurfaceVariant,
                )
                AppFilledButton(
                    text = stringResource(if (finished) R.string.confirm else R.string.cancel),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { dismissAllowingStateLoss() },
                )
            }
        }
    }

    private suspend fun importRedTheme(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val tempDir = requireContext().externalFiles.getFile("themePackageImports").apply { mkdirs() }
        val file = File(tempDir, "import_${System.currentTimeMillis()}.red")
        try {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException(getString(R.string.wrong_format))
            AppearanceKitManager.importPackage(file)
        } finally {
            file.delete()
        }
    }
}
