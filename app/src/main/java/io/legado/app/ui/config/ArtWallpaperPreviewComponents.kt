package io.legado.app.ui.config

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.help.config.pkg.WallpaperScaleMode
import io.legado.app.help.config.pkg.WallpaperTransform
import io.legado.app.help.config.pkg.drawTransformedBitmap
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.LegadoSegmentedControl
import io.legado.app.ui.widget.compose.SegmentedControlTone
import io.legado.app.ui.widget.compose.SegmentedOption
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.toMiuixPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

internal enum class ArtWallpaperSlotMeta(
    val slotId: String,
    val label: String,
    val previewAspectRatio: Float,
    val previewMaxHeightDp: Int,
    val previewCornerDp: Int,
    val inlineHeightDp: Int
) {
    MAIN(WallpaperTransform.SLOT_MAIN, "主界面壁纸", 9f / 16f, 220, 12, 84),
    TOP_BAR(WallpaperTransform.SLOT_TOP_BAR, "顶栏壁纸", 4.5f, 84, 10, 52),
    BOTTOM_BAR(WallpaperTransform.SLOT_BOTTOM_BAR, "底栏壁纸", 4.5f, 84, 10, 52),
    BOOK_INFO(WallpaperTransform.SLOT_BOOK_INFO, "书籍详情背景图", 9f / 16f, 220, 12, 84),
    PANEL(WallpaperTransform.SLOT_PANEL, "弹窗面板背景图", 4f / 3f, 170, 12, 76),
    WELCOME(WallpaperTransform.SLOT_WELCOME, "启动页背景图 (Welcome)", 9f / 16f, 220, 12, 84)
}

internal fun resolveWallpaperPreviewFile(
    rawPath: String,
    packageDir: File?,
    baseDir: File?
): File? {
    val trimmed = rawPath.trim().takeIf { it.isNotBlank() } ?: return null
    if (trimmed.startsWith("http", ignoreCase = true)) return null
    val direct = File(trimmed)
    if (direct.isAbsolute) {
        return direct.takeIf { it.isFile && it.canRead() }
    }
    packageDir?.let { File(it, trimmed).takeIf { f -> f.isFile && f.canRead() } }?.let { return it }
    baseDir?.let { File(it, trimmed).takeIf { f -> f.isFile && f.canRead() } }?.let { return it }
    return null
}

private suspend fun decodeWallpaperPreviewBitmap(
    file: File,
    maxSidePx: Int = 1080
): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        val srcW = boundsOpts.outWidth
        val srcH = boundsOpts.outHeight
        if (srcW <= 0 || srcH <= 0) return@runCatching null
        var sample = 1
        while (maxOf(srcW / sample, srcH / sample) > maxSidePx) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
    }.getOrNull()
}

@Composable
internal fun ArtWallpaperPreviewSurface(
    file: File?,
    fallbackColor: Color,
    transform: WallpaperTransform,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    emptyText: String = "无法加载本地预览图"
) {
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = file?.absolutePath,
        key2 = file?.length(),
        key3 = file?.lastModified()
    ) {
        value = if (file != null && file.isFile && file.canRead()) {
            decodeWallpaperPreviewBitmap(file)
        } else {
            null
        }
    }
    val tileShader = remember(bitmap, transform.mode) {
        val bmp = bitmap
        if (transform.mode == WallpaperScaleMode.TILE && bmp != null && !bmp.isRecycled) {
            BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        } else {
            null
        }
    }
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    }

    Box(
        modifier = modifier.background(fallbackColor),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
                drawIntoCanvas { canvas ->
                    drawTransformedBitmap(
                        canvas = canvas.nativeCanvas,
                        bitmap = bmp,
                        bounds = RectF(0f, 0f, size.width, size.height),
                        transform = transform,
                        paint = paint,
                        tileShader = tileShader
                    )
                }
            }
        } else {
            Text(
                text = emptyText,
                color = Color.Gray,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
internal fun ArtWallpaperSlotRow(
    slotMeta: ArtWallpaperSlotMeta,
    value: String,
    transform: WallpaperTransform,
    packageDir: File?,
    baseDir: File?,
    fallbackColor: Color,
    alpha: Float = 1f,
    style: AppDialogStyle,
    palette: LegadoMiuixPalette,
    onPickFile: ((currentPath: String?, onPicked: (String) -> Unit) -> Unit)?,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    onOpenPreview: () -> Unit
) {
    val hasPath = value.isNotBlank()
    val previewFile = remember(value, packageDir?.absolutePath, baseDir?.absolutePath) {
        resolveWallpaperPreviewFile(value, packageDir, baseDir)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = slotMeta.label,
                color = style.primaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            if (hasPath) {
                Text(
                    text = transform.summaryText(),
                    color = style.accent,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (hasPath) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(slotMeta.inlineHeightDp.dp)
                    .clip(RoundedCornerShape(slotMeta.previewCornerDp.dp))
                    .border(
                        width = 1.dp,
                        color = style.secondaryText.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(slotMeta.previewCornerDp.dp)
                    )
                    .clickable(onClick = onOpenPreview)
            ) {
                ArtWallpaperPreviewSurface(
                    file = previewFile,
                    fallbackColor = fallbackColor,
                    transform = transform,
                    alpha = alpha,
                    emptyText = if (value.trim().startsWith("http", ignoreCase = true)) {
                        "网络图片路径（可点击调整显示模式）"
                    } else {
                        "未找到本地图片文件"
                    },
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                ) {
                    Text(
                        text = "点击预览 / 调整",
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("本地路径或留空", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            if (onPickFile != null) {
                LegadoMiuixActionButton(
                    text = "选文件",
                    palette = palette,
                    minWidth = 58.dp,
                    minHeight = 36.dp,
                    insidePadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    onClick = { onPickFile(value, onValueChange) }
                )
            }
            if (hasPath) {
                LegadoMiuixActionButton(
                    text = "调整",
                    palette = palette,
                    prominentOutline = true,
                    minWidth = 52.dp,
                    minHeight = 36.dp,
                    insidePadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    onClick = onOpenPreview
                )
                LegadoMiuixActionButton(
                    text = "清空",
                    palette = palette,
                    minWidth = 52.dp,
                    minHeight = 36.dp,
                    insidePadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    onClick = onClear
                )
            }
        }
    }
}

@Composable
internal fun ArtWallpaperPreviewDialog(
    slotMeta: ArtWallpaperSlotMeta,
    rawPath: String,
    packageDir: File?,
    baseDir: File?,
    fallbackColor: Color,
    alpha: Float = 1f,
    initialTransform: WallpaperTransform,
    onDismiss: () -> Unit,
    onApply: (WallpaperTransform) -> Unit
) {
    val style = rememberAppDialogStyle()
    val dialogPalette = style.toMiuixPalette()
    val mgmtPalette = rememberAppManagementPalette()
    var draft by remember(slotMeta, rawPath) {
        mutableStateOf(initialTransform.normalized())
    }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    val previewFile = remember(rawPath, packageDir?.absolutePath, baseDir?.absolutePath) {
        resolveWallpaperPreviewFile(rawPath, packageDir, baseDir)
    }
    val modeOptions = remember {
        listOf(
            SegmentedOption(WallpaperScaleMode.CROP, WallpaperScaleMode.CROP.label),
            SegmentedOption(WallpaperScaleMode.FIT, WallpaperScaleMode.FIT.label),
            SegmentedOption(WallpaperScaleMode.FILL, WallpaperScaleMode.FILL.label),
            SegmentedOption(WallpaperScaleMode.TILE, WallpaperScaleMode.TILE.label)
        )
    }

    fun nudge(dx: Float, dy: Float) {
        draft = draft.copy(
            offsetX = (draft.offsetX + dx).coerceIn(WallpaperTransform.MIN_OFFSET, WallpaperTransform.MAX_OFFSET),
            offsetY = (draft.offsetY + dy).coerceIn(WallpaperTransform.MIN_OFFSET, WallpaperTransform.MAX_OFFSET)
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 560.dp)
        ) {
            AppDialogFrame(
                title = "预览与调整 · ${slotMeta.label}",
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = slotMeta.previewMaxHeightDp.dp)
                                    .aspectRatio(slotMeta.previewAspectRatio)
                                    .clip(RoundedCornerShape(slotMeta.previewCornerDp.dp))
                                    .border(
                                        BorderStroke(1.dp, style.accent.copy(alpha = 0.45f)),
                                        RoundedCornerShape(slotMeta.previewCornerDp.dp)
                                    )
                                    .onSizeChanged { previewSize = it }
                                    .pointerInput(Unit) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            val w = previewSize.width.toFloat().coerceAtLeast(1f)
                                            val h = previewSize.height.toFloat().coerceAtLeast(1f)
                                            draft = draft.copy(
                                                scale = (draft.scale * zoom).coerceIn(
                                                    WallpaperTransform.MIN_SCALE,
                                                    WallpaperTransform.MAX_SCALE
                                                ),
                                                offsetX = (draft.offsetX + pan.x / w).coerceIn(
                                                    WallpaperTransform.MIN_OFFSET,
                                                    WallpaperTransform.MAX_OFFSET
                                                ),
                                                offsetY = (draft.offsetY + pan.y / h).coerceIn(
                                                    WallpaperTransform.MIN_OFFSET,
                                                    WallpaperTransform.MAX_OFFSET
                                                )
                                            )
                                        }
                                    }
                            ) {
                                ArtWallpaperPreviewSurface(
                                    file = previewFile,
                                    fallbackColor = fallbackColor,
                                    transform = draft,
                                    alpha = alpha,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        Text(
                            text = "手势：拖动可调整上下左右位置，双指捏合可放大缩小",
                            color = style.secondaryText,
                            fontSize = 11.sp
                        )

                        LegadoSegmentedControl(
                            options = modeOptions,
                            selectedValue = draft.mode,
                            palette = dialogPalette,
                            onSelected = { mode -> draft = draft.copy(mode = mode) },
                            tone = SegmentedControlTone.AccentThumb,
                            fontSize = 11.sp
                        )

                        val scalePct = (draft.scale * 100f).roundToInt().coerceIn(25, 400)
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
                                    text = "放大缩小",
                                    color = mgmtPalette.settings.primaryText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${scalePct}%",
                                    color = mgmtPalette.settings.accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            AppThemedStepperSlider(
                                value = scalePct,
                                range = 25..400,
                                step = 5,
                                palette = mgmtPalette.miuix,
                                onValueChange = { pct ->
                                    draft = draft.copy(scale = pct / 100f)
                                }
                            )
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "位置微调（上下左右）",
                                color = mgmtPalette.settings.primaryText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val btnPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                                LegadoMiuixActionButton(
                                    text = "← 左",
                                    palette = dialogPalette,
                                    minWidth = 44.dp,
                                    minHeight = 34.dp,
                                    insidePadding = btnPadding,
                                    modifier = Modifier.weight(1f),
                                    onClick = { nudge(-0.05f, 0f) }
                                )
                                LegadoMiuixActionButton(
                                    text = "→ 右",
                                    palette = dialogPalette,
                                    minWidth = 44.dp,
                                    minHeight = 34.dp,
                                    insidePadding = btnPadding,
                                    modifier = Modifier.weight(1f),
                                    onClick = { nudge(0.05f, 0f) }
                                )
                                LegadoMiuixActionButton(
                                    text = "↑ 上",
                                    palette = dialogPalette,
                                    minWidth = 44.dp,
                                    minHeight = 34.dp,
                                    insidePadding = btnPadding,
                                    modifier = Modifier.weight(1f),
                                    onClick = { nudge(0f, -0.05f) }
                                )
                                LegadoMiuixActionButton(
                                    text = "↓ 下",
                                    palette = dialogPalette,
                                    minWidth = 44.dp,
                                    minHeight = 34.dp,
                                    insidePadding = btnPadding,
                                    modifier = Modifier.weight(1f),
                                    onClick = { nudge(0f, 0.05f) }
                                )
                                LegadoMiuixActionButton(
                                    text = "居中",
                                    palette = dialogPalette,
                                    minWidth = 44.dp,
                                    minHeight = 34.dp,
                                    insidePadding = btnPadding,
                                    modifier = Modifier.weight(1f),
                                    onClick = { draft = draft.copy(offsetX = 0f, offsetY = 0f) }
                                )
                            }

                            val xPct = (draft.offsetX * 100f).roundToInt().coerceIn(-100, 100)
                            val yPct = (draft.offsetY * 100f).roundToInt().coerceIn(-100, 100)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "左右偏移 (X)",
                                    color = style.secondaryText,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = if (xPct >= 0) "+${xPct}%" else "${xPct}%",
                                    color = style.accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            AppThemedStepperSlider(
                                value = xPct,
                                range = -100..100,
                                step = 5,
                                palette = mgmtPalette.miuix,
                                onValueChange = { v -> draft = draft.copy(offsetX = v / 100f) }
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "上下偏移 (Y)",
                                    color = style.secondaryText,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = if (yPct >= 0) "+${yPct}%" else "${yPct}%",
                                    color = style.accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            AppThemedStepperSlider(
                                value = yPct,
                                range = -100..100,
                                step = 5,
                                palette = mgmtPalette.miuix,
                                onValueChange = { v -> draft = draft.copy(offsetY = v / 100f) }
                            )
                        }
                    }
                },
                actions = {
                    LegadoMiuixActionButton(
                        text = "重置",
                        palette = dialogPalette,
                        onClick = { draft = WallpaperTransform.DEFAULT }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    LegadoMiuixActionButton(
                        text = "取消",
                        palette = dialogPalette,
                        onClick = onDismiss
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    LegadoMiuixActionButton(
                        text = "应用",
                        palette = dialogPalette,
                        primary = true,
                        onClick = { onApply(draft.normalized()) }
                    )
                }
            )
        }
    }
}
