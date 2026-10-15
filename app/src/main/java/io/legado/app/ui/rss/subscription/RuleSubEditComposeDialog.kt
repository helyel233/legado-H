package io.legado.app.ui.rss.subscription

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.RuleSub
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.toastOnUi

/**
 * 规则订阅编辑（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Management` 映射为 widthFraction 0.96f / maxWidthDp 700；
 * 内容由旧 AppDialogFrame + LegadoMiuixCard(类型卡片组) + 自定义 OutlinedTextField + LegadoMiuixSwitch + LegadoMiuixActionButton
 * 换为 V5 AppDialog + `AppChip(selected)` + `AppTextField`（**支持 keyboardType/enabled**）+ `AppRowWithSwitch(card = false)`
 * + AppGhostButton/AppFilledButton。
 * **降级**：`AppRowWithSwitch` 无 enabled 参数，静默更新的禁用态改为「onClick 内判断 autoUpdate + 整行 alpha 0.5」。
 * 业务全部保留：类型/名称/Url/自动更新/静默更新/更新间隔的联动
 * （开启自动更新且间隔为 0 → 默认 24；关闭自动更新 → 静默更新与间隔清零；间隔填 0 → 自动与静默均关闭）、
 * Url 非空校验、保存走 `callback.saveRuleSub(next) { dismiss }`。
 */
internal class RuleSubEditComposeDialog : V5DialogFragment() {

    /** 旧 AppDialogSize.Management(widthFraction = 0.96f, maxWidthDp = 700) */
    override val widthFraction: Float = 0.96f
    override val maxWidthDp: Int = 700

    private val callback: Callback?
        get() = (parentFragment as? Callback) ?: activity as? Callback

    @Composable
    override fun Content() {
        val initial = requireArguments().toRuleSub()
        val typeLabels = stringArrayResource(R.array.rule_type).toList()
        var type by rememberSaveable(initial.id) {
            mutableIntStateOf(initial.type.coerceIn(0, typeLabels.lastIndex))
        }
        var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
        var url by rememberSaveable(initial.id) { mutableStateOf(initial.url) }
        var autoUpdate by rememberSaveable(initial.id) {
            mutableStateOf(initial.autoUpdate || initial.updateInterval > 0)
        }
        var silentUpdate by rememberSaveable(initial.id) {
            mutableStateOf(initial.silentUpdate && (initial.autoUpdate || initial.updateInterval > 0))
        }
        var updateInterval by rememberSaveable(initial.id) {
            mutableStateOf(initial.updateInterval.coerceAtLeast(0).toString())
        }

        RuleSubEditContent(
            typeLabels = typeLabels,
            selectedType = type,
            onTypeSelected = { type = it },
            name = name,
            onNameChange = { name = it },
            url = url,
            onUrlChange = { url = it },
            autoUpdate = autoUpdate,
            onAutoUpdateChange = { checked ->
                autoUpdate = checked
                if (checked && updateInterval.toIntOrNull().orZero() == 0) {
                    updateInterval = "24"
                }
                if (!checked) {
                    silentUpdate = false
                    updateInterval = "0"
                }
            },
            silentUpdate = silentUpdate,
            onSilentUpdateChange = { checked ->
                if (autoUpdate) {
                    silentUpdate = checked
                }
            },
            updateInterval = updateInterval,
            onUpdateIntervalChange = { text ->
                val normalized = text.filter { it.isDigit() }.take(4)
                updateInterval = normalized
                val interval = normalized.toIntOrNull().orZero()
                if (interval == 0) {
                    autoUpdate = false
                    silentUpdate = false
                } else {
                    autoUpdate = true
                }
            },
            onDismiss = { dismissAllowingStateLoss() },
            onSave = {
                if (url.isBlank()) {
                    requireContext().toastOnUi(R.string.null_url)
                    return@RuleSubEditContent
                }
                val next = initial.copy(
                    type = type,
                    name = name,
                    url = url,
                    autoUpdate = autoUpdate,
                    silentUpdate = silentUpdate && autoUpdate,
                    updateInterval = if (autoUpdate) {
                        updateInterval.toIntOrNull().orZero()
                    } else {
                        0
                    }
                )
                callback?.saveRuleSub(next) {
                    dismissAllowingStateLoss()
                }
            }
        )
    }

    companion object {
        private const val ARG_ID = "id"
        private const val ARG_NAME = "name"
        private const val ARG_URL = "url"
        private const val ARG_TYPE = "type"
        private const val ARG_CUSTOM_ORDER = "customOrder"
        private const val ARG_AUTO_UPDATE = "autoUpdate"
        private const val ARG_UPDATE = "update"
        private const val ARG_UPDATE_INTERVAL = "updateInterval"
        private const val ARG_SILENT_UPDATE = "silentUpdate"
        private const val ARG_JS = "js"
        private const val ARG_SHOW_RULE = "showRule"
        private const val ARG_SOURCE_URL = "sourceUrl"

        internal fun create(ruleSub: RuleSub): RuleSubEditComposeDialog {
            return RuleSubEditComposeDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ID, ruleSub.id)
                    putString(ARG_NAME, ruleSub.name)
                    putString(ARG_URL, ruleSub.url)
                    putInt(ARG_TYPE, ruleSub.type)
                    putInt(ARG_CUSTOM_ORDER, ruleSub.customOrder)
                    putBoolean(ARG_AUTO_UPDATE, ruleSub.autoUpdate)
                    putLong(ARG_UPDATE, ruleSub.update)
                    putInt(ARG_UPDATE_INTERVAL, ruleSub.updateInterval)
                    putBoolean(ARG_SILENT_UPDATE, ruleSub.silentUpdate)
                    putString(ARG_JS, ruleSub.js)
                    putString(ARG_SHOW_RULE, ruleSub.showRule)
                    putString(ARG_SOURCE_URL, ruleSub.sourceUrl)
                }
            }
        }

        private fun Bundle.toRuleSub(): RuleSub {
            return RuleSub(
                id = getLong(ARG_ID),
                name = getString(ARG_NAME).orEmpty(),
                url = getString(ARG_URL).orEmpty(),
                type = getInt(ARG_TYPE),
                customOrder = getInt(ARG_CUSTOM_ORDER),
                autoUpdate = getBoolean(ARG_AUTO_UPDATE),
                update = getLong(ARG_UPDATE),
                updateInterval = getInt(ARG_UPDATE_INTERVAL),
                silentUpdate = getBoolean(ARG_SILENT_UPDATE),
                js = getString(ARG_JS),
                showRule = getString(ARG_SHOW_RULE),
                sourceUrl = getString(ARG_SOURCE_URL)
            )
        }
    }

    interface Callback {
        fun saveRuleSub(ruleSub: RuleSub, onSaved: () -> Unit)
    }
}

@Composable
private fun RuleSubEditContent(
    typeLabels: List<String>,
    selectedType: Int,
    onTypeSelected: (Int) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    url: String,
    onUrlChange: (String) -> Unit,
    autoUpdate: Boolean,
    onAutoUpdateChange: (Boolean) -> Unit,
    silentUpdate: Boolean,
    onSilentUpdateChange: (Boolean) -> Unit,
    updateInterval: String,
    onUpdateIntervalChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density

    AppDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
        ) {
            Text(
                text = stringResource(R.string.rule_subscription),
                style = visual.type.title,
                color = colors.onSurface
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.book_type),
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    typeLabels.forEachIndexed { index, label ->
                        AppChip(
                            text = label,
                            selected = index == selectedType,
                            modifier = Modifier.weight(1f),
                            onClick = { onTypeSelected(index) },
                        )
                    }
                }
            }
            AppTextField(
                value = name,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.name),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = url,
                onValueChange = onUrlChange,
                placeholder = "Url",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            AppRowWithSwitch(
                title = stringResource(R.string.auto_update),
                checked = autoUpdate,
                onCheckedChange = onAutoUpdateChange,
                card = false,
            )
            AppRowWithSwitch(
                title = stringResource(R.string.silent_update),
                checked = silentUpdate,
                onCheckedChange = { checked -> if (autoUpdate) onSilentUpdateChange(checked) },
                card = false,
                // 禁用态：AppRowWithSwitch 无 enabled 参数，用透明度表达
                modifier = Modifier.alpha(if (autoUpdate) 1f else 0.5f),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppTextField(
                    value = updateInterval,
                    onValueChange = onUpdateIntervalChange,
                    placeholder = stringResource(R.string.update_interval),
                    enabled = autoUpdate,
                    singleLine = true,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.time_hour),
                    style = visual.type.body,
                    color = if (autoUpdate) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .alignByBaseline()
                        .alpha(if (autoUpdate) 1f else 0.58f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                AppGhostButton(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                AppFilledButton(
                    text = stringResource(R.string.action_save),
                    modifier = Modifier.weight(1f),
                    onClick = onSave,
                )
            }
        }
    }
}

private fun Int?.orZero(): Int = this ?: 0
