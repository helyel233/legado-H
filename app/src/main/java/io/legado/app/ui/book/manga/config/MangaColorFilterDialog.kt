package io.legado.app.ui.book.manga.config

import android.content.DialogInterface
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppSlider
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 漫画颜色滤镜弹窗（V5 基座版）。
 *
 * 保留旧版 `dialogWidth = MATCH_PARENT`（V5 基座同名 open val 默认即 MATCH_PARENT，故不覆写）与
 * **`onStart` 里 `clearFlags(FLAG_DIM_BEHIND)`**（去掉背景遮罩才能实时看到阅读页滤镜效果，行为必须保留）；
 * 内容由旧 LegadoMiuixCard + AppDialogSliderGrid(AppDialogSliderItem) 换为 V5 AppDialog + AppSlider×5
 * （0..255 整数滑条 → `valueRange = 0f..255f, steps = 254`）。
 * 「用户改动后 debounce 180ms 回调 updateColorFilter」「dismiss 时落盘 AppConfig.mangaColorFilter」等语义不变。
 */
internal class MangaColorFilterDialog : V5DialogFragment() {

    private val mConfig =
        GSON.fromJsonObject<MangaColorFilterConfig>(AppConfig.mangaColorFilter).getOrNull()
            ?: MangaColorFilterConfig()
    private var latestConfig = mConfig.copy()
    private val callback get() = activity as? Callback

    override fun onStart() {
        super.onStart()
        dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density
        var brightness by rememberSaveable { mutableIntStateOf(mConfig.l) }
        var red by rememberSaveable { mutableIntStateOf(mConfig.r) }
        var green by rememberSaveable { mutableIntStateOf(mConfig.g) }
        var blue by rememberSaveable { mutableIntStateOf(mConfig.b) }
        var alpha by rememberSaveable { mutableIntStateOf(mConfig.a) }
        var hasUserChanges by rememberSaveable { mutableStateOf(false) }

        fun currentConfig(): MangaColorFilterConfig {
            return MangaColorFilterConfig(
                r = red,
                g = green,
                b = blue,
                a = alpha,
                l = brightness
            )
        }

        fun markChanged() {
            latestConfig = currentConfig()
            hasUserChanges = true
        }

        SideEffect {
            latestConfig = currentConfig()
        }

        LaunchedEffect(brightness, red, green, blue, alpha, hasUserChanges) {
            if (!hasUserChanges) {
                return@LaunchedEffect
            }
            delay(180)
            callback?.updateColorFilter(latestConfig)
        }

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                Text(
                    text = stringResource(R.string.manga_color_filter),
                    style = visual.type.title,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                AppSlider(
                    value = brightness.toFloat(),
                    onValueChange = {
                        brightness = it.roundToInt()
                        markChanged()
                    },
                    valueRange = 0f..255f,
                    steps = 254,
                    valueLabel = stringResource(R.string.brightness),
                )
                AppSlider(
                    value = red.toFloat(),
                    onValueChange = {
                        red = it.roundToInt()
                        markChanged()
                    },
                    valueRange = 0f..255f,
                    steps = 254,
                    valueLabel = "R",
                )
                AppSlider(
                    value = green.toFloat(),
                    onValueChange = {
                        green = it.roundToInt()
                        markChanged()
                    },
                    valueRange = 0f..255f,
                    steps = 254,
                    valueLabel = "G",
                )
                AppSlider(
                    value = blue.toFloat(),
                    onValueChange = {
                        blue = it.roundToInt()
                        markChanged()
                    },
                    valueRange = 0f..255f,
                    steps = 254,
                    valueLabel = "B",
                )
                AppSlider(
                    value = alpha.toFloat(),
                    onValueChange = {
                        alpha = it.roundToInt()
                        markChanged()
                    },
                    valueRange = 0f..255f,
                    steps = 254,
                    valueLabel = "A",
                )
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        AppConfig.mangaColorFilter = latestConfig.toJson()
    }

    interface Callback {
        fun updateColorFilter(config: MangaColorFilterConfig)
    }
}
