package io.legado.app.ui.host

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.main.ai.AiModelConfig
import io.legado.app.ui.host.V5DialogPanel
import io.legado.app.ui.widget.dialog.V5DialogColors
import io.legado.app.ui.widget.dialog.rememberV5DialogColors
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppSegmentedTabs
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppSwitch
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind

// ── Tab Bar ──────────────────────────────────────────────────────────────────

@Composable
fun AiProviderTabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit
) {
    AppSegmentedTabs(
        options = tabs,
        selectedIndex = selectedIndex,
        onSelect = onTabSelected,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

// ── Provider Config Tab ──────────────────────────────────────────────────────

@Composable
fun AiProviderConfigTab(
    name: String,
    onNameChange: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    headers: String,
    onHeadersChange: (String) -> Unit,
    apiModeLabel: String,
    onApiModeClick: () -> Unit,
    promptCache: Boolean,
    onPromptCacheChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val style = rememberV5DialogColors()
    val visual = LocalVisualSpec.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AiProviderTextField(
            value = name,
            onValueChange = onNameChange,
            label = stringResource(R.string.ai_provider_name),
            singleLine = true,
            keyboardType = KeyboardType.Text,
            style = style
        )
        AiProviderTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            label = stringResource(R.string.ai_base_url),
            singleLine = true,
            keyboardType = KeyboardType.Uri,
            style = style
        )
        AiProviderTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = stringResource(R.string.ai_api_key),
            singleLine = true,
            isPassword = true,
            style = style
        )
        // API mode selector
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onApiModeClick),
            shape = visual.shape(ShapeKind.BUTTON),
            color = style.fieldSurface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Text(
                text = apiModeLabel,
                color = style.primaryText,
                fontSize = 14.sp,
                fontFamily = style.bodyFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
            )
        }
        // Prompt cache switch
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPromptCacheChange(!promptCache) },
            shape = visual.shape(ShapeKind.BUTTON),
            color = style.fieldSurface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.ai_enable_prompt_cache_key),
                    modifier = Modifier.weight(1f),
                    color = if (promptCache) style.primaryText else style.secondaryText,
                    fontSize = 14.sp,
                    fontFamily = style.bodyFontFamily,
                    fontWeight = if (promptCache) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(10.dp))
                AppSwitch(
                    checked = promptCache,
                    onCheckedChange = onPromptCacheChange
                )
            }
        }
        // Custom headers
        AiProviderTextField(
            value = headers,
            onValueChange = onHeadersChange,
            label = stringResource(R.string.ai_custom_headers),
            singleLine = false,
            minLines = 4,
            style = style
        )
    }
}

@Composable
private fun AiProviderTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    style: V5DialogColors,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    val visual = LocalVisualSpec.current
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = label,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else minLines + 4,
        keyboardType = keyboardType,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = visual.type.body.copy(
            color = style.primaryText,
            fontFamily = style.bodyFontFamily
        )
    )
}

// ── Model Manage Tab ─────────────────────────────────────────────────────────

@Composable
fun AiModelManageTab(
    models: List<AiModelConfig>,
    currentModelId: String?,
    providerName: String,
    onModelClick: (AiModelConfig) -> Unit,
    onAddModel: () -> Unit,
    onFetchModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = rememberV5DialogColors()
    val visual = LocalVisualSpec.current
    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        // Summary
        Text(
            text = providerName,
            color = style.secondaryText,
            fontSize = 13.sp,
            fontFamily = style.bodyFontFamily,
            modifier = Modifier.padding(start = 18.dp, top = 10.dp, end = 18.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // Model list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(models, key = { it.id }) { model ->
                AiModelItem(
                    model = model,
                    isCurrent = model.id == currentModelId,
                    style = style,
                    onClick = { onModelClick(model) }
                )
            }
        }
        // Action bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AppGhostButton(
                text = stringResource(R.string.ai_add_model_manual),
                onClick = onAddModel,
                modifier = Modifier.weight(1f)
            )
            AppGhostButton(
                text = stringResource(R.string.ai_fetch_models),
                onClick = onFetchModels,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun AiModelItem(
    model: AiModelConfig,
    isCurrent: Boolean,
    style: V5DialogColors,
    onClick: () -> Unit
) {
    val visual = LocalVisualSpec.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = visual.shape(ShapeKind.BUTTON),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.modelId,
                    color = style.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = style.titleFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrent) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.ai_current_model),
                        color = style.accent,
                        fontSize = 12.sp,
                        fontFamily = style.bodyFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            AppGhostButton(
                text = stringResource(R.string.more),
                onClick = onClick
            )
        }
    }
}

// ── Fetched Model Selector Dialog (Compose) ──────────────────────────────────

@Composable
fun FetchedModelSelectorContent(
    modelIds: List<String>,
    existingIds: Set<String>,
    onAddSingle: (String) -> Unit,
    onAddSelected: (List<String>) -> Unit,
    onAddAll: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val style = rememberV5DialogColors()
    var searchQuery by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    val filteredModels = remember(modelIds, searchQuery) {
        if (searchQuery.isBlank()) modelIds
        else modelIds.filter { it.contains(searchQuery, ignoreCase = true) }
    }

    fun toggleSelection(id: String) {
        selectionMode = true
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    val summaryText = if (selectionMode) {
        stringResource(R.string.ai_fetch_models_selected_hint, selectedIds.size)
    } else {
        stringResource(R.string.ai_fetch_models_long_press_hint)
    }

    V5DialogPanel(
        title = stringResource(R.string.ai_add_model_from_list),
        scrollContent = false,
        content = {
            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.screen_find),
                        color = style.secondaryText,
                        fontSize = 14.sp
                    )
                },
                shape = RoundedCornerShape(style.actionRadius),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = style.primaryText,
                    unfocusedTextColor = style.primaryText,
                    focusedContainerColor = style.fieldSurface,
                    unfocusedContainerColor = style.fieldSurface,
                    cursorColor = style.accent,
                    focusedBorderColor = style.accent.copy(alpha = 0.55f),
                    unfocusedBorderColor = style.stroke
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = style.primaryText,
                    fontFamily = style.bodyFontFamily
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            // Summary
            Text(
                text = summaryText,
                color = style.secondaryText,
                fontSize = 13.sp,
                fontFamily = style.bodyFontFamily,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Model list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredModels, key = { it }) { modelId ->
                    val isExisting = modelId in existingIds
                    val isSelected = modelId in selectedIds
                    FetchedModelRow(
                        modelId = modelId,
                        existing = isExisting,
                        selected = isSelected,
                        selectionMode = selectionMode,
                        style = style,
                        onClick = {
                            if (selectionMode) {
                                toggleSelection(modelId)
                            } else {
                                onDismiss()
                                onAddSingle(modelId)
                            }
                        },
                        onLongClick = { toggleSelection(modelId) }
                    )
                }
            }
        },
        actions = {
            // Add All button
            AppGhostButton(
                text = stringResource(R.string.ai_add_all_models),
                onClick = {
                    onDismiss()
                    onAddAll(modelIds)
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            // Add Selected button
            AppFilledButton(
                text = if (selectedIds.isEmpty()) {
                    stringResource(R.string.ai_fetch_models_add_selected_empty)
                } else {
                    stringResource(R.string.ai_fetch_models_add_selected, selectedIds.size)
                },
                enabled = selectedIds.isNotEmpty(),
                onClick = {
                    onDismiss()
                    onAddSelected(selectedIds.toList())
                }
            )
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FetchedModelRow(
    modelId: String,
    existing: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    style: V5DialogColors,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val visual = LocalVisualSpec.current
    val rowColor = if (selected) style.fieldSurface else style.surface
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = visual.shape(ShapeKind.BUTTON),
        color = rowColor,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = style.accent,
                        uncheckedColor = style.secondaryText,
                        checkmarkColor = style.surface
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = modelId,
                    color = style.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = style.bodyFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when {
                        existing -> stringResource(R.string.ai_fetch_models_existing)
                        selectionMode -> stringResource(R.string.ai_fetch_models_click_toggle)
                        else -> stringResource(R.string.ai_fetch_models_click_add_long_select)
                    },
                    color = if (existing) style.accent else style.secondaryText,
                    fontSize = 12.sp,
                    fontFamily = style.bodyFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
