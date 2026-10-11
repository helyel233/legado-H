package io.legado.app.ui.host

import android.os.Bundle
import android.graphics.Color as AndroidColor
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import dev.chrisbanes.haze.HazeState
import io.legado.app.help.AppBootstrap
import io.legado.app.ui.host.pack.V5PackageStore
import io.legado.app.uikit.v5.resolve.LayoutResolver
import io.legado.app.uikit.v5.resolve.ThemeResolver
import io.legado.app.uikit.v5.runtime.LocalHazeState
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec

/**
 * P1-1 新宿主骨架入口。
 *
 * 与旧 [io.legado.app.ui.main.MainActivity]（2141 行，兼宿主 / 导航 / 玻璃 / 侧栏 /
 * 指示器 / 启动编排 / 剪贴板导入）形成对照：**这个类本体不含任何绘制逻辑**，
 * 只做三件事 —— 边缘到边缘、提供取值环境、把组合根挂上去。
 *
 * P1 阶段它与旧 MainActivity 并存，通过 debug 入口进入；P4-5 旧壳退役后成为唯一主界面。
 */
class AppHostActivity : AppCompatActivity() {

    private var bootstrap: AppBootstrap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边缘到边缘：背景层画满全屏，避让由 HostInsets 统一处理
        enableEdgeToEdge()
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT

        setContentView(
            ComposeView(this).apply {
                setContent { HostRoot() }
            }
        )

        // P4-5a 转正：接管旧壳的应用级启动职责
        bootstrap = AppBootstrap(this).also { it.onCreated(savedInstanceState) }
    }

    override fun onResume() {
        super.onResume()
        bootstrap?.onResumed()
    }

    override fun onPause() {
        bootstrap?.onPaused()
        super.onPause()
    }
}

/**
 * 组合根：把解析器输出与玻璃采样状态注入 CompositionLocal。
 *
 * **这里就是"换肤不重启"的全部秘密** —— `LayoutResolver.spec` 与
 * `ThemeResolver` 的内部状态都是 Compose `mutableStateOf`，在 setContent 里读一次
 * 即订阅；改任意参数触发整棵树重组，不需要 `recreate()`（旧体系 P1 起退役）。
 */
@Composable
private fun HostRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    // P3-2：读双包库并应用 active 包（同步，json 很小）
    remember { V5PackageStore.init(context) }

    // P3-1：把主题包的字体（FontFamily）并入排版规格 —— 结构来自界面包，字体来自主题包
    val baseSpec = LayoutResolver.spec
    val visual = remember(baseSpec, ThemeResolver.revision) {
        baseSpec.copy(
            type = baseSpec.type.copy(
                uiFont = ThemeResolver.uiFontFamily(),
                titleFont = ThemeResolver.titleFontFamily(),
            )
        )
    }
    val colors = ThemeResolver.colors()
    val hazeState = remember { HazeState() }

    CompositionLocalProvider(
        LocalVisualSpec provides visual,
        LocalSemanticColors provides colors,
        LocalHazeState provides hazeState,
    ) {
        val state = remember { HostState(HostDestinations.registry(), "shelf") }
        val overlay = remember { HostOverlay() }
        HostScaffold(state = state, overlay = overlay, hazeState = hazeState)
    }
}
