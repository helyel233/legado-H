package io.legado.app.ui.host

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * P1-1 系统栏与手势区处理（手册 P1-1 的 HostInsets 分包）。
 *
 * 边缘到边缘：内容可以画到状态栏/导航栏下面，由宿主统一决定哪里需要避让。
 * 页面不参与这件事 —— 它拿不到 insets，也就没法自己决定"我要不要顶上去"。
 */

/** 状态栏高度（页面顶部至少要留这么多） */
val hostStatusBarPadding: PaddingValues
    @Composable get() = WindowInsets.statusBars.asPaddingValues()

/** 导航栏高度（底栏/悬浮胶囊要抬高这么多） */
val hostNavigationBarPadding: PaddingValues
    @Composable get() = WindowInsets.navigationBars.asPaddingValues()

/**
 * 底部导航（底栏或悬浮胶囊）占据的总高度 = 控件本身 + 导航栏高度。
 * 内容区据此留出底部空间，避免最后一项被挡住。
 */
val hostBottomNavReserve: Dp
    @Composable get() = 72.dp + hostNavigationBarPadding.calculateBottomPadding()

/**
 * 顶栏总高度 = 状态栏 + 控件高度（控件高度随密度缩放）。
 */
@Composable
fun hostTopBarPadding(barHeight: Dp): PaddingValues =
    PaddingValues(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + barHeight)
