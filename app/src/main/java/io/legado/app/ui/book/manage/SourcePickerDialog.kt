package io.legado.app.ui.book.manage

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn

/**
 * 书源选择（V5 基座版）。
 *
 * 保留旧版全屏尺寸 `widthFraction = 1f` + `dialogHeight = MATCH_PARENT`（V5 基座同名 open val 直接对应）；
 * 内容由旧 AppDialogFrame + LegadoComposeTheme 包裹 + 自定义 OutlinedTextField + LegadoMiuixCard 换为
 * V5 AppDialog + AppTextField + AppCard。
 * 「搜索走 flowEnabled/flowSearchEnabled（catch 只记日志）」「点击书源 → callback.sourceOnClick 后关闭」
 * 「换源延迟走 NumberPickerDialog（旧 View 弹窗，保留）」等语义不变。
 */
internal class SourcePickerDialog : V5DialogFragment() {

    override val widthFraction: Float = 1f
    override val dialogHeight: Int = ViewGroup.LayoutParams.MATCH_PARENT

    private val callback: Callback?
        get() = (parentFragment as? Callback) ?: activity as? Callback

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density
        var query by remember { mutableStateOf("") }
        val sourceFlow = remember(query) {
            when {
                query.isEmpty() -> appDb.bookSourceDao.flowEnabled()
                else -> appDb.bookSourceDao.flowSearchEnabled(query)
            }.catch {
                AppLog.put("书源选择界面获取书源数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO)
        }
        val sources by sourceFlow.collectAsStateWithLifecycle(initialValue = emptyList())

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                Text(
                    text = "选择书源",
                    style = visual.type.title,
                    color = colors.onSurface
                )
                AppTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.search_book_source),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                ) {
                    items(sources, key = { it.bookSourceUrl }) { part ->
                        AppCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                part.getBookSource()?.let { callback?.sourceOnClick(it) }
                                dismissAllowingStateLoss()
                            },
                        ) {
                            Text(
                                text = part.getDisPlayNameGroup(),
                                style = visual.type.body,
                                color = colors.onSurface
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    AppGhostButton(
                        text = stringResource(R.string.change_source_delay),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            NumberPickerDialog(requireContext())
                                .setTitle(getString(R.string.change_source_delay))
                                .setMaxValue(9999)
                                .setMinValue(0)
                                .setValue(AppConfig.batchChangeSourceDelay)
                                .show {
                                    AppConfig.batchChangeSourceDelay = it
                                }
                        },
                    )
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { dismiss() },
                    )
                }
            }
        }
    }

    interface Callback {
        fun sourceOnClick(source: BookSource)
    }
}
