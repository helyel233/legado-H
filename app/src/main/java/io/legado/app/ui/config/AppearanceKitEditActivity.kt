package io.legado.app.ui.config

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.help.config.AppearanceKitEditOptions
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.ComponentRef
import io.legado.app.help.config.KitBinding
import io.legado.app.help.config.MainLayoutPresetConfig
import io.legado.app.help.config.StoredAppearanceKit
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppListItem
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch

class AppearanceKitEditActivity : AppCompatActivity() {

    internal var kitState by mutableStateOf<StoredAppearanceKit?>(null)
    internal var dayOptions by mutableStateOf(AppearanceKitEditOptions())
    internal var nightOptions by mutableStateOf(AppearanceKitEditOptions())
    internal var nameText by mutableStateOf("")
    internal var selectorState by mutableStateOf<SelectorState?>(null)
    internal var confirmDelete by mutableStateOf(false)
    internal var menuOpen by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { AppearanceKitEditScreen(this) }
        loadKit()
    }

    private fun loadKit() {
        val kitId = intent.getStringExtra(EXTRA_KIT_ID).orEmpty()
        lifecycleScope.launch {
            val kit = AppearanceKitManager.importedKit(kitId)
            if (kit == null) {
                toastOnUi(R.string.error)
                finish()
                return@launch
            }
            kitState = kit
            nameText = kit.name
            dayOptions = AppearanceKitManager.editableOptions(false)
            nightOptions = AppearanceKitManager.editableOptions(true)
        }
    }

    internal fun onKitChange(kit: StoredAppearanceKit) {
        kitState = kit
    }

    internal fun saveKit() {
        val kit = kitState ?: return
        lifecycleScope.launch {
            runCatching {
                AppearanceKitManager.saveImportedKit(kit, this@AppearanceKitEditActivity)
            }.onSuccess { saved ->
                toastOnUi(if (saved) R.string.success else R.string.error)
                if (saved) finish()
            }.onFailure { toastOnUi(it.localizedMessage ?: getString(R.string.error)) }
        }
    }

    internal fun deleteKit() {
        val kit = kitState ?: return
        lifecycleScope.launch {
            runCatching {
                AppearanceKitManager.deleteImportedTheme(
                    this@AppearanceKitEditActivity,
                    kit.toAppearanceKit()
                )
            }.onSuccess { deleted ->
                toastOnUi(if (deleted) R.string.delete_success else R.string.error)
                if (deleted) finish()
            }.onFailure { toastOnUi(it.localizedMessage ?: getString(R.string.error)) }
        }
    }

    internal fun openPresetSelector() {
        val kit = kitState ?: return
        val values = listOf<String?>(
            null,
            MainLayoutPresetConfig.PRESET_DEFAULT,
            MainLayoutPresetConfig.PRESET_REGULAR,
            MainLayoutPresetConfig.PRESET_SIDEBAR
        )
        val labels = listOf("默认缺省: 常规", "悬浮", "常规", "侧栏")
        selectorState = SelectorState(
            title = "导航样式",
            options = labels,
            selectedIndex = values.indexOf(kit.binding.preset).takeIf { it >= 0 } ?: 0,
            onSelected = { index ->
                val preset = values.getOrNull(index)
                onKitChange(kit.copy(binding = kit.binding.copy(preset = preset)))
            }
        )
    }

    internal fun openFieldSelector(field: Field, isNight: Boolean) {
        val kit = kitState ?: return
        val options = optionsFor(field, isNight)
        val current = currentRef(field, isNight)
        val labels = listOf(field.defaultLabel) + options.map { it.name.ifBlank { it.dirName } }
        val currentIndex = current
            ?.let { value -> options.indexOfFirst { it.key() == value.key() } }
            ?.takeIf { it >= 0 }
        selectorState = SelectorState(
            title = field.title,
            options = labels,
            selectedIndex = currentIndex?.plus(1) ?: 0,
            onSelected = { index ->
                val ref = if (index <= 0) null else options.getOrNull(index - 1)
                val newBinding = kit.binding.copy().apply { setField(field, isNight, ref) }
                onKitChange(kit.copy(binding = newBinding))
            }
        )
    }

    internal fun currentRef(field: Field, isNight: Boolean): ComponentRef? {
        val kit = kitState ?: return null
        return when (field) {
            Field.THEME -> if (isNight) kit.binding.nightTheme else kit.binding.dayTheme
            Field.TOP_BAR -> if (isNight) kit.binding.nightTopBar else kit.binding.dayTopBar
            Field.NAV_BAR -> if (isNight) kit.binding.nightNavigationBar else kit.binding.dayNavigationBar
            Field.COVER -> if (isNight) kit.binding.nightCoverCollection else kit.binding.dayCoverCollection
        }
    }

    internal fun optionsFor(field: Field, isNight: Boolean): List<ComponentRef> {
        val opts = if (isNight) nightOptions else dayOptions
        return when (field) {
            Field.THEME -> opts.themes
            Field.TOP_BAR -> opts.topBars
            Field.NAV_BAR -> opts.navigationBars
            Field.COVER -> opts.coverCollections
        }
    }

    private fun KitBinding.setField(field: Field, isNight: Boolean, ref: ComponentRef?) {
        when (field) {
            Field.THEME -> setTheme(isNight, ref)
            Field.TOP_BAR -> setTopBar(isNight, ref)
            Field.NAV_BAR -> setNavigationBar(isNight, ref)
            Field.COVER -> setCoverCollection(isNight, ref)
        }
    }

    internal enum class Field(val title: String, val defaultLabel: String) {
        THEME("界面主题", "使用内置界面主题"),
        TOP_BAR("顶栏", "缺省: 常规顶栏"),
        NAV_BAR("底栏", "使用默认 MD3 常规底栏"),
        COVER("封面图集", "使用默认封面图集")
    }

    internal data class SelectorState(
        val title: String,
        val options: List<String>,
        val selectedIndex: Int,
        val onSelected: (Int) -> Unit
    )

    companion object {
        const val EXTRA_KIT_ID = "kitId"
    }
}

@Composable
private fun AppearanceKitEditScreen(activity: AppearanceKitEditActivity) {
    BackHandler { activity.finish() }

    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    val kit = activity.kitState ?: return

    HostV5Page(
        title = stringResource(R.string.edit),
        onBack = { activity.finish() },
        trailing = {
            AppOverflowMenuButton(
                contentDescription = stringResource(R.string.more),
                onClick = { activity.menuOpen = true },
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = density.screenPadding,
                end = density.screenPadding,
                bottom = density.blockGap / 2,
            ),
        ) {
            item {
                SectionTitle("应用主题")
                AppCard {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                        AppTextField(
                            value = activity.nameText,
                            onValueChange = {
                                activity.nameText = it
                                activity.onKitChange(kit.copy(name = it))
                            },
                            placeholder = "名称",
                            singleLine = true,
                        )
                        Spacer(Modifier.height(4.dp))
                        AppListItem(
                            headline = "导航样式",
                            supporting = presetLabel(kit.binding.preset),
                            onClick = { activity.openPresetSelector() },
                            card = false,
                        )
                    }
                }
                Spacer(Modifier.height(density.blockGap))
            }
            item {
                SectionTitle("预览")
                AppCard {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = kit.name.ifBlank { "应用主题" },
                            style = visual.type.body,
                            color = colors.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = kit.toAppearanceKit().summary,
                            style = visual.type.caption,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "界面形态：${kit.binding.interfaceTweaks?.summary ?: "未自定义"}",
                            style = visual.type.caption,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(density.blockGap))
            }
            item {
                BindingCard(activity, kit.binding, "日间", false, activity.dayOptions)
                Spacer(Modifier.height(density.blockGap))
                BindingCard(activity, kit.binding, "夜间", true, activity.nightOptions)
                Spacer(Modifier.height(density.blockGap))
                AppFilledButton(
                    text = "保存",
                    onClick = { activity.saveKit() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (activity.menuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.edit),
            actions = listOf(
                stringResource(R.string.delete) to { activity.confirmDelete = true },
            ),
            onDismiss = { activity.menuOpen = false },
        )
    }

    if (activity.confirmDelete) {
        AppConfirmDialog(
            title = stringResource(R.string.delete),
            message = stringResource(R.string.appearance_kit_delete_confirm, kit.name),
            confirmLabel = stringResource(R.string.delete),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                activity.confirmDelete = false
                activity.deleteKit()
            },
            onDismiss = { activity.confirmDelete = false },
        )
    }

    activity.selectorState?.let { sel ->
        AppFilterDialog(
            title = sel.title,
            options = sel.options,
            selectedIndex = sel.selectedIndex,
            onSelect = { index ->
                activity.selectorState = null
                sel.onSelected(index)
            },
            onDismiss = { activity.selectorState = null },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Text(
        text = text,
        style = visual.type.body,
        color = colors.onSurface,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = visual.density.screenPadding,
                vertical = visual.density.blockGap / 2,
            ),
    )
}

@Composable
private fun BindingCard(
    activity: AppearanceKitEditActivity,
    binding: KitBinding,
    title: String,
    isNight: Boolean,
    options: AppearanceKitEditOptions,
) {
    SectionTitle(title)
    AppCard {
        Column {
            for (field in AppearanceKitEditActivity.Field.entries) {
                val current = activity.currentRef(field, isNight)
                AppListItem(
                    headline = field.title,
                    supporting = current?.name?.ifBlank { current.dirName } ?: field.defaultLabel,
                    onClick = { activity.openFieldSelector(field, isNight) },
                    card = false,
                )
            }
        }
    }
}

private fun presetLabel(preset: String?): String = when (preset) {
    MainLayoutPresetConfig.PRESET_DEFAULT -> "悬浮"
    MainLayoutPresetConfig.PRESET_REGULAR -> "常规"
    MainLayoutPresetConfig.PRESET_SIDEBAR -> "侧栏"
    else -> "默认缺省: 常规"
}
