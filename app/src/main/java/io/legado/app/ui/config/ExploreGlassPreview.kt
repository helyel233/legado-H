package io.legado.app.ui.config

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.ui.main.explore.ExploreGlassBackdrop
import io.legado.app.ui.main.explore.ROW_LIQUID_ENABLED
import io.legado.app.ui.main.explore.ROW_LIQUID_MIN_SDK
import io.legado.app.ui.main.explore.RowLiquidRuntimeState
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import java.util.Locale

/**
 * 设置页的「实时预览」：在壁纸底上并排放一条书源名行与一颗底栏药丸。
 *
 * 两块玻璃都走生产同一条入口 [ExploreGlassBackdrop.previewBackground]，
 * 因此这里看到的不是另一套模拟：材质、模糊、色散、壳层、描边与发现页 / 底栏
 * 完全同源。拖动本页任何滑杆都会让 [ExploreGlassConfigFragment] 重建 spec，
 * 预览随之重建背景（底图按 key 缓存，只有模糊半径变化时才重做软件模糊）。
 *
 * 预览容器自己把降采样壁纸按**屏幕对齐**画在底层，两块玻璃仍按各自的屏幕位置
 * 采样同一张壁纸，于是「玻璃后面的内容」与「玻璃采样到的内容」是同一块区域，
 * 观感与真实页面一致。
 */
@Composable
internal fun ExploreGlassPreview(
    signature: String,
    mode: String,
    material: GlassMaterialPreset.Params,
    level: Int,
    pipeline: ExploreGlassPreviewPipeline,
    modifier: Modifier = Modifier
) {
    val colors = rememberAppSettingPalette()
    val decimals = remember(material) {
        listOf(
            material.blurDp,
            material.tintAlpha,
            material.dispersion,
            material.shell.start,
            material.shell.center,
            material.shell.end,
            material.shell.stroke
        ).map { String.format(Locale.US, "%.2f", it) }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .appSettingPanelBackground(
                normalColor = colors.row,
                panelImage = null,
                borderColor = colors.border,
                radiusPx = colors.panelRadiusPx
            )
            .padding(12.dp)
    ) {
        Text(
            text = stringResource(R.string.explore_glass_preview_title),
            color = colors.primaryText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        AndroidView(
            factory = { context ->
                GlassPreviewStage(context).apply { applySignature(signature, mode) }
            },
            update = { stage ->
                stage.applySignature(signature, mode)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.explore_glass_preview_metrics,
                level,
                decimals[0], decimals[1], decimals[2],
                decimals[3], decimals[4], decimals[5], decimals[6]
            ),
            color = colors.secondaryText,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )
        Text(
            text = stringResource(
                R.string.explore_glass_preview_pipeline,
                stringResource(pipeline.labelRes)
            ),
            color = colors.secondaryText,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )
    }
}

/** 预览里实际会走哪条管线（与 [ExploreGlassBackdrop] 的降级矩阵逐条同源）。 */
internal enum class ExploreGlassPreviewPipeline(val labelRes: Int) {
    SOLID(R.string.explore_glass_preview_pipeline_solid),
    LIQUID(R.string.explore_glass_preview_pipeline_liquid),
    HARDWARE(R.string.explore_glass_preview_pipeline_hardware),
    SOFTWARE(R.string.explore_glass_preview_pipeline_software),
    DISABLED(R.string.explore_glass_preview_pipeline_disabled)
}

/**
 * 管线判定（纯函数）：顺序与 `ExploreGlassBackdrop.glassDrawable()` 完全一致——
 * 无玻璃档优先（不看设备降级）、墨水屏 / 低内存关闭、液态 → 硬件高斯 → 软件模糊。
 *
 * `hardwareBlurPref` 同时是液态与硬件的门槛：关掉「硬件真高斯」后两条 GPU 管线
 * 都不再启用，行回到已验证的软件模糊。
 */
internal fun exploreGlassPreviewPipeline(
    mode: String,
    sdkInt: Int,
    rowLiquidEnabled: Boolean,
    hardwareBlurPref: Boolean,
    isEInk: Boolean,
    isLowRam: Boolean,
    liquidFailed: Boolean
): ExploreGlassPreviewPipeline = when {
    mode == GlassMaterialPreset.SOLID -> ExploreGlassPreviewPipeline.SOLID
    isEInk || isLowRam -> ExploreGlassPreviewPipeline.DISABLED
    sdkInt >= ROW_LIQUID_MIN_SDK && rowLiquidEnabled && hardwareBlurPref &&
        !liquidFailed -> ExploreGlassPreviewPipeline.LIQUID
    sdkInt >= Build.VERSION_CODES.S && hardwareBlurPref -> ExploreGlassPreviewPipeline.HARDWARE
    else -> ExploreGlassPreviewPipeline.SOFTWARE
}

/** 生产上下文下的管线判定：把设备事实一次性喂给 [exploreGlassPreviewPipeline]。 */
internal fun exploreGlassPreviewPipelineNow(
    context: Context,
    mode: String,
    hardwareBlurPref: Boolean
): ExploreGlassPreviewPipeline = exploreGlassPreviewPipeline(
    mode = mode,
    sdkInt = Build.VERSION.SDK_INT,
    rowLiquidEnabled = ROW_LIQUID_ENABLED,
    hardwareBlurPref = hardwareBlurPref,
    isEInk = AppConfig.isEInkMode,
    isLowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
        ?.isLowRamDevice == true,
    liquidFailed = RowLiquidRuntimeState.shaderFailed
)

/**
 * 预览舞台：壁纸按屏幕对齐铺底，内部放一块「书源名行」与一颗「底栏药丸」。
 *
 * 两块玻璃的背景都由 [ExploreGlassBackdrop.previewBackground] 创建，圆角分别取
 * 面板圆角与高度一半（与真实行 / 底栏 pill 同口径）。Compose 的 LazyColumn 滚动
 * 不会触发子 View 的布局回调，因此每次绘制前比对自身屏幕坐标，位置变化时主动让
 * 玻璃层重采样，避免滑动时玻璃内容与壁纸错位。
 */
private class GlassPreviewStage @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val gutterPx = 16f * density
    private val rowHeightPx = 44f * density
    private val barHeightPx = 52f * density
    private val barMaxWidthPx = 220f * density

    private val rowChip = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        text = context.getString(R.string.explore_glass_preview_stage_row)
    }

    private val barPill = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 1
        text = context.getString(R.string.explore_glass_preview_stage_bar)
    }

    private val wallpaperPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val wallpaperMatrix = Matrix()
    private var wallpaper: Bitmap? = null
    private var signature: String? = null
    private var mode: String? = null
    private var backgroundsPending = true
    private var lastLocationX = Int.MIN_VALUE
    private var lastLocationY = Int.MIN_VALUE
    private val location = IntArray(2)
    private val rootLocation = IntArray(2)

    init {
        setWillNotDraw(false)
        addView(rowChip)
        addView(barPill)
        applyTheme()
        loadWallpaper()
    }

    fun applySignature(nextSignature: String, nextMode: String) {
        if (nextSignature == signature && nextMode == mode) return
        signature = nextSignature
        mode = nextMode
        backgroundsPending = true
        applyTheme()
        refreshBackgrounds()
        loadWallpaper()
        invalidate()
    }

    private fun applyTheme() {
        val textColor = ThemeStore.textColorPrimary(context)
        rowChip.setTextColor(textColor)
        barPill.setTextColor(textColor)
    }

    private fun refreshBackgrounds() {
        rowChip.background = ExploreGlassBackdrop.previewBackground(
            rowChip, resources.getDimension(R.dimen.ui_panel_radius)
        )
        if (barPill.height > 0) {
            barPill.background = ExploreGlassBackdrop.previewBackground(
                barPill, barPill.height / 2f
            )
            backgroundsPending = false
        }
    }

    private fun loadWallpaper() {
        wallpaper = ExploreGlassBackdrop.previewWallpaper(context) {
            wallpaper = it
            invalidate()
        }
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val chipLeft = gutterPx.toInt()
        val chipTop = gutterPx.toInt()
        rowChip.layout(chipLeft, chipTop, width - chipLeft, chipTop + rowHeightPx.toInt())
        val barWidth = minOf(barMaxWidthPx.toInt(), width - chipLeft * 2)
        val barLeft = (width - barWidth) / 2
        val barTop = height - gutterPx.toInt() - barHeightPx.toInt()
        barPill.layout(barLeft, barTop, barLeft + barWidth, barTop + barHeightPx.toInt())
        if (backgroundsPending) {
            refreshBackgrounds()
        }
    }

    override fun onDraw(canvas: Canvas) {
        // 兜底底色：底图未就绪（异步构建 / 动图壁纸纯色底）时也不能是一片透明。
        canvas.drawColor(ThemeStore.backgroundColor(context))
        val bitmap = wallpaper
        if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            val root = rootView
            if (root.width > 0 && root.height > 0) {
                getLocationOnScreen(location)
                root.getLocationOnScreen(rootLocation)
                wallpaperMatrix.setScale(
                    root.width / bitmap.width.toFloat(),
                    root.height / bitmap.height.toFloat()
                )
                wallpaperMatrix.postTranslate(
                    -(location[0] - rootLocation[0]).toFloat(),
                    -(location[1] - rootLocation[1]).toFloat()
                )
                canvas.drawBitmap(bitmap, wallpaperMatrix, wallpaperPaint)
            }
        }
        super.onDraw(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {
        getLocationOnScreen(location)
        if (location[0] != lastLocationX || location[1] != lastLocationY) {
            lastLocationX = location[0]
            lastLocationY = location[1]
            ExploreGlassBackdrop.refreshPreviewPosition(rowChip)
            ExploreGlassBackdrop.refreshPreviewPosition(barPill)
        }
        super.dispatchDraw(canvas)
    }
}
