package io.legado.app.ui.widget.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 列表三态占位组件：空态 / 错误态（可带重试动作）。
 * 颜色由调用方传入（如 palette.secondaryText / palette.accent），不绑定具体主题桥，
 * 供搜索、RSS、各管理页等 Compose 列表统一使用。
 */
@Composable
fun ListStatePlaceholder(
    text: String,
    textColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier,
    hint: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    fontFamily: FontFamily? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 15.sp,
            fontFamily = fontFamily,
            textAlign = TextAlign.Center
        )
        hint?.let {
            Text(
                text = it,
                color = textColor.copy(alpha = 0.7f),
                fontSize = 13.sp,
                fontFamily = fontFamily,
                textAlign = TextAlign.Center
            )
        }
        if (actionText != null && onAction != null) {
            TextButton(
                onClick = onAction,
                colors = ButtonDefaults.textButtonColors(contentColor = accentColor)
            ) {
                Text(text = actionText, fontSize = 14.sp, fontFamily = fontFamily)
            }
        }
    }
}

/**
 * 列表加载指示（居中），色板由调用方传入。
 */
@Composable
fun ListLoadingIndicator(
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    CircularProgressIndicator(
        modifier = modifier.size(36.dp),
        color = accentColor,
        strokeWidth = 2.dp
    )
}
