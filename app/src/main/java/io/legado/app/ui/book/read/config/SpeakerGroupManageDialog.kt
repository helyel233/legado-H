// __P1__

package io.legado.app.ui.book.read.config

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.ReadAloudSpeakerGroup
import io.legado.app.data.entities.ReadAloudSpeakerGroupItem
import io.legado.app.help.readaloud.ReadAloudConfigChangeNotifier
import io.legado.app.help.readaloud.speech.SpeechVoiceCatalogRepository
import io.legado.app.help.readaloud.speech.SpeechVoiceEngineGroup
import io.legado.app.help.readaloud.speech.SpeechVoiceGroupRepository
import io.legado.app.help.readaloud.speech.SpeechVoiceOption
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 发言人分组管理 V5 化。
 * 旧文件含 Activity + DialogFragment 双宿主（DialogFragment 全仓零引用，属死代码已删），
 * Activity 壳换为 setV5Content + HostV5Page；手搓主题配色卡片全部换 V5 组件。
 * 业务逻辑（DAO 流订阅/保存/失效判定/多选去重）逐行保留。
 */
class SpeakerGroupManageActivity : AppCompatActivity() {

    internal var groups by mutableStateOf<List<ReadAloudSpeakerGroup>>(emptyList())
    internal var items by mutableStateOf<List<ReadAloudSpeakerGroupItem>>(emptyList())
    internal var httpTtsList by mutableStateOf<List<HttpTTS>>(emptyList())
    internal var editorTarget by mutableStateOf<ReadAloudSpeakerGroup?>(null)
    internal var editingNew by mutableStateOf(false)
    internal var pickerTarget by mutableStateOf<ReadAloudSpeakerGroup?>(null)
    internal var deleteTarget by mutableStateOf<ReadAloudSpeakerGroup?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { SpeakerGroupManageContent(this@SpeakerGroupManageActivity) }
        lifecycleScope.launch {
            combine(
                appDb.readAloudSpeakerGroupDao.flowGroups(),
                appDb.readAloudSpeakerGroupDao.flowItems(),
                appDb.httpTTSDao.flowAll()
            ) { groupList, itemList, ttsList ->
                Triple(groupList, itemList, ttsList)
            }
                .catch { toastOnUi(it.localizedMessage ?: "读取发言人分组失败") }
                .flowOn(Dispatchers.IO)
                .collect { (groupList, itemList, ttsList) ->
                    groups = groupList
                    items = itemList
                    httpTtsList = ttsList
                }
        }
    }

    internal fun openNewGroupEditor() {
        editingNew = true
        editorTarget = null
    }

    internal fun openGroupEditor(group: ReadAloudSpeakerGroup) {
        editingNew = false
        editorTarget = group
    }

    internal fun closeGroupEditor() {
        editingNew = false
        editorTarget = null
    }

    internal fun saveGroup(group: ReadAloudSpeakerGroup?, name: String, enabled: Boolean) {
        val value = name.trim()
        if (value.isBlank()) {
            toastOnUi("分组名称不能为空")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val saved = (group ?: ReadAloudSpeakerGroup()).copy(
                name = value,
                enabled = if (SpeechVoiceGroupRepository.isInvalidGroupName(value)) false else enabled,
                sortOrder = group?.sortOrder ?: ((appDb.readAloudSpeakerGroupDao.maxGroupOrder() ?: -1) + 1),
                createdAt = group?.createdAt?.takeIf { it > 0L } ?: now,
                updatedAt = now
            )
            if (saved.id > 0L) appDb.readAloudSpeakerGroupDao.updateGroup(saved)
            else appDb.readAloudSpeakerGroupDao.insertGroup(saved)
            notifyConfigChanged()
            launch(Dispatchers.Main) { closeGroupEditor() }
        }
    }

    internal fun toggleGroup(group: ReadAloudSpeakerGroup) {
        if (SpeechVoiceGroupRepository.isInvalidGroup(group)) return
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.readAloudSpeakerGroupDao.updateGroup(
                group.copy(enabled = !group.enabled, updatedAt = System.currentTimeMillis())
            )
            notifyConfigChanged()
        }
    }

    internal fun requestDeleteGroup(group: ReadAloudSpeakerGroup) {
        deleteTarget = group
    }

    internal fun closeDeleteGroup() {
        deleteTarget = null
    }

    internal fun deleteGroup(group: ReadAloudSpeakerGroup) {
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.readAloudSpeakerGroupDao.deleteItemsByGroup(group.id)
            appDb.readAloudSpeakerGroupDao.deleteGroup(group.id)
            notifyConfigChanged()
            launch(Dispatchers.Main) { closeDeleteGroup() }
        }
    }

    internal fun openSpeakerPicker(group: ReadAloudSpeakerGroup) {
        pickerTarget = group
    }

    internal fun closeSpeakerPicker() {
        pickerTarget = null
    }

    internal fun addSpeakers(group: ReadAloudSpeakerGroup, options: List<SpeechVoiceOption>) {
        if (options.isEmpty()) {
            toastOnUi("请先选择发言人")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val existingKeys = appDb.readAloudSpeakerGroupDao.itemsByGroup(group.id)
                .map { speakerItemKey(it.engineType, it.engineValue, it.toneID, it.speakerName) }
                .toMutableSet()
            var sortOrder = (appDb.readAloudSpeakerGroupDao.maxItemOrder(group.id) ?: -1) + 1
            val newItems = options.mapNotNull { option ->
                val key = speakerItemKey(option.engineType, option.engineValue, option.toneID, option.speakerName)
                if (!existingKeys.add(key)) null
                else SpeechVoiceGroupRepository.itemFromOption(group.id, option, sortOrder++, now)
            }
            if (newItems.isNotEmpty()) {
                appDb.readAloudSpeakerGroupDao.insertItems(newItems)
                notifyConfigChanged()
            }
            launch(Dispatchers.Main) {
                closeSpeakerPicker()
                toastOnUi("已添加 ${newItems.size} 个发言人")
            }
        }
    }

    internal fun deleteItem(item: ReadAloudSpeakerGroupItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.readAloudSpeakerGroupDao.deleteItems(listOf(item.id))
            notifyConfigChanged()
        }
    }

    private fun notifyConfigChanged() {
        ReadAloudConfigChangeNotifier.notifySpeech()
    }
}

// __P2__

@Composable
private fun SpeakerGroupManageContent(activity: SpeakerGroupManageActivity) {
    val density = LocalVisualSpec.current.density
    val context = androidx.compose.ui.platform.LocalContext.current
    val engineGroups = remember(activity.httpTtsList) {
        SpeechVoiceCatalogRepository.allGroups(context, activity.httpTtsList, includeSystem = true)
    }

    BackHandler {
        if (activity.pickerTarget != null) activity.closeSpeakerPicker()
        else if (activity.editorTarget != null || activity.editingNew) activity.closeGroupEditor()
        else activity.finish()
    }

    HostV5Page(
        title = "发言人管理",
        onBack = { activity.finish() },
        bottomBar = {
            AppFilledButton(
                text = "新建分组",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = density.screenPadding,
                        vertical = density.blockGap / 2,
                    ),
                onClick = { activity.openNewGroupEditor() },
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "多角色自动分配会优先使用这里启用的发言人",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = density.screenPadding,
                    vertical = density.blockGap / 2,
                ),
            )
            if (activity.groups.isEmpty()) {
                AppEmpty(
                    title = "暂无发言人分组",
                    supporting = "点击下方「新建分组」创建",
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        horizontal = density.screenPadding,
                        vertical = density.blockGap / 4,
                    ),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    items(activity.groups, key = { it.id }) { group ->
                        SpeakerGroupCard(activity, group, engineGroups)
                    }
                }
            }
        }
    }

    if (activity.editingNew || activity.editorTarget != null) {
        SpeakerGroupEditorDialog(activity)
    }

    activity.pickerTarget?.let { group ->
        SpeakerMultiSelectDialog(activity, group, engineGroups)
    }

    activity.deleteTarget?.let { group ->
        AppConfirmDialog(
            title = "删除分组",
            message = "确定删除“${group.displayName()}”？不会删除原始 TTS 引擎。",
            confirmLabel = stringResource(R.string.delete),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = { activity.deleteGroup(group) },
            onDismiss = { activity.closeDeleteGroup() },
        )
    }
}

// __P3__

@Composable
private fun SpeakerGroupEditorDialog(activity: SpeakerGroupManageActivity) {
    val density = LocalVisualSpec.current.density
    val group = activity.editorTarget
    var name by remember(group?.id) { mutableStateOf(group?.name.orEmpty()) }
    var enabled by remember(group?.id) { mutableStateOf(group?.enabled ?: true) }

    AppDialog(onDismiss = { activity.closeGroupEditor() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
        ) {
            Text(
                text = if (group == null) "新建分组" else "编辑分组",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AppTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "分组名称",
                singleLine = true,
            )
            AppRowWithSwitch(
                title = "启用分组",
                checked = enabled,
                onCheckedChange = { enabled = it },
                card = false,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                AppGhostButton(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.weight(1f),
                    onClick = { activity.closeGroupEditor() },
                )
                AppFilledButton(
                    text = "保存",
                    modifier = Modifier.weight(1f),
                    onClick = { activity.saveGroup(group, name, enabled) },
                )
            }
        }
    }
}

private fun speakerItemKey(
    engineType: String,
    engineValue: String,
    toneID: String,
    speakerName: String
): String {
    return "$engineType|$engineValue|$toneID|$speakerName"
}

private fun buildSpeakerSourceGroups(
    engine: SpeechVoiceEngineGroup?,
    query: String
): List<SpeakerSourceGroupUi> {
    if (engine == null) return emptyList()
    val normalizedQuery = query.trim()
    return engine.options
        .asSequence()
        .filter { option -> option.matchesSpeakerQuery(normalizedQuery) }
        .groupBy { option ->
            option.groupId.ifBlank { option.groupName.ifBlank { "default" } }
        }
        .map { (sourceGroupId, options) ->
            val sourceName = options.firstOrNull()?.groupName?.takeIf { it.isNotBlank() }
                ?: if (engine.engineType == SpeechRoute.ENGINE_SYSTEM) "系统默认" else "默认分组"
            SpeakerSourceGroupUi(
                key = "${engine.key}:$sourceGroupId",
                title = sourceName,
                subtitle = buildList {
                    add("${options.size} 个发言人")
                    engine.title.takeIf { it.isNotBlank() && it != sourceName }?.let(::add)
                }.joinToString(" · "),
                options = options.sortedWith(
                    compareBy<SpeechVoiceOption> { it.speakerName }
                        .thenBy { it.toneID }
                )
            )
        }
        .sortedWith(compareBy<SpeakerSourceGroupUi> { it.title == "默认分组" }.thenBy { it.title })
}

private fun SpeechVoiceOption.matchesSpeakerQuery(query: String): Boolean {
    if (query.isBlank()) return true
    return listOf(speakerName, groupName, groupId, toneID, engineName)
        .any { it.contains(query, ignoreCase = true) }
}

@Composable
private fun SpeakerOptionSelectRow(
    option: SpeechVoiceOption,
    selected: Boolean,
    exists: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = when {
            exists -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = selected || exists, onCheckedChange = { onClick() }, enabled = !exists)
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    option.speakerName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOf(option.engineName, option.groupName, option.toneID).filter { it.isNotBlank() }
                        .joinToString(" · ").ifBlank { option.engineName },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (exists) {
                Text("已在组内", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class SpeakerSourceGroupUi(
    val key: String,
    val title: String,
    val subtitle: String,
    val options: List<SpeechVoiceOption>
)

// __P4__

@Composable
private fun SpeakerMultiSelectDialog(
    activity: SpeakerGroupManageActivity,
    targetGroup: ReadAloudSpeakerGroup,
    engineGroups: List<SpeechVoiceEngineGroup>,
) {
    val density = LocalVisualSpec.current.density
    val initialGroup = engineGroups.firstOrNull()
    var selectedEngineKey by remember(engineGroups) { mutableStateOf(initialGroup?.key.orEmpty()) }
    var selectedKeys by remember(targetGroup.id) { mutableStateOf<Set<String>>(emptySet()) }
    var searchText by remember(targetGroup.id) { mutableStateOf("") }
    var expandedGroupKeys by remember(selectedEngineKey) { mutableStateOf<Set<String>>(emptySet()) }
    val selectedEngine = engineGroups.firstOrNull { it.key == selectedEngineKey } ?: initialGroup
    val existingKeys = remember(activity.items) {
        activity.items
            .filter { it.groupId == targetGroup.id }
            .map { speakerItemKey(it.engineType, it.engineValue, it.toneID, it.speakerName) }.toSet()
    }
    val sourceGroups = remember(selectedEngine, searchText) {
        buildSpeakerSourceGroups(selectedEngine, searchText)
    }
    LaunchedEffect(selectedEngineKey, sourceGroups.firstOrNull()?.key) {
        if (expandedGroupKeys.isEmpty()) {
            expandedGroupKeys = sourceGroups.firstOrNull()?.let { setOf(it.key) }.orEmpty()
        }
    }

    AppDialog(onDismiss = { activity.closeSpeakerPicker() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(density.screenPadding),
        ) {
            Text(
                text = "添加发言人",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = targetGroup.displayName(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = density.blockGap / 2),
                horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                engineGroups.forEach { group ->
                    AppChip(
                        text = group.title,
                        selected = selectedEngineKey == group.key,
                        onClick = { selectedEngineKey = group.key },
                    )
                }
            }
            AppTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier.padding(top = density.blockGap / 2),
                placeholder = "搜索发言人 / 分组 / toneID",
                singleLine = true,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 430.dp)
                    .padding(top = density.blockGap / 2),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                if (sourceGroups.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("没有匹配的发言人", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    sourceGroups.forEach { sourceGroup ->
                        val visibleKeys = sourceGroup.options.map {
                            speakerItemKey(it.engineType, it.engineValue, it.toneID, it.speakerName)
                        }
                        val selectableKeys = visibleKeys.filterNot { it in existingKeys }
                        val allSelected = selectableKeys.isNotEmpty() && selectableKeys.all { it in selectedKeys }
                        val expanded = searchText.isNotBlank() || sourceGroup.key in expandedGroupKeys
                        item(key = "group:${sourceGroup.key}") {
                            SpeakerSourceGroupHeaderRow(
                                sourceGroup = sourceGroup,
                                expanded = expanded,
                                allSelected = allSelected,
                                selectableCount = selectableKeys.size,
                                selectedCount = selectableKeys.count { it in selectedKeys },
                                onExpandToggle = {
                                    expandedGroupKeys = if (expanded) {
                                        expandedGroupKeys - sourceGroup.key
                                    } else {
                                        expandedGroupKeys + sourceGroup.key
                                    }
                                },
                                onSelectToggle = {
                                    selectedKeys = if (allSelected) {
                                        selectedKeys - selectableKeys.toSet()
                                    } else {
                                        selectedKeys + selectableKeys
                                    }
                                }
                            )
                        }
                        if (expanded) {
                            items(
                                sourceGroup.options,
                                key = { option ->
                                    "speaker:${sourceGroup.key}:${speakerItemKey(option.engineType, option.engineValue, option.toneID, option.speakerName)}"
                                }
                            ) { option ->
                                val key = speakerItemKey(option.engineType, option.engineValue, option.toneID, option.speakerName)
                                val exists = key in existingKeys
                                val selected = key in selectedKeys
                                SpeakerOptionSelectRow(
                                    option = option,
                                    selected = selected,
                                    exists = exists,
                                ) {
                                    if (!exists) {
                                        selectedKeys = if (selected) selectedKeys - key else selectedKeys + key
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.padding(top = density.blockGap / 2),
                horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                AppGhostButton(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.weight(1f),
                    onClick = { activity.closeSpeakerPicker() },
                )
                AppFilledButton(
                    text = "添加 ${selectedKeys.size}",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val picked = engineGroups
                            .flatMap { it.options }
                            .filter { option ->
                                speakerItemKey(option.engineType, option.engineValue, option.toneID, option.speakerName) in selectedKeys
                            }
                        activity.addSpeakers(targetGroup, picked)
                    },
                )
            }
        }
    }
}

@Composable
private fun SpeakerSourceGroupHeaderRow(
    sourceGroup: SpeakerSourceGroupUi,
    expanded: Boolean,
    allSelected: Boolean,
    selectableCount: Int,
    selectedCount: Int,
    onExpandToggle: () -> Unit,
    onSelectToggle: () -> Unit
) {
    Surface(
        onClick = onExpandToggle,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    sourceGroup.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildList {
                        add(if (expanded) "已展开" else "已收起")
                        add(sourceGroup.subtitle)
                        if (selectedCount > 0) add("已选 $selectedCount")
                    }.filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (allSelected) "取消全选" else "全选",
                style = MaterialTheme.typography.labelLarge,
                color = if (selectableCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .then(
                        if (selectableCount > 0) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onSelectToggle,
                            )
                        } else {
                            Modifier
                        }
                    ),
            )
        }
    }
}

@Composable
private fun SpeakerGroupCard(
    activity: SpeakerGroupManageActivity,
    group: ReadAloudSpeakerGroup,
    engineGroups: List<SpeechVoiceEngineGroup>,
) {
    val density = LocalVisualSpec.current.density
    val invalidGroup = SpeechVoiceGroupRepository.isInvalidGroup(group)
    val groupItems = activity.items.filter { it.groupId == group.id }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
        ) {
            AppRowWithSwitch(
                title = group.displayName(),
                summary = if (invalidGroup) {
                    "${groupItems.size} 个失效发言人 · 不参与自动分配"
                } else {
                    "${groupItems.size} 个发言人 · ${if (group.enabled) "已启用" else "已停用"}"
                },
                checked = if (invalidGroup) false else group.enabled,
                onCheckedChange = { activity.toggleGroup(group) },
                card = false,
                enabled = !invalidGroup,
            )
            if (groupItems.isEmpty()) {
                Text(
                    if (invalidGroup) "没有被标记失效的发言人" else "这个分组还没有发言人",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    groupItems.take(8).forEach { item ->
                        SpeakerItemRow(activity, item)
                    }
                    if (groupItems.size > 8) {
                        Text(
                            "还有 ${groupItems.size - 8} 个发言人未展开",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                if (!invalidGroup) {
                    AppGhostButton("添加发言人", Modifier.weight(1f)) { activity.openSpeakerPicker(group) }
                    AppGhostButton("重命名", Modifier.weight(1f)) { activity.openGroupEditor(group) }
                }
                AppGhostButton("删除", Modifier.weight(1f)) { activity.requestDeleteGroup(group) }
            }
        }
    }
}

@Composable
private fun SpeakerItemRow(
    activity: SpeakerGroupManageActivity,
    item: ReadAloudSpeakerGroupItem,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        val blocked = SpeechVoiceGroupRepository.isBlockedItem(item)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.displayName(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(
                        "已失效".takeIf { blocked },
                        item.engineName,
                        item.sourceGroupName,
                        item.toneID
                    ).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AppGhostButton("移除") { activity.deleteItem(item) }
        }
    }
}
