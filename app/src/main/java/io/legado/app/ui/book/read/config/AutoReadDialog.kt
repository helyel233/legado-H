package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.BaseReadBookActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppSlider
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * 自动翻页面板（V5 基座版）。
 *
 * 窗口行为逐项对应 V5 基座的 open val：`dialogTheme`(Bottom) / `dialogWidth`(MATCH_PARENT) /
 * `dialogHeight`(WRAP_CONTENT) / `dialogGravity`(BOTTOM) / `dialogWindowAnimations`(AnimDialogBottom)；
 * `onStart` 里额外的去遮罩（clearFlags FLAG_DIM_BEHIND + dimAmount=0）与 `setLayout(MATCH_PARENT, WRAP_CONTENT)` 仍需覆写保留。
 * **两条「看似 bug 但是行为」必须保留**：①`ReadBookActivity.bottomDialog` 自增计数与 onDismiss 自减；
 * ②若打开时已有其它底部面板（bottomDialog > 0）则本次不再显示并 dismiss（互斥），此判定原先在 onCreateView 里靠 View.post 延迟 dismiss，
 * 现改为 main looper post（等价）。
 * 内容保留了旧 Surface 自绘形态（底部面板不需要 AppDialog 卡片包裹），仅把色值与滑条换成 V5 令牌：
 * `LegadoMiuixCard` → AppCard，`AppThemedStepperSlider(1..120, step=1)` → `AppSlider(valueRange = 1f..120f, steps = 118)`。
 */
internal class AutoReadDialog : V5DialogFragment() {

    override val dialogTheme: Int = R.style.Theme_Legado_ComposeDialog_Bottom
    override val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    override val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    override val dialogGravity: Int = Gravity.BOTTOM
    override val dialogWindowAnimations: Int = R.style.AnimDialogBottom

    private val callBack: CallBack? get() = activity as? CallBack
    private var registeredBottomDialog = false
    private var shouldDismissForExistingDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val readActivity = activity as? ReadBookActivity
        val bottomDialog = readActivity?.bottomDialog ?: 0
        val alreadyRegistered = registeredBottomDialog
        shouldDismissForExistingDialog = !alreadyRegistered && bottomDialog > 0
        if (!alreadyRegistered && readActivity != null) {
            readActivity.bottomDialog = bottomDialog + 1
            registeredBottomDialog = true
        }
        if (shouldDismissForExistingDialog) {
            Handler(Looper.getMainLooper()).post { dismissAllowingStateLoss() }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (registeredBottomDialog) {
            (activity as? ReadBookActivity)?.let { it.bottomDialog-- }
            registeredBottomDialog = false
        }
    }

    @Composable
    override fun Content() {
        if (shouldDismissForExistingDialog) {
            return
        }
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            color = colors.surface,
            contentColor = colors.onSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(density.blockGap)
            ) {
                AutoReadPanel(onSpeedCommitted = ::upTtsSpeechRate)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AutoReadAction(
                        iconRes = R.drawable.ic_toc,
                        text = stringResource(R.string.chapter_list),
                        modifier = Modifier.weight(1f),
                        onClick = { callBack?.openChapterList() }
                    )
                    AutoReadAction(
                        iconRes = R.drawable.ic_menu,
                        text = stringResource(R.string.main_menu),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            callBack?.showMenuBar()
                            dismissAllowingStateLoss()
                        }
                    )
                    AutoReadAction(
                        iconRes = R.drawable.ic_auto_page_stop,
                        text = stringResource(R.string.stop),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            callBack?.autoPageStop()
                            Handler(Looper.getMainLooper()).post { dismissAllowingStateLoss() }
                        }
                    )
                    AutoReadAction(
                        iconRes = R.drawable.ic_settings,
                        text = stringResource(R.string.setting),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            (activity as? BaseReadBookActivity)?.showPageAnimConfig { previousPageAnim ->
                                (activity as? ReadBookActivity)
                                    ?.applyPageAnimationChange(previousPageAnim)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun upTtsSpeechRate() {
        ReadAloud.upTtsSpeechRate(requireContext())
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(requireContext())
            ReadAloud.resume(requireContext())
        }
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun autoPageStop()
    }
}

@Composable
private fun AutoReadPanel(onSpeedCommitted: () -> Unit) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    var mode by remember { mutableIntStateOf(ReadBookConfig.autoReadMode) }
    var speed by remember {
        mutableIntStateOf(ReadBookConfig.autoReadSpeed.coerceIn(1, 120))
    }
    val speedTitle = if (mode == ReadBookConfig.AUTO_READ_MODE_TIMED) {
        stringResource(R.string.auto_page_interval)
    } else {
        stringResource(R.string.auto_page_speed)
    }
    AppCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AutoReadModeButton(
                text = stringResource(R.string.auto_read_mode_scroll),
                selected = mode != ReadBookConfig.AUTO_READ_MODE_TIMED,
                modifier = Modifier.weight(1f),
                onClick = {
                    mode = ReadBookConfig.AUTO_READ_MODE_SCROLL
                    ReadBookConfig.autoReadMode = mode
                }
            )
            AutoReadModeButton(
                text = stringResource(R.string.auto_read_mode_timed),
                selected = mode == ReadBookConfig.AUTO_READ_MODE_TIMED,
                modifier = Modifier.weight(1f),
                onClick = {
                    mode = ReadBookConfig.AUTO_READ_MODE_TIMED
                    ReadBookConfig.autoReadMode = mode
                }
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = speedTitle,
                style = visual.type.body,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${speed}s",
                style = visual.type.caption,
                color = colors.onSurfaceVariant
            )
        }
        AppSlider(
            value = speed.toFloat(),
            onValueChange = { speed = it.toInt().coerceIn(1, 120) },
            valueRange = 1f..120f,
            steps = 118,
            onValueChangeFinished = {
                val nextSpeed = speed.coerceIn(1, 120)
                if (ReadBookConfig.autoReadSpeed != nextSpeed) {
                    ReadBookConfig.autoReadSpeed = nextSpeed
                    onSpeedCommitted()
                }
            }
        )
    }
}

@Composable
private fun AutoReadModeButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Column(
        modifier = modifier
            .height(40.dp)
            .clip(visual.shape(ShapeKind.BUTTON))
            .background(if (selected) colors.primary.copy(alpha = 0.18f) else colors.surfaceVariant)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            color = if (selected) colors.primary else colors.onSurface,
            style = visual.type.caption,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AutoReadAction(
    iconRes: Int,
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    Column(
        modifier = modifier
            .height(50.dp)
            .clip(visual.shape(ShapeKind.BUTTON))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = text,
            tint = colors.onSurface,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = text,
            color = colors.onSurface,
            style = visual.type.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
