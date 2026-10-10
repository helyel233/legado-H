package io.legado.app.ui.config

import android.os.Bundle
import androidx.compose.foundation.background
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
 * A2-4b 界面包详情（编辑指定用户包；官方包首次编辑自动克隆）。
 * 由管理中心携 pkgId 打开；无 pkgId 时编辑当前应用中的包。
 */
class LayoutCenterActivity : BaseActivity<ActivityLayoutCenterBinding>() {

    override val binding by viewBinding(ActivityLayoutCenterBinding::inflate)

    private val targetId: String? by lazy { intent?.getStringExtra(EXTRA_PKG_ID) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val spec = targetId?.let { id -> LayoutPackageStore.resolveSpec(LayoutPackageStore.load(this) ?: LayoutPackageStore.Runtime(), id) }
        if (spec != null && LayoutEngine.activeLayout.id != spec.id) {
            LayoutEngine.applyLayout(spec)
        }
        binding.composeContent.setContent {
            AppTheme(Applicator.rememberAppColorScheme()) {
                LayoutCenterScreen()
            }
        }
    }

    /** 官方包编辑 → 自动克隆为用户包。 */
    private fun editTarget(): LayoutPackageSpec {
        val cur = LayoutEngine.activeLayout
        if (BuiltinLayouts.byId(cur.id) == null && cur.basedOn != null) return cur
        val clone = cur.copy(
            id = LayoutPackageStore.newUserId(),
            name = cur.name + " 副本",
            basedOn = cur.id,
        )
        LayoutPackageStore.upsertUserSpec(this, clone)
        LayoutEngine.applyLayout(clone)
        return clone
    }

    fun updateTarget(transform: (LayoutPackageSpec) -> LayoutPackageSpec) {
        val spec = transform(editTarget())
        LayoutEngine.applyLayout(spec)
        LayoutPackageStore.upsertUserSpec(this, spec)
        LayoutPackageStore.persistActive(this)
    }

    companion object {
        const val EXTRA_PKG_ID = "pkgId"
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
    val scheme = Applicator.rememberAppColorScheme()
    val activity = LocalContext.current as? LayoutCenterActivity

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
                LayoutEngine.activeLayout.name + " · " + if (LayoutEngine.activeLayout.basedOn != null)
                    stringResource(R.string.theme_center_based_prefix, LayoutEngine.activeLayout.basedOn ?: "")
                else stringResource(R.string.theme_center_official),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.muted,
            )
        }
        item { LayoutSectionCard(R.string.layout_center_tune) {
            PackageEditor(LayoutEngine.activeLayout)
        } }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    activity?.updateTarget { it.copy(glassEnabled = !it.glassEnabled) }
                }) { Text(stringResource(R.string.layout_glass)) }
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
