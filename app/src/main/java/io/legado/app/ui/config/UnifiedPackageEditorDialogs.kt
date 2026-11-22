package io.legado.app.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.toColorInt
import io.legado.app.help.config.pkg.ArtPackagePayload
import io.legado.app.help.config.pkg.ArtPaletteSpec
import io.legado.app.help.config.pkg.ArtTypographySpec
import io.legado.app.help.config.pkg.ArtWallpapersSpec
import io.legado.app.help.config.pkg.LayoutParams
import io.legado.app.help.config.pkg.ParamPackagePayload
import io.legado.app.help.config.pkg.SurfaceGlobalParams
import io.legado.app.help.config.pkg.SurfaceScopeOverride
import io.legado.app.help.config.pkg.SurfaceScopesParams
import io.legado.app.help.config.pkg.UiScope
import io.legado.app.help.config.pkg.UnifiedPackageRepository
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.LegadoSegmentedControl
import io.legado.app.ui.widget.compose.SegmentedControlTone
import io.legado.app.ui.widget.compose.SegmentedOption
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.toMiuixPalette
import java.util.Locale

@Composable
internal fun PackageNamePromptDialog(
    title: String,
    subtitle: String? = null,
    initialName: String,
    existingNames: List<String>,
    confirmText: String = "确认",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    var draftName by remember(initialName) { mutableStateOf(initialName) }
    val trimmed = draftName.trim()
    val isDuplicate = trimmed.isNotBlank() &&
        !UnifiedPackageRepository.isPackageNameUnique(existingNames, trimmed)
    val isValid = trimmed.isNotBlank() && !isDuplicate

    Dialog(onDismissRequest = onDismiss) {
        AppDialogFrame(
            title = title,
            message = subtitle,
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        label = { Text("包名称（同类型内严禁重复）") },
                        singleLine = true,
                        isError = isDuplicate || trimmed.isBlank(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isDuplicate) {
                        Text(
                            text = "包名称「$trimmed」已存在，请修改为不重复的名称",
                            color = style.danger,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            actions = {
                LegadoMiuixActionButton(
                    text = "取消",
                    palette = palette,
                    onClick = onDismiss
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = confirmText,
                    palette = palette,
                    primary = true,
                    enabled = isValid,
                    onClick = { if (isValid) onConfirm(trimmed) }
                )
            }
        )
    }
}

@Composable
internal fun DeletePackageConfirmDialog(
    packageName: String,
    mountedSlots: List<String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val message = if (mountedSlots.isEmpty()) {
        "确定要删除「$packageName」吗？此操作不可撤销。"
    } else {
        "「$packageName」当前正被 [${mountedSlots.joinToString("、")}] 使用。\n确认删除后，对应槽位将自动安全回退至默认包。"
    }
    Dialog(onDismissRequest = onDismiss) {
        AppDialogFrame(
            title = "删除包确认",
            message = message,
            content = {},
            actions = {
                LegadoMiuixActionButton(
                    text = "取消",
                    palette = palette,
                    onClick = onDismiss
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = "确认删除",
                    palette = palette,
                    danger = true,
                    onClick = onConfirm
                )
            }
        )
    }
}

/**
 * 二级《美术资源包编辑器》：内聚包名（唯一校验）、12 项色板、7 处壁纸与启动图、字体与默认封面。
 */
@Composable
internal fun ArtPackageEditorDialog(
    entry: UnifiedPackageRepository.StoredEntry,
    existingNames: List<String>,
    onPickAssetFile: ((currentPath: String?, onPicked: (String) -> Unit) -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (newName: String, updatedArt: ArtPackagePayload) -> Unit
) {
    val style = rememberAppDialogStyle()
    val dialogPalette = style.toMiuixPalette()
    val mgmtPalette = rememberAppManagementPalette()
    val initialArt = entry.manifest.art ?: ArtPackagePayload()
    val basePalette = initialArt.dayPalette ?: initialArt.nightPalette ?: ArtPaletteSpec()
    val baseWallpapers = initialArt.dayWallpapers ?: initialArt.nightWallpapers ?: ArtWallpapersSpec()
    val baseTypography = initialArt.dayTypography ?: initialArt.nightTypography ?: ArtTypographySpec()

    var nameDraft by remember(entry.manifest.id) { mutableStateOf(entry.manifest.name) }
    var activeTab by remember { mutableIntStateOf(0) }

    // 12 Palette Colors
    var primaryColor by remember { mutableStateOf(basePalette.primaryColor.orEmpty()) }
    var accentColor by remember { mutableStateOf(basePalette.accentColor.orEmpty()) }
    var backgroundColor by remember { mutableStateOf(basePalette.backgroundColor.orEmpty()) }
    var bottomBackground by remember { mutableStateOf(basePalette.bottomBackground.orEmpty()) }
    var cardColor by remember { mutableStateOf(basePalette.cardColor.orEmpty()) }
    var mutedColor by remember { mutableStateOf(basePalette.mutedColor.orEmpty()) }
    var searchFieldBg by remember { mutableStateOf(basePalette.searchFieldBackgroundColor.orEmpty()) }
    var tabBg by remember { mutableStateOf(basePalette.tabBackgroundColor.orEmpty()) }
    var shelfColor by remember { mutableStateOf(basePalette.shelfColor.orEmpty()) }
    var panelBorderColor by remember { mutableStateOf(basePalette.panelBorderColor.orEmpty()) }
    var uiFontColor by remember { mutableStateOf(basePalette.uiFontColor.orEmpty()) }
    var titleFontColor by remember { mutableStateOf(basePalette.titleFontColor.orEmpty()) }

    // 7 Wallpapers + Welcome
    var mainWallpaper by remember { mutableStateOf(baseWallpapers.main.orEmpty()) }
    var topBarWallpaper by remember { mutableStateOf(baseWallpapers.topBar.orEmpty()) }
    var topBarAlpha by remember { mutableIntStateOf((baseWallpapers.topBarAlpha ?: 100).coerceIn(0, 100)) }
    var bottomBarWallpaper by remember { mutableStateOf(baseWallpapers.bottomBar.orEmpty()) }
    var bookInfoWallpaper by remember { mutableStateOf(baseWallpapers.bookInfo.orEmpty()) }
    var panelWallpaper by remember { mutableStateOf(baseWallpapers.panel.orEmpty()) }
    var welcomeWallpaper by remember { mutableStateOf(baseWallpapers.welcome.orEmpty()) }

    // Fonts & Cover
    var uiFontPath by remember { mutableStateOf(baseTypography.uiFontPath.orEmpty()) }
    var titleFontPath by remember { mutableStateOf(baseTypography.titleFontPath.orEmpty()) }
    var defaultCoverPath by remember { mutableStateOf(initialArt.defaultCoverPath.orEmpty()) }

    val otherNames = remember(existingNames, entry.manifest.name) {
        existingNames.filterNot {
            UnifiedPackageRepository.normalizePackageName(it) ==
                UnifiedPackageRepository.normalizePackageName(entry.manifest.name)
        }
    }
    val trimmedName = nameDraft.trim()
    val isDuplicateName = trimmedName.isNotBlank() &&
        !UnifiedPackageRepository.isPackageNameUnique(otherNames, trimmedName)
    val canSave = trimmedName.isNotBlank() && !isDuplicateName

    val tabs = remember {
        listOf(
            SegmentedOption(0, "配色(12项)"),
            SegmentedOption(1, "壁纸与启动图"),
            SegmentedOption(2, "字体与封面")
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        AppDialogFrame(
            title = "编辑美术资源包",
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (entry.manifest.basePackageId != null) {
                        Text(
                            text = "写时复制派生包（基底 ID: ${entry.manifest.basePackageId}）· 仅存储增量替换文件，导出时自动物化为完整独立整包",
                            color = style.secondaryText,
                            fontSize = 11.sp
                        )
                    }
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text("美术包名称（严禁重名）") },
                        singleLine = true,
                        isError = !canSave,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isDuplicateName) {
                        Text(
                            text = "包名称「$trimmedName」已存在，请更换名称",
                            color = style.danger,
                            fontSize = 12.sp
                        )
                    }
                    LegadoSegmentedControl(
                        options = tabs,
                        selectedValue = activeTab,
                        palette = dialogPalette,
                        onSelected = { activeTab = it },
                        tone = SegmentedControlTone.AccentThumb,
                        fontSize = 11.sp
                    )
                    when (activeTab) {
                        0 -> {
                            ColorFieldRow("主色 (Primary)", primaryColor) { primaryColor = it }
                            ColorFieldRow("强调色 (Accent)", accentColor) { accentColor = it }
                            ColorFieldRow("背景色 (Background)", backgroundColor) { backgroundColor = it }
                            ColorFieldRow("底栏背景 (BottomBar)", bottomBackground) { bottomBackground = it }
                            ColorFieldRow("一级卡片色 (Card)", cardColor) { cardColor = it }
                            ColorFieldRow("二级凹槽色 (Muted/Slot)", mutedColor) { mutedColor = it }
                            ColorFieldRow("搜索框底色 (Search)", searchFieldBg) { searchFieldBg = it }
                            ColorFieldRow("标签栏底色 (Tab)", tabBg) { tabBg = it }
                            ColorFieldRow("书架隔板色 (Shelf)", shelfColor) { shelfColor = it }
                            ColorFieldRow("面板边框色 (Border)", panelBorderColor) { panelBorderColor = it }
                            ColorFieldRow("正文字体色 (UI Text)", uiFontColor) { uiFontColor = it }
                            ColorFieldRow("标题字体色 (Title Text)", titleFontColor) { titleFontColor = it }
                        }
                        1 -> {
                            AssetPathFieldRow("主界面壁纸", mainWallpaper, onPickAssetFile) { mainWallpaper = it }
                            AssetPathFieldRow("顶栏壁纸", topBarWallpaper, onPickAssetFile) { topBarWallpaper = it }
                            EditorSliderRow(
                                title = "顶栏壁纸不透明度",
                                valueText = "${topBarAlpha}%",
                                value = topBarAlpha,
                                range = 0..100,
                                palette = mgmtPalette,
                                onValueChange = { topBarAlpha = it }
                            )
                            AssetPathFieldRow("底栏壁纸", bottomBarWallpaper, onPickAssetFile) { bottomBarWallpaper = it }
                            AssetPathFieldRow("书籍详情背景图", bookInfoWallpaper, onPickAssetFile) { bookInfoWallpaper = it }
                            AssetPathFieldRow("弹窗面板背景图", panelWallpaper, onPickAssetFile) { panelWallpaper = it }
                            AssetPathFieldRow("启动页背景图 (Welcome)", welcomeWallpaper, onPickAssetFile) { welcomeWallpaper = it }
                        }
                        2 -> {
                            AssetPathFieldRow("界面正文字体 (.ttf/.otf)", uiFontPath, onPickAssetFile) { uiFontPath = it }
                            AssetPathFieldRow("标题字体 (.ttf/.otf)", titleFontPath, onPickAssetFile) { titleFontPath = it }
                            AssetPathFieldRow("默认书籍封面图", defaultCoverPath, onPickAssetFile) { defaultCoverPath = it }
                            Text(
                                text = "包内已含底栏图标 ${initialArt.icons.size} 项、封面集 ${initialArt.covers.size} 项。",
                                color = style.secondaryText,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            actions = {
                LegadoMiuixActionButton(
                    text = "取消",
                    palette = dialogPalette,
                    onClick = onDismiss
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = "保存美术包",
                    palette = dialogPalette,
                    primary = true,
                    enabled = canSave,
                    onClick = {
                        if (!canSave) return@LegadoMiuixActionButton
                        val updatedPalette = basePalette.copy(
                            primaryColor = primaryColor.trim().ifBlank { null },
                            accentColor = accentColor.trim().ifBlank { null },
                            backgroundColor = backgroundColor.trim().ifBlank { null },
                            bottomBackground = bottomBackground.trim().ifBlank { null },
                            cardColor = cardColor.trim().ifBlank { null },
                            mutedColor = mutedColor.trim().ifBlank { null },
                            searchFieldBackgroundColor = searchFieldBg.trim().ifBlank { null },
                            tabBackgroundColor = tabBg.trim().ifBlank { null },
                            shelfColor = shelfColor.trim().ifBlank { null },
                            panelBorderColor = panelBorderColor.trim().ifBlank { null },
                            uiFontColor = uiFontColor.trim().ifBlank { null },
                            titleFontColor = titleFontColor.trim().ifBlank { null }
                        )
                        val updatedWallpapers = baseWallpapers.copy(
                            main = mainWallpaper.trim().ifBlank { null },
                            topBar = topBarWallpaper.trim().ifBlank { null },
                            topBarAlpha = topBarAlpha,
                            bottomBar = bottomBarWallpaper.trim().ifBlank { null },
                            bookInfo = bookInfoWallpaper.trim().ifBlank { null },
                            panel = panelWallpaper.trim().ifBlank { null },
                            welcome = welcomeWallpaper.trim().ifBlank { null }
                        )
                        val updatedTypography = baseTypography.copy(
                            uiFontPath = uiFontPath.trim().ifBlank { null },
                            titleFontPath = titleFontPath.trim().ifBlank { null }
                        )
                        val newArt = initialArt.copy(
                            dayPalette = updatedPalette,
                            nightPalette = updatedPalette,
                            dayWallpapers = updatedWallpapers,
                            nightWallpapers = updatedWallpapers,
                            dayTypography = updatedTypography,
                            nightTypography = updatedTypography,
                            defaultCoverPath = defaultCoverPath.trim().ifBlank { null }
                        )
                        onSave(trimmedName, newArt)
                    }
                )
            }
        )
    }
}

/**
 * 二级《参数设置包编辑器》：
 * - Tab 0：`@atmosphere`（4 档材质 solid/clear/frosted/glass + 强度 + 4 倍率 + 透明度 + 壁纸模糊，随日/夜槽切换）
 * - Tab 1：`@geometry`（全局圆角倍率 / 阴影 / 空间密度 densityScale，全局单份不随日夜跳变）
 * - Tab 2：`@surfaceScopes`（顶栏 / 底栏 / 书架卡 / 弹窗 4 大区域独立覆写）
 */
@Composable
internal fun ParamPackageEditorDialog(
    entry: UnifiedPackageRepository.StoredEntry,
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (newName: String, updatedParam: ParamPackagePayload) -> Unit
) {
    val style = rememberAppDialogStyle()
    val dialogPalette = style.toMiuixPalette()
    val mgmtPalette = rememberAppManagementPalette()
    val initialParam = entry.manifest.param ?: ParamPackagePayload()
    val g = initialParam.surfaceGlobal ?: SurfaceGlobalParams()
    val l = initialParam.layout ?: LayoutParams()

    var nameDraft by remember(entry.manifest.id) { mutableStateOf(entry.manifest.name) }
    var activeTab by remember { mutableIntStateOf(0) }

    // @atmosphere (Day/Night aware) — 4 modes from GlassMaterialPreset
    var materialMode by remember { mutableStateOf(g.materialMode ?: GlassMaterialPreset.FROSTED) }
    var frostedLevel by remember { mutableIntStateOf((g.frostedLevel ?: 68).coerceIn(0, 100)) }
    var liquidLevel by remember { mutableIntStateOf((g.liquidLevel ?: 68).coerceIn(0, 100)) }
    var blurScale by remember { mutableIntStateOf((g.blurScale ?: 100).coerceIn(50, 200)) }
    var tintScale by remember { mutableIntStateOf((g.tintScale ?: 100).coerceIn(50, 200)) }
    var shellScale by remember { mutableIntStateOf((g.shellScale ?: 100).coerceIn(50, 200)) }
    var strokeScale by remember { mutableIntStateOf((g.strokeScale ?: 100).coerceIn(50, 200)) }
    var strokeColor by remember { mutableStateOf(g.strokeColor.orEmpty()) }
    var surfaceAlpha by remember { mutableIntStateOf((g.surfaceAlpha ?: 100).coerceIn(0, 100)) }
    var dialogAlpha by remember { mutableIntStateOf((g.dialogAlpha ?: 100).coerceIn(0, 100)) }
    var borderAlpha by remember { mutableIntStateOf((g.panelBorderAlpha ?: 100).coerceIn(0, 100)) }
    var wallpaperBlur by remember {
        mutableIntStateOf((g.wallpaperBlurDay ?: g.wallpaperBlurNight ?: 0).coerceIn(0, 25))
    }

    // @geometry (Global single-track)
    var cornerStep by remember { mutableIntStateOf(((g.cornerBaseScale ?: 1f) * 10f).toInt().coerceIn(0, 30)) }
    var cardShadow by remember { mutableIntStateOf((g.cardShadow ?: 0).coerceIn(0, 24)) }
    var densityStep by remember { mutableIntStateOf(((l.densityScale ?: 1f) * 10f).toInt().coerceIn(8, 13)) }
    var searchCornerFollow by remember { mutableStateOf(g.uiCornerSearchFollow ?: true) }
    var replyCornerFollow by remember { mutableStateOf(g.uiCornerReplyFollow ?: true) }

    // @surfaceScopes (4 Scopes)
    var selectedScope by remember { mutableStateOf(UiScope.TOP_BAR) }
    var scopeMap by remember {
        val initialScopes = initialParam.surfaceScopes
        mutableStateOf(
            mapOf(
                UiScope.TOP_BAR to (initialScopes?.topBar ?: SurfaceScopeOverride()),
                UiScope.BOTTOM_BAR to (initialScopes?.bottomBar ?: SurfaceScopeOverride()),
                UiScope.BOOKSHELF_CARD to (initialScopes?.bookshelfCard ?: SurfaceScopeOverride()),
                UiScope.DIALOG to (initialScopes?.dialog ?: SurfaceScopeOverride())
            )
        )
    }

    val otherNames = remember(existingNames, entry.manifest.name) {
        existingNames.filterNot {
            UnifiedPackageRepository.normalizePackageName(it) ==
                UnifiedPackageRepository.normalizePackageName(entry.manifest.name)
        }
    }
    val trimmedName = nameDraft.trim()
    val isDuplicateName = trimmedName.isNotBlank() &&
        !UnifiedPackageRepository.isPackageNameUnique(otherNames, trimmedName)
    val canSave = trimmedName.isNotBlank() && !isDuplicateName

    val tabs = remember {
        listOf(
            SegmentedOption(0, "材质氛围(随日夜)"),
            SegmentedOption(1, "几何手感(全局)"),
            SegmentedOption(2, "4区域覆写")
        )
    }
    val fourMaterialOptions = remember {
        listOf(
            SegmentedOption(GlassMaterialPreset.SOLID, "实底"),
            SegmentedOption(GlassMaterialPreset.CLEAR, "透明"),
            SegmentedOption(GlassMaterialPreset.FROSTED, "磨砂"),
            SegmentedOption(GlassMaterialPreset.GLASS, "液态")
        )
    }
    val scopeMaterialOptions = remember {
        listOf(
            SegmentedOption("", "继承"),
            SegmentedOption(GlassMaterialPreset.SOLID, "实底"),
            SegmentedOption(GlassMaterialPreset.CLEAR, "透明"),
            SegmentedOption(GlassMaterialPreset.FROSTED, "磨砂"),
            SegmentedOption(GlassMaterialPreset.GLASS, "液态")
        )
    }
    val scopeOptions = remember {
        listOf(
            SegmentedOption(UiScope.TOP_BAR, "顶栏"),
            SegmentedOption(UiScope.BOTTOM_BAR, "底栏"),
            SegmentedOption(UiScope.BOOKSHELF_CARD, "书架卡"),
            SegmentedOption(UiScope.DIALOG, "弹窗")
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        AppDialogFrame(
            title = "编辑参数设置包",
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text("参数包名称（严禁重名）") },
                        singleLine = true,
                        isError = !canSave,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isDuplicateName) {
                        Text(
                            text = "包名称「$trimmedName」已存在，请更换名称",
                            color = style.danger,
                            fontSize = 12.sp
                        )
                    }
                    LegadoSegmentedControl(
                        options = tabs,
                        selectedValue = activeTab,
                        palette = dialogPalette,
                        onSelected = { activeTab = it },
                        tone = SegmentedControlTone.AccentThumb,
                        fontSize = 11.sp
                    )
                    when (activeTab) {
                        0 -> {
                            Text(
                                text = "材质模式（4 档，委托 GlassMaterialPreset 单一来源）",
                                color = style.primaryText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            LegadoSegmentedControl(
                                options = fourMaterialOptions,
                                selectedValue = materialMode,
                                palette = dialogPalette,
                                onSelected = { nextMode ->
                                    materialMode = nextMode
                                    val preset = GlassMaterialPreset.presetPoint(nextMode)
                                    frostedLevel = preset.level
                                    liquidLevel = preset.level
                                    blurScale = preset.scales.blur
                                    tintScale = preset.scales.tint
                                    shellScale = preset.scales.shell
                                    strokeScale = preset.scales.stroke
                                },
                                tone = SegmentedControlTone.SurfaceThumb,
                                fontSize = 11.sp
                            )
                            val usesGlass = materialMode == GlassMaterialPreset.FROSTED ||
                                materialMode == GlassMaterialPreset.GLASS
                            if (usesGlass) {
                                val currentLevel = if (materialMode == GlassMaterialPreset.FROSTED) frostedLevel else liquidLevel
                                EditorSliderRow(
                                    title = "玻璃材质强度",
                                    valueText = "$currentLevel%",
                                    value = currentLevel,
                                    range = 0..100,
                                    palette = mgmtPalette,
                                    onValueChange = {
                                        frostedLevel = it
                                        liquidLevel = it
                                    }
                                )
                                EditorSliderRow(
                                    title = "模糊倍率 (Blur)",
                                    valueText = "$blurScale%",
                                    value = blurScale,
                                    range = 50..200,
                                    palette = mgmtPalette,
                                    onValueChange = { blurScale = it }
                                )
                                EditorSliderRow(
                                    title = "着色倍率 (Tint)",
                                    valueText = "$tintScale%",
                                    value = tintScale,
                                    range = 50..200,
                                    palette = mgmtPalette,
                                    onValueChange = { tintScale = it }
                                )
                                EditorSliderRow(
                                    title = "壳层倍率 (Shell)",
                                    valueText = "$shellScale%",
                                    value = shellScale,
                                    range = 50..200,
                                    palette = mgmtPalette,
                                    onValueChange = { shellScale = it }
                                )
                                EditorSliderRow(
                                    title = "描边倍率 (Stroke)",
                                    valueText = "$strokeScale%",
                                    value = strokeScale,
                                    range = 50..200,
                                    palette = mgmtPalette,
                                    onValueChange = { strokeScale = it }
                                )
                                ColorFieldRow("玻璃描边颜色 (选填)", strokeColor) { strokeColor = it }
                            }
                            HorizontalDivider(color = mgmtPalette.settings.divider, thickness = 0.5.dp)
                            EditorSliderRow(
                                title = "全局表面不透明度",
                                valueText = "$surfaceAlpha%",
                                value = surfaceAlpha,
                                range = 0..100,
                                palette = mgmtPalette,
                                onValueChange = { surfaceAlpha = it }
                            )
                            EditorSliderRow(
                                title = "弹窗表面不透明度",
                                valueText = "$dialogAlpha%",
                                value = dialogAlpha,
                                range = 0..100,
                                palette = mgmtPalette,
                                onValueChange = { dialogAlpha = it }
                            )
                            EditorSliderRow(
                                title = "面板边框线不透明度",
                                valueText = "$borderAlpha%",
                                value = borderAlpha,
                                range = 0..100,
                                palette = mgmtPalette,
                                onValueChange = { borderAlpha = it }
                            )
                            EditorSliderRow(
                                title = "主背景壁纸模糊度",
                                valueText = "$wallpaperBlur",
                                value = wallpaperBlur,
                                range = 0..25,
                                palette = mgmtPalette,
                                onValueChange = { wallpaperBlur = it }
                            )
                        }
                        1 -> {
                            Text(
                                text = "几何与手感参数保持全局单份，切换日/夜模式时绝不跳变。",
                                color = style.secondaryText,
                                fontSize = 12.sp
                            )
                            EditorSliderRow(
                                title = "全局圆角倍率",
                                valueText = String.format(Locale.US, "%.1fx", cornerStep / 10f),
                                value = cornerStep,
                                range = 0..30,
                                palette = mgmtPalette,
                                onValueChange = { cornerStep = it }
                            )
                            EditorSliderRow(
                                title = "卡片阴影高度",
                                valueText = "${cardShadow}dp",
                                value = cardShadow,
                                range = 0..24,
                                palette = mgmtPalette,
                                onValueChange = { cardShadow = it }
                            )
                            EditorSliderRow(
                                title = "空间间距密度 (Density Scale)",
                                valueText = String.format(Locale.US, "%.1fx", densityStep / 10f),
                                value = densityStep,
                                range = 8..13,
                                palette = mgmtPalette,
                                onValueChange = { densityStep = it }
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("搜索框圆角跟随全局倍率", color = style.primaryText, fontSize = 13.sp)
                                LegadoMiuixSwitch(
                                    checked = searchCornerFollow,
                                    onCheckedChange = { searchCornerFollow = it },
                                    palette = dialogPalette
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("输入/回复框圆角跟随全局倍率", color = style.primaryText, fontSize = 13.sp)
                                LegadoMiuixSwitch(
                                    checked = replyCornerFollow,
                                    onCheckedChange = { replyCornerFollow = it },
                                    palette = dialogPalette
                                )
                            }
                        }
                        2 -> {
                            val currentScopeOverride = scopeMap[selectedScope] ?: SurfaceScopeOverride()
                            LegadoSegmentedControl(
                                options = scopeOptions,
                                selectedValue = selectedScope,
                                palette = dialogPalette,
                                onSelected = { selectedScope = it },
                                tone = SegmentedControlTone.AccentThumb,
                                fontSize = 11.sp
                            )
                            LegadoSegmentedControl(
                                options = scopeMaterialOptions,
                                selectedValue = currentScopeOverride.materialMode.orEmpty(),
                                palette = dialogPalette,
                                onSelected = { mode ->
                                    scopeMap = scopeMap.toMutableMap().apply {
                                        put(selectedScope, currentScopeOverride.copy(materialMode = mode.ifBlank { null }))
                                    }
                                },
                                tone = SegmentedControlTone.SurfaceThumb,
                                fontSize = 11.sp
                            )
                            val scopeCornerStep = ((currentScopeOverride.cornerScale ?: (cornerStep / 10f)) * 10f)
                                .toInt().coerceIn(0, 30)
                            EditorSliderRow(
                                title = "该区域圆角倍率",
                                valueText = currentScopeOverride.cornerScale?.let {
                                    String.format(Locale.US, "%.1fx", it)
                                } ?: "继承全局",
                                value = scopeCornerStep,
                                range = 0..30,
                                palette = mgmtPalette,
                                onValueChange = { step ->
                                    scopeMap = scopeMap.toMutableMap().apply {
                                        put(selectedScope, currentScopeOverride.copy(cornerScale = step / 10f))
                                    }
                                }
                            )
                            val scopeAlpha = (currentScopeOverride.alpha ?: surfaceAlpha).coerceIn(0, 100)
                            EditorSliderRow(
                                title = "该区域不透明度",
                                valueText = currentScopeOverride.alpha?.let { "$it%" } ?: "继承全局",
                                value = scopeAlpha,
                                range = 0..100,
                                palette = mgmtPalette,
                                onValueChange = { a ->
                                    scopeMap = scopeMap.toMutableMap().apply {
                                        put(selectedScope, currentScopeOverride.copy(alpha = a))
                                    }
                                }
                            )
                            val scopeShadow = (currentScopeOverride.shadowElevation ?: cardShadow).coerceIn(0, 24)
                            EditorSliderRow(
                                title = "该区域阴影高度",
                                valueText = currentScopeOverride.shadowElevation?.let { "${it}dp" } ?: "继承全局",
                                value = scopeShadow,
                                range = 0..24,
                                palette = mgmtPalette,
                                onValueChange = { sh ->
                                    scopeMap = scopeMap.toMutableMap().apply {
                                        put(selectedScope, currentScopeOverride.copy(shadowElevation = sh))
                                    }
                                }
                            )
                            if (!currentScopeOverride.isEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    LegadoMiuixActionButton(
                                        text = "恢复该区域继承全局",
                                        palette = dialogPalette,
                                        minHeight = 32.dp,
                                        onClick = {
                                            scopeMap = scopeMap.toMutableMap().apply {
                                                put(selectedScope, SurfaceScopeOverride())
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            actions = {
                LegadoMiuixActionButton(
                    text = "取消",
                    palette = dialogPalette,
                    onClick = onDismiss
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = "保存参数包",
                    palette = dialogPalette,
                    primary = true,
                    enabled = canSave,
                    onClick = {
                        if (!canSave) return@LegadoMiuixActionButton
                        val updatedGlobal = g.copy(
                            materialMode = materialMode,
                            frostedLevel = frostedLevel,
                            liquidLevel = liquidLevel,
                            blurScale = blurScale,
                            tintScale = tintScale,
                            shellScale = shellScale,
                            strokeScale = strokeScale,
                            strokeColor = strokeColor.trim().ifBlank { null },
                            cornerBaseScale = cornerStep / 10f,
                            surfaceAlpha = surfaceAlpha,
                            dialogAlpha = dialogAlpha,
                            panelBorderAlpha = borderAlpha,
                            cardShadow = cardShadow,
                            wallpaperBlurDay = wallpaperBlur,
                            wallpaperBlurNight = wallpaperBlur,
                            uiCornerSearchFollow = searchCornerFollow,
                            uiCornerReplyFollow = replyCornerFollow
                        )
                        val updatedScopes = SurfaceScopesParams(
                            topBar = scopeMap[UiScope.TOP_BAR]?.takeUnless { it.isEmpty() },
                            bottomBar = scopeMap[UiScope.BOTTOM_BAR]?.takeUnless { it.isEmpty() },
                            bookshelfCard = scopeMap[UiScope.BOOKSHELF_CARD]?.takeUnless { it.isEmpty() },
                            dialog = scopeMap[UiScope.DIALOG]?.takeUnless { it.isEmpty() }
                        )
                        val updatedLayout = l.copy(
                            densityScale = densityStep / 10f
                        )
                        onSave(
                            trimmedName,
                            initialParam.copy(
                                surfaceGlobal = updatedGlobal,
                                surfaceScopes = updatedScopes,
                                layout = updatedLayout
                            )
                        )
                    }
                )
            }
        )
    }
}

@Composable
private fun ColorFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    val parsedColor = remember(value) {
        runCatching {
            if (value.isNotBlank()) Color(value.trim().toColorInt()) else null
        }.getOrNull()
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(parsedColor ?: Color.Transparent)
                .border(1.dp, Color.Gray.copy(alpha = 0.5f), CircleShape)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, fontSize = 12.sp) },
            placeholder = { Text("#RRGGBB 或留空自动派生", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AssetPathFieldRow(
    label: String,
    value: String,
    onPickFile: ((currentPath: String?, onPicked: (String) -> Unit) -> Unit)?,
    onValueChange: (String) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, fontSize = 12.sp) },
            placeholder = { Text("本地路径或留空", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        if (onPickFile != null) {
            LegadoMiuixActionButton(
                text = "选文件",
                palette = palette,
                minHeight = 36.dp,
                onClick = { onPickFile(value) { picked -> onValueChange(picked) } }
            )
        }
        if (value.isNotBlank()) {
            LegadoMiuixActionButton(
                text = "清空",
                palette = palette,
                minHeight = 36.dp,
                onClick = { onValueChange("") }
            )
        }
    }
}

@Composable
private fun EditorSliderRow(
    title: String,
    valueText: String,
    value: Int,
    range: IntRange,
    palette: AppManagementPalette,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = palette.settings.primaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = valueText,
                color = palette.settings.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        AppThemedStepperSlider(
            value = value.coerceIn(range),
            range = range,
            onValueChange = onValueChange,
            palette = palette.miuix
        )
    }
}
