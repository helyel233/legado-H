package io.legado.app.ui.association

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * Load state for import dialogs.
 */
enum class ImportLoadState {
    LOADING,
    SUCCESS,
    ERROR
}

/**
 * 导入弹窗通用条目行（V5 版）。
 *
 * 旧版依赖 `style: AppDialogStyle`（Miuix 色板 + actionRadius）与 `LegadoMiuixActionButton`；
 * 新版直接读 `LocalVisualSpec` / `LocalSemanticColors`（未注入 Provider 时也会从
 * AppUiColors 派生当前主题色，见 AppSurface.kt），因此**去掉 style 参数**。
 * 因此未迁移基座的弹窗调用它也能拿到正确主题色，只是字阶/形状切换为 V5 规格。
 */
@Composable
fun ImportSourceItemRow(
    name: String,
    isChecked: Boolean,
    stateText: String,
    comment: String? = null,
    onCodeView: (() -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    var commentExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(visual.shape(ShapeKind.CARD))
            .clickable { onCheckedChange(!isChecked) }
            .padding(horizontal = visual.density.blockGap / 2, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = colors.primary,
                uncheckedColor = colors.outline,
                checkmarkColor = colors.onPrimary
            )
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
        ) {
            Text(
                text = name,
                style = visual.type.body,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (comment != null) {
                Text(
                    text = comment,
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = if (commentExpanded) 39 else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable { commentExpanded = !commentExpanded }
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stateText,
            style = visual.type.caption,
            color = colors.onSurfaceVariant
        )
        if (onCodeView != null) {
            Spacer(modifier = Modifier.width(8.dp))
            AppGhostButton(
                text = "查看",
                onClick = onCodeView
            )
        }
    }
}

/**
 * 保留选项切换芯片：选中（primary 填充）表示已开启，与普通动作按钮区分。
 * 旧版为 LegadoMiuixActionButton(primary = checked)，新版直接用 V5 [AppChip] 的 selected 态。
 */
@Composable
internal fun KeepOptionChip(
    text: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    AppChip(
        text = text,
        selected = checked,
        onClick = { onToggle(!checked) }
    )
}
