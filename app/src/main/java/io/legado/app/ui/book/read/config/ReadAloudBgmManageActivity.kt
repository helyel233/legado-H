// __P1__

package io.legado.app.ui.book.read.config

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadAloudBgmGroup
import io.legado.app.data.entities.ReadAloudBgmTrack
import io.legado.app.help.readaloud.ReadAloudConfigChangeNotifier
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppNumberPickerDialog
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 智能音频（配乐/音效）管理 V5 化。
 * 旧版 BaseActivity + 手搓 LinearLayout 行 + alert/selector/NumberPickerDialog，
 * 全部替换为 setV5Content + HostV5Page + Compose 分组列表 + v5 弹窗。
 * 业务逻辑（导入/ZIP 包/YAML 元数据/导出/DAO）逐行保留，仅换 UI 承载。
 */
class ReadAloudBgmManageActivity : AppCompatActivity() {

    internal var groups by mutableStateOf<List<ReadAloudBgmGroup>>(emptyList())
    internal var tracks by mutableStateOf<List<ReadAloudBgmTrack>>(emptyList())
    internal var selectedIds by mutableStateOf<Set<Long>>(emptySet())
    internal var expandedGroupIds by mutableStateOf<Set<Long>>(emptySet())
    internal var expandedGroupsInitialized by mutableStateOf(false)
    internal var currentAssetType by mutableStateOf(ReadAloudBgmTrack.TYPE_BGM)
    internal var importing by mutableStateOf(false)
    internal var importingText by mutableStateOf("")

    internal var overflowMenuOpen by mutableStateOf(false)
    internal var importMenuOpen by mutableStateOf(false)
    internal var zipImportChoice by mutableStateOf<Pair<Uri, String>?>(null)
    internal var groupEditorTarget by mutableStateOf<ReadAloudBgmGroup?>(null)
    internal var groupEditorOpen by mutableStateOf(false)
    internal var groupManageOpen by mutableStateOf(false)
    internal var groupActionTarget by mutableStateOf<ReadAloudBgmGroup?>(null)
    internal var tagsEditorTarget by mutableStateOf<ReadAloudBgmTrack?>(null)
    internal var volumeTarget by mutableStateOf<ReadAloudBgmTrack?>(null)
    internal var moveTracksIds by mutableStateOf<List<Long>?>(null)
    internal var deleteTracksIds by mutableStateOf<List<Long>?>(null)
    internal var deleteGroupTarget by mutableStateOf<ReadAloudBgmGroup?>(null)
    internal var trackMenuTarget by mutableStateOf<ReadAloudBgmTrack?>(null)

    private var pendingPackageAssetType: String = ReadAloudBgmTrack.TYPE_SFX
    private var pendingExportPackageFile: File? = null

    internal val currentAssetLabel: String
        get() = assetLabel(currentAssetType)

    private val importAudio = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            if (importing) return@registerForActivityResult
            lifecycleScope.launch {
                setImporting(true, "正在导入${currentAssetLabel}…")
                kotlin.runCatching {
                    withContext(Dispatchers.IO) { importTrack(uri) }
                }.onSuccess {
                    selectedIds = emptySet()
                    notifyAudioChanged()
                    finishImportingAndLoad()
                    toastOnUi("导入完成")
                }.onFailure {
                    finishImportingAndLoad()
                    toastOnUi(it.localizedMessage ?: "导入失败")
                }
            }
        }
    }

    private val importAudios = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        if (importing) return@registerForActivityResult
        lifecycleScope.launch {
            setImporting(true, "正在批量导入${currentAssetLabel}…")
            var success = 0
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    uris.forEach { uri ->
                        runCatching {
                            importTrack(uri)
                            success += 1
                        }
                    }
                }
            }.onSuccess {
                selectedIds = emptySet()
                if (success > 0) notifyAudioChanged()
                finishImportingAndLoad()
                toastOnUi("已导入 $success 个${currentAssetLabel}")
            }.onFailure {
                finishImportingAndLoad()
                toastOnUi(it.localizedMessage ?: "批量导入失败")
            }
        }
    }

    private val importAudioPackage = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            val assetType = pendingPackageAssetType
            zipImportChoice = uri to assetType
        }
    }

    private val exportAudioPackage = registerForActivityResult(HandleFileContract()) { result ->
        pendingExportPackageFile?.delete()
        pendingExportPackageFile = null
        result.uri?.let {
            toastOnUi("已导出${currentAssetLabel} ZIP")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { ReadAloudBgmManageContent(this@ReadAloudBgmManageActivity) }
        load()
    }

    override fun onDestroy() {
        pendingExportPackageFile?.delete()
        pendingExportPackageFile = null
        super.onDestroy()
    }

    private fun load() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                appDb.readAloudBgmDao.groupsByType(currentAssetType) to
                    appDb.readAloudBgmDao.enabledTracksByType(currentAssetType)
            }
            groups = data.first
            tracks = data.second
            selectedIds = selectedIds.filterTo(LinkedHashSet()) { id -> tracks.any { it.id == id } }
        }
    }

    internal fun switchAssetType(assetType: String) {
        val normalized = ReadAloudBgmTrack.normalizeAssetType(assetType)
        if (currentAssetType == normalized) return
        currentAssetType = normalized
        selectedIds = emptySet()
        expandedGroupIds = emptySet()
        expandedGroupsInitialized = false
        load()
    }

    private fun setImporting(value: Boolean, text: String = "") {
        importing = value
        importingText = if (value) text else ""
    }

    private fun finishImportingAndLoad() {
        setImporting(false)
        load()
    }

    internal fun assetLabel(assetType: String): String {
        return if (ReadAloudBgmTrack.normalizeAssetType(assetType) == ReadAloudBgmTrack.TYPE_SFX) {
            "音效"
        } else {
            "配乐"
        }
    }

    private fun notifyAudioChanged() {
        ReadAloudConfigChangeNotifier.notifyAudio()
    }

    internal fun onOverflowAction(index: Int) {
        overflowMenuOpen = false
        when (index) {
            0 -> showImportActions()
            1 -> exportCurrentAudioPackage()
            2 -> showGroupEditor()
            3 -> showGroupManage()
        }
    }

    internal fun showImportActions() {
        if (importing) {
            toastOnUi(importingText.ifBlank { "正在导入，请稍候" })
            return
        }
        importMenuOpen = true
    }

    internal fun onImportAction(index: Int) {
        importMenuOpen = false
        when (index) {
            0 -> importAudios.launch("audio/*")
            1 -> importAudio.launch {
                mode = HandleFileContract.FILE
                title = "导入${currentAssetLabel}"
                allowExtensions = arrayOf("mp3", "wav", "m4a", "aac", "ogg", "flac")
            }
            2 -> openAudioPackageImport(ReadAloudBgmTrack.TYPE_BGM)
            3 -> openAudioPackageImport(ReadAloudBgmTrack.TYPE_SFX)
            4 -> showGroupEditor()
            5 -> showGroupManage()
        }
    }

    private fun openAudioPackageImport(assetType: String) {
        if (importing) return
        pendingPackageAssetType = ReadAloudBgmTrack.normalizeAssetType(assetType)
        importAudioPackage.launch {
            mode = HandleFileContract.FILE
            title = "导入${assetLabel(pendingPackageAssetType)} ZIP"
            allowExtensions = arrayOf("zip")
        }
    }

    internal fun onZipImportChoice(replaceOld: Boolean) {
        val (uri, assetType) = zipImportChoice ?: return
        zipImportChoice = null
        val normalized = ReadAloudBgmTrack.normalizeAssetType(assetType)
        val label = assetLabel(normalized)
        lifecycleScope.launch {
            setImporting(true, "正在导入${label} ZIP…")
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    importAudioPackage(uri, normalized, replaceOld = replaceOld)
                }
            }.onSuccess { count ->
                currentAssetType = normalized
                selectedIds = emptySet()
                expandedGroupIds = emptySet()
                expandedGroupsInitialized = false
                notifyAudioChanged()
                finishImportingAndLoad()
                toastOnUi("已导入 $count 个${label}")
            }.onFailure {
                finishImportingAndLoad()
                toastOnUi(it.localizedMessage ?: "音频包导入失败")
            }
        }
    }

// __P2__

    private fun exportCurrentAudioPackage() {
        if (importing) {
            toastOnUi(importingText.ifBlank { "正在处理，请稍候" })
            return
        }
        val assetType = currentAssetType
        val label = assetLabel(assetType)
        lifecycleScope.launch {
            setImporting(true, "正在整理${label} ZIP…")
            val result = kotlin.runCatching {
                withContext(Dispatchers.IO) { buildAudioPackageZip(assetType) }
            }
            finishImportingAndLoad()
            result.onSuccess { data ->
                pendingExportPackageFile?.delete()
                pendingExportPackageFile = data
                exportAudioPackage.launch {
                    mode = HandleFileContract.EXPORT
                    title = "导出${label} ZIP"
                    fileData = HandleFileContract.FileData(
                        "${label}包_${System.currentTimeMillis()}.zip",
                        data,
                        "application/zip"
                    )
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: "${label} ZIP 导出失败")
            }
        }
    }

    internal fun showGroupEditor(group: ReadAloudBgmGroup? = null) {
        if (group != null) {
            importMenuOpen = false
            groupManageOpen = false
            groupActionTarget = null
        }
        groupEditorTarget = group
        groupEditorOpen = true
    }

    internal fun onGroupEditorConfirmed(name: String) {
        val group = groupEditorTarget
        groupEditorOpen = false
        groupEditorTarget = null
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            toastOnUi("分组名称不能为空")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (group == null) {
                appDb.readAloudBgmDao.insertGroup(
                    ReadAloudBgmGroup(
                        name = trimmed,
                        assetType = currentAssetType,
                        sortOrder = (appDb.readAloudBgmDao.maxGroupOrderByType(currentAssetType) ?: 0) + 1,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            } else {
                appDb.readAloudBgmDao.updateGroup(group.copy(name = trimmed, updatedAt = now))
            }
            notifyAudioChanged()
            launch(Dispatchers.Main) { load() }
        }
    }

    internal fun showGroupManage() {
        importMenuOpen = false
        overflowMenuOpen = false
        val managedGroups = groups.filterNot { it.isDefaultGroup() }
        if (managedGroups.isEmpty()) {
            toastOnUi("暂无分组")
            return
        }
        groupManageOpen = true
    }

    internal fun onGroupManageSelected(group: ReadAloudBgmGroup) {
        groupManageOpen = false
        groupActionTarget = group
    }

    internal fun onGroupAction(index: Int) {
        val group = groupActionTarget ?: return
        groupActionTarget = null
        when (index) {
            0 -> showGroupEditor(group)
            1 -> confirmDeleteGroup(group)
        }
    }

    private fun confirmDeleteGroup(group: ReadAloudBgmGroup) {
        if (group.isDefaultGroup()) {
            toastOnUi("默认分组不能删除")
            return
        }
        deleteGroupTarget = group
    }

    internal fun onDeleteGroupConfirmed() {
        val group = deleteGroupTarget ?: return
        deleteGroupTarget = null
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.readAloudBgmDao.resetTrackGroup(group.id, currentAssetType)
            appDb.readAloudBgmDao.deleteGroup(group.id, currentAssetType)
            selectedIds = emptySet()
            notifyAudioChanged()
            launch(Dispatchers.Main) { load() }
        }
    }

// __P3__

    internal fun onTrackAction(index: Int) {
        val track = trackMenuTarget ?: return
        trackMenuTarget = null
        when (index) {
            0 -> tagsEditorTarget = track
            1 -> volumeTarget = track
            2 -> moveTracks(listOf(track.id))
            3 -> deleteTracksIds = listOf(track.id)
        }
    }

    internal fun onTagsConfirmed(tags: String) {
        val track = tagsEditorTarget ?: return
        tagsEditorTarget = null
        val normalized = tags.split(',', '，')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.readAloudBgmDao.updateTrack(track.copy(tags = normalized, updatedAt = System.currentTimeMillis()))
            notifyAudioChanged()
            launch(Dispatchers.Main) { load() }
        }
    }

    internal fun onVolumeConfirmed(value: Int) {
        val track = volumeTarget ?: return
        volumeTarget = null
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.readAloudBgmDao.updateTrack(
                track.copy(
                    defaultVolume = (value / 100f).coerceIn(0f, 1f),
                    updatedAt = System.currentTimeMillis()
                )
            )
            notifyAudioChanged()
            launch(Dispatchers.Main) { load() }
        }
    }

    internal fun moveSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) {
            toastOnUi("请先长按选择${currentAssetLabel}")
            return
        }
        moveTracks(ids)
    }

    internal fun moveTracks(ids: List<Long>) {
        moveTracksIds = ids
    }

    internal fun onMoveTargetSelected(groupId: Long) {
        val ids = moveTracksIds ?: return
        moveTracksIds = null
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.readAloudBgmDao.moveTracks(ids, groupId)
            selectedIds = emptySet()
            notifyAudioChanged()
            launch(Dispatchers.Main) { load() }
        }
    }

    internal fun deleteSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) {
            toastOnUi("请先长按选择${currentAssetLabel}")
            return
        }
        deleteTracksIds = ids
    }

    internal fun onDeleteTracksConfirmed() {
        val ids = deleteTracksIds ?: return
        deleteTracksIds = null
        lifecycleScope.launch(Dispatchers.IO) {
            ids.mapNotNull { appDb.readAloudBgmDao.track(it) }.forEach { track ->
                runCatching { File(track.filePath).delete() }
            }
            appDb.readAloudBgmDao.deleteTracks(ids)
            selectedIds = emptySet()
            notifyAudioChanged()
            launch(Dispatchers.Main) { load() }
        }
    }

    internal fun toggleGroup(groupId: Long) {
        expandedGroupIds = if (groupId in expandedGroupIds) {
            expandedGroupIds - groupId
        } else {
            expandedGroupIds + groupId
        }
    }

    internal fun toggleGroupSelection(groupId: Long) {
        val ids = tracks.filter { it.groupId == groupId }.map { it.id }
        if (ids.isEmpty()) return
        selectedIds = if (ids.all { it in selectedIds }) {
            selectedIds - ids.toSet()
        } else {
            selectedIds + ids.toSet()
        }
    }

    internal fun toggleTrack(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    internal fun groupName(groupId: Long): String {
        if (groupId == 0L) return "默认分组"
        return groups.firstOrNull { it.id == groupId }?.displayName() ?: "默认分组"
    }

    internal fun buildRows(): List<AudioRow> {
        val tracksByGroup = tracks.groupBy { it.groupId }
        val rowGroups = (listOf(ReadAloudBgmGroup(id = 0L, name = "默认分组", assetType = currentAssetType, sortOrder = Int.MIN_VALUE)) + groups)
            .distinctBy { it.id }
            .filter { group -> group.id != 0L || tracksByGroup.containsKey(0L) || tracks.isEmpty() }
            .sortedWith(compareBy<ReadAloudBgmGroup> { it.sortOrder }.thenBy { it.id })
        if (!expandedGroupsInitialized) {
            expandedGroupIds = rowGroups.filterNot { it.isDefaultGroup() }.map { it.id }.toSet()
            expandedGroupsInitialized = true
        } else {
            expandedGroupIds = expandedGroupIds.filter { id -> rowGroups.any { it.id == id } }.toSet()
        }
        return buildList {
            rowGroups.forEach { group ->
                val groupTracks = tracksByGroup[group.id].orEmpty()
                val expanded = group.id in expandedGroupIds
                add(
                    AudioRow.GroupHeader(
                        group = group,
                        count = groupTracks.size,
                        selectedCount = groupTracks.count { it.id in selectedIds },
                        expanded = expanded
                    )
                )
                if (expanded) {
                    groupTracks.forEach { track ->
                        add(AudioRow.TrackItem(track))
                    }
                }
            }
        }
    }

    internal sealed class AudioRow {
        data class GroupHeader(
            val group: ReadAloudBgmGroup,
            val count: Int,
            val selectedCount: Int,
            val expanded: Boolean
        ) : AudioRow()

        data class TrackItem(val track: ReadAloudBgmTrack) : AudioRow()
    }

    private fun ReadAloudBgmGroup.isDefaultGroup(): Boolean {
        return id == 0L || name == "默认分组"
    }

    private suspend fun importTrack(uri: Uri) {
        val groupId = ensureDefaultGroup(currentAssetType)
        val displayName = displayName(uri).ifBlank { "${currentAssetType}-${System.currentTimeMillis()}" }
        val extension = displayName.substringAfterLast('.', "mp3").lowercase()
        val folder = File(filesDir, "readAloudAudio/$currentAssetType").apply { mkdirs() }
        val target = File(folder, "${System.currentTimeMillis()}_${displayName.toSafeFileName(extension)}")
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取音频文件")
        val checksum = target.inputStream().use { MD5Utils.md5Encode(it) }
        val duration = readDuration(target)
        val now = System.currentTimeMillis()
        appDb.readAloudBgmDao.insertTrack(
            ReadAloudBgmTrack(
                groupId = groupId,
                assetType = currentAssetType,
                name = displayName.substringBeforeLast('.'),
                fileName = displayName,
                filePath = target.absolutePath,
                checksum = checksum,
                durationMs = duration,
                sortOrder = (appDb.readAloudBgmDao.maxTrackOrder(groupId, currentAssetType) ?: 0) + 1,
                createdAt = now,
                updatedAt = now
            )
        )
    }

// __P4__

    private suspend fun importAudioPackage(
        uri: Uri,
        assetType: String,
        replaceOld: Boolean
    ): Int {
        val normalizedType = ReadAloudBgmTrack.normalizeAssetType(assetType)
        val metadata = readAudioPackageMetadata(uri)
        if (replaceOld) {
            appDb.readAloudBgmDao.tracksByType(normalizedType).forEach { track ->
                runCatching { File(track.filePath).delete() }
            }
            appDb.readAloudBgmDao.deleteTracksByType(normalizedType)
            appDb.readAloudBgmDao.deleteGroupsByType(normalizedType)
        }
        val folder = File(filesDir, "readAloudAudio/$normalizedType").apply { mkdirs() }
        val groupIds = mutableMapOf<String, Long>()
        val nextSortByGroup = mutableMapOf<Long, Int>()
        var imported = 0
        contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    if (!entry.isDirectory && entryName.isSupportedAudioEntry()) {
                        val fileName = entryName.substringAfterLast('/').ifBlank {
                            "audio-${System.currentTimeMillis()}.mp3"
                        }
                        val info = metadata[fileName.lowercase(Locale.ROOT)]
                            ?: metadata[entryName.substringAfterLast('/').lowercase(Locale.ROOT)]
                        val extension = fileName.substringAfterLast('.', "mp3").lowercase(Locale.ROOT)
                        val target = File(
                            folder,
                            "${System.currentTimeMillis()}_${imported}_${fileName.toSafeFileName(extension)}"
                        )
                        target.outputStream().use { output -> zip.copyTo(output) }
                        val checksum = target.inputStream().use { MD5Utils.md5Encode(it) }
                        val fallbackGroup = fallbackGroupName(entryName)
                        val groupName = info?.groupName?.ifBlank { fallbackGroup } ?: fallbackGroup
                        val groupId = groupIds.getOrPut(groupName) { ensureGroup(groupName, normalizedType) }
                        val sortOrder = nextSortByGroup.getOrPut(groupId) {
                            (appDb.readAloudBgmDao.maxTrackOrder(groupId, normalizedType) ?: 0) + 1
                        }
                        nextSortByGroup[groupId] = sortOrder + 1
                        val now = System.currentTimeMillis()
                        appDb.readAloudBgmDao.insertTrack(
                            ReadAloudBgmTrack(
                                groupId = groupId,
                                assetType = normalizedType,
                                name = info?.name?.ifBlank { fallbackTrackName(fileName) }
                                    ?: fallbackTrackName(fileName),
                                fileName = fileName,
                                filePath = target.absolutePath,
                                tags = info?.tags?.ifBlank { fallbackTags(groupName, fileName) }
                                    ?: fallbackTags(groupName, fileName),
                                checksum = checksum,
                                durationMs = readDuration(target),
                                defaultVolume = (info?.volume ?: 1f).coerceIn(0f, 1f),
                                sortOrder = sortOrder,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                        imported += 1
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: error("无法读取音频包")
        if (imported == 0) error("音频包内没有可导入的音频")
        return imported
    }

    private fun buildAudioPackageZip(assetType: String): File {
        val normalizedType = ReadAloudBgmTrack.normalizeAssetType(assetType)
        val exportTracks = appDb.readAloudBgmDao.enabledTracksByType(normalizedType)
        if (exportTracks.isEmpty()) error("暂无可导出的${assetLabel(normalizedType)}")
        val exportGroups = appDb.readAloudBgmDao.groupsByType(normalizedType).associateBy { it.id }
        val entries = buildPackageExportEntries(exportTracks, exportGroups)
        if (entries.isEmpty()) error("没有可导出的本地音频文件")
        val output = File.createTempFile("read_aloud_${normalizedType}_", ".zip", cacheDir)
        runCatching {
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("config.yaml"))
                zip.write(buildPackageConfigYaml(entries).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                entries.forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.entryName))
                    entry.file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }.onFailure {
            output.delete()
            throw it
        }
        return output
    }

    private fun buildPackageExportEntries(
        exportTracks: List<ReadAloudBgmTrack>,
        exportGroups: Map<Long, ReadAloudBgmGroup>
    ): List<PackageExportEntry> {
        val usedNames = linkedSetOf<String>()
        return exportTracks.mapNotNull { track ->
            val file = File(track.filePath)
            if (!file.exists() || file.length() <= 0L) return@mapNotNull null
            val groupName = exportGroups[track.groupId]?.name
                ?.takeUnless { it == "默认分组" }
                ?: "默认分组"
            val extension = track.fileName.substringAfterLast('.', "mp3").lowercase(Locale.ROOT)
            val safeFileName = track.fileName
                .ifBlank { "${track.displayName()}.$extension" }
                .toSafeFileName(extension)
            val folder = groupName.toZipPathSegment()
            var entryName = "$folder/$safeFileName"
            var index = 1
            while (!usedNames.add(entryName.lowercase(Locale.ROOT))) {
                val base = safeFileName.substringBeforeLast('.', safeFileName)
                val ext = safeFileName.substringAfterLast('.', "")
                entryName = if (ext.isBlank()) {
                    "$folder/${base}_$index"
                } else {
                    "$folder/${base}_$index.$ext"
                }
                index += 1
            }
            PackageExportEntry(
                track = track,
                groupName = groupName,
                entryName = entryName,
                file = file
            )
        }
    }

    private fun buildPackageConfigYaml(entries: List<PackageExportEntry>): String {
        return buildString {
            entries.forEach { entry ->
                val track = entry.track
                val tags = track.tags.split(',', '，')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                val desc = (listOf(entry.groupName) + tags)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString("｜")
                appendLine("- name: ${track.displayName().toYamlScalar()}")
                appendLine("  desc: ${desc.toYamlScalar()}")
                appendLine("  param: ${entry.entryName.toYamlScalar()}")
                appendLine("  volume: ${track.defaultVolume.coerceIn(0f, 1f)}")
            }
        }
    }

    private fun readAudioPackageMetadata(uri: Uri): Map<String, PackageAudioInfo> {
        val configText = contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.substringAfterLast('/') == "config.yaml") {
                        return@use zip.readTextLimited(MAX_AUDIO_PACKAGE_CONFIG_BYTES)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                ""
            }
        }.orEmpty()
        return parsePackageAudioConfig(configText)
    }

    private fun ZipInputStream.readTextLimited(maxBytes: Int): String {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "音频包配置文件过大" }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun parsePackageAudioConfig(text: String): Map<String, PackageAudioInfo> {
        if (text.isBlank()) return emptyMap()
        val result = linkedMapOf<String, PackageAudioInfo>()
        var name = ""
        var desc = ""
        var param = ""
        var volume = 1f

        fun commit() {
            val fileName = param.substringAfterLast('/').trim()
            if (!fileName.isSupportedAudioFileName()) return
            val groupName = desc.substringBefore('｜')
                .substringBefore('|')
                .trim()
                .ifBlank { "默认分组" }
            val tags = desc.replace('｜', ',')
                .replace('|', ',')
                .split(',', '，')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(",")
            result[fileName.lowercase(Locale.ROOT)] = PackageAudioInfo(
                name = name.ifBlank { fileName.substringBeforeLast('.') },
                groupName = groupName,
                tags = tags,
                volume = volume
            )
        }

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trimStart()
            if (line.startsWith("- ")) {
                commit()
                name = ""
                desc = ""
                param = ""
                volume = 1f
            }
            when {
                line.startsWith("name:") -> name = line.substringAfter(':').cleanYamlScalar()
                line.startsWith("desc:") -> desc = line.substringAfter(':').cleanYamlScalar()
                line.startsWith("param:") -> param = line.substringAfter(':').cleanYamlScalar()
                line.startsWith("volume:") -> volume = line.substringAfter(':').cleanYamlScalar()
                    .toFloatOrNull()
                    ?: 1f
            }
        }
        commit()
        return result
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty().substringAfterLast('/')
    }

    private fun readDuration(file: File): Long {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
    }

    private fun ensureDefaultGroup(assetType: String = currentAssetType): Long {
        val normalized = ReadAloudBgmTrack.normalizeAssetType(assetType)
        groups.firstOrNull { (it.id == 0L || it.name == "默认分组") && it.assetType == normalized }?.let { return it.id }
        appDb.readAloudBgmDao.groupsByType(normalized).firstOrNull { it.name == "默认分组" }?.let { return it.id }
        val now = System.currentTimeMillis()
        return appDb.readAloudBgmDao.insertGroup(
            ReadAloudBgmGroup(name = "默认分组", assetType = normalized, sortOrder = 0, createdAt = now, updatedAt = now)
        )
    }

    private fun ensureGroup(name: String, assetType: String = currentAssetType): Long {
        val normalizedType = ReadAloudBgmTrack.normalizeAssetType(assetType)
        val normalized = name.trim().ifBlank { "默认分组" }
        if (normalized == "默认分组") return ensureDefaultGroup(normalizedType)
        appDb.readAloudBgmDao.groupsByType(normalizedType).firstOrNull { it.name == normalized }?.let { return it.id }
        val now = System.currentTimeMillis()
        return appDb.readAloudBgmDao.insertGroup(
            ReadAloudBgmGroup(
                name = normalized,
                assetType = normalizedType,
                sortOrder = (appDb.readAloudBgmDao.maxGroupOrderByType(normalizedType) ?: 0) + 1,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    private fun String.toSafeFileName(extension: String): String {
        val safe = replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "$currentAssetType.$extension" }
        return if (safe.contains('.')) safe else "$safe.$extension"
    }

    private fun String.toZipPathSegment(): String {
        return replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { "默认分组" }
    }

    private fun String.toYamlScalar(): String {
        return "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun String.cleanYamlScalar(): String {
        val value = trim()
        if (value.isBlank() || value == "null" || value.startsWith("|")) return ""
        return value.trim('"', '\'')
    }

    private fun fallbackGroupName(entryName: String): String {
        val firstSegment = entryName.split('/').firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != entryName.substringAfterLast('/') }
        return firstSegment ?: "默认分组"
    }

    private fun fallbackTrackName(fileName: String): String {
        return fileName.substringBeforeLast('.')
            .replace(Regex("""^\d+[-_ ]*"""), "")
            .trim()
            .ifBlank { fileName.substringBeforeLast('.') }
    }

    private fun fallbackTags(groupName: String, fileName: String): String {
        val bracketTags = Regex("""[（(【\[]([^）)】\]]+)[）)】\]]""")
            .findAll(fileName)
            .map { it.groupValues.getOrNull(1).orEmpty().trim() }
            .filter { it.isNotBlank() }
        return (sequenceOf(groupName.takeIf { it != "默认分组" }.orEmpty()) + bracketTags)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
    }

    private fun String.isSupportedAudioEntry(): Boolean {
        return substringAfterLast('/').isSupportedAudioFileName()
    }

    private fun String.isSupportedAudioFileName(): Boolean {
        val ext = substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in supportedAudioExtensions
    }

    private companion object {
        private const val MAX_AUDIO_PACKAGE_CONFIG_BYTES = 1024 * 1024
        private val supportedAudioExtensions = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac")
    }

    private data class PackageAudioInfo(
        val name: String,
        val groupName: String,
        val tags: String,
        val volume: Float
    )

    private data class PackageExportEntry(
        val track: ReadAloudBgmTrack,
        val groupName: String,
        val entryName: String,
        val file: File
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReadAloudBgmManageContent(activity: ReadAloudBgmManageActivity) {
    val density = LocalVisualSpec.current.density
    val rows = activity.buildRows()

    HostV5Page(
        title = "智能音频",
        onBack = { activity.finish() },
        trailing = {
            AppOverflowMenuButton(
                contentDescription = stringResource(R.string.more),
                onClick = { activity.overflowMenuOpen = true },
            )
        },
        bottomBar = {
            if (activity.selectedIds.isNotEmpty() && !activity.importing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = density.screenPadding,
                            vertical = density.blockGap / 2,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    AppGhostButton("批量分组", Modifier.weight(1f)) { activity.moveSelected() }
                    AppGhostButton("删除", Modifier.weight(1f)) { activity.deleteSelected() }
                    AppGhostButton("取消选择", Modifier.weight(1f)) { activity.selectedIds = emptySet() }
                }
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.padding(horizontal = density.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                AppChip(
                    text = "配乐",
                    selected = activity.currentAssetType == ReadAloudBgmTrack.TYPE_BGM,
                    onClick = { activity.switchAssetType(ReadAloudBgmTrack.TYPE_BGM) },
                )
                AppChip(
                    text = "音效",
                    selected = activity.currentAssetType == ReadAloudBgmTrack.TYPE_SFX,
                    onClick = { activity.switchAssetType(ReadAloudBgmTrack.TYPE_SFX) },
                )
            }
            Text(
                text = when {
                    activity.importing -> activity.importingText.ifBlank { "正在导入…" }
                    activity.tracks.isEmpty() -> if (activity.currentAssetType == ReadAloudBgmTrack.TYPE_SFX) {
                        "暂无音效。导入短音频并添加标签后，AI 可以为开门、脚步、撞击等候选事件选择音效。"
                    } else {
                        "暂无配乐。导入音乐后，AI 可以按段落范围选择配乐。"
                    }
                    else -> "${activity.tracks.size} 个${activity.currentAssetLabel} · ${activity.groups.size.coerceAtLeast(1)} 个分组 · 已选 ${activity.selectedIds.size}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = density.screenPadding,
                    vertical = density.blockGap / 2,
                ),
            )
            if (rows.isEmpty() && !activity.importing) {
                AppEmpty(
                    title = "暂无${activity.currentAssetLabel}",
                    supporting = "点右上角菜单导入音频或 ZIP 包",
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
                    items(rows, key = { row ->
                        when (row) {
                            is ReadAloudBgmManageActivity.AudioRow.GroupHeader -> "g_${row.group.id}"
                            is ReadAloudBgmManageActivity.AudioRow.TrackItem -> "t_${row.track.id}"
                        }
                    }) { row ->
                        when (row) {
                            is ReadAloudBgmManageActivity.AudioRow.GroupHeader -> GroupHeaderRow(activity, row)
                            is ReadAloudBgmManageActivity.AudioRow.TrackItem -> TrackRow(activity, row.track)
                        }
                    }
                }
            }
        }
    }

    if (activity.overflowMenuOpen) {
        AppMenuSheet(
            title = "智能音频",
            actions = listOf(
                "导入" to { activity.onOverflowAction(0) },
                "导出 ZIP" to { activity.onOverflowAction(1) },
                "新增分组" to { activity.onOverflowAction(2) },
                "管理分组" to { activity.onOverflowAction(3) },
            ),
            onDismiss = { activity.overflowMenuOpen = false },
        )
    }

    if (activity.importMenuOpen) {
        AppMenuSheet(
            title = "导入智能音频",
            actions = listOf(
                "批量导入${activity.currentAssetLabel}" to { activity.onImportAction(0) },
                "导入单个${activity.currentAssetLabel}" to { activity.onImportAction(1) },
                "导入配乐 ZIP" to { activity.onImportAction(2) },
                "导入音效 ZIP" to { activity.onImportAction(3) },
                "新增分组" to { activity.onImportAction(4) },
                "管理分组" to { activity.onImportAction(5) },
            ),
            onDismiss = { activity.importMenuOpen = false },
        )
    }

    activity.zipImportChoice?.let { (_, assetType) ->
        val label = activity.assetLabel(assetType)
        AppFilterDialog(
            title = "导入${label} ZIP",
            options = listOf("清空旧${label}并导入", "追加导入"),
            selectedIndex = 1,
            onSelect = { activity.onZipImportChoice(it == 0) },
            onDismiss = { activity.zipImportChoice = null },
        )
    }

    if (activity.groupEditorOpen) {
        var name by remember(activity.groupEditorTarget) {
            mutableStateOf(activity.groupEditorTarget?.name.orEmpty())
        }
        AppInputDialog(
            title = if (activity.groupEditorTarget == null) "新增分组" else "编辑分组",
            value = name,
            onValueChange = { name = it },
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            placeholder = "分组名称",
            onConfirm = { activity.onGroupEditorConfirmed(name) },
            onDismiss = { activity.groupEditorOpen = false },
        )
    }

    if (activity.groupManageOpen) {
        val managed = activity.groups.filter { !(it.id == 0L || it.name == "默认分组") }
        AppMenuSheet(
            title = "管理分组",
            actions = managed.map { group ->
                group.displayName() to { activity.onGroupManageSelected(group) }
            },
            onDismiss = { activity.groupManageOpen = false },
        )
    }

    activity.groupActionTarget?.let { group ->
        AppMenuSheet(
            title = group.displayName(),
            actions = listOf(
                "编辑分组" to { activity.onGroupAction(0) },
                "删除分组" to { activity.onGroupAction(1) },
            ),
            onDismiss = { activity.groupActionTarget = null },
        )
    }

    activity.deleteGroupTarget?.let { group ->
        AppConfirmDialog(
            title = "删除分组",
            message = "确定删除“${group.displayName()}”？组内${activity.currentAssetLabel}会移回默认分组，本地文件不会删除。",
            confirmLabel = stringResource(R.string.delete),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = { activity.onDeleteGroupConfirmed() },
            onDismiss = { activity.deleteGroupTarget = null },
        )
    }

    if (activity.tagsEditorTarget != null) {
        var tags by remember(activity.tagsEditorTarget?.id) {
            mutableStateOf(activity.tagsEditorTarget?.tags.orEmpty())
        }
        AppInputDialog(
            title = "编辑标签",
            value = tags,
            onValueChange = { tags = it },
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            placeholder = "标签用逗号分隔，例如 紧张,日常,战斗",
            onConfirm = { activity.onTagsConfirmed(tags) },
            onDismiss = { activity.tagsEditorTarget = null },
        )
    }

    activity.volumeTarget?.let { track ->
        AppNumberPickerDialog(
            title = "${track.assetTypeLabel()}默认音量",
            value = (track.defaultVolume * 100).toInt().coerceIn(0, 100),
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            range = 0..100,
            step = 1,
            valueLabel = "${(track.defaultVolume * 100).toInt().coerceIn(0, 100)}%",
            onConfirm = { activity.onVolumeConfirmed(it) },
            onDismiss = { activity.volumeTarget = null },
        )
    }

    activity.moveTracksIds?.let { _ ->
        val options = listOf(ReadAloudBgmGroup(id = 0L, name = "默认分组", assetType = activity.currentAssetType)) +
            activity.groups.filterNot { it.id == 0L || it.name == "默认分组" }
        AppMenuSheet(
            title = "选择分组",
            actions = options.map { group ->
                group.displayName() to { activity.onMoveTargetSelected(group.id) }
            },
            onDismiss = { activity.moveTracksIds = null },
        )
    }

    activity.deleteTracksIds?.let { ids ->
        AppConfirmDialog(
            title = "删除${activity.currentAssetLabel}",
            message = "确定删除选中的 ${ids.size} 个${activity.currentAssetLabel}？文件也会从本地移除。",
            confirmLabel = stringResource(R.string.delete),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = { activity.onDeleteTracksConfirmed() },
            onDismiss = { activity.deleteTracksIds = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupHeaderRow(
    activity: ReadAloudBgmManageActivity,
    row: ReadAloudBgmManageActivity.AudioRow.GroupHeader,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { activity.toggleGroup(row.group.id) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { activity.toggleGroup(row.group.id) },
                    onLongClick = { activity.toggleGroupSelection(row.group.id) },
                )
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${if (row.expanded) "▼" else "▶"} ${row.group.displayName()}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = listOfNotNull(
                        "${row.count} 个${activity.currentAssetLabel}",
                        if (row.selectedCount > 0) "已选 ${row.selectedCount}" else null
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    activity: ReadAloudBgmManageActivity,
    track: ReadAloudBgmTrack,
) {
    val selected = track.id in activity.selectedIds
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            if (activity.selectedIds.isNotEmpty()) activity.toggleTrack(track.id)
            else activity.trackMenuTarget = track
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (activity.selectedIds.isNotEmpty()) activity.toggleTrack(track.id)
                        else activity.trackMenuTarget = track
                    },
                    onLongClick = { activity.toggleTrack(track.id) },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (selected) "✓" else "♪",
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.displayName(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = listOfNotNull(
                        activity.groupName(track.groupId),
                        track.assetTypeLabel(),
                        track.tags.takeIf { it.isNotBlank() },
                        "音量 ${(track.defaultVolume * 100).toInt().coerceIn(0, 100)}%",
                        if (track.durationMs > 0) "${track.durationMs / 1000}s" else null
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            AppOverflowMenuButton(
                contentDescription = stringResource(R.string.more),
                onClick = { activity.trackMenuTarget = track },
            )
        }
    }
}
