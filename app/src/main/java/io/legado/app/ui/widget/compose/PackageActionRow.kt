package io.legado.app.ui.widget.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.glass.GlassSurface

/**
 * Primary action pill shown directly on a package card's action row.
 */
data class PackageActionItem(
    val label: String,
    val accent: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * Secondary or destructive action inside the `⋯` overflow menu of [PackageActionRow].
 * Destructive items ([danger] = true) are automatically grouped at the bottom after a divider.
 */
data class PackageOverflowItem(
    val label: String,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * Shared package-card action row (`primary` persistent pills + `⋯` overflow menu).
 *
 * - Every touch target enforces at least `40.dp * fontScale` height so large-font accessibility
 *   never clips or shrinks hit areas (aligned with [LegadoMiuixActionButton]).
 * - Uses [FlowRow] so future primary actions or large font scales wrap cleanly without squeezing
 *   or truncating destructive actions (which live safely inside [overflow]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PackageActionRow(
    primary: List<PackageActionItem>,
    overflow: List<PackageOverflowItem>,
    palette: AppManagementPalette,
    modifier: Modifier = Modifier
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val minTouchHeight = (40f * fontScale).dp
    val actionRadius = palette.miuix.actionRadius ?: 8.dp
    val actionShape = RoundedCornerShape(actionRadius)
    val context = LocalContext.current
    val normalOverflow = remember(overflow) { overflow.filterNot { it.danger } }
    val dangerOverflow = remember(overflow) { overflow.filter { it.danger } }
    var menuExpanded by remember { mutableStateOf(false) }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        primary.forEach { item ->
            val containerColor = when {
                item.selected -> palette.settings.accent.copy(alpha = 0.14f)
                item.accent -> palette.settings.accent.copy(alpha = 0.08f)
                else -> Color(palette.settings.slot)
            }
            val borderStroke = when {
                item.selected -> BorderStroke(1.dp, palette.settings.accent.copy(alpha = 0.55f))
                item.accent -> BorderStroke(0.75.dp, palette.settings.accent.copy(alpha = 0.32f))
                else -> BorderStroke(
                    0.75.dp,
                    palette.settings.border?.let(::Color) ?: palette.settings.divider
                )
            }
            val textColor = if (item.selected || item.accent) {
                palette.settings.accent
            } else {
                palette.settings.primaryText
            }
            Surface(
                shape = actionShape,
                color = containerColor,
                border = borderStroke,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                modifier = Modifier
                    .heightIn(min = minTouchHeight)
                    .widthIn(min = 56.dp)
                    .alpha(if (item.enabled) 1f else 0.45f)
                    .clip(actionShape)
                    .clickable(enabled = item.enabled, role = Role.Button, onClick = item.onClick)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.label,
                        color = textColor,
                        fontSize = 12.sp,
                        fontWeight = if (item.selected || item.accent) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (overflow.isNotEmpty()) {
            Box {
                Surface(
                    shape = actionShape,
                    color = Color(palette.settings.slot),
                    border = BorderStroke(
                        0.75.dp,
                        palette.settings.border?.let(::Color) ?: palette.settings.divider
                    ),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .heightIn(min = minTouchHeight)
                        .widthIn(min = minTouchHeight)
                        .clip(actionShape)
                        .semantics { contentDescription = "更多操作" }
                        .clickable(role = Role.Button) { menuExpanded = true }
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⋯",
                            color = palette.settings.primaryText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }

                val useTranslucentMenu = remember(menuExpanded, context) {
                    menuExpanded && !AppConfig.isEInkMode && GlassSurface.pageGlassAllowed(context)
                }
                val baseContainer = palette.miuix.surface
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    shape = RoundedCornerShape(palette.miuix.panelRadius ?: 12.dp),
                    containerColor = if (useTranslucentMenu) {
                        baseContainer.copy(alpha = 0.92f)
                    } else {
                        baseContainer
                    },
                    tonalElevation = if (useTranslucentMenu) 0.dp else MenuDefaults.TonalElevation
                ) {
                    normalOverflow.forEach { action ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = action.label,
                                    color = if (action.enabled) {
                                        palette.settings.primaryText
                                    } else {
                                        palette.settings.secondaryText.copy(alpha = 0.5f)
                                    },
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            enabled = action.enabled,
                            onClick = {
                                menuExpanded = false
                                action.onClick()
                            }
                        )
                    }
                    if (normalOverflow.isNotEmpty() && dangerOverflow.isNotEmpty()) {
                        HorizontalDivider(
                            color = palette.settings.divider,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                    dangerOverflow.forEach { action ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = action.label,
                                    color = if (action.enabled) {
                                        palette.settings.danger
                                    } else {
                                        palette.settings.danger.copy(alpha = 0.45f)
                                    },
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            enabled = action.enabled,
                            onClick = {
                                menuExpanded = false
                                action.onClick()
                            }
                        )
                    }
                }
            }
        }
    }
}
