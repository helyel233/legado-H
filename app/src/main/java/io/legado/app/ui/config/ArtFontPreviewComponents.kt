package io.legado.app.ui.config

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Immutable
data class ArtTypographyPreviewColors(
    val pageBg: Color? = null,
    val cardBg: Color? = null,
    val primaryColor: Color? = null,
    val accentColor: Color? = null,
    val uiTextColor: Color? = null,
    val titleTextColor: Color? = null,
    val bottomBarBg: Color? = null
)

/**
 * 编辑器内嵌实时字体排版预览卡（使用固定 2 槽位加载，与候选列表滑动 LRU 严格隔离）。
 */
@Composable
fun ArtTypographyPreviewCard(
    uiFontPath: String,
    titleFontPath: String,
    isNightPreview: Boolean,
    modifier: Modifier = Modifier,
    packageDir: File? = null,
    baseDir: File? = null,
    previewColors: ArtTypographyPreviewColors? = null
) {
    val style = rememberAppDialogStyle()
    val uiState by rememberPinnedPreviewTypeface(uiFontPath, packageDir, baseDir)
    val titleState by rememberPinnedPreviewTypeface(titleFontPath, packageDir, baseDir)

    val uiFamily = remember(uiState.typeface) {
        uiState.typeface?.let { FontFamily(it) } ?: FontFamily.Default
    }
    val titleFamily = remember(titleState.typeface) {
        titleState.typeface?.let { FontFamily(it) } ?: FontFamily.Default
    }

    val previewPageBg = previewColors?.pageBg ?: if (isNightPreview) Color(0xFF14171D) else Color(0xFFF5F6FA)
    val previewCardBg = previewColors?.cardBg ?: if (isNightPreview) Color(0xFF1E222B) else Color(0xFFFFFFFF)
    val previewBottomBg = previewColors?.bottomBarBg ?: if (isNightPreview) Color(0xFF191C24) else Color(0xFFECEFF6)
    val previewAccent = previewColors?.accentColor ?: style.accent
    val previewTitleColor = previewColors?.titleTextColor ?: if (isNightPreview) Color(0xFFF1F4F9) else Color(0xFF181B22)
    val previewBodyColor = previewColors?.uiTextColor ?: if (isNightPreview) Color(0xFFC9D0DC) else Color(0xFF333945)
    val previewSubColor = previewBodyColor.copy(alpha = 0.68f)
    val previewStroke = if (isNightPreview) Color(0x28FFFFFF) else Color(0x1E000000)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(style.fieldSurface)
            .border(1.dp, style.stroke, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "实时排版效果对比（${if (isNightPreview) "夜间槽" else "日间槽"}）",
                color = style.primaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (uiState.isLoading || titleState.isLoading) {
                Text(
                    text = "异步解码中…",
                    color = style.accent,
                    fontSize = 11.sp
                )
            }
        }

        // 真实 Family Name + 体积 + 来源/报错徽标
        FontSlotMetaRow(
            slotLabel = "标题",
            state = titleState,
            style = style
        )
        FontSlotMetaRow(
            slotLabel = "正文",
            state = uiState,
            style = style
        )

        // 模拟真实页面微缩画布
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(previewPageBg)
                .border(1.dp, previewStroke, RoundedCornerShape(12.dp))
        ) {
            // 1. 顶栏标题（应用 titleFont）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(previewCardBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "书架 · Legado 2026",
                    color = previewTitleColor,
                    fontFamily = titleFamily,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "共 12 本",
                    color = previewAccent,
                    fontFamily = uiFamily,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }

            HorizontalDivider(color = previewStroke, thickness = 0.5.dp)

            // 2. 书名列表 + 正文段落 + 数字标点（应用 uiFont）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(previewCardBg)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "长安的荔枝",
                            color = previewTitleColor,
                            fontFamily = uiFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "第 128 章 · 98.5% · 2026/11/23",
                            color = previewSubColor,
                            fontFamily = uiFamily,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    // 标准高度胶囊按钮压力测试（检查大行高艺术字是否裁切）
                    Box(
                        modifier = Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(50))
                            .background(previewAccent.copy(alpha = 0.14f))
                            .border(0.8.dp, previewAccent.copy(alpha = 0.4f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "继续阅读 82%",
                            color = previewAccent,
                            fontFamily = uiFamily,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }

                Text(
                    text = "春风吹又生，青山依旧在。数字标点：0123456789 · 《阅读》「测试」— 14.2 MB",
                    color = previewBodyColor,
                    fontFamily = uiFamily,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }

            HorizontalDivider(color = previewStroke, thickness = 0.5.dp)

            // 3. 模拟底栏 Tab 标签压力测试微缩区（固定 40dp 高度）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(previewBottomBg)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val tabs = listOf("书架", "发现", "订阅", "我的")
                tabs.forEachIndexed { index, label ->
                    val active = index == 0
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (active) previewAccent else previewSubColor,
                            fontFamily = uiFamily,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FontSlotMetaRow(
    slotLabel: String,
    state: FontPreviewLoadState,
    style: AppDialogStyle
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "$slotLabel：",
                color = style.secondaryText,
                fontSize = 11.sp
            )
            Text(
                text = state.familyName?.takeIf { it.isNotBlank() } ?: state.displayName,
                color = if (state.isValid) style.primaryText else style.danger,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            state.formattedSize?.let { size ->
                Text(
                    text = size,
                    color = style.secondaryText,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
            FontSourceBadgePill(
                text = state.sourceBadge,
                isError = !state.isValid,
                style = style
            )
        }
        if (!state.isValid && !state.errorReason.isNullOrBlank()) {
            Text(
                text = state.errorReason,
                color = style.danger,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FontSourceBadgePill(
    text: String,
    isError: Boolean,
    style: AppDialogStyle
) {
    val bg = if (isError) style.danger.copy(alpha = 0.14f) else style.accent.copy(alpha = 0.12f)
    val fg = if (isError) style.danger else style.accent
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

private enum class PickerCategory {
    SYSTEM,
    PACKAGE_EMBEDDED,
    FONT_LIBRARY
}

private data class ArtFontPickerItem(
    val id: String,
    val title: String,
    val subtitle: String?,
    val category: PickerCategory,
    val sourceBadge: String,
    val previewPath: String,
    val selectValue: String,
    val sizeBytes: Long? = null,
    val libraryEntry: FontLibraryEntry? = null
)

/**
 * 候选字体选择与异步预览面板：
 * 聚合字体库、包内已嵌入字体、系统内置预设与本地文件导入回调，并支持「嵌入包内 / 仅引用 @font:」切换。
 */
@Composable
fun ArtFontPickerSheet(
    currentFontPath: String,
    title: String = "选择字体",
    packageDir: File? = null,
    baseDir: File? = null,
    packageAssetsFonts: List<String> = emptyList(),
    initialEmbedLibraryFont: Boolean = !currentFontPath.trim().startsWith("@font:", ignoreCase = true),
    onImportLocalFont: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onSelectFont: (selectedPath: String) -> Unit
) {
    val context = LocalContext.current
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val coroutineScope = rememberCoroutineScope()

    var embedLibraryFont by remember(currentFontPath) {
        mutableStateOf(initialEmbedLibraryFont)
    }
    var libraryEntries by remember { mutableStateOf<List<FontLibraryEntry>>(emptyList()) }
    var embeddedAssetPaths by remember { mutableStateOf<List<Pair<String, Long?>>>(emptyList()) }
    var isScanningSources by remember { mutableStateOf(true) }

    LaunchedEffect(packageDir?.absolutePath, baseDir?.absolutePath, packageAssetsFonts) {
        isScanningSources = true
        val scanned = withContext(Dispatchers.IO) {
            val lib = FontProtocolResolver.scanFontLibrary(context)
            val embedded = collectPackageAssetFonts(packageDir, baseDir, packageAssetsFonts)
            lib to embedded
        }
        libraryEntries = scanned.first
        embeddedAssetPaths = scanned.second
        isScanningSources = false
    }

    val items = remember(libraryEntries, embeddedAssetPaths) {
        buildPickerItems(embeddedAssetPaths, libraryEntries)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .widthIn(max = 560.dp)
        ) {
            AppDialogFrame(
                title = title,
                scrollContent = false,
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (onImportLocalFont != null) {
                            LegadoMiuixActionButton(
                                text = "从本地文件导入字体（支持 .ttf / .otf / .ttc）",
                                palette = palette,
                                prominentOutline = true,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onImportLocalFont
                            )
                        }

                        // 「嵌入包内（随包导出）」与「仅引用字体库（@font: 省空间）」开关
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(style.fieldSurface)
                                .border(0.8.dp, style.stroke, RoundedCornerShape(10.dp))
                                .clickable { embedLibraryFont = !embedLibraryFont }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = if (embedLibraryFont) {
                                        "嵌入包内（随包导出）"
                                    } else {
                                        "仅引用字体库（@font: 省空间）"
                                    },
                                    color = style.primaryText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (embedLibraryFont) {
                                        "选择字体库字体时将复制进美术包，跨设备导入不丢字"
                                    } else {
                                        "仅保存 @font: 名称引用，显著减小导出包体积"
                                    },
                                    color = style.secondaryText,
                                    fontSize = 11.sp
                                )
                            }
                            LegadoMiuixSwitch(
                                checked = embedLibraryFont,
                                onCheckedChange = { embedLibraryFont = it },
                                palette = palette
                            )
                        }

                        if (isScanningSources && libraryEntries.isEmpty()) {
                            Text(
                                text = "正在扫描字体库与包内字体…",
                                color = style.secondaryText,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 2.dp)
                        ) {
                            items(
                                items = items,
                                key = { it.id }
                            ) { item ->
                                val isSelected = isItemSelected(currentFontPath, item)
                                ArtFontCandidateRow(
                                    item = item,
                                    isSelected = isSelected,
                                    packageDir = packageDir,
                                    baseDir = baseDir,
                                    style = style,
                                    onClick = {
                                        val lib = item.libraryEntry
                                        if (lib == null) {
                                            onSelectFont(item.selectValue)
                                        } else if (!embedLibraryFont) {
                                            onSelectFont(lib.atFontRef)
                                        } else {
                                            coroutineScope.launch {
                                                val local = withContext(Dispatchers.IO) {
                                                    FontProtocolResolver.resolveEmbeddableLocalPath(context, lib)
                                                }
                                                onSelectFont(local ?: lib.atFontRef)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                },
                actions = {
                    LegadoMiuixActionButton(
                        text = "关闭",
                        palette = palette,
                        onClick = onDismiss
                    )
                }
            )
        }
    }
}

@Composable
private fun ArtFontCandidateRow(
    item: ArtFontPickerItem,
    isSelected: Boolean,
    packageDir: File?,
    baseDir: File?,
    style: AppDialogStyle,
    onClick: () -> Unit
) {
    // 候选列表项采用 produceState + 有界 LRU(maxSize=10) 异步解码，滑出视口即自动取消
    val previewState by rememberCandidatePreviewTypeface(
        path = item.previewPath,
        packageDir = packageDir,
        baseDir = baseDir
    )
    val previewFamily = remember(previewState.typeface) {
        previewState.typeface?.let { FontFamily(it) } ?: FontFamily.Default
    }
    val displayTitle = previewState.familyName
        ?.takeIf { it.isNotBlank() && item.category != PickerCategory.SYSTEM }
        ?: item.title
    val sizeText = previewState.formattedSize
        ?: item.sizeBytes?.let(FontFileValidator::formatFileSize)
    val borderColor = if (isSelected) style.accent else style.stroke
    val bgColor = if (isSelected) style.accent.copy(alpha = 0.08f) else style.fieldSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(if (isSelected) 1.3.dp else 0.8.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = displayTitle,
                color = if (previewState.isValid) style.primaryText else style.danger,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (sizeText != null) {
                Text(
                    text = sizeText,
                    color = style.secondaryText,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
            FontSourceBadgePill(
                text = if (!previewState.isValid) "格式无效" else item.sourceBadge,
                isError = !previewState.isValid,
                style = style
            )
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(style.accent)
                )
            }
        }

        if (!item.subtitle.isNullOrBlank() && item.subtitle != displayTitle) {
            Text(
                text = item.subtitle,
                color = style.secondaryText,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (!previewState.isValid) {
            Text(
                text = previewState.errorReason ?: "字体文件格式无效",
                color = style.danger,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "春风吹又生 · Legado 123",
                    color = style.primaryText,
                    fontFamily = previewFamily,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (previewState.isLoading) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "加载中…",
                        color = style.secondaryText,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

private fun collectPackageAssetFonts(
    packageDir: File?,
    baseDir: File?,
    explicitPaths: List<String>
): List<Pair<String, Long?>> {
    val dedup = linkedMapOf<String, Long?>()
    fun scanAssetsDir(root: File?) {
        val assetsDir = root?.let { File(it, "assets") } ?: return
        if (!assetsDir.isDirectory) return
        assetsDir.listFiles().orEmpty()
            .filter { file ->
                file.isFile && (
                    FontFileValidator.isSupportedExtension(file.name) ||
                        FontFileValidator.isNativeRenderableFile(file)
                    )
            }
            .sortedBy { it.name }
            .forEach { file ->
                val rel = "assets/${file.name}"
                dedup.putIfAbsent(rel, file.length())
            }
    }
    scanAssetsDir(packageDir)
    scanAssetsDir(baseDir)
    explicitPaths.forEach { raw ->
        val trimmed = raw.trim()
        if (trimmed.isNotEmpty() && !dedup.containsKey(trimmed)) {
            val file = when {
                File(trimmed).isAbsolute -> File(trimmed)
                packageDir != null -> File(packageDir, trimmed).takeIf { it.isFile }
                    ?: baseDir?.let { File(it, trimmed).takeIf(File::isFile) }
                else -> null
            }
            dedup[trimmed] = file?.takeIf { it.isFile }?.length()
        }
    }
    return dedup.entries.map { it.key to it.value }
}

private fun buildPickerItems(
    embeddedAssets: List<Pair<String, Long?>>,
    libraryEntries: List<FontLibraryEntry>
): List<ArtFontPickerItem> {
    val result = mutableListOf<ArtFontPickerItem>()
    // 1. 系统内置字体预设
    result += ArtFontPickerItem(
        id = "sys:default",
        title = "默认跟随系统",
        subtitle = "沿用系统默认界面字体设置",
        category = PickerCategory.SYSTEM,
        sourceBadge = "系统预设",
        previewPath = "",
        selectValue = ""
    )
    result += ArtFontPickerItem(
        id = "sys:sans",
        title = "系统无衬线 (Sans)",
        subtitle = "system:sans",
        category = PickerCategory.SYSTEM,
        sourceBadge = "系统预设",
        previewPath = "system:sans",
        selectValue = "system:sans"
    )
    result += ArtFontPickerItem(
        id = "sys:serif",
        title = "系统衬线 (Serif)",
        subtitle = "system:serif",
        category = PickerCategory.SYSTEM,
        sourceBadge = "系统预设",
        previewPath = "system:serif",
        selectValue = "system:serif"
    )
    result += ArtFontPickerItem(
        id = "sys:mono",
        title = "系统等宽 (Mono)",
        subtitle = "system:mono",
        category = PickerCategory.SYSTEM,
        sourceBadge = "系统预设",
        previewPath = "system:mono",
        selectValue = "system:mono"
    )

    // 2. 当前美术包内已嵌入字体
    embeddedAssets.forEach { (relPath, size) ->
        val name = relPath.substringAfterLast('/').substringAfterLast(File.separator)
        result += ArtFontPickerItem(
            id = "pkg:$relPath",
            title = name,
            subtitle = relPath,
            category = PickerCategory.PACKAGE_EMBEDDED,
            sourceBadge = "包内嵌入",
            previewPath = relPath,
            selectValue = relPath,
            sizeBytes = size
        )
    }

    // 3. 字体库（externalFiles/font & PreferKey.fontFolder）
    libraryEntries.forEach { entry ->
        result += ArtFontPickerItem(
            id = "lib:${entry.atFontRef}",
            title = entry.fileName,
            subtitle = "${entry.originLabel} · ${entry.atFontRef}",
            category = PickerCategory.FONT_LIBRARY,
            sourceBadge = "字体库",
            previewPath = entry.localPath ?: entry.contentUri ?: entry.atFontRef,
            selectValue = entry.atFontRef,
            sizeBytes = entry.sizeBytes,
            libraryEntry = entry
        )
    }

    return result
}

private fun isItemSelected(currentFontPath: String, item: ArtFontPickerItem): Boolean {
    val cur = currentFontPath.trim()
    if (cur.isEmpty()) return item.selectValue.isEmpty()
    val normCur = FontProtocolResolver.normalizeSystemProtocol(cur)
    if (normCur != null) {
        return item.selectValue == normCur
    }
    if (item.selectValue == cur) return true
    val lib = item.libraryEntry
    if (lib != null) {
        if (cur.equals(lib.atFontRef, ignoreCase = true)) return true
        if (lib.localPath != null && cur == lib.localPath) return true
        if (lib.contentUri != null && cur == lib.contentUri) return true
    }
    return false
}
