package io.legado.app.ui.config

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.help.AppCloudStorage
import io.legado.app.lib.cloud.S3CloudStorageBackend
import io.legado.app.lib.cloud.S3Config
import io.legado.app.lib.cloud.S3Container
import io.legado.app.lib.cloud.S3ContainerScope
import io.legado.app.ui.host.setV5Content
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

class S3ContainerManageActivity : AppCompatActivity() {

    internal val containersState = mutableStateOf<List<S3Container>>(emptyList())
    private var _waitDialog: WaitDialog? = null
    private val waitDialog: WaitDialog
        get() = _waitDialog ?: WaitDialog(this).also { _waitDialog = it }

    internal var editDialogOpen by mutableStateOf(false)
    internal var editingItem by mutableStateOf<S3Container?>(null)
    internal var fieldName by mutableStateOf("")
    internal var fieldEndpoint by mutableStateOf("")
    internal var fieldBucket by mutableStateOf("")
    internal var fieldAccessKey by mutableStateOf("")
    internal var fieldSecretKey by mutableStateOf("")
    internal var fieldCapacity by mutableStateOf("")
    internal var fieldPrefix by mutableStateOf("")
    internal var fieldRegion by mutableStateOf("")
    internal var fieldSessionToken by mutableStateOf("")
    internal var checkEnabled by mutableStateOf(true)
    internal var checkPathStyle by mutableStateOf(true)
    internal var deleteTarget by mutableStateOf<S3Container?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { S3ContainerManageHost(this@S3ContainerManageActivity) }
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        _waitDialog?.dismiss()
    }

    private fun reload() {
        containersState.value = AppCloudStorage.listContainers()
    }

    internal fun showEditDialog(item: S3Container? = null) {
        editingItem = item
        fieldName = item?.name.orEmpty()
        fieldEndpoint = item?.endpoint.orEmpty()
        fieldBucket = item?.bucket.orEmpty()
        fieldAccessKey = item?.accessKey.orEmpty()
        fieldSecretKey = item?.secretKey.orEmpty()
        fieldCapacity = capacityMbToGbText(item?.capacityMb ?: DEFAULT_CAPACITY_MB)
        fieldPrefix = item?.prefix ?: "legado"
        fieldRegion = item?.region ?: "auto"
        fieldSessionToken = item?.sessionToken.orEmpty()
        checkEnabled = item?.enabled ?: true
        checkPathStyle = item?.pathStyle ?: true
        editDialogOpen = true
    }

    internal fun onEditSave() {
        editDialogOpen = false
        val item = editingItem
        val fields = listOf(
            fieldName, fieldEndpoint, fieldBucket, fieldAccessKey, fieldSecretKey,
            fieldCapacity, fieldPrefix, fieldRegion, fieldSessionToken
        )
        val checks = booleanArrayOf(checkEnabled, checkPathStyle)
        saveDialogItem(item, fields, checks)?.let { saved ->
            if (item == null) refreshCapacity(saved, showWait = false)
        }
    }

    private fun saveDialogItem(
        oldItem: S3Container?,
        fields: List<String>,
        checks: BooleanArray
    ): S3Container? {
        val pathStyle = checks.checkedAt(CHECK_PATH_STYLE, default = true)
        val parsed = S3Config.parseAddress(
            fields.fieldAt(FIELD_ENDPOINT),
            fields.fieldAt(FIELD_BUCKET),
            fields.fieldAt(FIELD_REGION),
            pathStyle
        )
        val capacityMb = gbTextToCapacityMb(fields.fieldAt(FIELD_CAPACITY))
        val usedBytes = oldItem?.usedBytes?.coerceAtLeast(0L) ?: 0L
        if (parsed.endpoint.isBlank() || parsed.bucket.isBlank()) {
            toastOnUi(R.string.s3_container_endpoint_bucket_required)
            return null
        }
        if (fields.fieldAt(FIELD_ACCESS_KEY).isBlank()
            || fields.fieldAt(FIELD_SECRET_KEY).isBlank()
        ) {
            toastOnUi(R.string.s3_container_key_required)
            return null
        }
        val newItem = S3Container(
            id = oldItem?.id ?: S3Container.newId(),
            name = fields.fieldAt(FIELD_NAME).trim().ifBlank { parsed.bucket },
            endpoint = parsed.endpoint,
            bucket = parsed.bucket,
            prefix = fields.fieldAt(FIELD_PREFIX).trim().ifBlank { "legado" },
            region = parsed.region.ifBlank { "auto" },
            accessKey = fields.fieldAt(FIELD_ACCESS_KEY).trim(),
            secretKey = fields.fieldAt(FIELD_SECRET_KEY).trim(),
            sessionToken = fields.fieldAt(FIELD_SESSION_TOKEN).trim().ifBlank { null },
            pathStyle = parsed.pathStyle,
            capacityMb = capacityMb,
            usedBytes = if (capacityMb > 0) usedBytes.coerceAtMost(mbToBytes(capacityMb)) else usedBytes,
            lastRefreshTime = oldItem?.lastRefreshTime ?: 0L,
            isFull = capacityMb > 0 && usedBytes >= mbToBytes(capacityMb),
            enabled = checks.checkedAt(CHECK_ENABLED, default = true)
        )
        AppCloudStorage.addContainer(newItem)
        if (AppCloudStorage.selectedContainer(S3ContainerScope.DEFAULT) == null) {
            AppCloudStorage.selectContainer(S3ContainerScope.DEFAULT, newItem.id)
        }
        reload()
        return newItem
    }

    internal fun containerActions(item: S3Container): List<Pair<String, () -> Unit>> {
        val actions = listOf(
            Action.EDIT,
            Action.TEST,
            Action.REFRESH,
            Action.SET_DEFAULT,
            if (item.enabled) Action.DISABLE else Action.ENABLE,
            Action.DELETE
        )
        return actions.map { action ->
            getString(action.titleRes) to {
                when (action) {
                    Action.EDIT -> showEditDialog(item)
                    Action.TEST -> testConnection(item)
                    Action.REFRESH -> refreshCapacity(item)
                    Action.SET_DEFAULT -> {
                        if (!item.enabled) {
                            toastOnUi(R.string.s3_container_disabled)
                        } else {
                            AppCloudStorage.selectContainer(S3ContainerScope.DEFAULT, item.id)
                            toastOnUi(R.string.s3_container_set_default_success)
                            reload()
                        }
                    }
                    Action.ENABLE -> updateItem(item.copy(enabled = true, isFull = false))
                    Action.DISABLE -> updateItem(item.copy(enabled = false))
                    Action.DELETE -> confirmDelete(item)
                }
            }
        }
    }

    private fun updateItem(item: S3Container) {
        AppCloudStorage.updateContainer(item)
        reload()
    }

    private fun confirmDelete(item: S3Container) {
        deleteTarget = item
    }

    internal fun onDeleteConfirmed() {
        val item = deleteTarget ?: return
        deleteTarget = null
        AppCloudStorage.deleteContainer(item.id)
        reload()
    }

    private fun testConnection(item: S3Container) {
        waitDialog.setText(R.string.loading)
        waitDialog.show()
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    S3CloudStorageBackend(S3ContainerScope.DEFAULT, item.id).check()
                }
            }.onSuccess {
                toastOnUi(R.string.s3_container_test_success)
            }.onFailure {
                toastOnUi(getString(R.string.s3_container_test_failed, it.localizedMessage.orEmpty()))
            }
            waitDialog.dismiss()
        }
    }

    private fun refreshCapacity(item: S3Container, showWait: Boolean = true) {
        if (showWait) {
            waitDialog.setText(R.string.loading)
            waitDialog.show()
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AppCloudStorage.refreshUsage(item.id) }
            }.onSuccess {
                toastOnUi(R.string.s3_container_capacity_refreshed)
                reload()
            }.onFailure {
                toastOnUi(it.localizedMessage.orEmpty())
            }
            if (showWait) waitDialog.dismiss()
        }
    }

    private enum class Action(val titleRes: Int) {
        EDIT(R.string.s3_container_edit),
        TEST(R.string.s3_container_test),
        REFRESH(R.string.s3_container_refresh_capacity),
        SET_DEFAULT(R.string.s3_container_set_default),
        ENABLE(R.string.s3_container_enable),
        DISABLE(R.string.s3_container_disable),
        DELETE(R.string.s3_container_delete)
    }

    internal companion object {
        const val DEFAULT_CAPACITY_MB = 5L * 1024L
        private const val FIELD_NAME = 0
        private const val FIELD_ENDPOINT = 1
        private const val FIELD_BUCKET = 2
        private const val FIELD_ACCESS_KEY = 3
        private const val FIELD_SECRET_KEY = 4
        private const val FIELD_CAPACITY = 5
        private const val FIELD_PREFIX = 6
        private const val FIELD_REGION = 7
        private const val FIELD_SESSION_TOKEN = 8
        private const val CHECK_ENABLED = 0
        private const val CHECK_PATH_STYLE = 1

        private fun List<String>.fieldAt(index: Int): String = getOrNull(index).orEmpty()

        private fun BooleanArray.checkedAt(index: Int, default: Boolean): Boolean {
            return if (index in indices) this[index] else default
        }

        internal fun mbToBytes(value: Long): Long = value.coerceAtLeast(0L) * 1024L * 1024L
        private fun gbTextToCapacityMb(value: String): Long {
            val gb = value.trim().toDoubleOrNull() ?: return 0L
            if (gb <= 0.0) return 0L
            return max(ceil(gb * 1024.0).toLong(), 1L)
        }

        private fun capacityMbToGbText(value: Long): String {
            if (value <= 0L) return ""
            val gb = value / 1024.0
            return if (value % 1024L == 0L) {
                (value / 1024L).toString()
            } else {
                String.format(Locale.US, "%.2f", gb).trimEnd('0').trimEnd('.')
            }
        }

        internal fun formatBytes(value: Long): String {
            val bytes = value.coerceAtLeast(0L).toDouble()
            val gb = bytes / 1024.0 / 1024.0 / 1024.0
            return if (gb >= 1.0) {
                "${formatDecimal(gb)} GB"
            } else {
                val mb = bytes / 1024.0 / 1024.0
                "${max(mb.toLong(), 0L)} MB"
            }
        }

        private fun formatDecimal(value: Double): String {
            return String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }

    }
}

@Composable
private fun S3ContainerManageHost(activity: S3ContainerManageActivity) {
    S3ContainerManageScreen(
        containers = activity.containersState.value,
        onBack = { activity.finish() },
        onAdd = { activity.showEditDialog(null) },
        onItemClick = { activity.showEditDialog(it) },
        onMoreActions = { activity.containerActions(it) },
    )

    if (activity.editDialogOpen) {
        val density = LocalVisualSpec.current.density
        AppDialog(onDismiss = { activity.editDialogOpen = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(density.screenPadding),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                Text(
                    text = stringResource(
                        if (activity.editingItem == null) R.string.s3_container_add
                        else R.string.s3_container_edit
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                AppTextField(
                    value = activity.fieldName,
                    onValueChange = { activity.fieldName = it },
                    placeholder = stringResource(R.string.s3_container_name),
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldEndpoint,
                    onValueChange = { activity.fieldEndpoint = it },
                    placeholder = stringResource(R.string.s3_endpoint),
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldBucket,
                    onValueChange = { activity.fieldBucket = it },
                    placeholder = stringResource(R.string.s3_bucket),
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldAccessKey,
                    onValueChange = { activity.fieldAccessKey = it },
                    placeholder = stringResource(R.string.s3_access_key),
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldSecretKey,
                    onValueChange = { activity.fieldSecretKey = it },
                    placeholder = stringResource(R.string.s3_secret_key),
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldCapacity,
                    onValueChange = { activity.fieldCapacity = it },
                    placeholder = stringResource(R.string.s3_container_capacity_gb),
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldPrefix,
                    onValueChange = { activity.fieldPrefix = it },
                    placeholder = stringResource(R.string.s3_prefix),
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldRegion,
                    onValueChange = { activity.fieldRegion = it },
                    placeholder = stringResource(R.string.s3_region),
                    singleLine = true,
                )
                AppTextField(
                    value = activity.fieldSessionToken,
                    onValueChange = { activity.fieldSessionToken = it },
                    placeholder = stringResource(R.string.s3_session_token),
                    singleLine = true,
                )
                AppRowWithSwitch(
                    title = stringResource(R.string.s3_container_enabled),
                    checked = activity.checkEnabled,
                    onCheckedChange = { activity.checkEnabled = it },
                    card = false,
                )
                AppRowWithSwitch(
                    title = stringResource(R.string.s3_path_style),
                    checked = activity.checkPathStyle,
                    onCheckedChange = { activity.checkPathStyle = it },
                    card = false,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { activity.editDialogOpen = false },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.ok),
                        modifier = Modifier.weight(1f),
                        onClick = { activity.onEditSave() },
                    )
                }
            }
        }
    }

    activity.deleteTarget?.let { item ->
        AppConfirmDialog(
            title = stringResource(R.string.s3_container_delete),
            message = stringResource(
                R.string.s3_container_delete_confirm,
                AppCloudStorage.containerDisplayLabel(item)
            ),
            confirmLabel = stringResource(R.string.delete),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = { activity.onDeleteConfirmed() },
            onDismiss = { activity.deleteTarget = null },
        )
    }
}
