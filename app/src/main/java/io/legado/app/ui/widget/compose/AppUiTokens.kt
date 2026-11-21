package io.legado.app.ui.widget.compose

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.lib.theme.SurfaceHierarchyDerivation

/**
 * Shared dialog width tiers. Content type chooses the tier while every dialog in that tier
 * keeps the same phone width and tablet cap.
 */
enum class AppDialogSize(
    val widthFraction: Float,
    val maxWidthDp: Int
) {
    Confirm(widthFraction = 0.92f, maxWidthDp = 620),
    Form(widthFraction = 0.94f, maxWidthDp = 660),
    Management(widthFraction = 0.96f, maxWidthDp = 700),
    Wide(widthFraction = 0.98f, maxWidthDp = 760)
}

object AppListSpacing {
    val Compact = 6.dp
    val Normal = 8.dp
    val Section = 12.dp
}

/**
 * 全 App 统一空间度量与控件高度三档契约（与颜色/圆角/玻璃 Token 并列）。
 */
object AppLayoutTokens {
    val PageHorizontal = 12.dp
    val DetailHorizontal = 18.dp
    val SectionGap = 12.dp
    val CardGap = 8.dp
    val GridGap = 10.dp

    val CardPaddingHorizontal = 14.dp
    val CardPaddingVertical = 10.dp
    val RowPaddingHorizontal = 16.dp
    val RowPaddingVertical = 10.dp
    val RowMinHeight = 56.dp
    val TextMetaSpacing = 3.dp

    val ControlHeightCompact = 32.dp
    val ControlHeightRegular = 44.dp
    val ControlHeightProminent = 48.dp

    fun concentricCardHorizontalPadding(
        panelRadiusDp: Dp,
        base: Dp = CardPaddingHorizontal
    ): Dp = SurfaceHierarchyDerivation.resolveConcentricCardPaddingDp(
        panelRadiusDp = panelRadiusDp.value,
        basePaddingDp = base.value
    ).dp
}
