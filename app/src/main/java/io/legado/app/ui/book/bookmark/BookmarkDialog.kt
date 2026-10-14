package io.legado.app.ui.book.bookmark

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书签编辑弹窗（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Form` 映射为 widthFraction 0.94f / maxWidthDp 660；
 * 内容由旧 AppDialogFrame + LegadoComposeTheme 包裹 + 自定义 OutlinedTextField(label) 换为
 * V5 AppDialog + AppTextField（label 降级为 placeholder）；删除/取消/保存三按钮等分。
 * 「bookmark 为空则直接关闭」「IO 线程落库后 dismiss」「showDelete = editPos >= 0」等语义不变。
 */
internal class BookmarkDialog() : V5DialogFragment() {

    constructor(bookmark: Bookmark, editPos: Int = -1) : this() {
        arguments = Bundle().apply {
            putInt("editPos", editPos)
            putParcelable("bookmark", bookmark)
        }
    }

    /** 旧 AppDialogSize.Form(widthFraction = 0.94f, maxWidthDp = 660) */
    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int = 660

    private var bookmark: Bookmark? = null
    private var editPos: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        bookmark = arguments?.getParcelable("bookmark")
        editPos = arguments?.getInt("editPos", -1) ?: -1
        if (bookmark == null) {
            dismiss()
        }
    }

    @Composable
    override fun Content() {
        val bookmark = this.bookmark ?: return
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density
        var bookText by remember { mutableStateOf(bookmark.bookText) }
        var content by remember { mutableStateOf(bookmark.content) }

        AppDialog(onDismiss = { dismiss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                Text(
                    text = bookmark.chapterName.ifBlank { stringResource(R.string.bookmark) },
                    style = visual.type.title,
                    color = colors.onSurface
                )
                AppTextField(
                    value = bookText,
                    onValueChange = { bookText = it },
                    placeholder = stringResource(R.string.bookmark),
                    singleLine = false,
                    maxLines = 6,
                )
                AppTextField(
                    value = content,
                    onValueChange = { content = it },
                    placeholder = stringResource(R.string.content),
                    singleLine = false,
                    maxLines = 6,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (editPos >= 0) {
                        AppGhostButton(
                            text = stringResource(R.string.delete),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                lifecycleScope.launch {
                                    withContext(IO) { appDb.bookmarkDao.delete(bookmark) }
                                    dismiss()
                                }
                            },
                        )
                    }
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { dismiss() },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.ok),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            bookmark.bookText = bookText
                            bookmark.content = content
                            lifecycleScope.launch {
                                withContext(IO) { appDb.bookmarkDao.insert(bookmark) }
                                dismiss()
                            }
                        },
                    )
                }
            }
        }
    }
}
