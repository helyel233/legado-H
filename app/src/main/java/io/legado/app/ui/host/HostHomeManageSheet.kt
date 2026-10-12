package io.legado.app.ui.host

import io.legado.app.R

import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.domain.model.ModuleDef
import io.legado.app.ui.main.homepage.HomepageManageUiState
import io.legado.app.ui.main.homepage.HomepageModuleManageUi
import io.legado.app.ui.main.homepage.HomepageSourceManageUi
import io.legado.app.ui.main.homepage.HomepageViewModel
import io.legado.app.uikit.v5.components.AppBottomSheet
import io.legado.app.uikit.v5.components.AppSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.contract.ContentScope
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import com.google.gson.Gson

/**
 * P4 整改①：主页模块管理 —— V5 原生实现，完全替代旧
 * HomepageModuleManageSheet（View 体系 RecyclerView + databinding 弹层）。
 *
 * 页面栈（抽屉内自导航，全部走 V5 组件）：
 *   集列表（显隐开关/新建/重命名/删除）
 *     → 集内模块（显隐开关/删除/上移/下移/添加模块）
 *       → 添加：源列表 → 分类勾选 → 命名入库
 *
 * 数据层零改动：全部经 HomepageViewModel 现有公开方法。
 */
private sealed interface ManagePage {
    data object Sets : ManagePage
    data class SetDetail(val setUrl: String, val setName: String) : ManagePage
    data class PickSource(val setUrl: String?) : ManagePage
    data class PickKinds(
        val sourceUrl: String,
        val sourceName: String,
        val sourceType: String,
        val setId: String?,
    ) : ManagePage
}

@Composable
fun HostHomeManageSheet(
    scope: ContentScope,
    viewModel: HomepageViewModel,
    onDismiss: () -> Unit,
) {
    val manage by viewModel.manageStateFlow.collectAsState()
    var stack by remember { mutableStateOf(listOf<ManagePage>(ManagePage.Sets)) }
    val page = stack.last()

    AppBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(scope.density.screenPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            SheetHeader(scope, page, stack.size > 1) { stack = stack.dropLast(1) }

            when (page) {
                is ManagePage.Sets -> SetsPage(scope, manage, viewModel,
                    onOpenSet = { stack = stack + it },
                    onCreateSet = { stack = stack + ManagePage.PickSource(null) },
                )

                is ManagePage.SetDetail -> SetDetailPage(scope, manage, viewModel, page,
                    onAdd = { stack = stack + ManagePage.PickSource(page.setUrl) },
                    onReorder = { ids -> viewModel.reorderModules(ids) },
                )

                is ManagePage.PickSource -> PickSourcePage(scope, manage,
                    onPick = { src ->
                        stack = stack + ManagePage.PickKinds(
                            src.sourceUrl, src.sourceName, src.sourceType ?: "book", page.setUrl,
                        )
                    },
                )

                is ManagePage.PickKinds -> PickKindsPage(scope, viewModel, page,
                    onDone = { stack = stack.dropLast(2) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 集列表

@Composable
private fun SetsPage(
    scope: ContentScope,
    manage: HomepageManageUiState,
    viewModel: HomepageViewModel,
    onOpenSet: (ManagePage.SetDetail) -> Unit,
    onCreateSet: () -> Unit,
) {
    val colors = LocalSemanticColors.current
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    manage.sets.forEach { set ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(scope.shape(ShapeKind.FIELD))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { onOpenSet(ManagePage.SetDetail(set.sourceUrl, set.sourceName)) }
                .padding(horizontal = scope.density.screenPadding / 2, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(set.sourceName, style = scope.type.body, color = colors.onSurface, maxLines = 1)
                Text(
                    stringResource(R.string.v5_home_manage_modules_count, set.moduleCount) + if (set.isCustomSet) stringResource(R.string.v5_home_manage_custom) else "",
                    style = scope.type.caption,
                    color = colors.onSurfaceVariant,
                )
            }
            if (set.isCustomSet) {
                Text(
                    "删除",
                    style = scope.type.caption,
                    color = colors.error,
                    modifier = Modifier
                        .padding(end = scope.density.blockGap)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { viewModel.deleteCustomSet(HomepageViewModel.customSetIdFromUrl(set.sourceUrl)) },
                )
            }
            AppSwitch(
                checked = set.isSelected,
                onCheckedChange = { on -> viewModel.toggleSet(set.sourceUrl, on) },
            )
        }
    }

    SheetActionButton(scope, stringResource(R.string.v5_home_manage_new_set)) {
        creating = true
        name = ""
    }

    if (creating) {
        NameInputDialog(
            scope = scope,
            title = "新建自定义集",
            initial = name,
            onValueChange = { name = it },
            onConfirm = {
                if (name.isNotBlank()) viewModel.createCustomSet(name.trim())
                creating = false
            },
            onDismiss = { creating = false },
        )
    }
}

// ---------------------------------------------------------------- 集内模块

@Composable
private fun SetDetailPage(
    scope: ContentScope,
    manage: HomepageManageUiState,
    viewModel: HomepageViewModel,
    page: ManagePage.SetDetail,
    onAdd: () -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    val colors = LocalSemanticColors.current
    val setId = resolveSetUrl(page.setUrl)
    val modules = manage.allJoinedModules.filter { it.customSetId == setId }

    if (modules.isEmpty()) {
        Text(
            stringResource(R.string.v5_home_manage_no_modules),
            style = scope.type.caption,
            color = colors.onSurfaceVariant,
        )
    }
    modules.forEachIndexed { index, module ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(module.title, style = scope.type.body, color = colors.onSurface, maxLines = 1)
                Text(
                    module.sourceName,
                    style = scope.type.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (index > 0) {
                Text(
                    "↑",
                    style = scope.type.label,
                    color = colors.primary,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) {
                            val ids = modules.map { it.id }.toMutableList()
                            ids[index] = ids[index - 1].also { ids[index - 1] = ids[index] }
                            onReorder(ids)
                        },
                )
            }
            if (index < modules.lastIndex) {
                Text(
                    "↓",
                    style = scope.type.label,
                    color = colors.primary,
                    modifier = Modifier
                        .padding(end = scope.density.blockGap)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) {
                            val ids = modules.map { it.id }.toMutableList()
                            ids[index] = ids[index + 1].also { ids[index + 1] = ids[index] }
                            onReorder(ids)
                        },
                )
            }
            Text(
                "删除",
                style = scope.type.caption,
                color = colors.error,
                modifier = Modifier
                    .padding(end = scope.density.blockGap)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { viewModel.deleteModule(module.id) },
            )
            AppSwitch(
                checked = module.isVisible,
                onCheckedChange = { on -> viewModel.toggleModule(module.id, on) },
            )
        }
    }

    SheetActionButton(scope, stringResource(R.string.v5_home_manage_add_module), onClick = onAdd)
}

// ---------------------------------------------------------------- 添加模块

@Composable
private fun PickSourcePage(
    scope: ContentScope,
    manage: HomepageManageUiState,
    onPick: (HomepageSourceManageUi) -> Unit,
) {
    val colors = LocalSemanticColors.current
    Text(stringResource(R.string.v5_home_manage_pick_hint), style = scope.type.caption, color = colors.onSurfaceVariant)
    Spacer(Modifier.height(scope.density.blockGap / 2))
    (manage.browseSources + manage.rssSources).forEach { src ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(scope.shape(ShapeKind.FIELD))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { onPick(src) }
                .padding(horizontal = scope.density.screenPadding / 2, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(src.sourceName, style = scope.type.body, color = colors.onSurface, maxLines = 1)
                if (!src.sourceGroup.isNullOrBlank()) {
                    Text(src.sourceGroup, style = scope.type.caption, color = colors.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PickKindsPage(
    scope: ContentScope,
    viewModel: HomepageViewModel,
    page: ManagePage.PickKinds,
    onDone: () -> Unit,
) {
    val colors = LocalSemanticColors.current
    var kinds by remember { mutableStateOf<List<ExploreKind>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var checked by remember { mutableStateOf(setOf<Int>()) }
    var title by remember { mutableStateOf("") }

    LaunchedEffect(page.sourceUrl) {
        loading = true
        kinds = runCatching {
            if (page.sourceType == "rss") {
                viewModel.getRssKinds(page.sourceUrl).map { (t, u) -> ExploreKind(title = t, url = u) }
            } else {
                viewModel.getExploreKinds(page.sourceUrl)
            }
        }.getOrElse { emptyList() }
        loading = false
    }

    when {
        loading -> Text("加载分类中…", style = scope.type.caption, color = colors.onSurfaceVariant)
        kinds.isEmpty() -> Text(
            stringResource(R.string.v5_home_manage_no_kinds),
            style = scope.type.caption,
            color = colors.onSurfaceVariant,
        )
        else -> {
            kinds.forEachIndexed { i, kind ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) {
                            checked = if (i in checked) checked - i else checked + i
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (i in checked) "☑" else "☐",
                        style = scope.type.label,
                        color = colors.primary,
                        modifier = Modifier.padding(end = scope.density.blockGap / 2),
                    )
                    Text(kind.title, style = scope.type.body, color = colors.onSurface, maxLines = 1)
                }
            }
            Spacer(Modifier.height(scope.density.blockGap / 2))
            AppTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = "模块标题（默认取分类名）",
            )
            Spacer(Modifier.height(scope.density.blockGap))
            SheetActionButton(scope, "添加到主页") {
                val picked = checked.mapNotNull { kinds.getOrNull(it) }
                if (picked.isEmpty()) return@SheetActionButton
                val def = ModuleDef(
                    key = "explore_" + picked.joinToString("_") { it.title },
                    type = HomepageModuleType.Ranking.key,
                    title = title.ifBlank { picked.joinToString("、") { it.title } },
                    args = Gson().toJson(
                        picked.map { mapOf("t" to it.title, "u" to (it.url ?: "")) },
                    ),
                    url = picked.firstOrNull()?.url,
                    sourceUrl = page.sourceUrl,
                )
                if (page.sourceType == "rss") {
                    viewModel.addRssCustomModule(page.sourceUrl, page.setId?.let(::resolveSetUrl), def, page.sourceName)
                } else {
                    viewModel.addCustomModule(page.sourceUrl, page.setId?.let(::resolveSetUrl), def)
                }
                onDone()
            }
        }
    }
}

// ---------------------------------------------------------------- 通用件

@Composable
private fun SheetHeader(scope: ContentScope, page: ManagePage, canBack: Boolean, onBack: () -> Unit) {
    val colors = LocalSemanticColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (canBack) {
            Text(
                "←",
                style = scope.type.title,
                color = colors.primary,
                modifier = Modifier
                    .padding(end = scope.density.blockGap)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onBack() },
            )
        }
        Text(
            text = when (page) {
                ManagePage.Sets -> stringResource(R.string.v5_home_manage_title)
                is ManagePage.SetDetail -> page.setName.ifBlank { stringResource(R.string.v5_home_manage_set_detail) }
                is ManagePage.PickSource -> stringResource(R.string.v5_home_manage_pick_source)
                is ManagePage.PickKinds -> page.sourceName
            },
            style = scope.type.title,
            color = colors.onSurface,
        )
    }
    Spacer(Modifier.height(scope.density.blockGap))
}

@Composable
internal fun SheetActionButton(scope: ContentScope, label: String, onClick: () -> Unit) {
    val colors = LocalSemanticColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.primary.copy(alpha = 0.14f), scope.shape(ShapeKind.BUTTON))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = scope.type.label, color = colors.primary)
    }
}

@Composable
private fun NameInputDialog(
    scope: ContentScope,
    title: String,
    initial: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    io.legado.app.uikit.v5.components.AppInputDialog(
        title = title,
        value = initial,
        onValueChange = onValueChange,
        placeholder = "名称",
        confirmLabel = stringResource(R.string.v5_ok2),
        cancelLabel = stringResource(R.string.v5_cancel2),
        onConfirm = { onConfirm() },
        onDismiss = onDismiss,
    )
}

private fun resolveSetUrl(setUrl: String): String =
    if (HomepageViewModel.isCustomSetUrl(setUrl)) {
        HomepageViewModel.customSetIdFromUrl(setUrl)
    } else {
        setUrl
    }
