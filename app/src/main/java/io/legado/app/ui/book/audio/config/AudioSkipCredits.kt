package io.legado.app.ui.book.audio.config

import android.content.DialogInterface
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppSlider
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import java.lang.ref.WeakReference

/**
 * 音频跳过片头片尾（V5 基座版）。
 *
 * 保持 [newInstance] 入口、无遮罩（FLAG_DIM_BEHIND 清除）与 dismiss 时保存
 * 的行为不变；内容由旧 LegadoMiuixCard/AppDialogSliderRow 桥换为 V5 AppDialog + AppSlider。
 */
internal class AudioSkipCredits : V5DialogFragment() {

    companion object {
        private var bookRef: WeakReference<Book>? = null

        fun newInstance(book: Book): AudioSkipCredits {
            return AudioSkipCredits().apply {
                bookRef = WeakReference(book)
            }
        }
    }

    private val book: Book by lazy {
        bookRef?.get() ?: throw IllegalStateException("Book reference lost")
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        book.save()
    }

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        var openCredits by rememberSaveable { mutableIntStateOf(book.getOpenCredits()) }
        var closeCredits by rememberSaveable { mutableIntStateOf(book.getCloseCredits()) }

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(visual.density.blockGap),
            ) {
                Text(
                    text = stringResource(R.string.skip_book_credits),
                    style = visual.type.title,
                    color = colors.primary,
                )
                Text(
                    text = stringResource(R.string.opening_credits),
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                )
                AppSlider(
                    value = openCredits.toFloat(),
                    onValueChange = { value ->
                        val intValue = value.toInt()
                        openCredits = intValue
                        book.setOpenCredits(intValue)
                    },
                    valueRange = 0f..180f,
                    steps = 179,
                    valueLabel = openCredits.toString(),
                )
                Text(
                    text = stringResource(R.string.closing_credits),
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                )
                AppSlider(
                    value = closeCredits.toFloat(),
                    onValueChange = { value ->
                        val intValue = value.toInt()
                        closeCredits = intValue
                        book.setCloseCredits(intValue)
                    },
                    valueRange = 0f..180f,
                    steps = 179,
                    valueLabel = closeCredits.toString(),
                )
            }
        }
    }
}
