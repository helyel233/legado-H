package io.legado.app.uikit.token

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Size tokens (docs/ui-rewrite-plan.md 6.1).
 */
object AppSize {

    /** Button heights: standard / compact / mini */
    val button: Dp = 48.dp
    val buttonCompact: Dp = 40.dp
    val buttonMini: Dp = 32.dp

    /** Minimum touch target */
    val touchTarget: Dp = 48.dp

    /** List item heights: single-line / double-line */
    val listSingle: Dp = 56.dp
    val listDouble: Dp = 72.dp

    /** Top bar / bottom bar height */
    val bar: Dp = 56.dp

    /** Icons: content / dense / large */
    val icon: Dp = 24.dp
    val iconDense: Dp = 20.dp
    val iconLarge: Dp = 32.dp

    /** Divider hairline */
    val divider: Dp = 1.dp
}
