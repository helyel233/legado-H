package io.legado.app.ui.config

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.help.config.pkg.WallpaperScaleMode
import io.legado.app.help.config.pkg.WallpaperTransform
import io.legado.app.help.config.pkg.applyWallpaperPan
import io.legado.app.help.config.pkg.buildWallpaperMatrix
import io.legado.app.help.config.pkg.computeWallpaperPanBounds
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
    val previewCornerDp: Int,
    val isWideBar: Boolean
) {
    MAIN(WallpaperTransform.SLOT_MAIN, "主界面壁纸", 12, false),
    TOP_BAR(WallpaperTransform.SLOT_TOP_BAR, "顶栏壁纸", 10, true),
    BOTTOM_BAR(WallpaperTransform.SLOT_BOTTOM_BAR, "底栏壁纸", 10, true),
    BOOK_INFO(WallpaperTransform.SLOT_BOOK_INFO, "书籍详情背景图", 12, false),
    PANEL(WallpaperTransform.SLOT_PANEL, "弹窗面板背景图", 12, false),
    WELCOME(WallpaperTransform.SLOT_WELCOME, "启动页背景图 (Welcome)", 12, false)
}

@Composable
private fun rememberSlotAspectRatio(slotMeta: ArtWallpaperSlotMeta): Float {
    val cfg = LocalConfiguration.current
    val screenW = cfg.screenWidthDp.toFloat().coerceAtLeast(320f)
    val screenH = cfg.screenHeightDp.toFloat().coerceAtLeast(480f)
    return when (slotMeta) {
        ArtWallpaperSlotMeta.MAIN,
        ArtWallpaperSlotMeta.BOOK_INFO,
        ArtWallpaperSlotMeta.WELCOME -> (screenW / screenH).coerceIn(0.42f, 0.75f)
        ArtWallpaperSlotMeta.TOP_BAR,
        ArtWallpaperSlotMeta.BOTTOM_BAR -> (screenW / 68f).coerceIn(3.6f, 5.8f)
        ArtWallpaperSlotMeta.PANEL -> 4f / 3f
    }
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
    val slotRatio = rememberSlotAspectRatio(slotMeta)
    val previewFile = remember(value, packageDir?.absolutePath, baseDir?.absolutePath) {
        resolveWallpaperPreviewFile(value, packageDir, baseDir)
    }
    val emptyHint = remember(value) {
        if (value.trim().startsWith("http", ignoreCase = true)) {
            "网络图"
        } else {
            "无图"
        }
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
            if (slotMeta.isWideBar) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(slotRatio)
                        .heightIn(min = 50.dp)
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
                        modifier = Modifier.matchParentSize()
                    )
                    Surface(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(5.dp)
                    ) {
                        Text(
                            text = "点击预览 / 调整",
                            color = Color.White,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(style.secondaryText.copy(alpha = 0.06f))
                        .clickable(onClick = onOpenPreview)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val thumbModifier = if (slotMeta == ArtWallpaperSlotMeta.PANEL) {
                        Modifier
                            .width(92.dp)
                            .aspectRatio(slotRatio)
                    } else {
                        Modifier
                            .height(96.dp)
                            .aspectRatio(slotRatio)
                    }
                    ArtWallpaperPreviewSurface(
                        file = previewFile,
                        fallbackColor = fallbackColor,
                        transform = transform,
                        alpha = alpha,
                        emptyText = emptyHint,
                        modifier = thumbModifier
                            .clip(RoundedCornerShape(slotMeta.previewCornerDp.dp))
                            .border(
                                width = 1.dp,
                                color = style.secondaryText.copy(alpha = 0.28f),
                                shape = RoundedCornerShape(slotMeta.previewCornerDp.dp)
                            )
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = transform.mode.label,
                            color = style.primaryText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = transform.mode.description,
                            color = style.secondaryText,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "点击预览区可打开原图取景与位置调整",
                            color = style.accent,
                            fontSize = 11.sp
                        )
                    }
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
    val density = LocalDensity.current
    val slotRatio = rememberSlotAspectRatio(slotMeta)
    var draft by remember(slotMeta, rawPath) {
        mutableStateOf(initialTransform.normalized())
    }
    val previewFile = remember(rawPath, packageDir?.absolutePath, baseDir?.absolutePath) {
        resolveWallpaperPreviewFile(rawPath, packageDir, baseDir)
    }
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = previewFile?.absolutePath,
        key2 = previewFile?.length(),
        key3 = previewFile?.lastModified()
    ) {
        value = if (previewFile != null && previewFile.isFile && previewFile.canRead()) {
            decodeWallpaperPreviewBitmap(previewFile)
        } else {
            null
        }
    }
    val tileShader = remember(bitmap, draft.mode) {
        val bmp = bitmap
        if (draft.mode == WallpaperScaleMode.TILE && bmp != null && !bmp.isRecycled) {
            BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        } else {
            null
        }
    }
    val dimPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val brightPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val ghostMatrix = remember { Matrix() }
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
        ).normalized()
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
                        var activePanBounds by remember {
                            mutableStateOf<io.legado.app.help.config.pkg.WallpaperPanBounds?>(null)
                        }
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(256.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF141418))
                        ) {
                            val stageW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                            val stageH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                            val padH = with(density) { 20.dp.toPx() }
                            val padV = with(density) { 16.dp.toPx() }
                            val availW = (stageW - padH * 2f).coerceAtLeast(1f)
                            val availH = (stageH - padV * 2f).coerceAtLeast(1f)
                            val frameW: Float
                            val frameH: Float
                            if (availW / availH > slotRatio) {
                                frameH = availH
                                frameW = frameH * slotRatio
                            } else {
                                frameW = availW
                                frameH = frameW / slotRatio
                            }
                            val frameLeft = (stageW - frameW) / 2f
                            val frameTop = (stageH - frameH) / 2f
                            val frameRect = remember(frameLeft, frameTop, frameW, frameH) {
                                RectF(frameLeft, frameTop, frameLeft + frameW, frameTop + frameH)
                            }
                            val cornerPx = with(density) { slotMeta.previewCornerDp.dp.toPx() }
                            val latestDraft by rememberUpdatedState(draft)
                            val latestBitmap by rememberUpdatedState(bitmap)
                            val latestFrame by rememberUpdatedState(frameRect)
                            val currentBounds = bitmap?.takeUnless { it.isRecycled }?.let { bmp ->
                                computeWallpaperPanBounds(
                                    srcWidth = bmp.width.toFloat(),
                                    srcHeight = bmp.height.toFloat(),
                                    dstWidth = frameRect.width(),
                                    dstHeight = frameRect.height(),
                                    transform = draft
                                )
                            }
                            activePanBounds = currentBounds

                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(slotMeta, rawPath) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            val cur = latestDraft
                                            val zoomed = cur.copy(
                                                scale = (cur.scale * zoom).coerceIn(
                                                    cur.mode.minScale,
                                                    cur.mode.maxScale
                                                )
                                            ).normalized()
                                            val bmp = latestBitmap?.takeUnless { it.isRecycled }
                                            val rect = latestFrame
                                            draft = if (bmp != null && rect.width() > 0f && rect.height() > 0f) {
                                                applyWallpaperPan(
                                                    transform = zoomed,
                                                    panX = pan.x,
                                                    panY = pan.y,
                                                    srcWidth = bmp.width.toFloat(),
                                                    srcHeight = bmp.height.toFloat(),
                                                    dstWidth = rect.width(),
                                                    dstHeight = rect.height()
                                                )
                                            } else {
                                                zoomed
                                            }
                                        }
                                    }
                            ) {
                                val bmp = bitmap?.takeUnless { it.isRecycled }
                                val alphaInt = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
                                if (bmp != null && draft.mode != WallpaperScaleMode.TILE) {
                                    dimPaint.alpha = (90 * alpha.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
                                    buildWallpaperMatrix(
                                        srcWidth = bmp.width.toFloat(),
                                        srcHeight = bmp.height.toFloat(),
                                        dstWidth = frameRect.width(),
                                        dstHeight = frameRect.height(),
                                        transform = draft,
                                        dstLeft = frameRect.left,
                                        dstTop = frameRect.top,
                                        outMatrix = ghostMatrix
                                    )
                                    drawIntoCanvas { canvas ->
                                        canvas.nativeCanvas.drawBitmap(bmp, ghostMatrix, dimPaint)
                                    }
                                }
                                val frameComposeRect = Rect(
                                    left = frameRect.left,
                                    top = frameRect.top,
                                    right = frameRect.right,
                                    bottom = frameRect.bottom
                                )
                                val framePath = Path().apply {
                                    addRoundRect(
                                        RoundRect(
                                            rect = frameComposeRect,
                                            cornerRadius = CornerRadius(cornerPx, cornerPx)
                                        )
                                    )
                                }
                                clipPath(framePath) {
                                    drawRect(
                                        color = fallbackColor,
                                        topLeft = Offset(frameRect.left, frameRect.top),
                                        size = Size(frameRect.width(), frameRect.height())
                                    )
                                    if (bmp != null) {
                                        brightPaint.alpha = alphaInt
                                        drawIntoCanvas { canvas ->
                                            drawTransformedBitmap(
                                                canvas = canvas.nativeCanvas,
                                                bitmap = bmp,
                                                bounds = frameRect,
                                                transform = draft,
                                                paint = brightPaint,
                                                tileShader = tileShader
                                            )
                                        }
                                    }
                                    if (draft.mode != WallpaperScaleMode.TILE) {
                                        val gridColor = Color.White.copy(alpha = 0.22f)
                                        val gridStroke = 0.75.dp.toPx()
                                        val thirdW = frameRect.width() / 3f
                                        val thirdH = frameRect.height() / 3f
                                        for (i in 1..2) {
                                            drawLine(
                                                color = gridColor,
                                                start = Offset(frameRect.left + thirdW * i, frameRect.top),
                                                end = Offset(frameRect.left + thirdW * i, frameRect.bottom),
                                                strokeWidth = gridStroke
                                            )
                                            drawLine(
                                                color = gridColor,
                                                start = Offset(frameRect.left, frameRect.top + thirdH * i),
                                                end = Offset(frameRect.right, frameRect.top + thirdH * i),
                                                strokeWidth = gridStroke
                                            )
                                        }
                                    }
                                }
                                drawPath(
                                    path = framePath,
                                    color = style.accent.copy(alpha = 0.9f),
                                    style = Stroke(width = 1.5.dp.toPx())
                                )
                                val bracketLen = minOf(10.dp.toPx(), frameRect.width() / 4f, frameRect.height() / 4f)
                                val bracketStroke = 2.dp.toPx()
                                val bracketColor = Color.White.copy(alpha = 0.92f)
                                // Top-left
                                drawLine(bracketColor, Offset(frameRect.left, frameRect.top), Offset(frameRect.left + bracketLen, frameRect.top), bracketStroke)
                                drawLine(bracketColor, Offset(frameRect.left, frameRect.top), Offset(frameRect.left, frameRect.top + bracketLen), bracketStroke)
                                // Top-right
                                drawLine(bracketColor, Offset(frameRect.right - bracketLen, frameRect.top), Offset(frameRect.right, frameRect.top), bracketStroke)
                                drawLine(bracketColor, Offset(frameRect.right, frameRect.top), Offset(frameRect.right, frameRect.top + bracketLen), bracketStroke)
                                // Bottom-left
                                drawLine(bracketColor, Offset(frameRect.left, frameRect.bottom), Offset(frameRect.left + bracketLen, frameRect.bottom), bracketStroke)
                                drawLine(bracketColor, Offset(frameRect.left, frameRect.bottom - bracketLen), Offset(frameRect.left, frameRect.bottom), bracketStroke)
                                // Bottom-right
                                drawLine(bracketColor, Offset(frameRect.right - bracketLen, frameRect.bottom), Offset(frameRect.right, frameRect.bottom), bracketStroke)
                                drawLine(bracketColor, Offset(frameRect.right, frameRect.bottom - bracketLen), Offset(frameRect.right, frameRect.bottom), bracketStroke)
                            }

                            if (bitmap == null) {
                                Text(
                                    text = if (rawPath.trim().startsWith("http", ignoreCase = true)) {
                                        "网络图片暂无法本地预览，仍可调整模式与参数"
                                    } else {
                                        "未找到本地图片文件"
                                    },
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }

                        Text(
                            text = "高亮框为实际显示区域，外部暗区为原图范围 · 拖动取景 / 双指缩放",
                            color = style.secondaryText,
                            fontSize = 11.sp
                        )

                        LegadoSegmentedControl(
                            options = modeOptions,
                            selectedValue = draft.mode,
                            palette = dialogPalette,
                            onSelected = { mode ->
                                if (mode != draft.mode) {
                                    draft = WallpaperTransform(mode = mode)
                                }
                            },
                            tone = SegmentedControlTone.AccentThumb,
                            fontSize = 11.sp
                        )

                        Text(
                            text = draft.mode.description,
                            color = style.secondaryText,
                            fontSize = 11.sp
                        )

                        val minScalePct = (draft.mode.minScale * 100f).roundToInt()
                        val maxScalePct = (draft.mode.maxScale * 100f).roundToInt()
                        val scalePct = (draft.scale * 100f).roundToInt().coerceIn(minScalePct, maxScalePct)
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
                                range = minScalePct..maxScalePct,
                                step = 5,
                                palette = mgmtPalette.miuix,
                                onValueChange = { pct ->
                                    draft = draft.copy(scale = pct / 100f).normalized()
                                }
                            )
                        }

                        val canPanX = activePanBounds?.let { it.isTile || it.maxShiftX > 0.5f } ?: true
                        val canPanY = activePanBounds?.let { it.isTile || it.maxShiftY > 0.5f } ?: true
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
                            if (!canPanX && !canPanY) {
                                Text(
                                    text = "当前画面已贴合该方向边界，放大后可拖动",
                                    color = style.secondaryText,
                                    fontSize = 11.sp
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val btnPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                                LegadoMiuixActionButton(
                                    text = "← 左",
                                    palette = dialogPalette,
                                    enabled = canPanX,
                                    minWidth = 44.dp,
                                    minHeight = 34.dp,
                                    insidePadding = btnPadding,
                                    modifier = Modifier.weight(1f),
                                    onClick = { nudge(-0.10f, 0f) }
                                )
                                LegadoMiuixActionButton(
                                    text = "→ 右",
                                    palette = dialogPalette,
                                    enabled = canPanX,
                                    minWidth = 44.dp,
                                    minHeight = 34.dp,
                                    insidePadding = btnPadding,
                                    modifier = Modifier.weight(1f),
                                    onClick = { nudge(0.10f, 0f) }
                                )
                                LegadoMiuixActionButton(
                                    text = "↑ 上",
                                    palette = dialogPalette,
                                    enabled = canPanY,
                                    minWidth = 44.dp,
                                    minHeight = 34.dp,
                                    insidePadding = btnPadding,
                                    modifier = Modifier.weight(1f),
                                    onClick = { nudge(0f, -0.10f) }
                                )
                                LegadoMiuixActionButton(
                                    text = "↓ 下",
                                    palette = dialogPalette,
                                    enabled = canPanY,
                                    minWidth = 44.dp,
                                    minHeight = 34.dp,
                                    insidePadding = btnPadding,
                                    modifier = Modifier.weight(1f),
                                    onClick = { nudge(0f, 0.10f) }
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
                                    text = "左右取景 (X)",
                                    color = style.secondaryText,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = if (!canPanX) "已贴合" else if (xPct >= 0) "+${xPct}%" else "${xPct}%",
                                    color = style.accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            AppThemedStepperSlider(
                                value = if (canPanX) xPct else 0,
                                range = -100..100,
                                step = 5,
                                enabled = canPanX,
                                palette = mgmtPalette.miuix,
                                onValueChange = { v -> draft = draft.copy(offsetX = v / 100f).normalized() }
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "上下取景 (Y)",
                                    color = style.secondaryText,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = if (!canPanY) "已贴合" else if (yPct >= 0) "+${yPct}%" else "${yPct}%",
                                    color = style.accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            AppThemedStepperSlider(
                                value = if (canPanY) yPct else 0,
                                range = -100..100,
                                step = 5,
                                enabled = canPanY,
                                palette = mgmtPalette.miuix,
                                onValueChange = { v -> draft = draft.copy(offsetY = v / 100f).normalized() }
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
