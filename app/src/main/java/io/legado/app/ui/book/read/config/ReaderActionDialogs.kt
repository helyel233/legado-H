package io.legado.app.ui.book.read.config

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogSwitchRow
import io.legado.app.ui.widget.compose.AppDialogTextField
import io.legado.app.ui.widget.compose.AppLayoutTokens
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.DismissWhenCallbackMissing
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LocalMiuixContainerSurfaceColor
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.resolveL3ControlSurfaceColor
import io.legado.app.ui.widget.compose.toMiuixPalette
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ReaderDownloadDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var onConfirm: ((start: Int, end: Int) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        val defaultStart = args.getInt(ARG_START_CHAPTER)
        val defaultEnd = args.getInt(ARG_END_CHAPTER)
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DismissWhenCallbackMissing(
                    missing = onConfirm == null,
                    dismiss = ::dismissAllowingStateLoss
                )
                var startText by rememberSaveable { mutableStateOf(defaultStart.toString()) }
                var endText by rememberSaveable { mutableStateOf(defaultEnd.toString()) }
                val style = rememberAppDialogStyle()
                val palette = style.toMiuixPalette()
                AppDialogFrame(
                    title = stringResource(R.string.offline_cache),
                    content = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.chapter),
                                color = style.secondaryText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AppDialogTextField(
                                    value = startText,
                                    onValueChange = { next ->
                                        startText = next.filter { it.isDigit() }.take(7)
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = stringResource(R.string.start),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                Box(
                                    modifier = Modifier.height(AppLayoutTokens.ControlHeightRegular),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.to),
                                        color = style.secondaryText,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                AppDialogTextField(
                                    value = endText,
                                    onValueChange = { next ->
                                        endText = next.filter { it.isDigit() }.take(7)
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = stringResource(R.string.end),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                        }
                    },
                    actions = {
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.cancel),
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.ok),
                            palette = palette,
                            onClick = {
                                val start = startText.toIntOrNull() ?: 0
                                val end = endText.toIntOrNull() ?: defaultEnd
                                dismissAllowingStateLoss()
                                onConfirm?.invoke(start, end)
                            },
                            primary = true,
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

    companion object {
        private const val ARG_START_CHAPTER = "startChapter"
        private const val ARG_END_CHAPTER = "endChapter"

        fun create(
            startChapter: Int,
            endChapter: Int,
            onConfirm: (start: Int, end: Int) -> Unit
        ): ReaderDownloadDialog {
            return ReaderDownloadDialog().apply {
                arguments = Bundle().apply {
                    putInt(ARG_START_CHAPTER, startChapter)
                    putInt(ARG_END_CHAPTER, endChapter)
                }
                this.onConfirm = onConfirm
            }
        }
    }
}

class SimulatedReadingDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var onConfirm: ((enabled: Boolean, date: LocalDate, startChapter: Int, dailyChapters: Int) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        val totalChapters = args.getInt(ARG_TOTAL_CHAPTERS)
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DismissWhenCallbackMissing(
                    missing = onConfirm == null,
                    dismiss = ::dismissAllowingStateLoss
                )
                var enabled by rememberSaveable { mutableStateOf(args.getBoolean(ARG_ENABLED)) }
                var startDateText by rememberSaveable {
                    mutableStateOf(args.getString(ARG_START_DATE).orEmpty())
                }
                var startChapterText by rememberSaveable {
                    mutableStateOf(args.getInt(ARG_START_CHAPTER).toString())
                }
                var dailyChaptersText by rememberSaveable {
                    mutableStateOf(args.getInt(ARG_DAILY_CHAPTERS).toString())
                }
                val style = rememberAppDialogStyle()
                val palette = style.toMiuixPalette()
                val context = LocalContext.current
                val resolvedFieldColor = resolveL3ControlSurfaceColor(
                    baseColor = style.fieldSurface,
                    containerColor = LocalMiuixContainerSurfaceColor.current,
                    palette = palette,
                    isNight = AppConfig.isNightTheme,
                    isEInk = AppConfig.isEInkMode
                )
                val dateFieldShape = RoundedCornerShape(style.actionRadius)
                AppDialogFrame(
                    title = stringResource(R.string.simulated_reading),
                    content = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AppDialogSwitchRow(
                                text = stringResource(R.string.switch_on),
                                checked = enabled,
                                onCheckedChange = { enabled = it }
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.start_from),
                                    color = style.secondaryText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = AppLayoutTokens.ControlHeightRegular)
                                        .clip(dateFieldShape)
                                        .background(resolvedFieldColor)
                                        .border(1.dp, style.stroke, dateFieldShape)
                                        .clickable {
                                            val initialDate = runCatching {
                                                LocalDate.parse(startDateText, dateFormatter)
                                            }.getOrElse { LocalDate.now() }
                                            DatePickerDialog(
                                                context,
                                                { _, year, month, dayOfMonth ->
                                                    startDateText = LocalDate.of(year, month + 1, dayOfMonth)
                                                        .format(dateFormatter)
                                                },
                                                initialDate.year,
                                                initialDate.monthValue - 1,
                                                initialDate.dayOfMonth
                                            ).show()
                                        }
                                        .padding(horizontal = 13.dp, vertical = 10.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = startDateText.ifBlank {
                                            LocalDate.now().format(dateFormatter)
                                        },
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = if (startDateText.isBlank()) {
                                                style.secondaryText
                                            } else {
                                                style.primaryText
                                            },
                                            fontFamily = style.bodyFontFamily
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AppDialogTextField(
                                    value = startChapterText,
                                    onValueChange = { next ->
                                        startChapterText = next.filter { it.isDigit() }.take(7)
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = stringResource(R.string.start_chapter),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                AppDialogTextField(
                                    value = dailyChaptersText,
                                    onValueChange = { next ->
                                        dailyChaptersText = next.filter { it.isDigit() }.take(7)
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = stringResource(R.string.daily_chapters),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                        }
                    },
                    actions = {
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.cancel),
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.ok),
                            palette = palette,
                            onClick = {
                                val start = startChapterText.toIntOrNull() ?: 0
                                val num = dailyChaptersText.toIntOrNull() ?: totalChapters
                                val date = runCatching {
                                    if (startDateText.isBlank()) LocalDate.now()
                                    else LocalDate.parse(startDateText, dateFormatter)
                                }.getOrElse { LocalDate.now() }
                                dismissAllowingStateLoss()
                                onConfirm?.invoke(enabled, date, start, num)
                            },
                            primary = true,
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

    companion object {
        private const val ARG_ENABLED = "enabled"
        private const val ARG_START_DATE = "startDate"
        private const val ARG_START_CHAPTER = "startChapter"
        private const val ARG_DAILY_CHAPTERS = "dailyChapters"
        private const val ARG_TOTAL_CHAPTERS = "totalChapters"

        fun create(
            enabled: Boolean,
            startDate: String,
            startChapter: Int,
            dailyChapters: Int,
            totalChapters: Int,
            onConfirm: (enabled: Boolean, date: LocalDate, startChapter: Int, dailyChapters: Int) -> Unit
        ): SimulatedReadingDialog {
            return SimulatedReadingDialog().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_ENABLED, enabled)
                    putString(ARG_START_DATE, startDate)
                    putInt(ARG_START_CHAPTER, startChapter)
                    putInt(ARG_DAILY_CHAPTERS, dailyChapters)
                    putInt(ARG_TOTAL_CHAPTERS, totalChapters)
                }
                this.onConfirm = onConfirm
            }
        }
    }
}
