package io.legado.app.uikit.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.theme.LocalAppRadiusScale
import io.legado.app.uikit.token.AppShapes
import io.legado.app.uikit.token.AppSize
import io.legado.app.uikit.token.AppTypeScale

/**
 * Button styles (docs/ui-rewrite-plan.md 6.2).
 */
enum class AppButtonStyle { FILLED, TONAL, OUTLINED, TEXT }

/**
 * Button sizes: 48 / 40 / 32 dp.
 */
enum class AppButtonSize(val height: Dp) {
    STANDARD(AppSize.button),
    COMPACT(AppSize.buttonCompact),
    MINI(AppSize.buttonMini),
}

/**
 * Unified app button. Replaces the 142 TextView-as-button layouts.
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AppButtonStyle = AppButtonStyle.FILLED,
    size: AppButtonSize = AppButtonSize.STANDARD,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scale = LocalAppRadiusScale.current
    val shape = AppShapes.action(scale)
    val contentPadding = PaddingValues(horizontal = 16.dp)
    val commonModifier = modifier
        .height(size.height)

    when (style) {
        AppButtonStyle.FILLED -> Button(
            onClick = onClick,
            modifier = commonModifier,
            enabled = enabled,
            shape = shape,
            contentPadding = contentPadding,
        ) { ButtonContent(text, leading, trailing) }

        AppButtonStyle.TONAL -> androidx.compose.material3.FilledTonalButton(
            onClick = onClick,
            modifier = commonModifier,
            enabled = enabled,
            shape = shape,
            contentPadding = contentPadding,
        ) { ButtonContent(text, leading, trailing) }

        AppButtonStyle.OUTLINED -> OutlinedButton(
            onClick = onClick,
            modifier = commonModifier,
            enabled = enabled,
            shape = shape,
            contentPadding = contentPadding,
        ) { ButtonContent(text, leading, trailing) }

        AppButtonStyle.TEXT -> TextButton(
            onClick = onClick,
            modifier = commonModifier,
            enabled = enabled,
            shape = shape,
            contentPadding = contentPadding,
        ) { ButtonContent(text, leading, trailing) }
    }
}

@Composable
private fun ButtonContent(
    text: String,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
) {
    leading?.invoke()
    Text(text = text, style = AppTypeScale.body)
    trailing?.invoke()
}

/**
 * Unified icon button: 48dp touch target, 24dp icon.
 */
@Composable
fun AppIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    androidx.compose.material3.IconButton(
        onClick = onClick,
        modifier = modifier.then(Modifier.size(AppSize.touchTarget)),
        enabled = enabled,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(AppSize.icon),
        )
    }
}
