package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.reader.config.ReadConfigEvent
import io.legado.app.reader.config.ReadConfigEvent.HeaderFooterTips
import io.legado.app.reader.config.ReadConfigEvent.Relayout
import io.legado.app.reader.config.ReadConfigEvent.Typography
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppSlider
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import kotlin.math.roundToInt

private data class PaddingItem(
    val label: String,
    val value: Int,
    val range: IntRange,
    val onValueChange: (Int) -> Unit
)

/**
 * 页边距配置弹窗（V5 基座版）。
 *
 * 尺寸沿用旧值 `widthFraction = 0.91f / maxWidthDp = 400`（V5 基座同名 open val 直接对应，无需换算）；
 * `onStart` 的去遮罩（`clearFlags(FLAG_DIM_BEHIND)` + `dimAmount = 0`）必须保留（要边调边看阅读页实时效果）；
 * `onDismiss` 的 `ReadBookConfig.save()` 落盘不变。
 * 内容：外层旧 LegadoMiuixCard 去掉（避免与 AppDialog 形成双层卡片），内部三段（Header/Body/Footer）
 * 的 section 卡片由 Surface 改为 Box + `visual.shape(ShapeKind.BAR)` + `colors.surfaceVariant`；
 * **自定义 Slider thumb 开关降级为 M3 Switch**（原实现是手绘 CircleShape + animateDpAsState 的 48×28 拇指，
 * V5 无裸 AppSwitch 组件，改用标准 Switch 语义一致、尺寸略小）；
 * `AppThemedStepperSlider` → `AppSlider(valueRange, steps = range.count()-1)`；
 * 「值变动立即写 ReadBookConfig 并 postEvent 触发重排 / 头尾提示刷新」的实时预览链路不变。
 */
internal class PaddingConfigDialog : V5DialogFragment() {

    override val widthFraction: Float = 0.91f
    override val maxWidthDp: Int? = 400

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attr = window.attributes
            attr.dimAmount = 0f
            window.attributes = attr
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
    }

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                HeaderSection()
                BodySection()
                FooterSection()
            }
        }
    }

    @Composable
    private fun HeaderSection() {
        var showLine by rememberSaveable { mutableStateOf(ReadBookConfig.showHeaderLine) }
        var top by rememberSaveable { mutableIntStateOf(ReadBookConfig.headerPaddingTop) }
        var bottom by rememberSaveable { mutableIntStateOf(ReadBookConfig.headerPaddingBottom) }
        var left by rememberSaveable { mutableIntStateOf(ReadBookConfig.headerPaddingLeft) }
        var right by rememberSaveable { mutableIntStateOf(ReadBookConfig.headerPaddingRight) }

        PaddingSection(
            title = stringResource(R.string.header),
            showLine = showLine,
            onShowLineChange = {
                showLine = it
                ReadBookConfig.showHeaderLine = it
                postHeaderFooterChanged()
            }
        ) {
            PaddingSliderRows(
                top = top,
                bottom = bottom,
                left = left,
                right = right,
                topRange = 0..150,
                bottomRange = 0..150,
                sideRange = 0..100,
                onTopChange = {
                    top = it
                    ReadBookConfig.headerPaddingTop = it
                    postHeaderFooterChanged()
                },
                onBottomChange = {
                    bottom = it
                    ReadBookConfig.headerPaddingBottom = it
                    postHeaderFooterChanged()
                },
                onLeftChange = {
                    left = it
                    ReadBookConfig.headerPaddingLeft = it
                    postHeaderFooterChanged()
                },
                onRightChange = {
                    right = it
                    ReadBookConfig.headerPaddingRight = it
                    postHeaderFooterChanged()
                }
            )
        }
    }

    @Composable
    private fun BodySection() {
        var top by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingTop) }
        var bottom by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingBottom) }
        var left by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingLeft) }
        var right by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingRight) }

        PaddingSection(
            title = stringResource(R.string.main_body)
        ) {
            PaddingSliderRows(
                top = top,
                bottom = bottom,
                left = left,
                right = right,
                topRange = 0..200,
                bottomRange = 0..100,
                sideRange = 0..100,
                onTopChange = {
                    top = it
                    ReadBookConfig.paddingTop = it
                    postBodyChanged()
                },
                onBottomChange = {
                    bottom = it
                    ReadBookConfig.paddingBottom = it
                    postBodyChanged()
                },
                onLeftChange = {
                    left = it
                    ReadBookConfig.paddingLeft = it
                    postBodyChanged()
                },
                onRightChange = {
                    right = it
                    ReadBookConfig.paddingRight = it
                    postBodyChanged()
                }
            )
        }
    }

    @Composable
    private fun FooterSection() {
        var showLine by rememberSaveable { mutableStateOf(ReadBookConfig.showFooterLine) }
        var top by rememberSaveable { mutableIntStateOf(ReadBookConfig.footerPaddingTop) }
        var bottom by rememberSaveable { mutableIntStateOf(ReadBookConfig.footerPaddingBottom) }
        var left by rememberSaveable { mutableIntStateOf(ReadBookConfig.footerPaddingLeft) }
        var right by rememberSaveable { mutableIntStateOf(ReadBookConfig.footerPaddingRight) }

        PaddingSection(
            title = stringResource(R.string.footer),
            showLine = showLine,
            onShowLineChange = {
                showLine = it
                ReadBookConfig.showFooterLine = it
                postHeaderFooterChanged()
            }
        ) {
            PaddingSliderRows(
                top = top,
                bottom = bottom,
                left = left,
                right = right,
                topRange = 0..150,
                bottomRange = 0..150,
                sideRange = 0..100,
                onTopChange = {
                    top = it
                    ReadBookConfig.footerPaddingTop = it
                    postHeaderFooterChanged()
                },
                onBottomChange = {
                    bottom = it
                    ReadBookConfig.footerPaddingBottom = it
                    postHeaderFooterChanged()
                },
                onLeftChange = {
                    left = it
                    ReadBookConfig.footerPaddingLeft = it
                    postHeaderFooterChanged()
                },
                onRightChange = {
                    right = it
                    ReadBookConfig.footerPaddingRight = it
                    postHeaderFooterChanged()
                }
            )
        }
    }

    @Composable
    private fun PaddingSection(
        title: String,
        showLine: Boolean? = null,
        onShowLineChange: ((Boolean) -> Unit)? = null,
        content: @Composable () -> Unit
    ) {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(visual.shape(ShapeKind.BAR))
                .background(colors.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = colors.primary,
                    style = visual.type.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (showLine != null && onShowLineChange != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.showLine),
                        modifier = Modifier.widthIn(max = 72.dp),
                        color = colors.onSurfaceVariant,
                        style = visual.type.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = showLine,
                        onCheckedChange = onShowLineChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.onPrimary,
                            checkedTrackColor = colors.primary,
                            uncheckedThumbColor = colors.outline,
                            uncheckedTrackColor = colors.surface
                        )
                    )
                }
            }
            content()
        }
    }

    @Composable
    private fun PaddingSliderRows(
        top: Int,
        bottom: Int,
        left: Int,
        right: Int,
        topRange: IntRange,
        bottomRange: IntRange,
        sideRange: IntRange,
        onTopChange: (Int) -> Unit,
        onBottomChange: (Int) -> Unit,
        onLeftChange: (Int) -> Unit,
        onRightChange: (Int) -> Unit
    ) {
        val items = listOf(
            PaddingItem(stringResource(R.string.top), top, topRange) { if (it != top) onTopChange(it) },
            PaddingItem(stringResource(R.string.bottom), bottom, bottomRange) { if (it != bottom) onBottomChange(it) },
            PaddingItem(stringResource(R.string.left), left, sideRange) { if (it != left) onLeftChange(it) },
            PaddingItem(stringResource(R.string.right), right, sideRange) { if (it != right) onRightChange(it) }
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            items.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    rowItems.forEach { item ->
                        PaddingSliderTile(
                            item = item,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PaddingSliderTile(
        item: PaddingItem,
        modifier: Modifier = Modifier
    ) {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        Column(
            modifier = modifier
                .heightIn(min = 58.dp)
                .clip(visual.shape(ShapeKind.BAR))
                .background(colors.surface)
                .padding(horizontal = 7.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.label,
                    modifier = Modifier.weight(1f),
                    color = colors.onSurface,
                    style = visual.type.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.value.toString(),
                    color = colors.primary,
                    style = visual.type.caption,
                    maxLines = 1
                )
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                AppSlider(
                    value = item.value.toFloat(),
                    onValueChange = { item.onValueChange(it.roundToInt()) },
                    valueRange = item.range.first.toFloat()..item.range.last.toFloat(),
                    steps = item.range.count() - 1,
                )
            }
        }
    }

    private fun postBodyChanged() {
        ReadConfigEvent.post(Typography, Relayout)
    }

    private fun postHeaderFooterChanged() {
        ReadConfigEvent.post(HeaderFooterTips)
    }
}
