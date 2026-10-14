package io.legado.app.ui.widget.dialog

import android.app.Application
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec

/**
 * 变量编辑弹窗（V5 基座版）。
 *
 * 参数入口与 [Callback] 契约保持不变，调用方零改动；
 * 内容由旧 AppDialogFrame/LegadoMiuix 桥换为 V5 AppDialog 组件。
 */
internal class VariableDialog() : V5DialogFragment() {

    constructor(title: String, key: String, variable: String?, comment: String) : this() {
        arguments = Bundle().apply {
            putString("title", title)
            putString("key", key)
            putString("variable", variable)
            putString("comment", comment)
        }
    }

    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int = 660

    val callback get() = (parentFragment as? Callback) ?: (activity as? Callback)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (arguments == null) {
            dismissAllowingStateLoss()
        }
    }

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val args = arguments ?: return
        val key = args.getString("key").orEmpty()
        val title = args.getString("title").orEmpty()
        val comment = args.getString("comment").orEmpty()
        var variable by rememberSaveable {
            mutableStateOf(args.getString("variable").orEmpty())
        }

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(visual.density.blockGap / 2),
            ) {
                Text(
                    text = title,
                    style = visual.type.title,
                    color = colors.onSurface,
                )
                if (comment.isNotBlank()) {
                    Text(
                        text = comment,
                        style = visual.type.caption,
                        color = colors.onSurfaceVariant,
                    )
                }
                AppTextField(
                    value = variable,
                    onValueChange = { variable = it },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(visual.density.blockGap / 2)) {
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { dismissAllowingStateLoss() },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.ok),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            callback?.setVariable(key, variable)
                            dismissAllowingStateLoss()
                        },
                    )
                }
            }
        }
    }

    class ViewModel(application: Application) : BaseViewModel(application)

    interface Callback {

        fun setVariable(key: String, variable: String?)
    }
}
