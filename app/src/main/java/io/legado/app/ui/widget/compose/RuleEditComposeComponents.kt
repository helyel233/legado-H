package io.legado.app.ui.widget.compose

import io.legado.app.ui.widget.dialog.V5DialogColors
import io.legado.app.ui.widget.dialog.rememberV5DialogColors
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
fun AppRuleTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    keyboardType: KeyboardType = KeyboardType.Text,
    style: V5DialogColors = rememberV5DialogColors(),
    onFocused: (() -> Unit)? = null,
    onOpenEditor: (() -> Unit)? = null
) {
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused?.invoke() },
        label = label,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines.coerceAtLeast(minLines),
        keyboardType = keyboardType,
        trailingIcon = onOpenEditor?.let { openEditor ->
            {
                IconButton(onClick = openEditor) {
                    Icon(
                        painter = painterResource(io.legado.app.R.drawable.ic_code),
                        contentDescription = label,
                        tint = style.accent
                    )
                }
            }
        }
    )
}

@Composable
fun AppRuleSwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    style: V5DialogColors = rememberV5DialogColors()
) {
    AppRowWithSwitch(
        title = text,
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier
    )
}

@Composable
fun AppRuleTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    style: V5DialogColors = rememberV5DialogColors()
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            AppChip(
                text = label,
                selected = selected,
                modifier = Modifier,
                onClick = { onSelected(index) }
            )
        }
    }
}

@Composable
fun AppRuleActionBar(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

@Composable
fun AppRuleFieldSpacer() {
    Spacer(modifier = Modifier.height(10.dp))
}
