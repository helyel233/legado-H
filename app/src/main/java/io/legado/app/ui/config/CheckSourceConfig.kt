package io.legado.app.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.model.CheckSource
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppSelectionRow
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.putPrefString
import io.legado.app.utils.toastOnUi

/**
 * 校验源配置（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Form` 映射为 widthFraction 0.94f / maxWidthDp 660；
 * 内容由旧 AppDialogFrame + 自定义 OutlinedTextField + LegadoMiuixChoiceRow + LegadoMiuixActionButton
 * 换为 V5 AppDialog + `AppTextField`（**保留 `keyboardType = KeyboardType.Number`**）+ `AppSelectionRow(selectionMode = true)`
 * + AppGhostButton/AppFilledButton。
 * **降级说明**：原 `LegadoMiuixChoiceRow(enabled = false)` 的禁用态，AppSelectionRow 无 enabled 参数，
 * 改为「onClick 内判断 + 整行 alpha 0.45」表达（视觉与行为等价）。
 * 业务保留：超时/线程数校验（超时必须 > 0、线程数 1..64，不合法 toast 且不关闭）、
 * 校验项之间的联动约束（`refreshCheckItemState` / `disableInfoSection`：无搜索/发现则禁用信息项、
 * 无信息则禁用目录、无目录则禁用正文，且至少保留一个入口）。
 */
internal class CheckSourceConfig : V5DialogFragment() {

    private val minTimeout = 0L

    /** 旧 AppDialogSize.Form(widthFraction = 0.94f, maxWidthDp = 660) */
    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int = 660

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density

        var timeoutText by rememberSaveable { mutableStateOf((CheckSource.timeout / 1000).toString()) }
        var threadCountText by rememberSaveable {
            mutableStateOf(CheckSource.normalizedThreadCount().toString())
        }
        var quickMode by rememberSaveable { mutableStateOf(CheckSource.quickMode) }
        var writeSourceComment by rememberSaveable { mutableStateOf(CheckSource.wSourceComment) }
        var checkDomain by rememberSaveable { mutableStateOf(CheckSource.checkDomain) }
        var checkSearch by rememberSaveable { mutableStateOf(CheckSource.checkSearch) }
        var checkDiscovery by rememberSaveable { mutableStateOf(CheckSource.checkDiscovery) }
        var checkInfo by rememberSaveable { mutableStateOf(CheckSource.shouldCheckInfo()) }
        var checkCategory by rememberSaveable { mutableStateOf(CheckSource.shouldCheckCategory()) }
        var checkContent by rememberSaveable { mutableStateOf(CheckSource.shouldCheckContent()) }

        fun refreshCheckItemState() {
            val infoEnabled = checkSearch || checkDiscovery
            if (!infoEnabled || !checkInfo) {
                checkCategory = false
                checkContent = false
            } else if (!checkCategory) {
                checkContent = false
            }
        }

        fun disableInfoSection() {
            checkInfo = false
            checkCategory = false
            checkContent = false
        }

        val infoEnabled = checkSearch || checkDiscovery
        val categoryEnabled = infoEnabled && checkInfo
        val contentEnabled = categoryEnabled && checkCategory

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                Text(
                    text = stringResource(R.string.check_source_config),
                    style = visual.type.title,
                    color = colors.onSurface
                )
                AppTextField(
                    value = timeoutText,
                    onValueChange = { timeoutText = it.filterAsciiDigits() },
                    placeholder = stringResource(R.string.check_source_timeout),
                    singleLine = true,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppTextField(
                    value = threadCountText,
                    onValueChange = { threadCountText = it.filterAsciiDigits() },
                    placeholder = stringResource(R.string.check_source_thread_count),
                    singleLine = true,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.fillMaxWidth(),
                )
                CheckSourceChoiceRow(
                    text = stringResource(R.string.write_source_comment),
                    selected = writeSourceComment,
                    onClick = { writeSourceComment = !writeSourceComment }
                )
                CheckSourceChoiceRow(
                    text = stringResource(R.string.check_source_quick_mode),
                    selected = quickMode,
                    onClick = { quickMode = !quickMode }
                )
                Text(
                    text = stringResource(R.string.check_source_item),
                    style = visual.type.body,
                    color = colors.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                CheckSourceChoiceRow(
                    text = stringResource(R.string.domain),
                    selected = checkDomain,
                    onClick = {
                        checkDomain = !checkDomain
                        if (!checkSearch && !checkDiscovery && !checkDomain) {
                            checkSearch = true
                        }
                        refreshCheckItemState()
                    }
                )
                CheckSourceChoiceRow(
                    text = stringResource(R.string.search),
                    selected = checkSearch,
                    onClick = {
                        checkSearch = !checkSearch
                        if (!checkSearch && !checkDiscovery) {
                            disableInfoSection()
                            if (!checkDomain) {
                                checkDiscovery = true
                            }
                        }
                        refreshCheckItemState()
                    }
                )
                CheckSourceChoiceRow(
                    text = stringResource(R.string.discovery),
                    selected = checkDiscovery,
                    onClick = {
                        checkDiscovery = !checkDiscovery
                        if (!checkSearch && !checkDiscovery) {
                            disableInfoSection()
                            if (!checkDomain) {
                                checkSearch = true
                            }
                        }
                        refreshCheckItemState()
                    }
                )
                CheckSourceChoiceRow(
                    text = stringResource(R.string.source_tab_info),
                    selected = checkInfo,
                    enabled = infoEnabled,
                    onClick = {
                        checkInfo = !checkInfo
                        refreshCheckItemState()
                    }
                )
                CheckSourceChoiceRow(
                    text = stringResource(R.string.chapter_list),
                    selected = checkCategory,
                    enabled = categoryEnabled,
                    onClick = {
                        checkCategory = !checkCategory
                        refreshCheckItemState()
                    }
                )
                CheckSourceChoiceRow(
                    text = stringResource(R.string.main_body),
                    selected = checkContent,
                    enabled = contentEnabled,
                    onClick = {
                        checkContent = !checkContent
                        refreshCheckItemState()
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { dismissAllowingStateLoss() },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.ok),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            saveConfig(
                                timeoutText = timeoutText,
                                threadCountText = threadCountText,
                                quickMode = quickMode,
                                writeSourceComment = writeSourceComment,
                                checkDomain = checkDomain,
                                checkSearch = checkSearch,
                                checkDiscovery = checkDiscovery,
                                checkInfo = checkInfo,
                                checkCategory = checkCategory,
                                checkContent = checkContent
                            )
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun CheckSourceChoiceRow(
        text: String,
        selected: Boolean,
        onClick: () -> Unit,
        enabled: Boolean = true
    ) {
        AppSelectionRow(
            title = text,
            selected = selected,
            selectionMode = true,
            onClick = { if (enabled) onClick() },
            onLongClick = {},
            // 禁用态：AppSelectionRow 无 enabled 参数，用透明度表达
            modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
        )
    }

    private fun saveConfig(
        timeoutText: String,
        threadCountText: String,
        quickMode: Boolean,
        writeSourceComment: Boolean,
        checkDomain: Boolean,
        checkSearch: Boolean,
        checkDiscovery: Boolean,
        checkInfo: Boolean,
        checkCategory: Boolean,
        checkContent: Boolean
    ) {
        val timeoutSeconds = timeoutText.toLongOrNull()
        when {
            timeoutText.isBlank() || timeoutSeconds == null -> {
                toastOnUi("${getString(R.string.timeout)}${getString(R.string.cannot_empty)}")
                return
            }
            timeoutSeconds <= minTimeout -> {
                toastOnUi(
                    "${getString(R.string.timeout)}${getString(R.string.less_than)}$minTimeout${
                        getString(R.string.seconds)
                    }"
                )
                return
            }
            else -> CheckSource.timeout = timeoutSeconds * 1000
        }

        val threadValue = threadCountText.toIntOrNull()
        if (threadValue == null || threadValue !in 1..64) {
            toastOnUi(R.string.check_source_thread_count_invalid)
            return
        }
        CheckSource.threadCount = threadValue
        CheckSource.quickMode = quickMode
        CheckSource.wSourceComment = writeSourceComment
        CheckSource.checkDomain = checkDomain
        CheckSource.checkSearch = checkSearch
        CheckSource.checkDiscovery = checkDiscovery
        CheckSource.checkInfo = checkInfo
        CheckSource.checkCategory = checkCategory
        CheckSource.checkContent = checkContent
        CheckSource.putConfig()
        requireContext().putPrefString(PreferKey.checkSource, CheckSource.summary)
        dismissAllowingStateLoss()
    }

    private fun String.filterAsciiDigits(): String {
        return filter { it in '0'..'9' }
    }
}
