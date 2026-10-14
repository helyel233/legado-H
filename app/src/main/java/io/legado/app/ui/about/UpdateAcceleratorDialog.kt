package io.legado.app.ui.about

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.help.update.AppUpdateConfig
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppListItem
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.showDialogFragment

object UpdateAcceleratorDialog {

    fun show(fragment: Fragment, onChanged: () -> Unit) {
        // 更新通道固定为 GitHub Releases 单通道，此处仅管理 GitHub 加速代理
        fragment.showDialogFragment(
            UpdateAcceleratorComposeDialog.create(
                initialProxyTemplates = AppUpdateConfig.githubProxyTemplates.toList(),
                initialProxyIndex = AppUpdateConfig.githubProxyIndex,
                onProxySelected = { index ->
                    AppUpdateConfig.githubProxyIndex = index
                    onChanged()
                },
                onProxyAdded = { value ->
                    val proxies = AppUpdateConfig.githubProxyTemplates.toMutableList()
                    proxies += value
                    AppUpdateConfig.githubProxyTemplates = proxies
                    AppUpdateConfig.githubProxyIndex = AppUpdateConfig.githubProxyTemplates.indexOf(value)
                    onChanged()
                },
                onProxyEdited = { index, value ->
                    val proxies = AppUpdateConfig.githubProxyTemplates.toMutableList()
                    proxies[index] = value
                    AppUpdateConfig.githubProxyTemplates = proxies
                    AppUpdateConfig.githubProxyIndex = AppUpdateConfig.githubProxyTemplates.indexOf(value)
                    onChanged()
                },
                onProxyDeleted = { index ->
                    val proxies = AppUpdateConfig.githubProxyTemplates.toMutableList()
                    if (index in proxies.indices) {
                        proxies.removeAt(index)
                        AppUpdateConfig.githubProxyTemplates = proxies
                        AppUpdateConfig.githubProxyIndex = -1
                        onChanged()
                    }
                },
                onDismissed = {
                    onChanged()
                }
            )
        )
    }

    /** V5 页（AppCompatActivity）复用同一对话框（2026-10-12 关于页迁移）。 */
    fun show(activity: androidx.appcompat.app.AppCompatActivity, onChanged: () -> Unit) {
        UpdateAcceleratorComposeDialog.create(
            initialProxyTemplates = AppUpdateConfig.githubProxyTemplates.toList(),
            initialProxyIndex = AppUpdateConfig.githubProxyIndex,
            onProxySelected = { index ->
                AppUpdateConfig.githubProxyIndex = index
                onChanged()
            },
            onProxyAdded = { value ->
                val proxies = AppUpdateConfig.githubProxyTemplates.toMutableList()
                proxies += value
                AppUpdateConfig.githubProxyTemplates = proxies
                AppUpdateConfig.githubProxyIndex = AppUpdateConfig.githubProxyTemplates.indexOf(value)
                onChanged()
            },
            onProxyEdited = { index, value ->
                val proxies = AppUpdateConfig.githubProxyTemplates.toMutableList()
                proxies[index] = value
                AppUpdateConfig.githubProxyTemplates = proxies
                AppUpdateConfig.githubProxyIndex = AppUpdateConfig.githubProxyTemplates.indexOf(value)
                onChanged()
            },
            onProxyDeleted = { index ->
                val proxies = AppUpdateConfig.githubProxyTemplates.toMutableList()
                if (index in proxies.indices) {
                    proxies.removeAt(index)
                    AppUpdateConfig.githubProxyTemplates = proxies
                    AppUpdateConfig.githubProxyIndex = -1
                    onChanged()
                }
            },
            onDismissed = { onChanged() },
        ).show(activity.supportFragmentManager, "updateAccelerator")
    }
}

internal class UpdateAcceleratorComposeDialog : V5DialogFragment() {

    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int = 660

    private var onProxySelected: ((Int) -> Unit)? = null
    private var onProxyAdded: ((String) -> Unit)? = null
    private var onProxyEdited: ((Int, String) -> Unit)? = null
    private var onProxyDeleted: ((Int) -> Unit)? = null
    private var onDismissed: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proxyTemplates = arguments?.getStringArrayList(ARG_PROXY_TEMPLATES)?.toList().orEmpty()
        selectedProxyIndex = arguments?.getInt(ARG_PROXY_INDEX) ?: -1
    }

    @Composable
    override fun Content() {
        val density = LocalVisualSpec.current.density

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                Text(
                    text = "加速管理",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "代理模板支持 \${url} 占位符。未填写占位符时，会自动把原始 GitHub 地址拼到代理地址后面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 4),
                ) {
                    item(key = "proxy_none") {
                        ProxyRow(text = "不使用代理", selected = selectedProxyIndex < 0) {
                            selectedProxyIndex = -1
                            onProxySelected?.invoke(-1)
                        }
                    }
                    itemsIndexed(
                        proxyTemplates,
                        key = { index, _ -> "proxy_$index" }
                    ) { index, template ->
                        ProxyRow(text = template, selected = selectedProxyIndex == index) {
                            selectedProxyIndex = index
                            onProxySelected?.invoke(index)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    AppGhostButton(
                        text = "添加代理",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showProxyEditDialog(oldValue = null) { value ->
                                proxyTemplates = proxyTemplates + value
                                selectedProxyIndex = proxyTemplates.indexOf(value)
                                onProxyAdded?.invoke(value)
                            }
                        },
                    )
                    AppGhostButton(
                        text = "编辑",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val index = selectedProxyIndex
                            val oldValue = proxyTemplates.getOrNull(index) ?: return@AppGhostButton
                            showProxyEditDialog(oldValue = oldValue) { value ->
                                proxyTemplates = proxyTemplates.toMutableList().apply {
                                    set(index, value)
                                }
                                selectedProxyIndex = proxyTemplates.indexOf(value)
                                onProxyEdited?.invoke(index, value)
                            }
                        },
                    )
                    AppGhostButton(
                        text = "删除",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val index = selectedProxyIndex
                            if (index !in proxyTemplates.indices) return@AppGhostButton
                            proxyTemplates = proxyTemplates.toMutableList().apply {
                                removeAt(index)
                            }
                            selectedProxyIndex = -1
                            onProxyDeleted?.invoke(index)
                        },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.ok),
                        modifier = Modifier.weight(1f),
                        onClick = { dismissAllowingStateLoss() },
                    )
                }
            }
        }

        if (proxyEditOpen) {
            AppInputDialog(
                title = if (proxyEditOldValue == null) "添加代理" else "编辑代理",
                value = proxyEditText,
                onValueChange = { proxyEditText = it },
                placeholder = "https://proxy.example/\${url}",
                confirmLabel = "确定",
                cancelLabel = "取消",
                onConfirm = { onProxyEditConfirmed(proxyEditText) },
                onDismiss = { proxyEditOpen = false },
            )
        }
    }

    @Composable
    private fun ProxyRow(text: String, selected: Boolean, onClick: () -> Unit) {
        AppListItem(
            headline = if (selected) "✓ $text" else text,
            card = false,
            onClick = onClick,
        )
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissed?.invoke()
    }

    private var proxyTemplates by mutableStateOf<List<String>>(emptyList())
    private var selectedProxyIndex by mutableIntStateOf(-1)
    private var proxyEditOpen by mutableStateOf(false)
    private var proxyEditOldValue by mutableStateOf<String?>(null)
    private var proxyEditText by mutableStateOf("")
    private var proxyEditOnSaved by mutableStateOf<((String) -> Unit)?>(null)

    private fun showProxyEditDialog(
        oldValue: String?,
        onSaved: (String) -> Unit
    ) {
        proxyEditOldValue = oldValue
        proxyEditText = oldValue.orEmpty()
        proxyEditOnSaved = onSaved
        proxyEditOpen = true
    }

    private fun onProxyEditConfirmed(value: String) {
        proxyEditOpen = false
        val trimmed = value.trim()
        if (trimmed.isNotBlank()) {
            proxyEditOnSaved?.invoke(trimmed)
        }
        proxyEditOnSaved = null
    }

    companion object {
        fun create(
            initialProxyTemplates: List<String>,
            initialProxyIndex: Int,
            onProxySelected: (Int) -> Unit,
            onProxyAdded: (String) -> Unit,
            onProxyEdited: (Int, String) -> Unit,
            onProxyDeleted: (Int) -> Unit,
            onDismissed: () -> Unit
        ): UpdateAcceleratorComposeDialog {
            return UpdateAcceleratorComposeDialog().apply {
                arguments = Bundle().apply {
                    putStringArrayList(
                        ARG_PROXY_TEMPLATES,
                        ArrayList(initialProxyTemplates)
                    )
                    putInt(ARG_PROXY_INDEX, initialProxyIndex)
                }
                this.onProxySelected = onProxySelected
                this.onProxyAdded = onProxyAdded
                this.onProxyEdited = onProxyEdited
                this.onProxyDeleted = onProxyDeleted
                this.onDismissed = onDismissed
            }
        }

        private const val ARG_PROXY_TEMPLATES = "proxyTemplates"
        private const val ARG_PROXY_INDEX = "proxyIndex"
    }
}
