package io.legado.app.ui.book.source.edit

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.ui.host.HostV5Page
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppDropdownField
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppFilterChip
import io.legado.app.uikit.v5.components.AppFilterChipRow
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppSwitch
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec

@Composable
internal fun BookSourceEditScreen(
    title: String,
    bookTypeOptions: List<String>,
    enabled: Boolean,
    enabledExplore: Boolean,
    enabledCookieJar: Boolean,
    bookTypeIndex: Int,
    eventListener: Boolean,
    customButton: Boolean,
    selectedTab: Int,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onEnabledExploreChange: (Boolean) -> Unit,
    onEnabledCookieJarChange: (Boolean) -> Unit,
    onBookTypeChange: (Int) -> Unit,
    onEventListenerChange: (Boolean) -> Unit,
    onCustomButtonChange: (Boolean) -> Unit,
    onTabSelected: (Int) -> Unit,
    onSave: () -> Unit,
    buildMenuActions: () -> List<Pair<String, () -> Unit>>,
    recyclerViewFactory: (Context) -> RecyclerView,
) {
    val visual = LocalVisualSpec.current
    var menuOpen by remember { mutableStateOf(false) }
    var menuActions by remember { mutableStateOf<List<Pair<String, () -> Unit>>>(emptyList()) }

    val tabLabels = listOf(
        stringResource(R.string.source_tab_base),
        stringResource(R.string.source_tab_search),
        stringResource(R.string.source_tab_find),
        stringResource(R.string.source_tab_info),
        stringResource(R.string.source_tab_toc),
        stringResource(R.string.source_tab_content),
    )

    HostV5Page(
        title = title,
        onBack = onBack,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppFilledButton(
                    text = stringResource(R.string.v5_save),
                    onClick = onSave,
                )
                Spacer(Modifier.width(visual.density.blockGap / 2))
                AppOverflowMenuButton(
                    contentDescription = stringResource(R.string.v5_more),
                    onClick = {
                        menuActions = buildMenuActions()
                        menuOpen = true
                    },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = visual.density.screenPadding),
            ) {
                AppCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(visual.density.cardPadding),
                    ) {
                        AppDropdownField(
                            label = stringResource(R.string.book_type).trimEnd(':', '：'),
                            options = bookTypeOptions,
                            selectedIndex = bookTypeIndex,
                            onSelect = onBookTypeChange,
                        )
                        Spacer(Modifier.height(visual.density.blockGap / 2))
                        Row(horizontalArrangement = Arrangement.spacedBy(visual.density.blockGap)) {
                            SourceToggle(
                                label = stringResource(R.string.is_enable),
                                checked = enabled,
                                modifier = Modifier.weight(1f),
                                onCheckedChange = onEnabledChange,
                            )
                            SourceToggle(
                                label = stringResource(R.string.discovery),
                                checked = enabledExplore,
                                modifier = Modifier.weight(1f),
                                onCheckedChange = onEnabledExploreChange,
                            )
                        }
                        Spacer(Modifier.height(visual.density.blockGap / 2))
                        Row(horizontalArrangement = Arrangement.spacedBy(visual.density.blockGap)) {
                            SourceToggle(
                                label = stringResource(R.string.auto_save_cookie),
                                checked = enabledCookieJar,
                                modifier = Modifier.weight(1f),
                                onCheckedChange = onEnabledCookieJarChange,
                            )
                            SourceToggle(
                                label = stringResource(R.string.is_event_listener),
                                checked = eventListener,
                                modifier = Modifier.weight(1f),
                                onCheckedChange = onEventListenerChange,
                            )
                            SourceToggle(
                                label = stringResource(R.string.custom_button),
                                checked = customButton,
                                modifier = Modifier.weight(1f),
                                onCheckedChange = onCustomButtonChange,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(visual.density.blockGap / 2))
                AppFilterChipRow(
                    chips = tabLabels.mapIndexed { index, label ->
                        AppFilterChip(label = label, selected = index == selectedTab)
                    },
                    onChipClick = onTabSelected,
                )
                Spacer(Modifier.height(visual.density.blockGap / 2))
            }
            AndroidView(
                factory = recyclerViewFactory,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }

    if (menuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.v5_more),
            actions = menuActions,
            onDismiss = { menuOpen = false },
        )
    }
}

@Composable
private fun SourceToggle(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(visual.density.blockGap / 2),
    ) {
        Text(
            text = label,
            style = visual.type.caption,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        AppSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
