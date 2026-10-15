package io.legado.app.ui.host

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.legado.app.ui.widget.dialog.DismissWhenCallbackMissing
import io.legado.app.ui.widget.dialog.rememberV5DialogColors

/**
 * 模型选择弹窗（V5 基座版）。
 * 尺寸：旧 AppDialogSize.Management → widthFraction 0.96f / maxWidthDp 700。
 * 内容沿用 FetchedModelSelectorContent（同文件，随 V5 壳渲染）。
 * 行为保留：`onAddSingle == null` 时自动 dismiss（DismissWhenCallbackMissing）。
 */
internal class ComposeFetchedModelDialog : V5DialogFragment() {

    override val widthFraction: Float = 0.96f
    override val maxWidthDp: Int = 700

    private var onAddSingle: ((String) -> Unit)? = null
    private var onAddSelected: ((List<String>) -> Unit)? = null
    private var onAddAll: ((List<String>) -> Unit)? = null

    @Composable
    override fun Content() {
        DismissWhenCallbackMissing(
            missing = onAddSingle == null,
            dismiss = ::dismissAllowingStateLoss
        )
        val args = arguments ?: Bundle()
        val modelIds = remember {
            args.getStringArrayList(ARG_MODEL_IDS)?.toList().orEmpty()
        }
        val existingIds = remember {
            args.getStringArrayList(ARG_EXISTING_IDS)?.toSet().orEmpty()
        }
        FetchedModelSelectorContent(
            modelIds = modelIds,
            existingIds = existingIds,
            onAddSingle = { modelId ->
                dismissAllowingStateLoss()
                onAddSingle?.invoke(modelId)
            },
            onAddSelected = { selectedIds ->
                dismissAllowingStateLoss()
                onAddSelected?.invoke(selectedIds)
            },
            onAddAll = { allIds ->
                dismissAllowingStateLoss()
                onAddAll?.invoke(allIds)
            },
            onDismiss = { dismissAllowingStateLoss() }
        )
    }

    companion object {
        fun create(
            modelIds: List<String>,
            existingIds: Set<String>,
            onAddSingle: (String) -> Unit,
            onAddSelected: (List<String>) -> Unit,
            onAddAll: (List<String>) -> Unit
        ): ComposeFetchedModelDialog {
            return ComposeFetchedModelDialog().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_MODEL_IDS, ArrayList(modelIds))
                    putStringArrayList(ARG_EXISTING_IDS, ArrayList(existingIds))
                }
                this.onAddSingle = onAddSingle
                this.onAddSelected = onAddSelected
                this.onAddAll = onAddAll
            }
        }

        private const val ARG_MODEL_IDS = "modelIds"
        private const val ARG_EXISTING_IDS = "existingIds"
    }
}
