package io.legado.app.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.help.DirectLinkUpload
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * 直链上传配置（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Management` 映射为 widthFraction 0.96f / maxWidthDp 700；
 * 内容由旧 AppDialogFrame + 自定义 OutlinedTextField + LegadoMiuixActionButton
 * 换为 V5 AppDialog + `AppTextField` + AppGhostButton/AppFilledButton。
 * 业务全部保留：`produceState` 异步读取已有规则（失败兜底空规则）、导入默认规则走 AppMenuSheet、
 * 复制/粘贴走剪贴板（encodeRule/importRule 的失败文案不变）、
 * 「上传 Url 与下载 Url 规则均非空才允许保存」的校验、`DirectLinkUpload.upLoad` 测试结果
 * （成功可复制 / 失败走 AppConfirmDialog）以及保存落盘后的关闭。
 */
internal class ComposeDirectLinkUploadDialog : V5DialogFragment() {

    /** 旧 AppDialogSize.Management(widthFraction = 0.96f, maxWidthDp = 700) */
    override val widthFraction: Float = 0.96f
    override val maxWidthDp: Int = 700

    @Composable
    override fun Content() {
        val initialRule by produceState<DirectLinkUpload.Rule?>(null) {
            value = withContext(IO) {
                runCatching { DirectLinkUpload.getRule() }
                    .getOrElse { DirectLinkUpload.Rule("", "", "") }
            }
        }
        initialRule?.let { rule ->
            DirectLinkUploadContent(
                initialRule = rule,
                onDismiss = { dismissAllowingStateLoss() },
                onImportDefault = ::importDefault,
                onCopyRule = ::copyRule,
                onPasteRule = ::pasteRule,
                onTest = ::test,
                onSave = ::saveRuleAndDismiss
            )
        } ?: DirectLinkUploadLoadingContent(
            onDismiss = { dismissAllowingStateLoss() }
        )
        DirectLinkUploadDialogs()
    }

    // ── V5 弹窗状态 ─────────────────────────────────────────────
    internal var defaultRuleOpen by mutableStateOf(false)
    internal var defaultRuleLabels by mutableStateOf<List<String>>(emptyList())
    internal var resultOpen by mutableStateOf(false)
    internal var resultMessage by mutableStateOf("")
    internal var resultCopyable by mutableStateOf(false)

    @Composable
    private fun DirectLinkUploadDialogs() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density
        if (defaultRuleOpen) {
            AppMenuSheet(
                title = getString(R.string.import_default_rule),
                actions = defaultRuleLabels.mapIndexed { index, label ->
                    label to {
                        defaultRuleOpen = false
                        val rules = runCatching { DirectLinkUpload.defaultRules }.getOrDefault(emptyList())
                        rules.getOrNull(index)?.let { rule -> saveRuleAndDismiss(rule) }
                    }
                },
                onDismiss = { defaultRuleOpen = false },
            )
        }
        if (resultOpen) {
            if (resultCopyable) {
                AppDialog(onDismiss = { resultOpen = false }) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                    ) {
                        Text(
                            text = "测试结果",
                            style = visual.type.title,
                            color = colors.onSurface,
                        )
                        Text(
                            text = resultMessage,
                            style = visual.type.caption,
                            color = colors.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                            AppGhostButton(
                                text = stringResource(R.string.copy_text),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    resultOpen = false
                                    appCtx.sendToClip(resultMessage)
                                },
                            )
                            AppFilledButton(
                                text = stringResource(android.R.string.ok),
                                modifier = Modifier.weight(1f),
                                onClick = { resultOpen = false },
                            )
                        }
                    }
                }
            } else {
                AppConfirmDialog(
                    title = "测试结果",
                    message = resultMessage,
                    confirmLabel = stringResource(android.R.string.ok),
                    cancelLabel = stringResource(R.string.cancel),
                    onConfirm = { resultOpen = false },
                    onDismiss = { resultOpen = false },
                )
            }
        }
    }

    private fun importDefault() {
        lifecycleScope.launch {
            val rules = withContext(IO) { DirectLinkUpload.defaultRules }
            defaultRuleLabels = rules.map { it.summary.orEmpty() }
            defaultRuleOpen = true
        }
    }

    private fun copyRule() {
        lifecycleScope.launch {
            val result = withContext(IO) {
                DirectLinkUpload.encodeRule(DirectLinkUpload.getRule())
            }
            result.onSuccess { appCtx.sendToClip(it) }
                .onFailure {
                    toastOnUi("复制失败：${it.localizedMessage ?: "规则格式不正确"}")
                }
        }
    }

    private fun pasteRule() {
        lifecycleScope.launch {
            val clipText = requireContext().getClipText()
            if (clipText.isNullOrBlank()) {
                toastOnUi("剪贴板为空或格式不对")
                return@launch
            }
            val result = withContext(IO) { DirectLinkUpload.importRule(clipText) }
            result.onSuccess {
                dismissAllowingStateLoss()
            }.onFailure {
                toastOnUi("格式不对：${it.localizedMessage ?: "规则校验失败"}")
            }
        }
    }

    private fun saveRuleAndDismiss(rule: DirectLinkUpload.Rule) {
        lifecycleScope.launch {
            val result = withContext(IO) { DirectLinkUpload.putConfig(rule) }
            result.onSuccess {
                dismissAllowingStateLoss()
            }.onFailure {
                toastOnUi("保存失败：${it.localizedMessage ?: "规则校验失败"}")
            }
        }
    }

    private fun test() {
        lifecycleScope.launch {
            val result = withContext(IO) {
                val rule = DirectLinkUpload.getRule()
                runCatching { DirectLinkUpload.upLoad("test.json", "{}", "application/json", rule) }
            }
            result.onSuccess { msg ->
                resultMessage = msg
                resultCopyable = true
                resultOpen = true
            }.onFailure { e ->
                resultMessage = e.localizedMessage ?: "ERROR"
                resultCopyable = false
                resultOpen = true
            }
        }
    }
}

@Composable
private fun DirectLinkUploadLoadingContent(onDismiss: () -> Unit) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
        ) {
            Text(
                text = stringResource(R.string.backup_restore),
                style = visual.type.title,
                color = colors.onSurface
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = colors.primary)
            }
            AppGhostButton(
                text = stringResource(R.string.cancel),
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun DirectLinkUploadContent(
    initialRule: DirectLinkUpload.Rule,
    onDismiss: () -> Unit,
    onImportDefault: () -> Unit,
    onCopyRule: () -> Unit,
    onPasteRule: () -> Unit,
    onTest: () -> Unit,
    onSave: (DirectLinkUpload.Rule) -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    var uploadUrl by rememberSaveable { mutableStateOf(initialRule.uploadUrl.orEmpty()) }
    var downloadUrlRule by rememberSaveable {
        mutableStateOf(initialRule.downloadUrlRule.orEmpty())
    }
    var summary by rememberSaveable { mutableStateOf(initialRule.summary.orEmpty()) }
    var compress by rememberSaveable { mutableStateOf(initialRule.compress) }

    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
        ) {
            Text(
                text = stringResource(R.string.backup_restore),
                style = visual.type.title,
                color = colors.onSurface
            )
            // Menu actions row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(density.blockGap / 3)
            ) {
                AppGhostButton(
                    text = stringResource(R.string.import_default_rule),
                    modifier = Modifier.weight(1f),
                    onClick = onImportDefault,
                )
                AppGhostButton(
                    text = stringResource(R.string.copy_text),
                    modifier = Modifier.weight(1f),
                    onClick = onCopyRule,
                )
                AppGhostButton(
                    text = stringResource(R.string.paste_rule),
                    modifier = Modifier.weight(1f),
                    onClick = onPasteRule,
                )
            }
            AppTextField(
                value = uploadUrl,
                onValueChange = { uploadUrl = it },
                placeholder = "上传Url",
                singleLine = false,
                minLines = 1,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = downloadUrlRule,
                onValueChange = { downloadUrlRule = it },
                placeholder = "下载Url规则",
                singleLine = false,
                minLines = 1,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = summary,
                onValueChange = { summary = it },
                placeholder = "注释",
                singleLine = false,
                minLines = 1,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            // Compress checkbox
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = compress,
                    onCheckedChange = { compress = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = colors.primary,
                        uncheckedColor = colors.onSurfaceVariant,
                        checkmarkColor = colors.surface
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "压缩",
                    color = colors.onSurface,
                    style = visual.type.body
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 3)) {
                AppGhostButton(
                    text = "测试",
                    modifier = Modifier.weight(1f),
                    onClick = onTest,
                )
                AppGhostButton(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                AppFilledButton(
                    text = stringResource(R.string.ok),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (uploadUrl.isBlank() || downloadUrlRule.isBlank()) {
                            return@AppFilledButton
                        }
                        onSave(
                            DirectLinkUpload.Rule(
                                uploadUrl,
                                downloadUrlRule,
                                summary,
                                compress,
                                initialRule.expiryDate
                            )
                        )
                    },
                )
            }
        }
    }
}
