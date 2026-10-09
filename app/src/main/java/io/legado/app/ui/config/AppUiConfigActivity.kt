package io.legado.app.ui.config

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAppUiConfigBinding
import io.legado.app.lib.theme.UiKitThemeBridge
import io.legado.app.ui.book.read.config.ReaderAssetManageActivity
import io.legado.app.ui.navigation.AppRoute
import io.legado.app.uikit.components.AppButton
import io.legado.app.uikit.components.AppButtonSize
import io.legado.app.uikit.components.AppButtonStyle
import io.legado.app.uikit.theme.AppTheme
import io.legado.app.uikit.token.AppSpacing
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.startActivity

/**
 * P2-a / requirement R2: the "App UI 配置" hub managing the three packages
 * (theme package / UI kit / reader kit) under the new architecture.
 */
class AppUiConfigActivity : BaseActivity<ActivityAppUiConfigBinding>() {

    override val binding by viewBinding(ActivityAppUiConfigBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeContent.setContent {
            AppTheme(UiKitThemeBridge.colorScheme(this)) {
                AppUiConfigScreen()
            }
        }
    }
}

@Composable
private fun AppUiConfigScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.s16, vertical = AppSpacing.s12),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s12),
    ) {
        PackageCard(
            title = stringResource(R.string.app_ui_config_theme_title),
            desc = stringResource(R.string.app_ui_config_theme_desc),
            actionLabel = stringResource(R.string.theme_list),
            onAction = { context.startActivity<ThemeManageActivity>() }
        )
        PackageCard(
            title = stringResource(R.string.app_ui_config_kit_title),
            desc = stringResource(R.string.app_ui_config_kit_desc),
            actionLabel = stringResource(R.string.appearance_kit_manage),
            onAction = { context.startActivity<AppearanceKitActivity>() }
        )
        PackageCard(
            title = stringResource(R.string.app_ui_config_ui_title),
            desc = stringResource(R.string.app_ui_config_ui_desc),
            actionLabel = stringResource(R.string.theme_setting),
            onAction = {
                context.startActivity<ConfigActivity> {
                    putExtra("configTag", ConfigTag.THEME_CONFIG)
                }
            }
        )
        PackageCard(
            title = stringResource(R.string.app_ui_config_reader_title),
            desc = stringResource(R.string.app_ui_config_reader_desc),
            actionLabel = stringResource(R.string.reader_asset_manage),
            onAction = { context.startActivity<ReaderAssetManageActivity>() }
        )
    }
}

@Composable
private fun PackageCard(
    title: String,
    desc: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaterialTheme.shapes.medium.topStart),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(AppSpacing.s16)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(AppSpacing.s4))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AppSpacing.s12))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                AppButton(
                    text = actionLabel,
                    onClick = onAction,
                    style = AppButtonStyle.TONAL,
                    size = AppButtonSize.COMPACT,
                )
            }
        }
    }
}
