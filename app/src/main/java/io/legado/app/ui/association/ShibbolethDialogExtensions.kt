package io.legado.app.ui.association

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.help.DirectLinkUpload
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.ShibbolethCodec
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

fun AppCompatActivity.showShibbolethDialog(url: String, type: String) {
    val code = createShibboleth(url, type) ?: return
    showDialogFragment(ShibbolethDialog(code))
}

fun Fragment.showShibbolethDialog(url: String, type: String) {
    val code = createShibboleth(url, type) ?: return
    showDialogFragment(ShibbolethDialog(code))
}

private fun AppCompatActivity.createShibboleth(url: String, type: String): String? {
    return ShibbolethCodec.encode(
        url = url,
        type = type,
        expiryDays = DirectLinkUpload.getExpiryDate()
    ).getOrElse {
        toastOnUi(R.string.shibboleth_https_only)
        null
    }
}

private fun Fragment.createShibboleth(url: String, type: String): String? {
    return ShibbolethCodec.encode(
        url = url,
        type = type,
        expiryDays = DirectLinkUpload.getExpiryDate()
    ).getOrElse {
        toastOnUi(R.string.shibboleth_https_only)
        null
    }
}

internal class ShibbolethDialog(private val code: String) : V5DialogFragment() {

    @Composable
    override fun Content() {
        val density = LocalVisualSpec.current.density
        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                Text(
                    text = stringResource(R.string.shibboleth),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SelectionContainer {
                    Text(
                        text = code,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppFilledButton(
                    text = stringResource(R.string.copy_text),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        dismissAllowingStateLoss()
                        requireContext().sendToClip(code)
                    },
                )
            }
        }
    }
}
