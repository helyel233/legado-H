package io.legado.app.ui.config

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityLayoutCenterBinding
import io.legado.app.help.config.LayoutPackageStore
import io.legado.app.uikit.layout.BuiltinLayouts
import io.legado.app.uikit.layout.LayoutEngine
import io.legado.app.uikit.layout.LayoutPackageSpec
import io.legado.app.uikit.theme.Applicator
import io.legado.app.uikit.theme.AppTheme
import io.legado.app.uikit.token.AppSpacing
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * A2-1 界面中心（新引擎）：可选不同的界面包 + 包内调整（另存为自定义界面包）。
 * 导航/玻璃/密度对主界面的接线随 A2-3/A2-4 完成；圆角已即时生效。
 */
class LayoutCenterActivity : BaseActivity<ActivityLayoutCenterBinding>() {

    override val binding by viewBinding(ActivityLayoutCenterBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeContent.setContent {
            AppTheme(Applicator.rememberAppColorScheme()) {
                LayoutCenterScreen()
            }
        }
    }
}

private fun navLabel(p: String): Int = when (p) {
    LayoutPackageSpec.NAV_BOTTOM -> R.string.layout_nav_bottom
    LayoutPackageSpec.NAV_FLOAT -> R.string.layout_nav_float
    LayoutPackageSpec.NAV_SIDE -> R.string.layout_nav_side
    else -> R.string.layout_nav_top
}

private fun densityLabel(p: String): Int = when (p) {
    LayoutPackageSpec.DENSITY_COMPACT -> R.string.layout_density_compact
    LayoutPackageSpec.DENSITY_COMFORTABLE -> R.string.layout_density_comfortable
    else -> R.string.layout_density_standard
}

@Composable
private fun LayoutCenterScreen() {
    val context = LocalContext.current
    val scheme = Applicator.rememberAppColorScheme()
    val rev = LayoutEngine.revision
    val active = remember(rev) { LayoutEngine.activeLayout }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .padding(horizontal = AppSpacing.s16, vertical = AppSpacing.s12),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s12),
    ) {
        item {
            Text(
                stringResource(R.string.layout_center_title),
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onBackground,
            )
            Text(
                stringResource(R.string.layout_center_desc),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.muted,
            )
        }
        item { LayoutSectionCard(R.string.layout_center_builtin) {
            BuiltinLayouts.all.forEach { spec ->
                val selected = active.id == spec.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            LayoutEngine.applyLayout(spec)
                            LayoutPackageStore.persistCurrent(context)
                        }
                        .padding(vertical = AppSpacing.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(spec.name, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                        Text(
                            stringResource(navLabel(spec.navPosition)) + " · " +
                                stringResource(densityLabel(spec.densityLevel)),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.muted,
                        )
                    }
                    if (selected) {
                        Text(
                            stringResource(R.string.layout_center_active),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.primary,
                        )
                    }
                }
            }
        } }
        item { LayoutSectionCard(R.string.layout_center_tune) {
            Text(
                if (active.id == LayoutPackageStore.CUSTOM_ID && active.basedOn != null)
                    stringResource(R.string.layout_center_based_on, active.basedOn ?: "")
                else stringResource(R.string.layout_center_tune_hint),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.muted,
            )
            Spacer(Modifier.height(AppSpacing.s8))
            PackageEditor(active)
        } }
        if (active.id == LayoutPackageStore.CUSTOM_ID) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        val base = BuiltinLayouts.byId(active.basedOn ?: "") ?: BuiltinLayouts.default
                        LayoutEngine.applyLayout(base)
                        LayoutPackageStore.persistCurrent(context)
                    }) { Text(stringResource(R.string.layout_center_discard)) }
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun LayoutSectionCard(titleRes: Int, content: @Composable () -> Unit) {
    val scheme = Applicator.rememberAppColorScheme()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaterialTheme.shapes.medium.topStart),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
    ) {
        Column(modifier = Modifier.padding(AppSpacing.s16)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            Spacer(Modifier.height(AppSpacing.s8))
            content()
        }
    }
}
