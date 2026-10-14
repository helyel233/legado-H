package io.legado.app.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.model.BookCover
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.toastOnUi

/**
 * 封面规则配置弹窗（V5 基座版）。
 *
 * 保留旧版 `dialogWidth = MATCH_PARENT`（V5 基座的同名 open val 默认即 MATCH_PARENT，故不覆写尺寸）；
 * 内容由旧 AppDialogFrame + AppDialogSwitchRow + 自定义 OutlinedTextField 换为
 * V5 AppDialog + AppRowWithSwitch + AppTextField（原 OutlinedTextField 的色值来自 style.primaryText/fieldSurface/accent/secondaryText，
 * 现统一由 AppTextField 走 V5 输入规范，label 降级为 placeholder）。
 * 「搜索url 与 cover 规则不得为空才允许保存」的校验与三个按钮语义不变。
 */
internal class CoverRuleConfigDialog : V5DialogFragment() {

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density
        val initialRule = remember { BookCover.getCoverRule() }
        var enable by rememberSaveable { mutableStateOf(initialRule.enable) }
        var searchUrl by rememberSaveable { mutableStateOf(initialRule.searchUrl) }
        var coverRule by rememberSaveable { mutableStateOf(initialRule.coverRule) }

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                Text(
                    text = stringResource(R.string.cover_config),
                    style = visual.type.title,
                    color = colors.onSurface
                )
                AppRowWithSwitch(
                    title = stringResource(R.string.enable),
                    checked = enable,
                    onCheckedChange = { enable = it },
                    card = false,
                )
                AppTextField(
                    value = searchUrl,
                    onValueChange = { searchUrl = it },
                    placeholder = stringResource(R.string.r_search_url),
                    modifier = Modifier.fillMaxWidth(),
                )
                AppTextField(
                    value = coverRule,
                    onValueChange = { coverRule = it },
                    placeholder = stringResource(R.string.rule_cover_url),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppGhostButton(
                        text = stringResource(R.string.btn_default_s),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            BookCover.delCoverRule()
                            dismissAllowingStateLoss()
                        },
                    )
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { dismissAllowingStateLoss() },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.ok),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            saveCoverRule(
                                enable = enable,
                                searchUrl = searchUrl,
                                coverRule = coverRule
                            )
                        },
                    )
                }
            }
        }
    }

    private fun saveCoverRule(
        enable: Boolean,
        searchUrl: String,
        coverRule: String
    ) {
        if (searchUrl.isBlank() || coverRule.isBlank()) {
            toastOnUi("搜索url和cover规则不能为空")
            return
        }
        BookCover.CoverRule(enable, searchUrl, coverRule).let { config ->
            BookCover.saveCoverRule(config)
        }
        dismissAllowingStateLoss()
    }
}
