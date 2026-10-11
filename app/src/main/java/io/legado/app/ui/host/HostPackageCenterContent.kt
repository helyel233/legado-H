package io.legado.app.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.theme.BuiltinThemes
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppGroup
import io.legado.app.uikit.v5.components.AppIcons
import io.legado.app.uikit.v5.theme.packSeedColors
import io.legado.app.uikit.v5.pack.LayoutPackageSpecV2
import io.legado.app.uikit.v5.pack.BuiltinLayoutPackages
import io.legado.app.ui.host.pack.V5PackageStore
import io.legado.app.uikit.v5.contract.AppContent
import io.legado.app.uikit.v5.contract.ChromeAction
import io.legado.app.uikit.v5.contract.ContentScope
import io.legado.app.uikit.v5.contract.DestIcon
import io.legado.app.uikit.v5.contract.DestTitle
import io.legado.app.uikit.v5.contract.ScreenChrome
import io.legado.app.uikit.v5.contract.TopBarMode
import io.legado.app.theme.pack.ThemePackageSpec

/**
 * P3-3 主题和界面管理中心（新组件重写，docs/ui-v5-skeleton-first.md 4.3）。
 *
 * 两个包库子区：官方包 + 用户包，**点包即应用**；
 * 编辑官方包自动克隆为"我的包 N"（另存模型），自定义包可重命名/删除。
 */
class HostPackageCenterContent : AppContent {

    override fun chrome(): ScreenChrome = ScreenChrome(
        topBar = TopBarMode.LARGE_TITLE,
        title = DestTitle.Text("管理中心"),
        subtitle = DestTitle.Text("主题包管美术 · 界面包管结构"),
        actions = listOf(
            ChromeAction(
                id = "new",
                icon = DestIcon.Vector(HostIcons.Plus),
                contentDescription = DestTitle.Text("基于当前外观新建"),
                onClick = {}, // 由内容区的按钮承担（actions 无 scope）
            ),
        ),
    )

    @Composable
    override fun Content(scope: ContentScope) {
        val d = scope.density
        var editingTheme by remember { mutableStateOf<ThemePackageSpec?>(null) }
        var editingLayout by remember { mutableStateOf<LayoutPackageSpecV2?>(null) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = d.screenPadding),
            verticalArrangement = Arrangement.spacedBy(d.blockGap),
        ) {
            AppCard {
                Column(Modifier.padding(d.cardPadding)) {
                    Text("入口链：我的 → UI 设置 → 主题和界面管理中心", style = scope.type.label, color = scope.color.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "点包即应用、不重启；编辑官方包会自动克隆为「我的包」再改（另存模型）。",
                        style = scope.type.caption,
                        color = scope.color.onSurfaceVariant,
                    )
                }
            }

            // ---------------- 主题包 ----------------
            AppGroup(
                title = "主题包 · 美术资源",
                trailing = {
                    Text(
                        "基于当前新建",
                        style = scope.type.label,
                        color = scope.color.primary,
                        modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { editingTheme = V5PackageStore.newThemeFromActive() },
                    )
                },
            ) {
                val active = V5PackageStore.activeThemeId()
                val themes = BuiltinThemes.all + V5PackageStore.userThemes()
                themes.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(d.blockGap)) {
                        pair.forEach { spec ->
                            ThemePackageCard(
                                spec = spec,
                                active = spec.id == active,
                                onApply = { V5PackageStore.applyTheme(spec.id) },
                                onEdit = {
                                    editingTheme = V5PackageStore.editableTheme(spec)
                                    V5PackageStore.applyTheme(spec.id)
                                },
                                onDelete = { V5PackageStore.deleteTheme(spec.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(d.blockGap))
                }
            }

            // ---------------- 界面包 ----------------
            AppGroup(
                title = "界面包 · 结构参数",
                trailing = {
                    Text(
                        "基于当前新建",
                        style = scope.type.label,
                        color = scope.color.primary,
                        modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { editingLayout = V5PackageStore.newLayoutFromActive() },
                    )
                },
            ) {
                val active = V5PackageStore.activeLayoutId()
                val layouts = BuiltinLayoutPackages.all + V5PackageStore.userLayouts()
                layouts.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(d.blockGap)) {
                        pair.forEach { spec ->
                            LayoutPackageCard(
                                spec = spec,
                                active = spec.id == active,
                                onApply = { V5PackageStore.applyLayout(spec.id) },
                                onEdit = {
                                    editingLayout = V5PackageStore.editableLayout(spec)
                                    V5PackageStore.applyLayout(spec.id)
                                },
                                onDelete = { V5PackageStore.deleteLayout(spec.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(d.blockGap))
                }
            }

            Spacer(Modifier.height(d.screenPadding))
        }

        // 包内编辑抽屉
        editingTheme?.let { spec ->
            ThemeEditorSheet(
                spec = spec,
                onUpdate = { updated ->
                    V5PackageStore.updateTheme(updated)
                    editingTheme = updated
                },
                onDismiss = { editingTheme = null },
            )
        }
        editingLayout?.let { spec ->
            LayoutEditorSheet(
                spec = spec,
                onUpdate = { updated ->
                    V5PackageStore.updateLayout(updated)
                    editingLayout = updated
                },
                onDismiss = { editingLayout = null },
            )
        }
    }
}

// ---------------------------------------------------------------- 包卡片

@Composable
private fun ThemePackageCard(
    spec: ThemePackageSpec,
    active: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = LocalScope()
    val d = scope.density
    val isBuiltin = BuiltinThemes.byId(spec.id) != null
    AppCard(
        modifier = modifier,
        onClick = onApply,
    ) {
        Column(Modifier.padding(d.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeedDots(spec, Modifier.weight(1f))
                if (active) ActiveDot()
            }
            Spacer(Modifier.height(d.blockGap / 2))
            Text(spec.name, style = scope.type.label, color = scope.color.onSurface, maxLines = 1)
            Text(
                if (isBuiltin) "内置 · ${spec.author}" else "我的包" + (spec.basedOn?.let { " · 基于 $it" } ?: ""),
                style = scope.type.caption,
                color = scope.color.onSurfaceVariant,
                maxLines = 1,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = d.blockGap / 2),
                horizontalArrangement = Arrangement.spacedBy(d.blockGap / 2),
            ) {
                Text(
                    "编辑",
                    style = scope.type.caption,
                    color = scope.color.primary,
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onEdit() },
                )
                if (!isBuiltin) {
                    Text(
                        "删除",
                        style = scope.type.caption,
                        color = scope.color.error,
                        modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun LayoutPackageCard(
    spec: LayoutPackageSpecV2,
    active: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = LocalScope()
    val d = scope.density
    val isBuiltin = BuiltinLayoutPackages.byId(spec.id) != null
    AppCard(
        modifier = modifier,
        onClick = onApply,
    ) {
        Column(Modifier.padding(d.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = spec.navPosition.replaceFirstChar { it.uppercase() } + " · " +
                        spec.shapeFamily + " · " + (if (spec.glassEnabled) "玻璃" else "实色"),
                    style = scope.type.caption,
                    color = scope.color.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (active) ActiveDot()
            }
            Spacer(Modifier.height(d.blockGap / 2))
            Text(spec.name, style = scope.type.label, color = scope.color.onSurface, maxLines = 1)
            Text(
                if (isBuiltin) "内置 · ${spec.author}" else "我的包" + (spec.basedOn?.let { " · 基于 $it" } ?: ""),
                style = scope.type.caption,
                color = scope.color.onSurfaceVariant,
                maxLines = 1,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = d.blockGap / 2),
                horizontalArrangement = Arrangement.spacedBy(d.blockGap / 2),
            ) {
                Text(
                    "编辑",
                    style = scope.type.caption,
                    color = scope.color.primary,
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onEdit() },
                )
                if (!isBuiltin) {
                    Text(
                        "删除",
                        style = scope.type.caption,
                        color = scope.color.error,
                        modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveDot() {
    val colors = io.legado.app.uikit.v5.runtime.LocalSemanticColors.current
    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(colors.primary),
    )
}

/** 主题包色卡：种子色派生的三个语义色圆点。 */
@Composable
private fun SeedDots(spec: ThemePackageSpec, modifier: Modifier = Modifier) {
    val colors = packSeedColors(spec)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        colors.forEach { c ->
            Box(
                modifier = Modifier
                    .size(17.dp)
                    .clip(CircleShape)
                    .background(c),
            )
        }
    }
}

/** 当前 ContentScope（包卡片在 Content 内组合，读宿主下发的 LocalVisualSpec 派生 scope）。 */
@Composable
internal fun LocalScope(): ContentScope {
    val visual = io.legado.app.uikit.v5.runtime.LocalVisualSpec.current
    val colors = io.legado.app.uikit.v5.runtime.LocalSemanticColors.current
    return remember(visual, colors) { io.legado.app.uikit.v5.contract.HostScope.create(visual, colors) }
}
