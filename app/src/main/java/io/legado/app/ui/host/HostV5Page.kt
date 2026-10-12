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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
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
    content: @Composable () -> Unit,
) {
    val hazeState = LocalHazeState.current ?: dev.chrisbanes.haze.HazeState()
    val visual = LocalVisualSpec.current
    val activity = LocalContext.current as? androidx.appcompat.app.AppCompatActivity

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
                title = DestTitle.Text(title),
                subtitle = subtitle?.let { DestTitle.Text(it) },
                mode = TopBarMode.TITLE,
                scrolled = false,
                showBack = onBack != null,
                actions = emptyList(),
                onBack = { onBack?.invoke() ?: activity?.finish() },
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
    }
}
