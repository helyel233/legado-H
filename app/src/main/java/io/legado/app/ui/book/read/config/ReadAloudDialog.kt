package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.observeEvent
import io.legado.app.utils.toastOnUi

/**
 * 朗读面板（P3-d 面板化：原 dialog_read_aloud.xml 迁移为 Compose）
 */
class ReadAloudDialog : ComposeDialogFragment() {

    private val callBack: CallBack? get() = activity as? CallBack

    private var playStateTick by mutableIntStateOf(0)
    private var timerMinute by mutableIntStateOf(BaseReadAloudService.timeMinute.coerceIn(0, 180))
    private var speechRate by mutableIntStateOf(AppConfig.ttsSpeechRate)
    private var followSys by mutableStateOf(AppConfig.ttsFlowSys)

    override val dialogGravity: Int = Gravity.BOTTOM
    override val dialogWindowAnimations: Int = R.style.AnimDialogBottom

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? ReadBookActivity)?.let {
            it.bottomDialog = (it.bottomDialog - 1).coerceAtLeast(0)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        (activity as? ReadBookActivity)?.let { act ->
            val bottomDialog = act.bottomDialog
            act.bottomDialog = bottomDialog + 1
            if (bottomDialog > 0) {
                dismissAllowingStateLoss()
            }
        }
        followSys = requireContext().getPrefBoolean("ttsFollowSys", true)
        observeEvent<Int>(EventBus.ALOUD_STATE) { playStateTick++ }
        observeEvent<Int>(EventBus.READ_ALOUD_DS) { timerMinute = it }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    ReadAloudSheet(style)
                }
            }
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
    private fun ReadAloudSheet(style: AppDialogStyle) {
        ReaderBottomSheetFrame {
            val playing = !BaseReadAloudService.pause
            // transport
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.previous_chapter),
                    color = style.primaryText,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(style.actionRadius))
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
                SheetIcon(R.drawable.ic_skip_previous, style, stringResource(R.string.prev_sentence)) {
                    ReadAloud.prevParagraph(requireContext())
                }
                SheetIcon(
                    if (playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp,
                    style,
                    stringResource(if (playing) R.string.pause else R.string.audio_play)
                ) {
                    callBack?.onClickReadAloud()
                }
                SheetIcon(R.drawable.ic_stop_black_24dp, style, stringResource(R.string.stop)) {
                    ReadAloud.stop(requireContext())
                    dismissAllowingStateLoss()
                }
                SheetIcon(R.drawable.ic_skip_next, style, stringResource(R.string.next_sentence)) {
                    ReadAloud.nextParagraph(requireContext())
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.next_chapter),
                    color = style.primaryText,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(style.actionRadius))
                        .clickable {
                            ReadBook.moveToNextChapter(true, fromReadAloud = BaseReadAloudService.isRun)
                        }
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                )
            }

            // timer
            ReaderSectionCard(style = style) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SheetIcon(R.drawable.ic_time_add_24dp, style, stringResource(R.string.set_timer)) {
                        AppConfig.ttsTimer = timerMinute
                        toastOnUi("保存设定时间成功！")
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    AppThemedStepperSlider(
                        value = timerMinute,
                        range = 0..180,
                        onValueChange = { timerMinute = it },
                        onValueChangeFinished = {
                            ReadAloud.setTimer(requireContext(), timerMinute)
                        },
                        palette = style.toMiuixPalette(),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.timer_m, timerMinute),
                        color = style.primaryText,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(style.actionRadius))
                            .clickable {
                                val times = intArrayOf(0, 5, 10, 15, 30, 60, 90, 180)
                                val timeKeys = times.map { "$it 分钟" }
                                showComposeChoiceListDialog(
                                    getString(R.string.set_timer),
                                    timeKeys
                                ) { index ->
                                    times.getOrNull(index)?.let { time ->
                                        ReadAloud.setTimer(requireContext(), time)
                                    }
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 8.dp)
                    )
                }
            }

            // speech rate
            ReaderSectionCard(style = style) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.read_aloud_speed),
                        color = style.secondaryText,
                        fontSize = 14.sp
                    )
                    if (!followSys) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = ((speechRate + 5) / 10f).toString(),
                            color = style.primaryText,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.flow_sys),
                        color = style.secondaryText,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    LegadoMiuixSwitch(
                        checked = followSys,
                        onCheckedChange = {
                            followSys = it
                            AppConfig.ttsFlowSys = it
                            if (it) {
                                speechRate = AppConfig.ttsSpeechRate
                                upTtsSpeechRate()
                            }
                        },
                        palette = style.toMiuixPalette()
                    )
                }
                if (!followSys) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SheetIcon(R.drawable.ic_reduce, style, stringResource(R.string.tts_speech_reduce)) {
                            if (speechRate > 0) {
                                speechRate -= 1
                                upTtsSpeechRate()
                            }
                        }
                        AppThemedStepperSlider(
                            value = speechRate,
                            range = 0..45,
                            onValueChange = { speechRate = it },
                            onValueChangeFinished = { upTtsSpeechRate() },
                            palette = style.toMiuixPalette(),
                            modifier = Modifier.weight(1f)
                        )
                        SheetIcon(R.drawable.ic_add, style, stringResource(R.string.tts_speech_add)) {
                            if (speechRate < 45) {
                                speechRate += 1
                                upTtsSpeechRate()
                            }
                        }
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
                SheetAction(R.drawable.ic_toc, R.string.chapter_list, style) {
                    callBack?.openChapterList()
                }
                SheetAction(R.drawable.ic_menu, R.string.main_menu, style) {
                    callBack?.showMenuBar()
                    dismissAllowingStateLoss()
                }
                SheetAction(R.drawable.ic_visibility_off, R.string.to_backstage, style) {
                    callBack?.finish()
                }
                SheetAction(R.drawable.ic_settings, R.string.setting, style) {
                    ReadAloudConfigDialog().show(childFragmentManager, "readAloudConfigDialog")
                }
            }
        }
    }

    @Composable
    private fun SheetIcon(
        res: Int,
        style: AppDialogStyle,
        contentDescription: String,
        onClick: () -> Unit
    ) {
        Image(
            painter = painterResource(res),
            contentDescription = contentDescription,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(style.primaryText),
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(style.actionRadius))
                .clickable(onClick = onClick)
                .padding(8.dp)
        )
    }

    @Composable
    private fun SheetAction(
        res: Int,
        labelRes: Int,
        style: AppDialogStyle,
        onClick: () -> Unit
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(64.dp)
                .clip(RoundedCornerShape(style.actionRadius))
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp)
        ) {
            Image(
                painter = painterResource(res),
                contentDescription = stringResource(labelRes),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(style.primaryText),
                modifier = Modifier
                    .size(22.dp)
                    .background(
                        androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(style.actionRadius)
                    )
            )
            Text(
                text = stringResource(labelRes),
                color = style.primaryText,
                fontSize = 12.sp,
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
