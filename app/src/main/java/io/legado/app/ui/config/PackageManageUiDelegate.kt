package io.legado.app.ui.config

import android.content.Context
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.help.AppCloudStorage
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskState
import io.legado.app.ui.book.cache.WebDavTaskStatus
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Shared UI orchestration delegate for package management activities:
 * - Cloud storage / S3 container selection state & menu actions
 * - WebDAV background upload/download task observation
 * - SAF import file staging & ZIP export launching
 */
object PackageManageUiDelegate {

    data class CloudContainerState(
        val selectedContainerId: String?,
        val menuLabel: String?
    )

    fun resolveCloudContainerState(context: Context, scope: String): CloudContainerState {
        val containers = AppCloudStorage.listContainers().filter { it.enabled }
        if (AppCloudStorage.type != CloudStorageType.S3) {
            return CloudContainerState(
                selectedContainerId = containers.firstOrNull()?.id,
                menuLabel = null
            )
        }
        val selectedId = AppCloudStorage.selectedContainer(scope)?.id ?: containers.firstOrNull()?.id
        val label = containers.firstOrNull { it.id == selectedId }
            ?.let(AppCloudStorage::containerDisplayLabel)
            ?: context.getString(R.string.s3_bucket)
        return CloudContainerState(selectedContainerId = selectedId, menuLabel = label)
    }

    fun buildContainerSelectorActions(
        context: Context,
        scope: String,
        currentSelectedId: String?,
        onContainerChanged: (String) -> Unit
    ): List<AppManagementMenuAction> {
        val containers = AppCloudStorage.listContainers().filter { it.enabled }
        if (containers.isEmpty()) {
            context.toastOnUi(R.string.cloud_storage_config_required)
            return emptyList()
        }
        val selected = currentSelectedId ?: AppCloudStorage.selectedContainer(scope)?.id
        return containers.map { container ->
            AppManagementMenuAction(
                text = AppCloudStorage.containerDisplayLabel(container),
                checked = container.id == selected,
                onClick = {
                    if (container.id != selected) {
                        AppCloudStorage.selectContainer(scope, container.id)
                        onContainerChanged(container.id)
                    }
                }
            )
        }
    }

    fun observeWebDavTasks(
        activity: AppCompatActivity,
        taskTypes: Set<WebDavTaskType>,
        handledTaskTokens: MutableSet<String>,
        onCompleted: (WebDavTaskState) -> Unit = {},
        onFailed: (WebDavTaskState) -> Unit = { state ->
            activity.toastOnUi(state.message.ifBlank { activity.getString(R.string.error) })
        }
    ): Job {
        return activity.lifecycleScope.launch {
            WebDavTaskManager.states.collectLatest { states ->
                states.values
                    .filter { it.type in taskTypes }
                    .forEach { state ->
                        if (state.active) {
                            handledTaskTokens.remove("${state.key}:${WebDavTaskStatus.COMPLETED.name}")
                            handledTaskTokens.remove("${state.key}:${WebDavTaskStatus.FAILED.name}")
                            return@forEach
                        }
                        val token = "${state.key}:${state.status.name}:${state.message}"
                        if (!handledTaskTokens.add(token)) return@forEach
                        when (state.status) {
                            WebDavTaskStatus.COMPLETED -> onCompleted(state)
                            WebDavTaskStatus.FAILED -> onFailed(state)
                            else -> Unit
                        }
                    }
            }
        }
    }

    suspend fun stageImportUriToTempFile(
        context: Context,
        uri: Uri,
        dirName: String,
        filePrefix: String,
        extension: String = "zip"
    ): File = withContext(Dispatchers.IO) {
        val target = context.externalFiles.getFile(
            dirName,
            "${filePrefix}_${System.currentTimeMillis()}.$extension"
        )
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException(context.getString(R.string.theme_zip_read_failed))
        target
    }

    fun launchExportZip(
        launcher: ActivityResultLauncher<(HandleFileContract.HandleFileParam.() -> Unit)?>,
        zipFile: File,
        fallbackName: String = "package.zip"
    ) {
        launcher.launch {
            mode = HandleFileContract.EXPORT
            showUploadUrl = false
            fileData = HandleFileContract.FileData(
                zipFile.name.ifBlank { fallbackName },
                zipFile,
                "application/zip"
            )
        }
    }
}
