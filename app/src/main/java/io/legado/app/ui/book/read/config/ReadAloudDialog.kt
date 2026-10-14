package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppSlider
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.observeEvent
import io.legado.app.utils.toastOnUi
import kotlin.math.roundToInt

/**
 * 朗读面板（V5 基座版）。
 *
 * 窗口：`dialogGravity = BOTTOM` + `dialogWindowAnimations = AnimDialogBottom`（V5 基座同名 open val 直接对应）；
 * **`ReadBookActivity.bottomDialog` 计数与「已有底部面板则本次不显示」互斥语义保留**（onCreate 里判定，
 * 用主队列 post 延迟 dismiss，等同旧版 onCreateView 的时序）。
 * **外观跟随阅读页背景色这一功能特性必须保留**：旧版靠 `ReaderBottomSheetFrame` 内部的
 * `rememberReaderMenuDialogStyle()`（`AppConfig.readBarStyleFollowPage` + `ReadBookConfig.durConfig.curBgStr()`
 * → `ReaderSheetStyle.resolve`）。为避免改动共享容器（被 11 个文件使用，签名被 lambda 消费）而波及全局，
 * 本页**内联**实现该取色（`rememberReaderPagePalette()`），并把 Surface/文字/图标 tint 显式绑定到这组色值。
 * 组件替换：`ReaderSectionCard` → Box+shape+色块，`LegadoMiuixSwitch` → M3 Switch，
 * `AppThemedStepperSlider` → `AppSlider`（timer 0..180 → steps 179；语速 0..45 → steps 44）。
 */
internal class ReadAloudDialog : V5DialogFragment() {

    private val callBack: CallBack? get() = activity as? CallBack

    private var playStateTick by mutableIntStateOf(0)
    private var timerMinute by mutableIntStateOf(BaseReadAloudService.timeMinute.coerceIn(0, 180))
    private var speechRate by mutableIntStateOf(AppConfig.ttsSpeechRate)
    private var followSys by mutableStateOf(AppConfig.ttsFlowSys)

    override val dialogGravity: Int = Gravity.BOTTOM
    override val dialogWindowAnimations: Int = R.style.AnimDialogBottom

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        followSys = requireContext().getPrefBoolean("ttsFollowSys", true)
        observeEvent<Int>(EventBus.ALOUD_STATE) { playStateTick++ }
        observeEvent<Int>(EventBus.READ_ALOUD_DS) { timerMinute = it }
        (activity as? ReadBookActivity)?.let { act ->
            val bottomDialog = act.bottomDialog
            act.bottomDialog = bottomDialog + 1
            if (bottomDialog > 0) {
                dismissAllowingStateLoss()
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? ReadBookActivity)?.let {
            it.bottomDialog = (it.bottomDialog - 1).coerceAtLeast(0)
        }
    }

    private fun upTtsSpeechRate() {
        AppConfig.ttsSpeechRate = speechRate
        ReadAloud.upTtsSpeechRate(requireContext())
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(requireContext())
            ReadAloud.resume(requireContext())
        }
    }

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val v5Colors = LocalSemanticColors.current
        val palette = rememberReaderPagePalette()
        val surface = Color(palette.surface)
        val panel = Color(palette.panel)
        val textColor = Color(palette.textColor)
        val secondaryText = Color(palette.secondaryTextColor)
        val accent = Color(palette.accentColor)
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f)
            .toInt()
            .coerceAtLeast(280)
            .dp
        var timerPickerOpen by remember { mutableStateOf(false) }
        playStateTick
        val playing = !BaseReadAloudService.pause

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .navigationBarsPadding(),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            color = surface,
            contentColor = textColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // transport
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.previous_chapter),
                        color = textColor,
                        style = visual.type.body,
                        modifier = Modifier
                            .clip(visual.shape(ShapeKind.BUTTON))
                            .clickable {
                                ReadBook.moveToPrevChapter(
                                    upContent = true,
                                    toLast = false,
                                    fromReadAloud = BaseReadAloudService.isRun
                                )
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    SheetIcon(
                        res = R.drawable.ic_skip_previous,
                        tint = textColor,
                        contentDescription = stringResource(R.string.prev_sentence),
                        onClick = { ReadAloud.prevParagraph(requireContext()) }
                    )
                    SheetIcon(
                        res = if (playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp,
                        tint = textColor,
                        contentDescription = stringResource(
                            if (playing) R.string.pause else R.string.audio_play
                        ),
                        onClick = { callBack?.onClickReadAloud() }
                    )
                    SheetIcon(
                        res = R.drawable.ic_stop_black_24dp,
                        tint = textColor,
                        contentDescription = stringResource(R.string.stop),
                        onClick = {
                            ReadAloud.stop(requireContext())
                            dismissAllowingStateLoss()
                        }
                    )
                    SheetIcon(
                        res = R.drawable.ic_skip_next,
                        tint = textColor,
                        contentDescription = stringResource(R.string.next_sentence),
                        onClick = { ReadAloud.nextParagraph(requireContext()) }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.next_chapter),
                        color = textColor,
                        style = visual.type.body,
                        modifier = Modifier
                            .clip(visual.shape(ShapeKind.BUTTON))
                            .clickable {
                                ReadBook.moveToNextChapter(
                                    true,
                                    fromReadAloud = BaseReadAloudService.isRun
                                )
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                    )
                }

                // timer
                SheetSection(panelColor = panel) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SheetIcon(
                            res = R.drawable.ic_time_add_24dp,
                            tint = textColor,
                            contentDescription = stringResource(R.string.set_timer),
                            onClick = {
                                AppConfig.ttsTimer = timerMinute
                                toastOnUi("保存设定时间成功！")
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        AppSlider(
                            value = timerMinute.toFloat(),
                            onValueChange = { timerMinute = it.roundToInt() },
                            valueRange = 0f..180f,
                            steps = 179,
                            onValueChangeFinished = {
                                ReadAloud.setTimer(requireContext(), timerMinute)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.timer_m, timerMinute),
                            color = textColor,
                            style = visual.type.caption,
                            modifier = Modifier
                                .clip(visual.shape(ShapeKind.BUTTON))
                                .clickable { timerPickerOpen = true }
                                .padding(horizontal = 6.dp, vertical = 8.dp)
                        )
                    }
                }

                // speech rate
                SheetSection(panelColor = panel) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.read_aloud_speed),
                            color = secondaryText,
                            style = visual.type.body
                        )
                        if (!followSys) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = ((speechRate + 5) / 10f).toString(),
                                color = textColor,
                                style = visual.type.body
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.flow_sys),
                            color = secondaryText,
                            style = visual.type.caption
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = followSys,
                            onCheckedChange = {
                                followSys = it
                                AppConfig.ttsFlowSys = it
                                if (it) {
                                    speechRate = AppConfig.ttsSpeechRate
                                    upTtsSpeechRate()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = v5Colors.onPrimary,
                                checkedTrackColor = accent,
                                uncheckedThumbColor = v5Colors.outline,
                                uncheckedTrackColor = surface
                            )
                        )
                    }
                    if (!followSys) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SheetIcon(
                                res = R.drawable.ic_reduce,
                                tint = textColor,
                                contentDescription = stringResource(R.string.tts_speech_reduce),
                                onClick = {
                                    if (speechRate > 0) {
                                        speechRate -= 1
                                        upTtsSpeechRate()
                                    }
                                }
                            )
                            AppSlider(
                                value = speechRate.toFloat(),
                                onValueChange = { speechRate = it.roundToInt() },
                                valueRange = 0f..45f,
                                steps = 44,
                                onValueChangeFinished = { upTtsSpeechRate() },
                                modifier = Modifier.weight(1f),
                            )
                            SheetIcon(
                                res = R.drawable.ic_add,
                                tint = textColor,
                                contentDescription = stringResource(R.string.tts_speech_add),
                                onClick = {
                                    if (speechRate < 45) {
                                        speechRate += 1
                                        upTtsSpeechRate()
                                    }
                                }
                            )
                        }
                    }
                }

                // actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SheetAction(R.drawable.ic_toc, R.string.chapter_list, textColor) {
                        callBack?.openChapterList()
                    }
                    SheetAction(R.drawable.ic_menu, R.string.main_menu, textColor) {
                        callBack?.showMenuBar()
                        dismissAllowingStateLoss()
                    }
                    SheetAction(R.drawable.ic_visibility_off, R.string.to_backstage, textColor) {
                        callBack?.finish()
                    }
                    SheetAction(R.drawable.ic_settings, R.string.setting, textColor) {
                        ReadAloudConfigDialog().show(childFragmentManager, "readAloudConfigDialog")
                    }
                }
            }
        }

        if (timerPickerOpen) {
            val times = intArrayOf(0, 5, 10, 15, 30, 60, 90, 180)
            AppFilterDialog(
                title = stringResource(R.string.set_timer),
                options = times.map { "$it 分钟" },
                selectedIndex = times.indexOf(timerMinute),
                onSelect = { index ->
                    timerPickerOpen = false
                    times.getOrNull(index)?.let { time ->
                        ReadAloud.setTimer(requireContext(), time)
                    }
                },
                onDismiss = { timerPickerOpen = false },
            )
        }
    }

    @Composable
    private fun SheetIcon(
        res: Int,
        tint: Color,
        contentDescription: String,
        onClick: () -> Unit
    ) {
        val visual = LocalVisualSpec.current
        Image(
            painter = painterResource(res),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier
                .size(38.dp)
                .clip(visual.shape(ShapeKind.BUTTON))
                .clickable(onClick = onClick)
                .padding(8.dp)
        )
    }

    @Composable
    private fun SheetSection(
        panelColor: Color,
        content: @Composable () -> Unit
    ) {
        val visual = LocalVisualSpec.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(visual.shape(ShapeKind.BAR))
                .background(panelColor)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }

    @Composable
    private fun SheetAction(
        res: Int,
        labelRes: Int,
        tint: Color,
        onClick: () -> Unit
    ) {
        val visual = LocalVisualSpec.current
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(64.dp)
                .clip(visual.shape(ShapeKind.BUTTON))
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp)
        ) {
            Image(
                painter = painterResource(res),
                contentDescription = stringResource(labelRes),
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = stringResource(labelRes),
                color = tint,
                style = visual.type.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun onClickReadAloud()
        fun finish()
    }
}
