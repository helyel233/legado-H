package io.legado.app.ui.book.manga.config

import android.content.DialogInterface
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppSlider
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import kotlin.math.roundToInt

/**
 * 漫画墨水屏阈值（V5 基座版）。
 *
 * 无遮罩（FLAG_DIM_BEHIND 清除）与 dismiss 落盘行为保持不变；
 * 内容由旧 LegadoMiuixCard/Section/Slider 桥换 V5 AppDialog + AppSlider。
 */
internal class MangaEpaperDialog : V5DialogFragment() {

    override val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    override val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT

    private val callback get() = activity as? Callback
    private var mMangaEInkThreshold = AppConfig.mangaEInkThreshold

    override fun onStart() {
        super.onStart()
        dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        var threshold by remember { mutableStateOf(mMangaEInkThreshold.toFloat()) }

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(visual.density.blockGap),
            ) {
                Text(
                    text = stringResource(R.string.manga_epaper_stting),
                    style = visual.type.title,
                    color = colors.onSurface,
                )
                Text(
                    text = stringResource(R.string.manga_epaper_value),
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                )
                AppSlider(
                    value = threshold,
                    onValueChange = { value ->
                        threshold = value
                        val intValue = value.roundToInt().coerceIn(0, 255)
                        mMangaEInkThreshold = intValue
                        callback?.updateEepaper(intValue)
                    },
                    valueRange = 0f..255f,
                    steps = 254,
                    valueLabel = threshold.roundToInt().toString(),
                )
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        AppConfig.mangaEInkThreshold = mMangaEInkThreshold
    }

    interface Callback {
        fun updateEepaper(value: Int)
    }
}
