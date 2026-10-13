package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.config.AiProviderEditActivity
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.uikit.facade.AppToast
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppListItem
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.postEvent

/**
 * AI 服务商管理（V5）。
 * 业务逻辑从旧 AiProviderManageActivity 搬运；编辑仍指向旧 AiProviderEditActivity（待迁）。
 */
class HostAiProviderManageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostAiProviderManageScreen() }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, HostAiProviderManageActivity::class.java))
        }
    }
}

@Composable
private fun HostAiProviderManageScreen() {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val density = LocalVisualSpec.current.density

    var providers by remember { mutableStateOf(AppConfig.aiProviderList) }
    var currentProviderId by remember { mutableStateOf(AppConfig.aiCurrentProviderId) }
    val modelCounts = remember(providers) {
        providers.associate { p -> p.id to AppConfig.aiModelConfigList.count { it.providerId == p.id } }
    }
    var menuFor by remember { mutableStateOf<AiProviderConfig?>(null) }
    var confirmDelete by remember { mutableStateOf<AiProviderConfig?>(null) }

    // 非 Flow 数据：从编辑页返回后刷新
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                providers = AppConfig.aiProviderList
                currentProviderId = AppConfig.aiCurrentProviderId
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun providerName(provider: AiProviderConfig): String {
        return provider.name.ifBlank {
            provider.baseUrl.ifBlank { context.getString(R.string.ai_provider) }
        }
    }

    fun openEdit(provider: AiProviderConfig?) {
        context.startActivity(Intent(context, AiProviderEditActivity::class.java).apply {
            provider?.id?.let { putExtra(AiProviderEditActivity.EXTRA_PROVIDER_ID, it) }
        })
    }

    fun removeProvider(provider: AiProviderConfig) {
        AppConfig.aiProviderList = AppConfig.aiProviderList.filterNot { it.id == provider.id }
        postEvent(EventBus.AI_CONFIG_CHANGED, true)
        providers = AppConfig.aiProviderList
        currentProviderId = AppConfig.aiCurrentProviderId
        AppToast.show(context.getString(R.string.ai_provider_removed))
    }

    HostV5Page(
        title = context.getString(R.string.ai_provider_manage_title),
        subtitle = context.getString(R.string.ai_provider_manage_summary),
        onBack = { activity.finish() },
        bottomBar = {
            AppFilledButton(
                text = context.getString(R.string.ai_add_provider),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap),
                onClick = { openEdit(null) },
            )
        },
    ) {
        if (providers.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                AppEmpty(
                    title = context.getString(R.string.ai_current_provider_summary_empty),
                    supporting = context.getString(R.string.ai_add_provider_summary),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = density.screenPadding,
                    end = density.screenPadding,
                    top = density.blockGap / 2,
                    bottom = density.screenPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                items(providers, key = { it.id }) { provider ->
                    val current = provider.id == currentProviderId
                    AppListItem(
                        headline = providerName(provider),
                        overline = if (current) context.getString(R.string.ai_current_provider) else null,
                        supporting = listOfNotNull(
                            provider.baseUrl.ifBlank { null },
                            context.getString(R.string.ai_manage_models_summary, modelCounts[provider.id] ?: 0),
                        ).joinToString(" · "),
                        trailing = {
                            AppOverflowMenuButton(
                                contentDescription = context.getString(R.string.more),
                                onClick = { menuFor = provider },
                            )
                        },
                        onClick = { openEdit(provider) },
                    )
                }
            }
        }
    }

    menuFor?.let { provider ->
        AppMenuSheet(
            title = providerName(provider),
            actions = listOf(
                context.getString(R.string.edit) to { openEdit(provider) },
                context.getString(R.string.delete) to { confirmDelete = provider },
            ),
            onDismiss = { menuFor = null },
        )
    }

    confirmDelete?.let { provider ->
        val relatedModelCount = AppConfig.aiModelConfigList.count { it.providerId == provider.id }
        AppConfirmDialog(
            title = providerName(provider),
            message = context.getString(
                if (relatedModelCount > 0) R.string.ai_remove_provider_confirm_with_models
                else R.string.ai_remove_provider_confirm,
                relatedModelCount,
            ),
            confirmLabel = context.getString(R.string.delete),
            cancelLabel = context.getString(R.string.cancel),
            danger = true,
            onConfirm = {
                confirmDelete = null
                removeProvider(provider)
            },
            onDismiss = { confirmDelete = null },
        )
    }
}
