package io.legado.app.ui.host

import android.graphics.Color as AndroidColor
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import dev.chrisbanes.haze.hazeSource
import io.legado.app.uikit.v5.contract.DestTitle
import io.legado.app.uikit.v5.contract.TopBarMode
import io.legado.app.uikit.v5.runtime.LocalHazeState
import io.legado.app.uikit.v5.runtime.LocalVisualSpec

/**
 * V5 二级页 Activity 样板收敛：edge-to-edge + 透明系统栏 + HostV5ThemeRoot 注入。
 * 此前这套三件套在 4 个二级页 Activity 里逐字重复。
 */
internal fun AppCompatActivity.setV5Content(content: @Composable () -> Unit) {
    enableEdgeToEdge()
    window.statusBarColor = AndroidColor.TRANSPARENT
    window.navigationBarColor = AndroidColor.TRANSPARENT
    setContentView(
        ComposeView(this).apply {
            setContent { HostV5ThemeRoot { content() } }
        }
    )
}

/**
 * V5 二级页脚手架（P4 整改④）：给独立 Compose Activity 与主壳一致的视觉环境。
 *
 * 此前 HostRssSortActivity / HostSearchActivity 只注入了取值环境（HostV5ThemeRoot），
 * 缺 L0 背景层与玻璃采样架构 —— 毛玻璃在主壳里有、进二级页就消失（用户反馈①）。
 *
 * 与 HostScaffold 同构但极简：背景层 + 内容（hazeSource 采样）+ 玻璃顶栏（返回 + 标题）。
 * 无底部导航 —— 二级页不该有 Tab；无侧栏；无弹层宿主。
 */
@Composable
internal fun HostV5Page(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)?,
    /** 顶栏右侧尾部槽（页面自己的操作入口，如溢出菜单）。 */
    trailing: (@Composable () -> Unit)? = null,
    /** 底部固定操作区槽（选择操作条等）。给定时内容区自动 weight(1f) 让位（迁移门 D2）。 */
    bottomBar: (@Composable () -> Unit)? = null,
    /** 右下角悬浮动作槽（FAB，与 bottomBar 互斥使用）。 */
    floatingAction: (@Composable () -> Unit)? = null,
    /**
     * 选择模式：非 null 时标题自动切「已选 N 项」、返回键（顶栏+系统）先退选择模式。
     * 配合 AppSelectionState 使用（审计 #28-③）。
     */
    selectionCount: Int? = null,
    onExitSelection: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val hazeState = LocalHazeState.current ?: dev.chrisbanes.haze.HazeState()
    val visual = LocalVisualSpec.current
    val activity = LocalContext.current as? androidx.appcompat.app.AppCompatActivity

    // 选择模式接管返回语义（顶栏返回 + 系统返回键）
    if (selectionCount != null && onExitSelection != null) {
        androidx.activity.compose.BackHandler { onExitSelection() }
    }

    Box(Modifier.fillMaxSize()) {
        // ① 采样源层：背景 + 内容（AppSurface 的玻璃从这里取样）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState),
        ) {
            HostBackgroundLayer()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                Spacer(Modifier.height(hostTopBarHeight(TopBarMode.TITLE, scrolled = false)))
                if (bottomBar == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                    ) {
                        content()
                    }
                } else {
                    // 底部固定操作区走插槽 + weight 让位（迁移门 D2），内容区不再靠 padding 硬撑
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                    ) {
                        content()
                    }
                    Box(modifier = Modifier.navigationBarsPadding()) {
                        bottomBar()
                    }
                }
            }
        }

        // ② 玻璃层：顶栏（AppSurface 内部自动 hazeEffect）
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
        ) {
            HostTopBar(
                title = if (selectionCount != null) {
                    DestTitle.Res(io.legado.app.R.string.v5_selected_count, listOf(selectionCount))
                } else {
                    DestTitle.Text(title)
                },
                subtitle = if (selectionCount == null) subtitle?.let { DestTitle.Text(it) } else null,
                mode = TopBarMode.TITLE,
                scrolled = false,
                showBack = onBack != null,
                actions = emptyList(),
                onBack = {
                    if (selectionCount != null && onExitSelection != null) onExitSelection()
                    else onBack?.invoke() ?: activity?.finish()
                },
            )
            if (trailing != null) {
                Box(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    contentAlignment = Alignment.Center,
                ) {
                    trailing()
                }
            }
        }

        // ③ 悬浮动作槽（FAB）
        if (floatingAction != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = visual.density.screenPadding, bottom = visual.density.screenPadding),
            ) {
                floatingAction()
            }
        }
    }
}

/**
 * V5 编辑页脚手架（台账 #30-P0①）。
 *
 * 在 [HostV5Page] 之上补齐编辑场景三件事：
 * ① 顶栏右侧固定「保存」按钮；
 * ② [dirty] 为 true 时系统返回键接管 —— 弹「放弃更改？」确认（B 门红线）；
 * ③ 未 dirty 时返回直接走 [onDiscard]（页面在此 finish）。
 *
 * [onSave] 保存成功后由页面自行关闭；[onDiscard] = 不保存退出（页面 finish）。
 */
@Composable
internal fun HostV5EditPage(
    title: String,
    dirty: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // dirty 时接管系统返回键，防止误触丢稿
    androidx.activity.compose.BackHandler(enabled = dirty) {
        confirmDiscard = true
    }

    HostV5Page(
        title = title,
        subtitle = subtitle,
        onBack = { if (dirty) confirmDiscard = true else onDiscard() },
        trailing = {
            io.legado.app.uikit.v5.components.AppFilledButton(
                text = context.getString(io.legado.app.R.string.v5_save),
                onClick = onSave,
            )
        },
        content = content,
    )

    if (confirmDiscard) {
        io.legado.app.uikit.v5.components.AppConfirmDialog(
            title = context.getString(io.legado.app.R.string.v5_unsaved_title),
            message = context.getString(io.legado.app.R.string.v5_unsaved_message),
            confirmLabel = context.getString(io.legado.app.R.string.v5_discard),
            cancelLabel = context.getString(io.legado.app.R.string.v5_cancel),
            danger = true,
            onConfirm = {
                confirmDiscard = false
                onDiscard()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}
