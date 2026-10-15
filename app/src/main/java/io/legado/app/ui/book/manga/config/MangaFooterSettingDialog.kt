package io.legado.app.ui.book.manga.config

import android.content.DialogInterface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.ui.widget.ReaderInfoBarView
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.postEvent

/**
 * 漫画页脚设置（V5 基座版）。
 *
 * 保留旧版 `dialogWidth = MATCH_PARENT`（V5 基座同名 open val 默认即 MATCH_PARENT，故不覆写）；
 * 内容去掉外层旧 LegadoMiuixCard（避免与 AppDialog 形成双层卡片），改为 AppDialog 直接承载；
 * `AppDialogSwitchRow` → `AppRowWithSwitch(card = false)`；`AppDialogOptionGroup`（显示/隐藏、对齐两个二选一）
 * → Row + `AppChip(selected)`。
 * **行为保留**：每组开关与选项改动即 `postEvent(EventBus.UP_MANGA_CONFIG, latestConfig)` 实时预览、
 * `SideEffect` 同步最新配置、`onDismiss` 落盘 `AppConfig.mangaFooterConfig`。
 */
internal class MangaFooterSettingDialog : V5DialogFragment() {

    private val config = GSON.fromJsonObject<MangaFooterConfig>(AppConfig.mangaFooterConfig)
        .getOrNull()
        ?: MangaFooterConfig()
    private var latestConfig = config.copy()

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density

        var hideChapterLabel by rememberSaveable { mutableStateOf(config.hideChapterLabel) }
        var hideChapter by rememberSaveable { mutableStateOf(config.hideChapter) }
        var hideChapterName by rememberSaveable { mutableStateOf(config.hideChapterName) }
        var hidePageNumberLabel by rememberSaveable { mutableStateOf(config.hidePageNumberLabel) }
        var hidePageNumber by rememberSaveable { mutableStateOf(config.hidePageNumber) }
        var hideProgressRatioLabel by rememberSaveable {
            mutableStateOf(config.hideProgressRatioLabel)
        }
        var hideProgressRatio by rememberSaveable { mutableStateOf(config.hideProgressRatio) }
        var hideFooter by rememberSaveable { mutableStateOf(config.hideFooter) }
        var footerOrientation by rememberSaveable { mutableStateOf(config.footerOrientation) }

        fun currentConfig(): MangaFooterConfig {
            return MangaFooterConfig(
                hideChapterLabel = hideChapterLabel,
                hideChapter = hideChapter,
                hidePageNumberLabel = hidePageNumberLabel,
                hidePageNumber = hidePageNumber,
                hideProgressRatioLabel = hideProgressRatioLabel,
                hideProgressRatio = hideProgressRatio,
                footerOrientation = footerOrientation,
                hideFooter = hideFooter,
                hideChapterName = hideChapterName
            )
        }

        fun updateConfig() {
            latestConfig = currentConfig()
            postEvent(EventBus.UP_MANGA_CONFIG, latestConfig)
        }

        SideEffect {
            latestConfig = currentConfig()
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
                    text = stringResource(R.string.manga_footer_config),
                    style = visual.type.title,
                    color = colors.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                MangaFooterSwitchSection(
                    title = stringResource(R.string.manga_header_chapter),
                    rows = listOf(
                        MangaFooterSwitchRow(
                            text = stringResource(R.string.manga_check_chapter_label),
                            checked = hideChapterLabel,
                            onChanged = { checked ->
                                hideChapterLabel = checked
                                updateConfig()
                            }
                        ),
                        MangaFooterSwitchRow(
                            text = stringResource(R.string.manga_check_chapter),
                            checked = hideChapter,
                            onChanged = { checked ->
                                hideChapter = checked
                                updateConfig()
                            }
                        ),
                        MangaFooterSwitchRow(
                            text = stringResource(R.string.manga_check_chapter_name),
                            checked = hideChapterName,
                            onChanged = { checked ->
                                hideChapterName = checked
                                updateConfig()
                            }
                        )
                    )
                )
                MangaFooterSwitchSection(
                    title = stringResource(R.string.manga_header_page),
                    rows = listOf(
                        MangaFooterSwitchRow(
                            text = stringResource(R.string.manga_check_page_label),
                            checked = hidePageNumberLabel,
                            onChanged = { checked ->
                                hidePageNumberLabel = checked
                                updateConfig()
                            }
                        ),
                        MangaFooterSwitchRow(
                            text = stringResource(R.string.manga_check_page_number),
                            checked = hidePageNumber,
                            onChanged = { checked ->
                                hidePageNumber = checked
                                updateConfig()
                            }
                        )
                    )
                )
                MangaFooterSwitchSection(
                    title = stringResource(R.string.manga_header_progress),
                    rows = listOf(
                        MangaFooterSwitchRow(
                            text = stringResource(R.string.manga_check_progress_label),
                            checked = hideProgressRatioLabel,
                            onChanged = { checked ->
                                hideProgressRatioLabel = checked
                                updateConfig()
                            }
                        ),
                        MangaFooterSwitchRow(
                            text = stringResource(R.string.manga_check_progress),
                            checked = hideProgressRatio,
                            onChanged = { checked ->
                                hideProgressRatio = checked
                                updateConfig()
                            }
                        )
                    )
                )
                OptionGroup(
                    title = stringResource(R.string.manga_header_footer),
                    options = listOf(stringResource(R.string.show), stringResource(R.string.hide)),
                    selectedIndex = if (hideFooter) 1 else 0,
                    onSelected = { index ->
                        hideFooter = index == 1
                        updateConfig()
                    }
                )
                OptionGroup(
                    title = stringResource(R.string.manga_header_footer_alignment),
                    options = listOf(
                        stringResource(R.string.manga_radio_left),
                        stringResource(R.string.manga_radio_center)
                    ),
                    selectedIndex = if (footerOrientation == ReaderInfoBarView.ALIGN_CENTER) 1 else 0,
                    onSelected = { index ->
                        footerOrientation = if (index == 1) {
                            ReaderInfoBarView.ALIGN_CENTER
                        } else {
                            ReaderInfoBarView.ALIGN_LEFT
                        }
                        updateConfig()
                    }
                )
            }
        }
    }

    @Composable
    private fun MangaFooterSwitchSection(
        title: String,
        rows: List<MangaFooterSwitchRow>
    ) {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = visual.type.body,
                color = colors.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
            )
            rows.forEach { row ->
                AppRowWithSwitch(
                    title = row.text,
                    checked = row.checked,
                    onCheckedChange = row.onChanged,
                    card = false,
                )
            }
        }
    }

    @Composable
    private fun OptionGroup(
        title: String,
        options: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = visual.type.body,
                color = colors.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                options.forEachIndexed { index, label ->
                    AppChip(
                        text = label,
                        selected = index == selectedIndex,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelected(index) },
                    )
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        AppConfig.mangaFooterConfig = GSON.toJson(latestConfig)
    }

    private data class MangaFooterSwitchRow(
        val text: String,
        val checked: Boolean,
        val onChanged: (Boolean) -> Unit
    )
}
