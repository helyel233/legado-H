package io.legado.app.ui.widget.compose

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.rememberThemeUiPalette
import io.legado.app.lib.theme.themeDividerColorOrDefault
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.ColorUtils
import kotlin.math.roundToInt

@Composable
internal fun DismissWhenCallbackMissing(
    missing: Boolean,
    dismiss: () -> Unit
) {
    LaunchedEffect(missing) {
        if (missing) {
            dismiss()
        }
    }
}

/**
 * 旧 V5DialogColors 的 V5 换源版：字段名保持不变（surface/fieldSurface/primaryText/...
 * /panelRadius/actionRadius/bodyFontFamily/titleFontFamily），取值全部改自 V5 运行时
 * （LocalSemanticColors / LocalVisualSpec / LayoutResolver 形状 / ThemeResolver 字体），
 * 使剩余参数串联型消费者零改动吃到 V5 主题源。字段清零后随旧库整体删除。
 */
@Stable
data class V5DialogColors(
    val accent: Color,
    val onAccent: Color,
    val surface: Color,
    val fieldSurface: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val stroke: Color,
    val danger: Color,
    val panelRadius: androidx.compose.ui.unit.Dp,
    val actionRadius: androidx.compose.ui.unit.Dp,
    val bodyFontFamily: FontFamily,
    val titleFontFamily: FontFamily
)

@Composable
fun rememberV5DialogColors(): V5DialogColors {
    val context = LocalContext.current
    val colors = LocalSemanticColors.current
    val v5Spec = io.legado.app.uikit.v5.resolve.LayoutResolver.current
    val v5Scale = v5Spec.shapeScale.coerceIn(io.legado.app.uikit.v5.token.SHAPE_SCALE_RANGE)
    val panelRadius = when (val c = v5Spec.shapeFamily.card) {
        is io.legado.app.uikit.v5.token.CornerSpec.Radius -> c.dp * v5Scale
        else -> context.composePanelRadius()
    }
    val actionRadius = when (val c = v5Spec.shapeFamily.button) {
        is io.legado.app.uikit.v5.token.CornerSpec.Radius -> c.dp * v5Scale
        is io.legado.app.uikit.v5.token.CornerSpec.Pill -> 100.dp
        else -> context.composeActionRadius()
    }
    return remember(
        colors, v5Spec, panelRadius, actionRadius
    ) {
        V5DialogColors(
            accent = colors.primary,
            onAccent = colors.onPrimary,
            surface = colors.surface,
            fieldSurface = colors.surfaceVariant,
            primaryText = colors.onSurface,
            secondaryText = colors.onSurfaceVariant,
            stroke = colors.outline,
            danger = colors.error,
            panelRadius = panelRadius,
            actionRadius = actionRadius,
            bodyFontFamily = io.legado.app.uikit.v5.resolve.ThemeResolver.uiFontFamily()
                ?: FontFamily(context.uiTypeface()),
            titleFontFamily = io.legado.app.uikit.v5.resolve.ThemeResolver.titleFontFamily()
                ?: FontFamily(context.titleTypeface())
        )
    }
}

/**
 * 模型选择弹窗（V5 基座版，旧 ComposeDialogFragment 退役批最后一个子类）。
 * 尺寸：旧 AppDialogSize.Management → widthFraction 0.96f / maxWidthDp 700。
 * 内容沿用 FetchedModelSelectorContent（旧桥 rememberV5DialogColors 只吃主题键 +
 * V5 形状镜像，不依赖 LegadoComposeTheme，在 V5 壳下渲染正常）。
 * 行为保留：`onAddSingle == null` 时自动 dismiss（DismissWhenCallbackMissing）。
 */
internal class ComposeFetchedModelDialog : V5DialogFragment() {

    override val widthFraction: Float = 0.96f
    override val maxWidthDp: Int = 700

    private var onAddSingle: ((String) -> Unit)? = null
    private var onAddSelected: ((List<String>) -> Unit)? = null
    private var onAddAll: ((List<String>) -> Unit)? = null

    @Composable
    override fun Content() {
        DismissWhenCallbackMissing(
            missing = onAddSingle == null,
            dismiss = ::dismissAllowingStateLoss
        )
        val args = arguments ?: Bundle()
        val modelIds = remember {
            args.getStringArrayList(ARG_MODEL_IDS)?.toList().orEmpty()
        }
        val existingIds = remember {
            args.getStringArrayList(ARG_EXISTING_IDS)?.toSet().orEmpty()
        }
        io.legado.app.ui.host.FetchedModelSelectorContent(
            modelIds = modelIds,
            existingIds = existingIds,
            onAddSingle = { modelId ->
                dismissAllowingStateLoss()
                onAddSingle?.invoke(modelId)
            },
            onAddSelected = { selectedIds ->
                dismissAllowingStateLoss()
                onAddSelected?.invoke(selectedIds)
            },
            onAddAll = { allIds ->
                dismissAllowingStateLoss()
                onAddAll?.invoke(allIds)
            },
            onDismiss = { dismissAllowingStateLoss() }
        )
    }

    companion object {
        fun create(
            modelIds: List<String>,
            existingIds: Set<String>,
            onAddSingle: (String) -> Unit,
            onAddSelected: (List<String>) -> Unit,
            onAddAll: (List<String>) -> Unit
        ): ComposeFetchedModelDialog {
            return ComposeFetchedModelDialog().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_MODEL_IDS, ArrayList(modelIds))
                    putStringArrayList(ARG_EXISTING_IDS, ArrayList(existingIds))
                }
                this.onAddSingle = onAddSingle
                this.onAddSelected = onAddSelected
                this.onAddAll = onAddAll
            }
        }

        private const val ARG_MODEL_IDS = "modelIds"
        private const val ARG_EXISTING_IDS = "existingIds"
    }
}
