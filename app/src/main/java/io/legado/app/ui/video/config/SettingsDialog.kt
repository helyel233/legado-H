package io.legado.app.ui.video.config

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.model.VideoPlay
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.ui.host.V5DialogPanel
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.ui.widget.number.NumberPickerDialog

/**
 * 视频播放设置弹窗（V5 全量版：V5DialogPanel + AppRowWithSwitch + AppGhostButton）。
 * 尺寸：旧 AppDialogSize.Form → widthFraction 0.94f / maxWidthDp 660。
 * 业务保留：自动播放/开播全屏联动、底部进度条、播放缓存、长按倍速 NumberPickerDialog（含默认 30）。
 */
internal class SettingsDialog(private val context: Context, private val callBack: CallBack? = null) :
    V5DialogFragment() {

    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int = 660

    @Composable
    override fun Content() {
        V5DialogPanel(
            title = stringResource(R.string.config_settings),
            content = { VideoSettingsContent() }
        )
    }

    @Composable
    private fun VideoSettingsContent() {
        var autoPlay by rememberSaveable { mutableStateOf(VideoPlay.autoPlay) }
        var startFull by rememberSaveable { mutableStateOf(VideoPlay.startFull) }
        var showStartFull by rememberSaveable { mutableStateOf(true) }
        var fullBottomProgress by rememberSaveable { mutableStateOf(VideoPlay.fullBottomProgressBar) }
        var playCacheEnabled by rememberSaveable { mutableStateOf(VideoPlay.playCacheEnabled) }
        var longPressSpeed by rememberSaveable { mutableIntStateOf(VideoPlay.longPressSpeed) }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppRowWithSwitch(
                title = stringResource(R.string.auto_play),
                checked = autoPlay,
                card = false,
                onCheckedChange = { checked ->
                    autoPlay = checked
                    showStartFull = checked
                    VideoPlay.autoPlay = checked
                }
            )
            if (showStartFull) {
                AppRowWithSwitch(
                    title = stringResource(R.string.start_full),
                    checked = startFull,
                    card = false,
                    onCheckedChange = { checked ->
                        startFull = checked
                        VideoPlay.startFull = checked
                    }
                )
            }
            AppRowWithSwitch(
                title = stringResource(R.string.full_bottom_progress),
                checked = fullBottomProgress,
                card = false,
                onCheckedChange = { checked ->
                    fullBottomProgress = checked
                    VideoPlay.fullBottomProgressBar = checked
                }
            )
            AppRowWithSwitch(
                title = stringResource(R.string.video_play_cache),
                checked = playCacheEnabled,
                card = false,
                onCheckedChange = { checked ->
                    playCacheEnabled = checked
                    VideoPlay.playCacheEnabled = checked
                }
            )
            AppGhostButton(
                text = longPressSpeed.toPressSpeedStr(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    showPressSpeedDialog { value ->
                        longPressSpeed = value
                    }
                }
            )
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showPressSpeedDialog(onChanged: (Int) -> Unit) {
        NumberPickerDialog(requireContext(), true)
            .setTitle(getString(R.string.press_speed))
            .setMaxValue(60)
            .setMinValue(5)
            .setValue(VideoPlay.longPressSpeed)
            .setCustomButton((R.string.btn_default_s)) {
                VideoPlay.longPressSpeed = 30
                onChanged(30)
            }
            .show {
                VideoPlay.longPressSpeed = it
                onChanged(it)
            }
    }

    private fun Int.toPressSpeedStr(): String {
        return context.getString(R.string.press_speed_summary, this / 10.0f)
    }

    interface CallBack {
//        fun upUi()
    }
}
