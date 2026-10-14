package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.ui.book.read.ContentSelectConfig
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import io.legado.app.utils.putPrefStringSet

/**
 * 「选中菜单配置」弹窗（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Form` 映射为 widthFraction 0.94f / maxWidthDp 660；
 * 内容由旧 AppDialogFrame + LegadoMiuixActionButton 换为 V5 AppDialog + AppGhostButton/AppFilledButton。
 * **保留 M3 Checkbox / RadioButton 控件**（V5 暂无多选/单选 tortoise 等价组件，且多选项与单默认的语义必须区分），
 * 仅把配色改为 V5 语义色；`toggleable/selectable` 的 accessibility Role 原样保留。
 * 「默认打开项必须包含在已选动作集合里 + 空则兜底 COPY + 保存后 postEvent」的校验逻辑不变。
 */
internal class ContentSelectMenuConfigDialog : V5DialogFragment() {

    /** 旧 AppDialogSize.Form(widthFraction = 0.94f, maxWidthDp = 660) */
    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int = 660

    private data class ActionItem(
        val id: String,
        val titleRes: Int
    )

    private data class DefaultOpenItem(
        val id: String,
        val titleRes: Int
    )

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density
        val initialSelected = remember {
            sanitizeActionIds(ContentSelectConfig.selectedActionIds(requireContext()))
                .toList()
        }
        var selectedActions by rememberSaveable {
            mutableStateOf(initialSelected)
        }
        val initialDefaultOpen = remember {
            requireContext()
                .getPrefString(PreferKey.contentSelectDefaultOpen, "")
                .orEmpty()
                .takeIf { it in defaultOpenValues }
                .orEmpty()
        }
        var defaultOpen by rememberSaveable {
            mutableStateOf(initialDefaultOpen)
        }

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                Text(
                    text = stringResource(R.string.content_select_menu_config),
                    style = visual.type.title,
                    color = colors.onSurface
                )
                ContentSelectMenuContent(
                    selectedActions = selectedActions.toSet(),
                    defaultOpen = defaultOpen,
                    onActionToggle = { actionId ->
                        selectedActions = selectedActions
                            .toMutableSet()
                            .apply {
                                if (!add(actionId)) {
                                    remove(actionId)
                                }
                            }
                            .toList()
                    },
                    onDefaultOpenChange = { defaultOpen = it }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { dismissAllowingStateLoss() },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.ok),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            saveConfig(selectedActions.toSet(), defaultOpen)
                        },
                    )
                }
            }
        }
    }

    private fun saveConfig(
        actions: Set<String>,
        defaultOpen: String
    ) {
        val selected = sanitizeActionIds(actions).toMutableSet()
        if (defaultOpen.isNotEmpty()) {
            selected += defaultOpen
        }
        if (selected.isEmpty()) {
            selected += ContentSelectConfig.ACTION_COPY
        }
        requireContext().putPrefStringSet(PreferKey.contentSelectActions, selected)
        requireContext().putPrefString(PreferKey.contentSelectDefaultOpen, defaultOpen)
        postEvent(EventBus.CONTENT_SELECT_MENU_CONFIG_CHANGED, true)
        dismissAllowingStateLoss()
    }

    @Composable
    private fun ContentSelectMenuContent(
        selectedActions: Set<String>,
        defaultOpen: String,
        onActionToggle: (String) -> Unit,
        onDefaultOpenChange: (String) -> Unit
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionTitle(title = stringResource(R.string.content_select_actions))
            actionItems.forEach { item ->
                CheckRow(
                    title = stringResource(item.titleRes),
                    checked = item.id in selectedActions,
                    onClick = { onActionToggle(item.id) }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            SectionTitle(title = stringResource(R.string.content_select_default_open))
            defaultOpenItems.forEach { item ->
                RadioRow(
                    title = stringResource(item.titleRes),
                    selected = defaultOpen == item.id,
                    onClick = { onDefaultOpenChange(item.id) }
                )
            }
        }
    }

    @Composable
    private fun SectionTitle(title: String) {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        Text(
            text = title,
            style = visual.type.body,
            color = colors.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 2.dp)
        )
    }

    @Composable
    private fun CheckRow(
        title: String,
        checked: Boolean,
        onClick: () -> Unit
    ) {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Checkbox,
                    onValueChange = { onClick() }
                )
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = visual.type.body,
                color = colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    checkedColor = colors.primary,
                    uncheckedColor = colors.onSurfaceVariant,
                    checkmarkColor = colors.surface
                )
            )
        }
    }

    @Composable
    private fun RadioRow(
        title: String,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onClick
                )
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = visual.type.body,
                color = colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = colors.primary,
                    unselectedColor = colors.onSurfaceVariant
                )
            )
        }
    }

    companion object {
        private val defaultOpenValues = ContentSelectConfig.defaultOpenValues

        private val actionItems = listOf(
            ActionItem(ContentSelectConfig.ACTION_REPLACE, R.string.replace),
            ActionItem(ContentSelectConfig.ACTION_COPY, R.string.copy_text),
            ActionItem(ContentSelectConfig.ACTION_WEB_SEARCH, R.string.search),
            ActionItem(ContentSelectConfig.ACTION_BOOKMARK, R.string.bookmark),
            ActionItem(ContentSelectConfig.ACTION_ALOUD, R.string.read_aloud),
            ActionItem(ContentSelectConfig.ACTION_DICT, R.string.dict),
            ActionItem(ContentSelectConfig.ACTION_ASK_AI, R.string.ask_ai),
            ActionItem(ContentSelectConfig.ACTION_GENERATE_IMAGE, R.string.ai_image_generate),
            ActionItem(ContentSelectConfig.ACTION_SHARE_IMAGE, R.string.share)
        )
        private val knownActionIds = actionItems.map { it.id }.toSet()

        private val defaultOpenItems = listOf(
            DefaultOpenItem("", R.string.default_none),
            DefaultOpenItem(ContentSelectConfig.ACTION_WEB_SEARCH, R.string.default_search),
            DefaultOpenItem(ContentSelectConfig.ACTION_DICT, R.string.default_dict),
            DefaultOpenItem(ContentSelectConfig.ACTION_ASK_AI, R.string.default_ask_ai)
        )

        private fun sanitizeActionIds(ids: Iterable<String>): Set<String> {
            return ids.filterTo(linkedSetOf()) { it in knownActionIds }
        }
    }
}
