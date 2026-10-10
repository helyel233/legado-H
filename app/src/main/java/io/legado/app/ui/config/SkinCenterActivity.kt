package io.legado.app.ui.config

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import io.legado.app.databinding.ActivitySkinCenterBinding
import io.legado.app.help.config.LayoutPackageStore
import io.legado.app.help.config.ThemePackageStore
import io.legado.app.theme.pack.ThemePackageSpec
import io.legado.app.theme.pack.resolvePalette
import io.legado.app.uikit.layout.BuiltinLayouts
import io.legado.app.uikit.layout.LayoutEngine
import io.legado.app.uikit.layout.LayoutPackageSpec
import io.legado.app.uikit.theme.Applicator
import io.legado.app.uikit.theme.AppTheme
import io.legado.app.uikit.theme.BuiltinThemes
import io.legado.app.uikit.token.AppSpacing
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * A2-4b 主题和界面管理中心（V4.10）：主题选包 / 界面选包两个包库。
 * 点包即应用；用户包可编辑/删除；官方包编辑自动克隆。
 */
class SkinCenterActivity : BaseActivity<ActivitySkinCenterBinding>() {

    override val binding by viewBinding(ActivitySkinCenterBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeContent.setContent {
            AppTheme(Applicator.rememberAppColorScheme()) {
                SkinCenterScreen()
            }
        }
    }
}

@Composable
private fun SkinCenterScreen() {
    val context = LocalContext.current
    val scheme = Applicator.rememberAppColorScheme()
    val rev = Applicator.revision
    val themeUsers = remember(rev) { ThemePackageStore.listUser(context) }
    val layoutUsers = remember(rev) { LayoutPackageStore.listUser(context) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .padding(horizontal = AppSpacing.s16, vertical = AppSpacing.s12),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s12),
    ) {
        item {
            Text(
                stringResource(R.string.skin_center_title),
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onBackground,
            )
            Text(
                stringResource(R.string.skin_center_desc),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.muted,
            )
        }
        item { SkinSectionCard(R.string.skin_center_theme_section) {
            BuiltinThemes.all.forEach { spec ->
                val selected = Applicator.activeTheme.id == spec.id
                val palette = remember(spec.id, scheme.isDark, rev) { spec.resolvePalette(scheme.isDark) }
                PackageRow(
                    title = spec.name,
                    subtitle = stringResource(R.string.skin_center_official_pkg),
                    selected = selected,
                    leading = Color(palette.getValue(io.legado.app.theme.palette.PaletteRole.PRIMARY)),
                    onClick = {
                        Applicator.applyTheme(spec)
                        ThemePackageStore.persistActive(context)
                    },
                    actions = {
                        TextButton(onClick = {
                            val clone = spec.copy(
                                id = ThemePackageStore.newUserId(),
                                name = spec.name + " 副本",
                                basedOn = spec.id,
                            )
                            ThemePackageStore.upsertUserSpec(context, clone)
                            Applicator.applyTheme(clone)
                            ThemePackageStore.persistActive(context)
                            context.startActivity<ThemeCenterActivity> { putExtra(ThemeCenterActivity.EXTRA_PKG_ID, clone.id) }
                        }) { Text(stringResource(R.string.skin_center_edit)) }
                    },
                )
            }
            themeUsers.forEach { spec ->
                val selected = Applicator.activeTheme.id == spec.id
                PackageRow(
                    title = spec.name,
                    subtitle = stringResource(R.string.skin_center_user_theme_pkg),
                    selected = selected,
                    leading = Color(ThemePackageSpec.parseColor(spec.seed) ?: 0xFF808080.toInt()),
                    onClick = {
                        Applicator.applyTheme(spec)
                        ThemePackageStore.persistActive(context)
                    },
                    actions = {
                        TextButton(onClick = {
                            Applicator.applyTheme(spec)
                            ThemePackageStore.persistActive(context)
                            context.startActivity<ThemeCenterActivity> { putExtra(ThemeCenterActivity.EXTRA_PKG_ID, spec.id) }
                        }) { Text(stringResource(R.string.skin_center_edit)) }
                        TextButton(onClick = {
                            ThemePackageStore.deleteUserSpec(context, spec.id)
                            if (Applicator.activeTheme.id == spec.id) {
                                Applicator.applyTheme(BuiltinThemes.default)
                                ThemePackageStore.persistActive(context)
                            }
                        }) { Text(stringResource(R.string.skin_center_delete)) }
                    },
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    val n = themeUsers.size + 1
                    val spec = ThemePackageSpec(
                        id = ThemePackageStore.newUserId(),
                        name = "我的主题包 $n",
                        author = "自定义",
                    )
                    ThemePackageStore.upsertUserSpec(context, spec)
                    Applicator.applyTheme(spec)
                    ThemePackageStore.persistActive(context)
                    context.startActivity<ThemeCenterActivity> { putExtra(ThemeCenterActivity.EXTRA_PKG_ID, spec.id) }
                }) { Text(stringResource(R.string.skin_center_new_theme)) }
            }
        } }
        item { SkinSectionCard(R.string.skin_center_layout_section) {
            BuiltinLayouts.all.forEach { spec ->
                val selected = LayoutEngine.activeLayout.id == spec.id
                PackageRow(
                    title = spec.name,
                    subtitle = stringResource(R.string.skin_center_official_pkg),
                    selected = selected,
                    leading = null,
                    onClick = {
                        LayoutEngine.applyLayout(spec)
                        LayoutPackageStore.persistActive(context)
                    },
                    actions = {
                        TextButton(onClick = {
                            val clone = spec.copy(
                                id = LayoutPackageStore.newUserId(),
                                name = spec.name + " 副本",
                                basedOn = spec.id,
                            )
                            LayoutPackageStore.upsertUserSpec(context, clone)
                            LayoutEngine.applyLayout(clone)
                            LayoutPackageStore.persistActive(context)
                            context.startActivity<LayoutCenterActivity> { putExtra(LayoutCenterActivity.EXTRA_PKG_ID, clone.id) }
                        }) { Text(stringResource(R.string.skin_center_edit)) }
                    },
                )
            }
            layoutUsers.forEach { spec ->
                val selected = LayoutEngine.activeLayout.id == spec.id
                PackageRow(
                    title = spec.name,
                    subtitle = stringResource(R.string.skin_center_user_layout_pkg),
                    selected = selected,
                    leading = null,
                    onClick = {
                        LayoutEngine.applyLayout(spec)
                        LayoutPackageStore.persistActive(context)
                    },
                    actions = {
                        TextButton(onClick = {
                            LayoutEngine.applyLayout(spec)
                            LayoutPackageStore.persistActive(context)
                            context.startActivity<LayoutCenterActivity> { putExtra(LayoutCenterActivity.EXTRA_PKG_ID, spec.id) }
                        }) { Text(stringResource(R.string.skin_center_edit)) }
                        TextButton(onClick = {
                            LayoutPackageStore.deleteUserSpec(context, spec.id)
                            if (LayoutEngine.activeLayout.id == spec.id) {
                                LayoutEngine.applyLayout(BuiltinLayouts.default)
                                LayoutPackageStore.persistActive(context)
                            }
                        }) { Text(stringResource(R.string.skin_center_delete)) }
                    },
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    val n = layoutUsers.size + 1
                    val spec = LayoutPackageSpec(
                        id = LayoutPackageStore.newUserId(),
                        name = "我的界面包 $n",
                        author = "自定义",
                    )
                    LayoutPackageStore.upsertUserSpec(context, spec)
                    LayoutEngine.applyLayout(spec)
                    LayoutPackageStore.persistActive(context)
                    context.startActivity<LayoutCenterActivity> { putExtra(LayoutCenterActivity.EXTRA_PKG_ID, spec.id) }
                }) { Text(stringResource(R.string.skin_center_new_layout)) }
            }
        } }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun SkinSectionCard(titleRes: Int, content: @Composable () -> Unit) {
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

@Composable
private fun PackageRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    leading: Color?,
    onClick: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val scheme = Applicator.rememberAppColorScheme()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AppSpacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(leading, CircleShape)
                    .border(1.dp, scheme.outline, CircleShape),
            )
            Spacer(Modifier.size(AppSpacing.s8))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title + if (selected) " ✓" else "",
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) scheme.primary else scheme.onSurface,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.muted)
        }
        actions()
    }
}
