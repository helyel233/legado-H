package io.legado.app.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.legado.app.R
import io.legado.app.help.AppCloudStorage
import io.legado.app.lib.cloud.S3Container
import io.legado.app.lib.cloud.S3ContainerScope
import io.legado.app.ui.host.HostV5Page
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec

@Composable
internal fun S3ContainerManageScreen(
    containers: List<S3Container>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onItemClick: (S3Container) -> Unit,
    onMoreActions: (S3Container) -> List<Pair<String, () -> Unit>>
) {
    val visual = LocalVisualSpec.current
    val density = visual.density
    val colors = LocalSemanticColors.current

    HostV5Page(
        title = stringResource(R.string.s3_container_manage),
        onBack = onBack,
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.s3_container_manage_summary),
                color = colors.onSurfaceVariant,
                style = visual.type.caption,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap / 2)
            )
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = density.blockGap / 2),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                items(containers, key = { it.id }) { container ->
                    S3ContainerCard(
                        container = container,
                        isDefault = AppCloudStorage.selectedContainer(S3ContainerScope.DEFAULT)?.id == container.id,
                        onClick = { onItemClick(container) },
                        moreActions = onMoreActions(container)
                    )
                }
            }
            AppFilledButton(
                text = stringResource(R.string.s3_container_add),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap / 2),
                onClick = onAdd
            )
        }
    }
}

@Composable
private fun S3ContainerCard(
    container: S3Container,
    isDefault: Boolean,
    onClick: () -> Unit,
    moreActions: List<Pair<String, () -> Unit>>
) {
    val visual = LocalVisualSpec.current
    val density = visual.density
    val colors = LocalSemanticColors.current
    var menuOpen by remember { mutableStateOf(false) }
    val displayName = if (isDefault) {
        stringResource(
            R.string.s3_container_default_name,
            AppCloudStorage.containerDisplayLabel(container)
        )
    } else {
        AppCloudStorage.containerDisplayLabel(container)
    }
    val capacityMb = container.capacityMb.coerceAtLeast(0)
    val usedBytes = container.usedBytes.coerceAtLeast(0)
    val capacityText = if (capacityMb > 0) {
        val capacityBytes = S3ContainerManageActivity.mbToBytes(capacityMb)
        stringResource(
            R.string.s3_container_capacity_line,
            S3ContainerManageActivity.formatBytes(capacityBytes),
            S3ContainerManageActivity.formatBytes(usedBytes),
            S3ContainerManageActivity.formatBytes((capacityBytes - usedBytes).coerceAtLeast(0)),
            if (container.isFull) stringResource(R.string.yes) else stringResource(R.string.no)
        )
    } else {
        stringResource(R.string.s3_container_capacity_unlimited_line, S3ContainerManageActivity.formatBytes(usedBytes))
    }
    val stateText = stringResource(
        R.string.s3_container_state_line,
        if (container.enabled) stringResource(R.string.s3_container_enabled)
        else stringResource(R.string.s3_container_disabled)
    )

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = density.screenPadding),
        onClick = onClick
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(density.screenPadding)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    color = colors.onSurface,
                    style = visual.type.body,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = "${container.bucket}/${container.prefix.trim('/')}",
                    color = colors.onSurfaceVariant,
                    style = visual.type.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = capacityText,
                    color = colors.onSurfaceVariant,
                    style = visual.type.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stateText,
                    color = if (container.enabled) colors.primary else colors.onSurfaceVariant,
                    style = visual.type.caption,
                    fontWeight = if (container.enabled) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            AppOverflowMenuButton(
                contentDescription = stringResource(R.string.more),
                onClick = { menuOpen = true }
            )
        }
    }

    if (menuOpen) {
        AppMenuSheet(
            title = displayName,
            actions = moreActions,
            onDismiss = { menuOpen = false }
        )
    }
}
